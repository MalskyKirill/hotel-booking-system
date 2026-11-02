# Hotel Service

Порт 8082; собственная H2 hoteldb. Владеет отелями, номерами, резервами,
рекомендациями, атомарным автоподбором и статистикой. Данные Booking получает
только через REST; межсервисных JPA-связей нет.

Нужны Eureka и публичный ключ Booking. Из корня после
`mvn -Psystem-tests -DskipTests package`:

```sh
RSA_PUBLIC_KEY=file:/absolute/path/public.pem \
  java -jar hotel-service/target/hotel-service-1.0-SNAPSHOT.jar
```

--spring.profiles.active=demo создаёт два отеля и шесть номеров, два недоступны.
available — эксплуатационный флаг. Занятость определяется пересечениями
HELD/CONFIRMED в room_reservations; RELEASED даты не занимает.

Публичные API: /api/hotels, /api/rooms, /api/rooms/recommend, /api/admin/rooms
и /api/admin/rooms/statistics. Запись требует ADMIN, чтение каталогов — USER/ADMIN.
/internal/** принимает только SERVICE JWT с нужными aud/sub и не публикуется
через Gateway. timesBooked увеличивается один раз при подтверждении.

Swagger непосредственно: http://localhost:8082/swagger-ui.html.
[Полный demo](../README.md), [алгоритм](../docs/room-selection.md),
[CRUD и статистика](../docs/admin-demo.md), [схема](../docs/data-model.md),
[системные проверки](../docs/system-verification.md).
