# Discovery Service

Eureka Server, порт 8761. Хранит регистрацию адресов Gateway, Booking и Hotel;
сам не регистрируется и не получает реестр как Eureka-клиент. Бизнес-БД и
пользовательского JWT API нет. Учебный запуск предполагает доверенную локальную сеть.

Из корня после `mvn -Psystem-tests -DskipTests package`:

```sh
java -jar discovery-service/target/discovery-service-1.0-SNAPSHOT.jar
```

UI: http://localhost:8761/. Запускайте перед клиентами. При изменении server.port
задайте eureka.client.serviceUrl.defaultZone всем трём клиентам. Распространение
реестра не мгновенно; ранние 503 возможны до обновления discovery.

[Полный локальный запуск](../README.md), [архитектура](../docs/architecture.md),
[системный стенд](../docs/system-verification.md).
