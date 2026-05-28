# Production deploy

Целевой стек: **Caddy** (auto Let's Encrypt) → **Spring Boot server** → **PostgreSQL 16**. Всё в Docker, файлы — `Dockerfile`, `docker-compose.prod.yml`, `Caddyfile`, `.env.prod.example`.

## Prerequisites

- Linux host с Docker 24+ и Docker Compose v2.
- DNS A-запись `<your-domain>` → IP сервера.
- Открытые порты 80 (для ACME challenge) и 443 (HTTPS).

## Шаги

```bash
# 1. Поставить Docker (Fedora пример)
dnf install -y moby-engine docker-compose git
systemctl enable --now docker

# 2. Получить код
git clone https://github.com/ZagirNur/plum-reader.git /opt/plum-reader
cd /opt/plum-reader

# 3. Подготовить env
cp .env.prod.example .env
sed -i "s|__CHANGE_ME__POSTGRES__|$(openssl rand -base64 32 | tr -d '=+/')|" .env  # вручную
sed -i "s|__CHANGE_ME__JWT__|$(openssl rand -base64 48)|" .env                    # вручную
# Открыть .env, проверить DOMAIN/ACME_EMAIL/CORS — отредактировать руками.

# 4. Поднять стек
docker compose -f docker-compose.prod.yml up -d --build

# 5. Подождать пока Caddy получит cert (видно в логах)
docker compose -f docker-compose.prod.yml logs -f caddy
```

## Проверка

```bash
curl https://<your-domain>/api/v1/health
# → {"status":"ok"}

curl https://<your-domain>/actuator/health
# → {"status":"UP","groups":["liveness","readiness"]}
```

## Update

```bash
cd /opt/plum-reader
git pull
docker compose -f docker-compose.prod.yml up -d --build server
```

Flyway применит новые миграции при старте.

## Бэкапы

Том `postgres-data` содержит всю БД. Том `plum-storage` содержит загруженные EPUB blob'ы. Снимать их раздельным `pg_dump` и `tar` соответственно.

## Troubleshooting

| Симптом | Где смотреть |
|---------|--------------|
| Cert не выдан | `docker compose logs caddy` — проверь что порт 80 доступен из интернета |
| 502 от Caddy | `docker compose logs server` — Spring Boot не стартует или БД недоступна |
| Migration error | `docker compose logs server` ищи `Migrating schema` / `FAILED` |
| Permissions /var/plum-storage | volume — chown происходит автоматически, если нет — `docker compose exec server chown -R 1001 /var/plum-storage` |
