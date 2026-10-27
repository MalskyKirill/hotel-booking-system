package ru.mephi.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;

@Configuration
public class GatewayConfig {

    @Bean
    public RestClientCustomizer restClientCustomizer () {
        return restClientBuilder -> restClientBuilder.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {});
    }
}
