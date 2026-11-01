package ru.mephi.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.mephi.service.JwtService;
import ru.mephi.observability.TracePropagationInterceptor;

@Configuration
public class RestClientConfig {

    /**
     * Обычный билдер (без LoadBalancer).
     * @Primary — чтобы Eureka и другие внутренние клиенты
     * использовали именно его, а не @LoadBalanced.
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Билдер с LoadBalancer — только для вызовов по имени сервиса.
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * Готовый клиент для Hotel Service.
     * - Таймауты 2s connect / 5s read.
     * - Автоматическая подстановка служебного Bearer-токена.
     */
    @Bean("hotelServiceRestClient")
    public RestClient hotelServiceRestClient(
        @LoadBalanced RestClient.Builder loadBalancedBuilder,
        JwtService jwtService) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);

        return loadBalancedBuilder
            .baseUrl("http://hotel-service")
            .requestFactory(factory)
            .requestInterceptor(new TracePropagationInterceptor())
            .requestInterceptor((request, body, execution) -> {
                String token = jwtService.generateServiceToken();
                request.getHeaders().add("Authorization", "Bearer " + token);
                return execution.execute(request, body);
            })
            .build();
    }
}
