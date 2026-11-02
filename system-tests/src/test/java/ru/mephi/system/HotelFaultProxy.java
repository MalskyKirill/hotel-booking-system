package ru.mephi.system;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;
import static org.junit.jupiter.api.Assertions.*;

/** Loopback transport faults; request bodies and credentials are never recorded. */
final class HotelFaultProxy implements AutoCloseable {
    enum Action { REJECT_BEFORE, DROP_AFTER, HOLD_BEFORE }
    private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect");
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final int target;
    private volatile Rule active;
    record Observation(String method, String path, String traceId, Integer upstreamStatus, String upstreamTraceId) { }
    private final Queue<Observation> observed = new ConcurrentLinkedQueue<>();
    List<Observation> observations(String traceId) {
        return observed.stream().filter(o -> Objects.equals(traceId, o.traceId())).toList();
    }

    HotelFaultProxy(int port, int target) throws IOException {
        this.target = target;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.setExecutor(executor);
        server.createContext("/", this::forward);
        server.start();
    }

    Rule rule(BiPredicate<String, String> predicate, Action action) {
        assertNull(active, "Overlapping fault rules");
        Rule rule = new Rule(predicate, action);
        active = rule;
        return rule;
    }

    final class Rule implements AutoCloseable {
        final BiPredicate<String, String> predicate;
        final Action action;
        final CountDownLatch gate = new CountDownLatch(1);
        private final List<Integer> statuses = new ArrayList<>();
        private int hits, finished;
        Rule(BiPredicate<String, String> predicate, Action action) { this.predicate = predicate; this.action = action; }
        synchronized void capture() { hits++; }
        synchronized void complete(Integer status) { finished++; if (status != null) statuses.add(status); notifyAll(); }
        synchronized int hits() { return hits; }
        void release() { gate.countDown(); }
        synchronized void awaitFinished() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (finished != hits && System.nanoTime() < deadline) wait(100);
            assertEquals(hits, finished, "Fault requests did not complete");
        }
        void requireCommitted() throws InterruptedException {
            awaitFinished();
            synchronized (this) {
                assertTrue(hits >= 3, "Fault did not exhaust retries");
                assertEquals(hits, statuses.size(), "Not every request reached Hotel");
                assertTrue(statuses.stream().allMatch(s -> s == 200), "Fault preceded upstream commit");
            }
        }
        @Override public void close() throws InterruptedException {
            active = null;
            release();
            awaitFinished();
        }
    }

    private void forward(HttpExchange exchange) throws IOException {
        Rule rule = active;
        if (rule != null && !rule.predicate.test(exchange.getRequestMethod(), exchange.getRequestURI().getPath())) rule = null;
        if (rule != null) rule.capture();
        Integer status = null;
        String responseTrace = null;
        String trace = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (rule != null && rule.action == Action.REJECT_BEFORE) {
                byte[] error = "{\"code\":\"TEST_OUTAGE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, error.length);
                exchange.getResponseBody().write(error);
                return;
            }
            if (rule != null && rule.action == Action.HOLD_BEFORE && !rule.gate.await(45, TimeUnit.SECONDS))
                throw new IOException("Held request was not released");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target + exchange.getRequestURI()))
                .timeout(Duration.ofSeconds(10)).method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!HOP.contains(name.toLowerCase(Locale.ROOT))) values.forEach(value -> request.header(name, value));
            });
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            status = response.statusCode();
            responseTrace = response.headers().firstValue("X-Correlation-Id").orElse(null);
            if (rule != null && rule.action == Action.DROP_AFTER) return; // close with no HTTP response
            response.headers().map().forEach((name, values) -> {
                if (!HOP.contains(name.toLowerCase(Locale.ROOT))) exchange.getResponseHeaders().put(name, values);
            });
            byte[] content = response.body();
            exchange.sendResponseHeaders(status, content.length == 0 ? -1 : content.length);
            if (content.length > 0) exchange.getResponseBody().write(content);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Clients deliberately disconnect after read timeout. Never log credentials.
        } finally {
            observed.add(new Observation(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), trace, status, responseTrace));
            if (rule != null) rule.complete(status);
        }
    }

    @Override public void close() {
        if (active != null) active.release();
        server.stop(0);
        executor.shutdownNow();
        client.close();
    }
}
