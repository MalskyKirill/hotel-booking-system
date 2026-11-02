# System Tests

Необязательный модуль Maven-профиля system-tests, Java 21 + JUnit Jupiter +
Failsafe. Запускает четыре настоящие JVM и HTTP-прокси сбоев; создаёт временные
RSA-ключи, demo-пароли, отдельные H2 и данные. Не подключается к существующему
развёртыванию. После проверок останавливает свои процессы и удаляет ключи.

Из корня репозитория:

```sh
mvn -B -ntp -Psystem-tests clean verify
# Весь диапазон портов вместо 19080–19084
mvn -B -ntp -Psystem-tests -Dsystem.base-port=20080 verify
```

Профиль также упаковывает приложения в исполняемые JAR. Reactor-зависимости
гарантируют их сборку перед Failsafe. 20 системных JUnit-проверок покрывают
конкуренцию, идемпотентность, компенсацию, timeout, Circuit Breaker, JWT, Swagger
и сквозные логи. Прокси создаёт сетевые сбои, бизнес-логика и БД не подменяются.

Отчёты: target/failsafe-reports, логи: target/stack-logs. Порты 19080–19084:
Gateway/Eureka/Hotel/Booking/прокси, только 127.0.0.1. Результаты ограничены одним
экземпляром каждого сервиса и сохранностью in-memory БД.

[Описание сценариев и CI](../docs/system-verification.md),
[общий запуск](../README.md), [архитектура](../docs/architecture.md).
