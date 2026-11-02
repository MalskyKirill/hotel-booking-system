# Hotel Booking System

REST API бронирования отелей: Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.0,
JWT RS256, JPA/Hibernate и отдельные H2 in-memory. Пользователь регистрируется,
входит, ищет свободные номера, бронирует вручную или автоматически, читает свою
историю и отменяет бронь. ADMIN управляет каталогом и пользователями, смотрит
загрузку. Booking координирует Saga, Hotel атомарно резервирует номера.

| Приложение | Порт по умолчанию | Назначение |
|---|---:|---|
| [discovery-service](discovery-service/README.md) | 8761 | Eureka |
| [hotel-service](hotel-service/README.md) | 8082 | Отели, номера, занятость, рекомендации, статистика |
| [booking-service](booking-service/README.md) | 8083 | Пользователи, JWT, бронирования, Saga |
| [api-gateway](api-gateway/README.md) | 8080 | Публичные маршруты, JWT/роли, Swagger |

Вспомогательные модули: [request-context](request-context/README.md),
[api-support](api-support/README.md), [system-tests](system-tests/README.md).
[Архитектура](docs/architecture.md) · [ER и словарь БД](docs/data-model.md) ·
[ADR](docs/adr/README.md).

## Требования и сборка

Установите полный **JDK 21** и Maven **3.9+**, добавьте их bin в PATH, задайте
JAVA_HOME на каталог JDK. Для ручного demo нужны Bash, OpenSSL, curl и jq.
Проверьте, что Maven использует тот же JDK, а javac установлен:

```sh
java -version
javac -version
mvn -version
git clone https://github.com/MalskyKirill/hotel-booking-system.git
cd hotel-booking-system
mvn -B -ntp -Psystem-tests -DskipTests package
```

Все Java-библиотеки и плагины загружает Maven из Maven Central; вручную копировать
JAR не нужно. Профиль system-tests включает repackage: четыре сервисных JAR
становятся исполняемыми. Обычный package без профиля этого не делает. Флаг
-DskipTests подходит для быстрого запуска; обязательный полный прогон приведён
ниже. Форматирование проверяется и при такой сборке.

Если среда требует HTTP-прокси, настройте Maven settings.xml и доверие к CA в
используемом JDK. Ошибка PKIX означает проблему доверия сертификатам; не отключайте
TLS-проверку. После успешного получения зависимостей возможен повтор с `mvn -o`.
Четыре JVM требуют отдельной памяти; приведённый demo ограничивает heap каждой
до 384 MiB, полный стенд дополнительно запускает Maven и тестовую JVM.

## Ключи и пароли demo

Откройте отдельный Bash и выполняйте следующие блоки **в одной сессии из корня
репозитория**. Порты 8761, 8080, 8082, 8083 должны быть свободны. Ключи создаются
в новом временном каталоге вне Git; существующие пары не перезаписываются.

```bash
set -euo pipefail
umask 077
HOTEL_RUN_DIR=$(mktemp -d "${TMPDIR:-/tmp}/hotel-demo.XXXXXX")
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$HOTEL_RUN_DIR/private.pem" 2>"$HOTEL_RUN_DIR/keygen.log"
openssl pkey -in "$HOTEL_RUN_DIR/private.pem" -pubout -out "$HOTEL_RUN_DIR/public.pem"
export RSA_PUBLIC_KEY="file:$HOTEL_RUN_DIR/public.pem"
export NO_PROXY="${NO_PROXY:+$NO_PROXY,}localhost,127.0.0.1"
DEMO_ADMIN_PASSWORD=$(openssl rand -hex 16)
DEMO_USER_PASSWORD=$(openssl rand -hex 16)
HOTEL_PIDS=()
stop_hotel_demo() {
  if ((${#HOTEL_PIDS[@]} > 1)); then
    kill "${HOTEL_PIDS[@]:1}" 2>/dev/null || true
    wait "${HOTEL_PIDS[@]:1}" 2>/dev/null || true
  fi
  if ((${#HOTEL_PIDS[@]})); then
    kill "${HOTEL_PIDS[0]}" 2>/dev/null || true
    wait "${HOTEL_PIDS[0]}" 2>/dev/null || true
  fi
  HOTEL_PIDS=()
  rm -f "$HOTEL_RUN_DIR/private.pem" "$HOTEL_RUN_DIR/public.pem"
  unset USER_TOKEN ADMIN_TOKEN NEW_TOKEN USER_LOGIN ADMIN_LOGIN NEW_PASSWORD
  unset DEMO_ADMIN_PASSWORD DEMO_USER_PASSWORD RSA_PUBLIC_KEY
}
trap stop_hotel_demo EXIT
```

