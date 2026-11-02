package ru.mephi.observability;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;
import static ru.mephi.observability.TraceContext.HEADER;

/** Runs before security, including error/async dispatches, and restores the previous MDC. */
@Component
public final class RequestCorrelationFilter extends OncePerRequestFilter implements Ordered {
    static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".traceId";
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException { doFilterInternal(request, response, chain); }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        String existing = (String) request.getAttribute(ATTRIBUTE);
        List<String> values = Collections.list(request.getHeaders(HEADER));
        String candidate = TraceContext.valid(existing) ? existing : values.size() == 1 ? values.getFirst() : null;
        long started = System.nanoTime();
        try (TraceContext context = TraceContext.open(candidate)) {
            request.setAttribute(ATTRIBUTE, context.id());
            response.setHeader(HEADER, context.id());
            boolean completed = false;
            try {
                chain.doFilter(new CorrelatedRequest(request, context.id()), response);
                completed = true;
            } finally {
                log.atInfo().addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("url.path", request.getRequestURI())
                    .addKeyValue("http.response.status_code", completed ? response.getStatus() : 500)
                    .addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .log("HTTP request completed");
            }
        }
    }

    private static final class CorrelatedRequest extends HttpServletRequestWrapper {
        private final String id;
        CorrelatedRequest(HttpServletRequest request, String id) { super(request); this.id = id; }
        @Override public String getHeader(String name) { return HEADER.equalsIgnoreCase(name) ? id : super.getHeader(name); }
        @Override public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name) ? Collections.enumeration(List.of(id)) : super.getHeaders(name);
        }
        @Override public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
            names.removeIf(HEADER::equalsIgnoreCase);
            names.add(HEADER);
            return Collections.enumeration(names);
        }
    }
}
