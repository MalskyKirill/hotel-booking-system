package ru.mephi.observability;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.*;
import java.io.IOException;

/** Propagates the current trace, preserving the caller's MDC across failures. */
public final class TracePropagationInterceptor implements ClientHttpRequestInterceptor {
    @Override public ClientHttpResponse intercept(HttpRequest request, byte[] body,
        ClientHttpRequestExecution execution) throws IOException {
        try (TraceContext context = TraceContext.open(TraceContext.currentOrNew())) {
            request.getHeaders().set(TraceContext.HEADER, context.id());
            return execution.execute(request, body);
        }
    }
}