PUBLIC KEY — X.509, PRIVATE KEY — PKCS#8. Публичный ключ нужен Gateway, Booking и
Hotel; приватный передаётся только Booking. Пароли генерируются во время запуска
и не выводятся. Не включайте `set -x` при работе с JWT и паролями. В репозитории
нет готового приватного ключа; встроенные публичные файлы не заменяют новую пару.

| Настройка | Где используется |
|---|---|
| RSA_PUBLIC_KEY / rsa.public-key | Общий публичный ключ: Gateway, Booking, Hotel |
| RSA_PRIVATE_KEY / rsa.private-key | Только Booking; ниже задан CLI-путём к файлу |
| DEMO_ADMIN_PASSWORD, DEMO_USER_PASSWORD | Booking с demo, минимум 8 символов и максимум 72 UTF-8 байта |
| DEMO_ADMIN_USERNAME, DEMO_USER_USERNAME | Необязательные имена demo; по умолчанию demo-admin / demo-user |
| SPRING_PROFILES_ACTIVE / spring.profiles.active | demo — данные; dev — локальная H2 Console |
| SERVER_PORT / server.port | Порт конкретного приложения |
| eureka.client.serviceUrl.defaultZone | URL Eureka; стандартно http://localhost:8761/eureka/ |
| SPRING_DATASOURCE_URL / spring.datasource.url | Отдельный datasource каждого бизнес-сервиса |

В примерах CLI явно задаёт порты, БД и demo-имена, чтобы старые переменные
окружения не меняли сценарий. Параметры Saga и Circuit Breaker описаны в [Saga](docs/booking-saga.md).

## Запуск

Функция сохраняет PID только запущенных ею JVM, а логи — в HOTEL_RUN_DIR. Ожидание
проверяет и процессы, и HTTP; ошибка завершает demo и вызывает очистку.

```bash
start_service() {
  local service="$1"
  shift
  java -Xmx384m -jar "$service/target/$service-1.0-SNAPSHOT.jar" \
    --server.address=127.0.0.1 \
    --eureka.instance.prefer-ip-address=true --eureka.instance.ip-address=127.0.0.1 \
    --eureka.client.registry-fetch-interval-seconds=1 \
    "$@" >"$HOTEL_RUN_DIR/$service.log" 2>&1 &
  HOTEL_PIDS+=("$!")
}
wait_http() {
  local url="$1"
  for attempt in {1..120}; do
    for pid in "${HOTEL_PIDS[@]}"; do
      if ! kill -0 "$pid" 2>/dev/null; then
        echo "Приложение завершилось. Логи: $HOTEL_RUN_DIR" >&2
        return 1
      fi
    done
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "HTTP не готов: $url. Логи: $HOTEL_RUN_DIR" >&2
  return 1
}
EUREKA_URL=http://localhost:8761/eureka/
start_service discovery-service --server.port=8761 --eureka.server.response-cache-update-interval-ms=1000
wait_http http://localhost:8761/
start_service hotel-service --server.port=8082 --spring.profiles.active=demo \
  --eureka.client.serviceUrl.defaultZone="$EUREKA_URL" \
  --spring.datasource.url=jdbc:h2:mem:hotel_readme \
  --spring.datasource.driver-class-name=org.h2.Driver \
  --spring.datasource.username=sa --spring.datasource.password= --spring.jpa.show-sql=false
wait_http http://localhost:8082/v3/api-docs
DEMO_ADMIN_PASSWORD="$DEMO_ADMIN_PASSWORD" DEMO_USER_PASSWORD="$DEMO_USER_PASSWORD" \
  start_service booking-service --server.port=8083 --spring.profiles.active=demo \
  --demo.admin.username=demo-admin --demo.user.username=demo-user \
  --rsa.private-key="file:$HOTEL_RUN_DIR/private.pem" \
  --eureka.client.serviceUrl.defaultZone="$EUREKA_URL" \
  --spring.datasource.url=jdbc:h2:mem:booking_readme \
  --spring.datasource.driver-class-name=org.h2.Driver \
  --spring.datasource.username=sa --spring.datasource.password= --spring.jpa.show-sql=false
wait_http http://localhost:8083/v3/api-docs
start_service api-gateway --server.port=8080 \
  --eureka.client.serviceUrl.defaultZone="$EUREKA_URL"
BASE_URL=http://localhost:8080
wait_http "$BASE_URL/v3/api-docs/booking"
wait_http "$BASE_URL/v3/api-docs/hotel"
```

