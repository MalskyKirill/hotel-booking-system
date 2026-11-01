# Swagger и OpenAPI

Документация генерируется springdoc-openapi 2.8.17 из контроллеров, DTO и аннотаций
Bean Validation. Поддерживаются Spring Boot 3.5 и OpenAPI 3.0. Spring Security
сохраняет проверку JWT и ролей на бизнес-маршрутах; страницы документации доступны
без авторизации.

При стандартных портах приложения:

| Адрес | Содержимое |
|---|---|
| http://localhost:8080/swagger-ui.html | Общий интерфейс через Gateway, выбор Booking Service или Hotel Service |
| http://localhost:8080/v3/api-docs/booking | Спецификация Booking через Gateway |
| http://localhost:8080/v3/api-docs/hotel | Спецификация Hotel через Gateway |
| http://localhost:8083/v3/api-docs | Спецификация непосредственно Booking |
| http://localhost:8082/v3/api-docs | Спецификация непосредственно Hotel |

Для UI через Gateway должны работать Eureka и оба бизнес-сервиса. Локальная UI
также доступна на /swagger-ui.html каждого бизнес-сервиса. В спецификациях задан
относительный server URL /, поэтому Try it out использует тот же origin, через
который открыта документация.

## Аутентификация в UI

1. Откройте Booking Service. Зарегистрируйте пользователя через POST /api/auth/register.
2. Вызовите POST /api/auth/login и скопируйте accessToken.
3. Нажмите Authorize и вставьте JWT. Схема bearerAuth добавляет Bearer автоматически.
4. Выполняйте операции. Для изменения отелей/номеров и /api/admin/** нужен токен ADMIN;
   регистрация создаёт USER. Тестовые администраторы описаны в [demo-профиле](admin-demo.md).

Операции login и register объявлены без обязательного JWT. Остальные операции
содержат bearerAuth и расширение x-required-roles. Из публичной схемы исключены
/internal/** и технический /error; отдельный внутренний SERVICE JWT не предлагается
как способ доступа к пользовательским операциям.

Регистрация возвращает 201 и id/username/role созданного USER; токен выдаётся
отдельным login. Заголовок Location не возвращается: публичного маршрута чтения
профиля пользователя в этом API нет. Административное чтение доступно через
/api/admin/users/{id} с ролью ADMIN.

Создание ресурсов описывает 201, удаление — 204. POST бронирования показывает
200 CONFIRMED, 202 с ожидающим итогом и 409 с ApiError: BOOKING_CANCELLED содержит
fields.bookingId, конфликт ключа — IDEMPOTENCY_CONFLICT. DELETE бронирования
показывает завершённую отмену 200 и отложенную 202. Общие ошибки используют
семипольную схему ApiError. Режим автоподбора, даты и requestId описаны в DTO.

MockMvc проверяет схемы, статусы и права доступа к документации. Системный JUnit
тест загружает обе спецификации и UI через настоящий Gateway:
[команды запуска](system-verification.md).
