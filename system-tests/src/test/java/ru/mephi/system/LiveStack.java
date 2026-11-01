package ru.mephi.system;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Owns four JVMs and isolated H2 databases. No connections to an existing deployment. */
final class LiveStack implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path ROOT = Path.of(System.getProperty("project.root")).toAbsolutePath();
    private static final String[] SERVICES = {"discovery-service", "hotel-service", "booking-service", "api-gateway"};
    private final Map<String, Integer> ports = new HashMap<>();
    private final List<Process> processes = new CopyOnWriteArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread shutdown = new Thread(this::close, "system-test-cleanup");
    private Path keys;
    final Path logs;
    HotelFaultProxy proxy;
    String admin, user, other, serviceToken;
    private PrivateKey privateKey;

    LiveStack() throws IOException {
        int base = Integer.getInteger("system.base-port", 19080);
        assertTrue(base >= 1024 && base <= 65531, "Invalid system.base-port");
        String[] names = {"gateway", "discovery", "hotel", "booking", "proxy"};
        for (int i = 0; i < names.length; i++) ports.put(names[i], base + i);
        Path parent = Files.createDirectories(ROOT.resolve("system-tests/target/stack-logs"));
        logs = Files.createTempDirectory(parent, "run-");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    void start() throws Exception {
        List<ServerSocket> reserved = new ArrayList<>();
        try {
            for (int port : ports.values()) {
                ServerSocket socket = new ServerSocket();
                reserved.add(socket);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", port));
            }
        } finally { for (ServerSocket socket : reserved) socket.close(); }
        for (String name : SERVICES) assertTrue(Files.isRegularFile(jar(name)), "Missing executable JAR for " + name);
        keys = Files.createTempDirectory("hotel-junit-keys-");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = pair.getPrivate();
        writePem(keys.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
        writePem(keys.resolve("private.pem"), "PRIVATE KEY", privateKey.getEncoded());
        proxy = new HotelFaultProxy(ports.get("proxy"), ports.get("hotel"));
        String adminPassword = UUID.randomUUID().toString(), userPassword = UUID.randomUUID().toString();
        String[] portNames = {"discovery", "hotel", "booking", "gateway"};
        for (int i = 0; i < SERVICES.length; i++) {
            String name = SERVICES[i];
            List<String> args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx384m", "-jar", jar(name).toString(), "--server.address=127.0.0.1", "--server.port=" + ports.get(portNames[i]),
                "--eureka.client.serviceUrl.defaultZone=http://127.0.0.1:" + ports.get("discovery") + "/eureka/",
                "--eureka.instance.prefer-ip-address=true", "--eureka.instance.ip-address=127.0.0.1",
                "--eureka.client.registry-fetch-interval-seconds=1", "--eureka.client.initial-instance-info-replication-interval-seconds=1",
                "--eureka.instance.lease-renewal-interval-in-seconds=1", "--spring.jpa.show-sql=false"));
            if (name.equals("discovery-service")) args.add("--eureka.server.response-cache-update-interval-ms=1000");
            if (name.equals("hotel-service") || name.equals("booking-service")) args.addAll(List.of(
                "--spring.profiles.active=demo", "--rsa.public-key=" + keys.resolve("public.pem").toUri(),
                "--spring.datasource.url=jdbc:h2:mem:systemcheck", "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=create-drop"));
            if (name.equals("hotel-service")) args.add("--eureka.instance.non-secure-port=" + ports.get("proxy"));
            if (name.equals("booking-service")) args.addAll(List.of("--rsa.private-key=" + keys.resolve("private.pem").toUri(),
                "--booking.saga.recovery.initial-delay-ms=1000", "--booking.saga.recovery.interval-ms=500"));
            ProcessBuilder builder = new ProcessBuilder(args).directory(ROOT.toFile()).redirectErrorStream(true)
                .redirectOutput(logs.resolve(name + ".log").toFile());
            builder.environment().put("DEMO_ADMIN_PASSWORD", adminPassword);
            builder.environment().put("DEMO_USER_PASSWORD", userPassword);
            processes.add(builder.start());
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        boolean ready = false;
        while (System.nanoTime() < deadline) {
            checkProcesses();
            try {
                if (call("gateway", "/api/hotels", "GET", null, null, 2).status == 401
                    && call("gateway", "/api/bookings", "GET", null, null, 2).status == 401) { ready = true; break; }
            } catch (IOException ignored) { }
            Thread.sleep(500);
        }
        assertTrue(ready, "Services did not become ready; logs: " + logs);
        admin = login("demo-admin", adminPassword);
        user = login("demo-user", userPassword);
        String password = UUID.randomUUID().toString();
        call("/api/auth/register", "POST", Map.of("username", "other-user", "password", password), null).require(201);
        other = login("other-user", password);
        serviceToken = serviceToken();
    }

    private Path jar(String name) { return ROOT.resolve(name + "/target/" + name + "-1.0-SNAPSHOT.jar"); }
    private static void writePem(Path file, String kind, byte[] encoded) throws IOException {
        Files.writeString(file, "-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
            + "\n-----END " + kind + "-----\n");
    }
    void checkProcesses() { for (Process process : processes) assertTrue(process.isAlive(), "Service exited; logs: " + logs); }
    private String login(String name, String password) throws Exception {
        return call("/api/auth/login", "POST", Map.of("username", name, "password", password), null).require(200).path("accessToken").asText();
    }
    private String serviceToken() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        long now = Instant.now().getEpochSecond();
        String message = encoder.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "RS256"))) + "." +
            encoder.encodeToString(JSON.writeValueAsBytes(Map.of("iss", "hotel-booking-system", "sub", "booking-service", "role", "SERVICE",
                "aud", List.of("hotel-service"), "iat", now, "exp", now + 3600)));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(message.getBytes(StandardCharsets.US_ASCII));
        return message + "." + encoder.encodeToString(signer.sign());
    }

    record Response(int status, JsonNode body, String contentType) {
        JsonNode require(int expected) { assertEquals(expected, status, "Unexpected HTTP status"); return body; }
        JsonNode error(int expected, String code, String path) {
            JsonNode node = require(expected);
            assertTrue(contentType.startsWith("application/json"));
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("timestamp", "status", "error", "code", "message", "path", "fields"), fields);
            assertEquals(expected, node.path("status").asInt());
            assertEquals(code, node.path("code").asText());
            assertEquals(path, node.path("path").asText());
            assertTrue(node.path("fields").isObject());
            return node;
        }
    }
    Response call(String path, String method, Object data, String token) throws Exception { return call("gateway", path, method, data, token, 60); }
    Response get(String path, String token) throws Exception { return call(path, "GET", null, token); }
    Response call(String service, String path, String method, Object data, String token, int timeout) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports.get(service) + path))
            .timeout(Duration.ofSeconds(timeout)).header("Content-Type", "application/json")
            .method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(data)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        JsonNode body = response.body().length == 0 ? JSON.nullNode() : contentType.contains("json")
            ? JSON.readTree(response.body()) : JSON.valueToTree(new String(response.body(), StandardCharsets.UTF_8));
        return new Response(response.statusCode(), body, contentType);
    }
    record Fixture(long hotel, List<Long> rooms) { }
    Fixture fixture(int count) throws Exception {
        long hotel = call("/api/hotels", "POST", Map.of("name", "JUnit " + UUID.randomUUID(), "address", "Local"), admin).require(201).path("id").asLong();
        List<Long> rooms = new ArrayList<>();
        for (int i = 0; i < count; i++) rooms.add(call("/api/rooms", "POST", Map.of("hotelId", hotel, "roomNumber", "" + (i + 1),
            "capacity", 2, "price", 100), admin).require(201).path("id").asLong());
        return new Fixture(hotel, rooms);
    }
    Map<String, Object> booking(Fixture fixture, boolean automatic) {
        Map<String, Object> request = new HashMap<>(Map.of("requestId", UUID.randomUUID().toString(), "autoSelect", automatic,
            "startDate", "2027-01-01", "endDate", "2027-01-03"));
        request.put(automatic ? "hotelId" : "roomId", automatic ? fixture.hotel : fixture.rooms.getFirst());
        return request;
    }
    Response create(Map<String, Object> request) throws Exception { return call("/api/bookings", "POST", request, user); }
    JsonNode booking(long id) throws Exception { return get("/api/bookings/" + id, user).require(200); }
    JsonNode reservation(long id) throws Exception { return call("hotel", "/internal/reservations/" + id, "GET", null, serviceToken, 10).require(200); }
    JsonNode remote(long id, Map<String, Object> request) throws Exception {
        if (!Boolean.TRUE.equals(request.get("autoSelect"))) return reservation(id);
        return call("hotel", "/internal/auto-reservations/status", "POST", Map.of("bookingId", id, "hotelId", request.get("hotelId"),
            "startDate", request.get("startDate"), "endDate", request.get("endDate")), serviceToken, 10).require(200);
    }
    JsonNode room(long id) throws Exception { return get("/api/admin/rooms/" + id, admin).require(200); }
    JsonNode search(long hotel, boolean adjacent) throws Exception {
        return get("/api/rooms/recommend?hotelId=" + hotel + (adjacent ? "&startDate=2027-01-03&endDate=2027-01-05" :
            "&startDate=2027-01-01&endDate=2027-01-03"), user).require(200);
    }
    JsonNode settled(long id, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        do {
            checkProcesses();
            JsonNode booking = booking(id);
            if ((expected == null ? !booking.path("status").asText().equals("PENDING") : booking.path("status").asText().equals(expected))
                && !booking.path("cancellationRequested").asBoolean()) return booking;
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Booking " + id + " did not settle as " + expected);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // Keep Eureka alive while its clients deregister.
        for (int i = 1; i < processes.size(); i++) processes.get(i).destroy();
        for (int i = 1; i < processes.size(); i++) awaitExit(processes.get(i));
        if (!processes.isEmpty()) { processes.getFirst().destroy(); awaitExit(processes.getFirst()); }
        if (proxy != null) proxy.close();
        client.close();
        if (keys != null) {
            try (var paths = Files.walk(keys)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ex) { throw new IllegalStateException("Cannot remove temporary test keys", ex); }
        }
        try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
    }
    private void awaitExit(Process process) {
        try { if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(); }
        catch (InterruptedException ex) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}