Локальный demo регистрирует в Eureka тот же 127.0.0.1, на котором слушают JVM.
Это предотвращает обращение к адресу контейнера при loopback-привязке. Для
развёртывания на разных машинах задаются реальные доступные адреса экземпляров.
Для demo интервалы обновления реестра и серверного кэша сокращены до секунды;
Hotel и Booking запускаются с ожиданием HTTP-готовности перед следующим JVM.
При остановке клиенты завершаются до Eureka, чтобы успеть снять регистрацию.
Eureka распространяет адреса не мгновенно; ранний 503 допустим до готовности
маршрутов. Ответ 401 Gateway сам по себе не доказывает готовность backend,
поэтому ожидаются обе спецификации. Если запуск не удался, проверьте свободные
порты, JAVA_HOME, пути ключей, demo-пароли и журналы в HOTEL_RUN_DIR.

Профиль demo создаёт двух пользователей, два отеля и шесть номеров, два из них
недоступны для эксплуатации. Резервов изначально нет. Без demo обе бизнес-БД
пустые; обычная регистрация создаёт только USER. Первичного ADMIN для учебного
запуска создаёт demo-профиль. Подробности: [демонстрационные данные](docs/admin-demo.md).

## Получение USER и ADMIN JWT

JWT хранится в shell-переменных. expiresIn=3600, tokenType=Bearer. Login выполняется
обычным публичным API; пароли не передаются в URL.

```bash
USER_LOGIN=$(jq -n --arg password "$DEMO_USER_PASSWORD" \
  '{username:"demo-user",password:$password}' | \
  curl -fsS "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' -d @-)
USER_TOKEN=$(printf '%s' "$USER_LOGIN" | jq -er '.accessToken')
ADMIN_LOGIN=$(jq -n --arg password "$DEMO_ADMIN_PASSWORD" \
  '{username:"demo-admin",password:$password}' | \
  curl -fsS "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' -d @-)
ADMIN_TOKEN=$(printf '%s' "$ADMIN_LOGIN" | jq -er '.accessToken')
printf '%s' "$USER_LOGIN" | jq '{tokenType,expiresIn}'
printf '%s' "$ADMIN_LOGIN" | jq '{tokenType,expiresIn}'
```

Новая регистрация — отдельная операция, возвращает **201 с id/username/role=USER**.
JWT выдаётся последующим login, роль ADMIN регистрацией получить нельзя.

```bash
NEW_USERNAME="reader-$(openssl rand -hex 6)"
NEW_PASSWORD=$(openssl rand -hex 16)
jq -n --arg username "$NEW_USERNAME" --arg password "$NEW_PASSWORD" \
  '{username:$username,password:$password}' | \
  curl -fsS "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' -d @- | jq .
NEW_TOKEN=$(jq -n --arg username "$NEW_USERNAME" --arg password "$NEW_PASSWORD" \
  '{username:$username,password:$password}' | \
  curl -fsS "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' -d @- | jq -er '.accessToken')
```

## Рекомендации и бронирование

Даты ISO, интервал `[startDate,endDate)`. available означает эксплуатационное
состояние; свободность дополнительно учитывает пересечение HELD/CONFIRMED.
Выбираем ID из API, фиксированные ID demo не предполагаются.

