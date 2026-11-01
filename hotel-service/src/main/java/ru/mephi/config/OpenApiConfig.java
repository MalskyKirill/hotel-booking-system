package ru.mephi.config;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.mephi.api.ApiError;
import java.util.*;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI publicApi() {
        return new OpenAPI().info(new Info().title("Hotel Service").version("1.0")
            .description("REST API системы бронирования. USER и ADMIN используют JWT, полученный через login."))
            .servers(List.of(new Server().url("/")))
            .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .security(List.of(new SecurityRequirement().addList("bearerAuth")));
    }

    @Bean
    OpenApiCustomizer publicContract() {
        return api -> {
            ModelConverters.getInstance().read(ApiError.class).forEach(api.getComponents()::addSchemas);
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                boolean auth = path.startsWith("/api/auth/");
                boolean admin = path.startsWith("/api/admin/") || ((path.startsWith("/api/hotels") || path.startsWith("/api/rooms"))
                    && method != PathItem.HttpMethod.GET);
                operation.setSecurity(auth ? List.of() : List.of(new SecurityRequirement().addList("bearerAuth")));
                operation.addExtension("x-required-roles", auth ? List.of() : admin ? List.of("ADMIN") : List.of("USER", "ADMIN"));
                addError(operation, "400", "Ошибка параметров или валидации. Подробности в fields.");
                if (!auth || path.endsWith("/login")) addError(operation, "401", "Требуется действующий JWT либо неверные учётные данные.");
                if (!auth) addError(operation, "403", "Недостаточно прав.");
                if (path.contains("{")) addError(operation, "404", "Ресурс отсутствует или недоступен владельцу.");
                if (method != PathItem.HttpMethod.GET && method != PathItem.HttpMethod.DELETE)
                    addError(operation, "409", "Конфликт данных или идемпотентности.");
                addError(operation, "500", "Внутренняя ошибка.");
                addError(operation, "503", "Сервис временно недоступен.");
            }));
        };
    }

    private static void addError(Operation operation, String status, String description) {
        if (!operation.getResponses().containsKey(status)) operation.getResponses().addApiResponse(status,
            new ApiResponse().description(description).content(new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
    }
}
