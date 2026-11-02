# Request Context

Обычная Java-библиотека, используется Gateway, Booking и Hotel. Собственного
порта, main-метода или БД нет. В Maven reactor собирается раньше потребителей.

RequestCorrelationFilter валидирует/создаёт X-Correlation-Id, возвращает его
клиенту, открывает MDC traceId и восстанавливает предыдущий контекст. Работает
до security, включая ERROR/ASYNC dispatch. TracePropagationInterceptor передаёт
тот же ID через RestClient. Допустимые ID: 1–64 символа A–Z/a–z/0–9/._-.

JUnit/MockMvc проверяет заголовки, восстановление MDC и исходящие запросы.
Из корня: `mvn -pl request-context -am verify`.
[Правила и ECS](../docs/observability.md), [архитектура](../docs/architecture.md),
[полные проверки](../docs/system-verification.md).
