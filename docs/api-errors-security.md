# Ошибки API, доступ и запуск

Booking Service, Hotel Service и Gateway используют единый JSON-контракт
[ApiError](api-error.schema.json): timestamp, status (число HTTP), error, code,
message, path, fields. fields всегда объект: ошибки полей при валидации либо
дополнительный контекст бизнес-ошибки. SQL, stack trace, причины разбора JSON и
отклонённые значения полей в ответ не включаются.

| Ситуация | HTTP / code |
|---|---|
| Отсутствующий/невалидный JWT | 401 UNAUTHORIZED, WWW-Authenticate: Bearer |
| Неверный логин или пароль | 401 INVALID_CREDENTIALS |
| Недостаточно прав | 403 ACCESS_DENIED |
| Ошибки DTO | 400 VALIDATION_FAILED, fields содержит сообщения |
| Повреждённое или отсутствующее тело | 400 INVALID_REQUEST_BODY |
| Отсутствующий параметр | 400 MISSING_PARAMETER |
| Неверный тип параметра | 400 INVALID_PARAMETER |
| Нет маршрута / неверный метод | 404 NOT_FOUND / 405 METHOD_NOT_ALLOWED (Allow сохраняется) |
| Конфликт БД | 409 DATA_INTEGRITY_CONFLICT |
| Сетевой отказ upstream в Gateway | 503 SERVICE_UNAVAILABLE |
| Ответ ошибки от upstream | Исходный HTTP, UPSTREAM_ERROR |
| Неожиданная ошибка | 500 INTERNAL_ERROR, безопасное сообщение |

Специфические доменные коды сохраняются: HOTEL_NOT_FOUND, ROOM_NOT_FOUND,
RESERVATION_NOT_FOUND, RESERVATION_CONFLICT, ROOM_UNAVAILABLE,
IDEMPOTENCY_CONFLICT, INVALID_RESERVATION_STATE, NO_AVAILABLE_ROOM,
HOTEL_HAS_ROOMS, ROOM_ALREADY_EXISTS, BOOKING_NOT_FOUND, USERNAME_ALREADY_EXISTS.
ResponseStatusException сохраняет HTTP-статус и безопасную причину из приложения;
код соответствует имени статуса, например BAD_REQUEST или CONFLICT.

POST бронирования, завершившийся CANCELLED, возвращает 409 BOOKING_CANCELLED:
fields.bookingId — строковый ID, fields.bookingStatus — CANCELLED. Полную запись
можно прочитать через GET /api/bookings/{id}. Успехи 200/202, история и успешный
DELETE возвращают свои DTO. Ошибки Registry Eureka сохраняют протокол библиотеки;
пользовательский ApiError на него не распространяется.

## Матрица доступа

- POST /api/auth/register и /api/auth/login — без JWT.
- GET /api/hotels/** и /api/rooms/** — USER или ADMIN.
- /api/bookings/** — USER или ADMIN; операции ограничены владельцем.
- Административные API и изменение отелей/номеров — ADMIN, включая методную защиту.
- /internal/** в Hotel — SERVICE, с проверкой подписи, issuer, времени, aud=hotel-service
  и sub=booking-service. Gateway эти маршруты не публикует.

Служебная и неизвестная роль не дают доступ к пользовательским каталогам.
Срок пользовательского JWT — час, служебного — пять минут. Смена роли/пароля
не отзывает выданные JWT: ограничение описано в [администрировании](admin-demo.md).

Регистрация ограничивает логин 100 символами, пароль — 8–72 символами и максимум
72 UTF-8 байтами. Слишком длинный пароль при входе даёт обычный INVALID_CREDENTIALS,
а не ошибку BCrypt. В Postman-коллекциях задайте token для чтения и adminToken для
административных изменений; значения токенов в репозитории отсутствуют.

## Запуск и H2 Console

Все четыре main-метода передают CLI-аргументы в SpringApplication. Например,
`java -jar booking-service/target/booking-service-1.0-SNAPSHOT.jar --server.port=8083`
принимает настройки после имени JAR. Внешние ключи задаются через
`--rsa.public-key=file:/path/public.pem` и (только Booking)
`--rsa.private-key=file:/path/private.pem`. Выбор demo-профиля и пароли описаны
в [demo-инструкции](admin-demo.md).

H2 Console по умолчанию отключена в обоих бизнес-сервисах, в том числе при demo.
Для локальной разработки профиль dev включает /h2-console и запрещает удалённый
доступ средствами H2 (web-allow-others=false). Можно совместить demo,dev. Console
разрешена без JWT только при явном включении свойства; остальные маршруты
сохраняют ограничения доступа. Gateway не публикует консоль. Явные настройки
оператора через Spring properties имеют обычный приоритет над YAML-профилем.
