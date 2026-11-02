# Booking Service

Порт 8083; собственная H2 bookingdb. Владеет пользователями, выпуском JWT,
историей и координатором Saga. Контроллеры используют DTO/Bean Validation,
транзакционные бины — локальные транзакции, репозитории — JPA и блокировки.

Нужны Eureka, Hotel и внешняя RSA-пара. Из корня после
`mvn -Psystem-tests -DskipTests package`:

```sh
RSA_PUBLIC_KEY=file:/absolute/path/public.pem \
RSA_PRIVATE_KEY=file:/absolute/path/private.pem \
  java -jar booking-service/target/booking-service-1.0-SNAPSHOT.jar
```

Обычный профиль пустой. Для demo задайте DEMO_ADMIN_PASSWORD/DEMO_USER_PASSWORD
и добавьте --spring.profiles.active=demo. Пароли в репозитории отсутствуют;
[полный сценарий](../README.md) генерирует их и получает ADMIN/USER JWT.

API: /api/auth/register, /api/auth/login, /api/bookings[/{id}], /api/admin/users[/{id}].
Регистрация возвращает 201 USER без JWT; login — токен на час. История и отмена
ограничены владельцем. Неизвестный результат Saga — 202 с сохранённым состоянием.
Booking → Hotel использует отдельный SERVICE JWT на пять минут.

Swagger непосредственно: http://localhost:8083/swagger-ui.html. Публичный вход
для demo — Gateway. H2 in-memory теряется при остановке.

[Saga](../docs/booking-saga.md), [история/отмена](../docs/booking-history-cancellation.md),
[пользователи](../docs/admin-demo.md), [модель](../docs/data-model.md),
[тесты](../docs/system-verification.md).
