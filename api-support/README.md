# API Support

Обычная Java-библиотека общего контракта ошибок для Gateway, Booking и Hotel.
Не содержит main-метод, порт, БД или JPA-сущности. Сборка — в Maven reactor.

Содержит ApiError (timestamp/status/error/code/message/path/fields), /error,
базовые MVC/security-обработчики и помощники ответов фильтров. Сервисы подключают
advice короткими классами, доменные ошибки остаются локальными. Gateway расширяет
обработку транспортных сбоев. Spring Security у потребителя библиотека сама не
включает; security-зависимость объявлена optional.

[Контракт и приоритеты](../docs/api-errors-security.md),
[ADR](../docs/adr/007-shared-api-support.md). Контрактные тесты выполняются в
каждом HTTP-приложении, включая Spring-контексты и реальный системный стек.
Из корня: `mvn verify` либо [полный прогон](../docs/system-verification.md).
