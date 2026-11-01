package ru.mephi.observability;

import org.slf4j.MDC;
import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation metadata shared by HTTP requests and background Saga recovery. */
public final class TraceContext implements AutoCloseable {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final String previous;
    private final String id;

    private TraceContext(String id) {
        previous = MDC.get(MDC_KEY);
        this.id = id;
        MDC.put(MDC_KEY, id);
    }
    public static boolean valid(String value) { return value != null && VALID.matcher(value).matches(); }
    public static String newId() { return UUID.randomUUID().toString().replace("-", ""); }
    public static String currentOrNew() {
        String value = MDC.get(MDC_KEY);
        return valid(value) ? value : newId();
    }
    public static TraceContext open(String candidate) {
        return new TraceContext(valid(candidate) ? candidate : newId());
    }
    public static TraceContext background(Long bookingId) {
        return open("saga-" + bookingId + "-" + newId());
    }
    public String id() { return id; }
    @Override public void close() {
        if (previous == null) MDC.remove(MDC_KEY);
        else MDC.put(MDC_KEY, previous);
    }
}