```bash
HOTELS=$(curl -fsS "$BASE_URL/api/hotels" -H "Authorization: Bearer $USER_TOKEN")
printf '%s' "$HOTELS" | jq .
HOTEL_ID=$(printf '%s' "$HOTELS" | jq -er '.[0].id')
START_DATE=2027-01-01
END_DATE=2027-01-03
SEARCH_URL="$BASE_URL/api/rooms?hotelId=$HOTEL_ID&startDate=$START_DATE&endDate=$END_DATE"
RECOMMEND_URL="$BASE_URL/api/rooms/recommend?hotelId=$HOTEL_ID&startDate=$START_DATE&endDate=$END_DATE"
curl -fsS "$SEARCH_URL" -H "Authorization: Bearer $USER_TOKEN" | jq .
ROOM_ID=$(curl -fsS "$RECOMMEND_URL" -H "Authorization: Bearer $USER_TOKEN" | jq -er '.[0].id')
```

Ручное бронирование: на здоровом стеке ожидается 200/CONFIRMED. Повторяем **то же
тело** с прежним requestId и получаем прежний ID, без увеличения счётчика.

```bash
MANUAL_BODY=$(jq -n --argjson roomId "$ROOM_ID" --arg requestId "manual-$(openssl rand -hex 12)" \
  --arg startDate "$START_DATE" --arg endDate "$END_DATE" \
  '{roomId:$roomId,requestId:$requestId,startDate:$startDate,endDate:$endDate}')
MANUAL_RESULT=$(curl -fsS "$BASE_URL/api/bookings" -H "Authorization: Bearer $USER_TOKEN" \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: readme-manual' -d "$MANUAL_BODY")
printf '%s' "$MANUAL_RESULT" | jq .
BOOKING_ID=$(printf '%s' "$MANUAL_RESULT" | jq -er '.id')
curl -fsS "$BASE_URL/api/bookings" -H "Authorization: Bearer $USER_TOKEN" \
  -H 'Content-Type: application/json' -d "$MANUAL_BODY" | jq .
```

Автоподбор в том же отеле возьмёт второй доступный номер. roomId при
autoSelect=true игнорируется; сортировка — timesBooked ASC, id ASC.

```bash
AUTO_BODY=$(jq -n --argjson hotelId "$HOTEL_ID" --arg requestId "auto-$(openssl rand -hex 12)" \
  --arg startDate "$START_DATE" --arg endDate "$END_DATE" \
  '{autoSelect:true,roomId:999999,hotelId:$hotelId,requestId:$requestId,startDate:$startDate,endDate:$endDate}')
AUTO_RESULT=$(curl -fsS "$BASE_URL/api/bookings" -H "Authorization: Bearer $USER_TOKEN" \
  -H 'Content-Type: application/json' -d "$AUTO_BODY")
printf '%s' "$AUTO_RESULT" | jq .
AUTO_ID=$(printf '%s' "$AUTO_RESULT" | jq -er '.id')
```

При неопределённом результате POST возвращает 202/PENDING. Перед продолжением
проверьте GET по ID до терминального статуса; 409 BOOKING_CANCELLED содержит ID
в fields.bookingId. Новая попытка после отмены требует нового requestId.
Повтор с изменёнными параметрами/владельцем даёт 409 IDEMPOTENCY_CONFLICT.
[Подробный протокол Saga](docs/booking-saga.md).

## История, статистика и отмена

История имеет нумерацию страниц с нуля, size от 1 до 100. Чужая бронь возвращает
404 даже ADMIN. Статистика требует ADMIN; USER получает 403.

