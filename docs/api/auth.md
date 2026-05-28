# Auth — email/password + JWT

Stateless JWT (HS256). Без cookies, без refresh-токенов (это MVP).

## Flow

```
┌─────────┐                       ┌──────────┐
│ Client  │ ──── /register ──────▶│ Server   │
│         │ ◀────  201 + JWT  ────│          │
│         │                       │          │
│         │ ───── /login ────────▶│          │
│         │ ◀───  200 + JWT  ─────│          │
│         │                       │          │
│         │ ── /me  Bearer JWT ──▶│          │
│         │ ◀────  200 user  ─────│          │
└─────────┘                       └──────────┘
```

## POST /api/v1/auth/register

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "email": "alice@example.com",
  "password": "supersecret-pass",   // min 12 chars (OWASP ASVS L2)
  "name": "Alice"                   // optional
}
```

→ **201 Created**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": { "id": 1, "email": "alice@example.com", "name": "Alice" }
}
```

Errors:
- `409 email_already_taken` — email уже зарегистрирован
- `400 validation_failed` — invalid email / password < 12 / etc; `details.<field>` содержит сообщение

## POST /api/v1/auth/login

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "email": "alice@example.com", "password": "supersecret-pass" }
```

→ **200 OK** — то же тело, что и register.

Errors:
- `401 invalid_credentials` — email не найден ИЛИ пароль неверный. Сервер не различает эти случаи в ответе и в **тайминге** (выровнено через dummy bcrypt) — клиенту не нужно знать, существует ли email.

## Использование токена

```http
GET /api/v1/me
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

→ **200 OK**

```json
{ "id": 1, "email": "alice@example.com", "name": "Alice" }
```

Errors:
- `401` — токен отсутствует, истёк, или подпись невалидна. Empty body (Spring `HttpStatusEntryPoint`).

## JWT внутри

| claim | type   | пример |
|-------|--------|--------|
| `sub` | string | `"1"` — `userId` как строка |
| `email` | string | `"alice@example.com"` |
| `name` | string | `"Alice"` (может отсутствовать) |
| `iat` | int | issued-at unix-timestamp |
| `exp` | int | unix-timestamp |

Алгоритм: HS256, симметричный секрет `PLUM_JWT_SECRET` (≥32 байт, обязательно override в prod). Срок жизни — `PLUM_JWT_EXPIRATION_MINUTES`, по умолчанию 60.

Сервер допускает 30s clock skew при verify (`Jwts.parser().clockSkewSeconds(30)`).

## Что должен делать клиент

1. Хранить токен в безопасном месте (Keychain / EncryptedSharedPreferences / httpOnly cookie — в зависимости от платформы).
2. Слать `Authorization: Bearer <token>` на каждом authed-запросе.
3. На `401` — не retry-loop'ить на том же токене. Перенаправить юзера на login.
4. Не сохранять `password` в локальном storage. Никогда.
5. Логирование: НЕ выводить токен или пароль в логи/Sentry/crash-reports.

## Production hardening (TODO в roadmap)

- Refresh tokens (opaque, БД-хранимые, revocable).
- Audit log (login attempts).
- Rate limiting на `/auth/*` (bucket4j или gateway).
- BCrypt cost = 12 (сейчас Spring default 10).
- HIBP-style breach check на register.
