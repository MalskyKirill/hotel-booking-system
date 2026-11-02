# API Gateway

Spring Cloud Gateway Server Web MVC, порт 8080. Публичный вход: маршрутизация
lb://booking-service и lb://hotel-service через Eureka, передача исходного JWT,
проверка RS256/issuer/времени и ролей, общая Swagger UI и корреляция.

Нужны работающая Eureka и тот же публичный RSA-ключ, что у Booking/Hotel.
Из корня проекта после сборки `mvn -Psystem-tests -DskipTests package`:

```sh
RSA_PUBLIC_KEY=file:/absolute/path/public.pem \
  java -jar api-gateway/target/api-gateway-1.0-SNAPSHOT.jar
```

Приватный ключ не нужен. /api/auth/register и /api/auth/login доступны без JWT;
каталоги и бронирования — USER/ADMIN, изменения каталога и /api/admin/** — ADMIN.
/actuator/** требует ADMIN. /internal/** не публикуется. Проверка в бизнес-сервисах
сохраняется. Swagger: http://localhost:8080/swagger-ui.html.

[Полный запуск и demo](../README.md), [маршруты и безопасность](../docs/api-errors-security.md),
[трассировка](../docs/observability.md), [проверки](../docs/system-verification.md).
