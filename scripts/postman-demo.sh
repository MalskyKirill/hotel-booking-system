#!/usr/bin/env bash
# Bash 3.2 compatible: works with the shell shipped on macOS.
set -euo pipefail
umask 077

HBS_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$HBS_ROOT"
HBS_STATE="$HBS_ROOT/.local/postman"
HBS_MAVEN_CMD=${HBS_MAVEN_CMD:-mvn}
HBS_JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
HBS_PIDS=()
HBS_RUN_DIR=
HBS_BUILD_PID=
HBS_WATCH_PID=
HBS_PARENT_PID=$PPID
HBS_LAUNCHER_PID=$$

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

for hbs_tool in "$HBS_JAVA" "$HBS_MAVEN_CMD" openssl curl; do
  command -v "$hbs_tool" >/dev/null 2>&1 || fail "Не найдена команда: $hbs_tool. Открой проект с JDK 21 и запусти конфигурацию Hotel Booking - Postman в IDEA."
done

for hbs_port in 8761 8080 8082 8083; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$hbs_port") 2>/dev/null; then
    fail "Порт $hbs_port занят. Останови ранее запущенные сервисы и повтори запуск. Чужие процессы не останавливаются автоматически."
  fi
done

mkdir -p "$HBS_STATE"
chmod 700 "$HBS_ROOT/.local" "$HBS_STATE"
mkdir "$HBS_STATE/run.lock" 2>/dev/null || fail "Другой запуск уже использует $HBS_STATE/run.lock. Если предыдущий запуск аварийно завершён, проверь его процессы перед удалением этого каталога."

