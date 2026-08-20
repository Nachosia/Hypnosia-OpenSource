# Hypnosia Visuals (Open Source)

Local, self-hosted sources for the Fabric mod, website, and admin panel.

This tree does **not** contain production databases, VPS credentials, or live API secrets.

## Layout

- `mod/` - Minecraft Fabric 1.21.11 client
- `website/` - site + API (Hono, tRPC, Drizzle, MySQL)
- `admin/` - license server (port 8080) and admin panel (port 9090)

## Local URLs

| Service | URL |
|---|---|
| Website | http://127.0.0.1:3000 |
| License API | http://127.0.0.1:8080 |
| Admin panel | http://127.0.0.1:9090 |

## Local configuration

Copy each `.env.example` to `.env`, then replace every `change-this-*` value with a strong local secret before running.

## Run

### Admin + license server

Requires Java 21.

```powershell
cd admin
copy .env.example .env
# start license server
cd server
# gradle run from this module, or:
# java -jar after ./gradlew :server:installDist
```

Set `HYPNOSIA_ADMIN_PASSWORD` from `admin/.env.example` before starting the panel.

### Website

Requires Node.js 20+, MySQL 8, Redis (optional for rate limits).

```powershell
cd website
copy .env.example .env
npm install
npm run db:push
npm run dev
```

### Mod

Requires Java 21.

```powershell
cd mod
.\gradlew.bat build
```

Put `HYPNOSIA_MOD_API_KEY` / `HYPNOSIA_MOD_SECRET_KEY` in the environment so they match `website/.env`.

## License

See `mod/LICENSE`.