```bash
curl -fsS "$BASE_URL/api/bookings?page=0&size=20" -H "Authorization: Bearer $USER_TOKEN" | jq .
curl -fsS "$BASE_URL/api/bookings/$BOOKING_ID" -H "Authorization: Bearer $USER_TOKEN" | jq .
curl -fsS "$BASE_URL/api/admin/rooms/statistics?hotelId=$HOTEL_ID&startDate=$START_DATE&endDate=$END_DATE" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
curl -fsS -X DELETE "$BASE_URL/api/bookings/$BOOKING_ID" -H "Authorization: Bearer $USER_TOKEN" | jq .
curl -fsS -X DELETE "$BASE_URL/api/bookings/$AUTO_ID" -H "Authorization: Bearer $USER_TOKEN" | jq .
curl -fsS -X DELETE "$BASE_URL/api/bookings/$BOOKING_ID" -H "Authorization: Bearer $USER_TOKEN" | jq .
curl -fsS "$SEARCH_URL" -H "Authorization: Bearer $USER_TOKEN" | jq .
```

DELETE возвращает 200/CANCELLED либо 202 с cancellationRequested=true. Во втором
случае проверяйте GET до CANCELLED и сброса флага: слот ещё может быть занят.
Повтор отмены безопасен, timesBooked не уменьшается.
[История и отмена](docs/booking-history-cancellation.md), [статистика](docs/admin-demo.md).

## Карта публичного API

| Маршруты | Операции и доступ |
|---|---|
| POST /api/auth/register, /api/auth/login | Регистрация USER и вход, без JWT |
| POST/GET /api/bookings; GET/DELETE /api/bookings/{id} | Создание, своя история, чтение и отмена; USER/ADMIN |
| GET /api/hotels и /api/hotels/{id} | Каталог; USER/ADMIN |
| POST/PUT/DELETE /api/hotels[/{id}] | Отели; ADMIN |
| GET /api/rooms, /api/rooms/recommend | Свободные номера и рекомендации с обязательными датами; USER/ADMIN |
| POST /api/rooms | Создание номера; ADMIN |
| /api/admin/rooms[/{id}] | GET/POST/PUT/DELETE номеров; ADMIN |
| GET /api/admin/rooms/statistics | Загрузка за период; ADMIN |
| /api/admin/users[/{id}] | GET/POST/PATCH/DELETE пользователей; ADMIN |

Точные тела, фильтры и ограничения удаления: [администрирование](docs/admin-demo.md)
и [Swagger](docs/openapi.md). Внутренние маршруты и SERVICE JWT клиентскому API
не предназначены. Ошибки имеют [единый контракт ApiError](docs/api-errors-security.md).

## Swagger

- http://localhost:8080/swagger-ui.html — выбор Booking Service / Hotel Service.
- http://localhost:8080/v3/api-docs/booking и http://localhost:8080/v3/api-docs/hotel — спецификации.
- http://localhost:8761/ — реестр Eureka.

В Authorize вставляйте значение JWT без префикса Bearer. Бизнес-операции остаются
защищёнными, документация доступна без JWT. H2 Console по умолчанию выключена;
локальный dev-профиль и ограничения доступа описаны в [безопасности](docs/api-errors-security.md).

## Остановка demo

В исходной сессии Bash выполните:

```bash
stop_hotel_demo
trap - EXIT
```

Процессы остановлены, временные ключи удалены, логи остаются в HOTEL_RUN_DIR.
H2 теряет данные после остановки JVM. Восстановление после потери самой БД,
распределённый отзыв JWT после смены роли/пароля, горизонтальный HA и production
SLA не реализованы. Подробнее о решениях: [ADR](docs/adr/README.md),
о диагностиках — [корреляция и ECS](docs/observability.md).

## Тесты и стиль

Остановите ручной demo перед clean-сборкой, затем из корня выполните:

```sh
# JUnit/MockMvc и проверки форматирования
mvn -B -ntp clean verify
# Исполняемые JAR и полный системный прогон
mvn -B -ntp -Psystem-tests clean verify
# Если стандартные тестовые порты 19080–19084 заняты
mvn -B -ntp -Psystem-tests -Dsystem.base-port=20080 verify
# Применить стиль, включая системный модуль
mvn -B -ntp -Psystem-tests spotless:apply
```

Системный стенд сам создаёт RSA-ключи, отдельные H2, запускает четыре JVM и
останавливает их. Отчёты: */target/surefire-reports и
system-tests/target/failsafe-reports; логи: system-tests/target/stack-logs.
[Все сценарии и CI](docs/system-verification.md), [правила стиля](docs/code-style.md).