stop_group() {
  local hbs_pid hbs_attempt hbs_alive
  if (($# == 0)); then return; fi
  kill "$@" 2>/dev/null || true
  for hbs_attempt in {1..10}; do
    hbs_alive=false
    for hbs_pid in "$@"; do
      if kill -0 "$hbs_pid" 2>/dev/null; then hbs_alive=true; fi
    done
    if [[ "$hbs_alive" == false ]]; then break; fi
    sleep 1
  done
  for hbs_pid in "$@"; do
    if kill -0 "$hbs_pid" 2>/dev/null; then kill -KILL "$hbs_pid" 2>/dev/null || true; fi
    wait "$hbs_pid" 2>/dev/null || true
  done
}

cleanup() {
  local hbs_status=$?
  trap - EXIT INT TERM
  if [[ -n "$HBS_WATCH_PID" ]]; then
    kill "$HBS_WATCH_PID" 2>/dev/null || true
    wait "$HBS_WATCH_PID" 2>/dev/null || true
  fi
  if [[ -n "$HBS_BUILD_PID" ]]; then stop_group "$HBS_BUILD_PID"; fi
  # Stop clients before Eureka so they can unregister normally.
  if ((${#HBS_PIDS[@]} > 1)); then stop_group "${HBS_PIDS[@]:1}"; fi
  if ((${#HBS_PIDS[@]} > 0)); then stop_group "${HBS_PIDS[0]}"; fi
  if [[ -n "$HBS_RUN_DIR" ]]; then rm -f "$HBS_RUN_DIR/private.pem" "$HBS_RUN_DIR/public.pem"; fi
  rm -f "$HBS_STATE/run.lock/pid"
  rmdir "$HBS_STATE/run.lock" 2>/dev/null || true
  exit "$hbs_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf '%s\n' "$$" > "$HBS_STATE/run.lock/pid"

# Maven's synchronous exec goal does not always terminate its child on Stop.
# Keep ownership even if the IDE terminates only the parent Maven JVM.
watch_parent() {
  while kill -0 "$HBS_PARENT_PID" 2>/dev/null; do sleep 1; done
  kill -TERM "$HBS_LAUNCHER_PID" 2>/dev/null || true
}
watch_parent &
HBS_WATCH_PID=$!

if [[ -f "$HBS_STATE/passwords" ]]; then
  {
    IFS= read -r HBS_ADMIN_PASSWORD
    IFS= read -r HBS_USER_PASSWORD
  } < "$HBS_STATE/passwords"
else
  HBS_ADMIN_PASSWORD=$(openssl rand -hex 16)
  HBS_USER_PASSWORD=$(openssl rand -hex 16)
  printf '%s\n%s\n' "$HBS_ADMIN_PASSWORD" "$HBS_USER_PASSWORD" > "$HBS_STATE/passwords.tmp"
  mv "$HBS_STATE/passwords.tmp" "$HBS_STATE/passwords"
fi
[[ "$HBS_ADMIN_PASSWORD" =~ ^[0-9a-f]{32}$ && "$HBS_USER_PASSWORD" =~ ^[0-9a-f]{32}$ ]] || fail "Повреждён локальный файл паролей: $HBS_STATE/passwords."
chmod 600 "$HBS_STATE/passwords"

printf '%s\n' 'Собирается проект. При первом запуске Maven загружает зависимости.'
"$HBS_MAVEN_CMD" -B -ntp -Psystem-tests -DskipTests package &
HBS_BUILD_PID=$!
wait "$HBS_BUILD_PID"
HBS_BUILD_PID=
HBS_RUN_DIR=$(mktemp -d "$HBS_STATE/run.XXXXXX")
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$HBS_RUN_DIR/private.pem" 2>"$HBS_RUN_DIR/keygen.log"
openssl pkey -in "$HBS_RUN_DIR/private.pem" -pubout -out "$HBS_RUN_DIR/public.pem"
printf '%s\n' "Логи запуска: $HBS_RUN_DIR"

check_processes() {
  local hbs_pid
  for hbs_pid in "${HBS_PIDS[@]}"; do
    kill -0 "$hbs_pid" 2>/dev/null || fail "Сервис завершился. Проверь логи: $HBS_RUN_DIR"
  done
}

wait_http() {
  local hbs_url=$1 hbs_attempt
  for hbs_attempt in {1..120}; do
    check_processes
    if curl --noproxy 127.0.0.1 --fail --silent --show-error --connect-timeout 1 --max-time 2 "$hbs_url" >/dev/null 2>&1; then return; fi
    sleep 1
  done
  fail "Не дождались готовности $hbs_url. Проверь логи: $HBS_RUN_DIR"
}

start_service() {
  local hbs_service=$1
  shift
  local hbs_cmd=(
    "$HBS_JAVA" -Xmx384m -jar "$HBS_ROOT/$hbs_service/target/$hbs_service-1.0-SNAPSHOT.jar"
    --server.address=127.0.0.1
    --eureka.client.serviceUrl.defaultZone=http://127.0.0.1:8761/eureka/
    --eureka.instance.prefer-ip-address=true --eureka.instance.ip-address=127.0.0.1
    --eureka.client.registry-fetch-interval-seconds=1
    --eureka.client.initial-instance-info-replication-interval-seconds=1
    --spring.jpa.show-sql=false
    "$@"
  )
  printf 'Запускается %s…\n' "$hbs_service"
  if [[ "$hbs_service" == booking-service ]]; then
    DEMO_ADMIN_PASSWORD="$HBS_ADMIN_PASSWORD" DEMO_USER_PASSWORD="$HBS_USER_PASSWORD" \
      "${hbs_cmd[@]}" >"$HBS_RUN_DIR/$hbs_service.log" 2>&1 &
  else
    "${hbs_cmd[@]}" >"$HBS_RUN_DIR/$hbs_service.log" 2>&1 &
  fi
  HBS_PIDS+=("$!")
}

start_service discovery-service --server.port=8761 --eureka.server.response-cache-update-interval-ms=1000
wait_http http://127.0.0.1:8761/
start_service hotel-service --server.port=8082 --spring.profiles.active=demo \
  "--rsa.public-key=file:$HBS_RUN_DIR/public.pem" \
  --spring.datasource.url=jdbc:h2:mem:hotel_postman --spring.datasource.driver-class-name=org.h2.Driver \
  --spring.datasource.username=sa --spring.datasource.password= --spring.jpa.hibernate.ddl-auto=create-drop
wait_http http://127.0.0.1:8082/v3/api-docs
start_service booking-service --server.port=8083 --spring.profiles.active=demo \
  --demo.admin.username=demo-admin --demo.user.username=demo-user \
  "--rsa.public-key=file:$HBS_RUN_DIR/public.pem" "--rsa.private-key=file:$HBS_RUN_DIR/private.pem" \
  --spring.datasource.url=jdbc:h2:mem:booking_postman --spring.datasource.driver-class-name=org.h2.Driver \
  --spring.datasource.username=sa --spring.datasource.password= --spring.jpa.hibernate.ddl-auto=create-drop
wait_http http://127.0.0.1:8083/v3/api-docs
start_service api-gateway --server.port=8080 "--rsa.public-key=file:$HBS_RUN_DIR/public.pem"
wait_http http://127.0.0.1:8080/v3/api-docs/booking
wait_http http://127.0.0.1:8080/v3/api-docs/hotel

# Passwords are random hex strings; no shell/JSON escaping of user input is involved.
cat > "$HBS_STATE/HotelBooking.ready.postman_environment.json.tmp" <<JSON
{
  "id": "283017d6-7774-49b5-b4f8-493e5d1d99f8",
  "name": "Hotel Booking — Local",
  "values": [
    {"key": "baseUrl", "value": "http://127.0.0.1:8080", "type": "default", "enabled": true},
    {"key": "adminUsername", "value": "demo-admin", "type": "default", "enabled": true},
    {"key": "userUsername", "value": "demo-user", "type": "default", "enabled": true},
    {"key": "adminPassword", "value": "$HBS_ADMIN_PASSWORD", "type": "secret", "enabled": true},
    {"key": "userPassword", "value": "$HBS_USER_PASSWORD", "type": "secret", "enabled": true}
  ],
  "_postman_variable_scope": "environment"
}
JSON
chmod 600 "$HBS_STATE/HotelBooking.ready.postman_environment.json.tmp"
mv "$HBS_STATE/HotelBooking.ready.postman_environment.json.tmp" "$HBS_STATE/HotelBooking.ready.postman_environment.json"

printf '\n%s\n' 'ГОТОВО: сервисы запущены, можно запускать Postman.' \
  "Коллекция: $HBS_ROOT/postman/HotelBooking.postman_collection.json" \
  "Заполненное окружение: $HBS_STATE/HotelBooking.ready.postman_environment.json" \
  'Импортируй оба файла, выбери Hotel Booking — Local → Run коллекции → 1 iteration.' \
  'Остановка: Stop в IDEA или Ctrl+C в терминале. Это окно оставь открытым.'
while true; do
  check_processes
  sleep 2
done
