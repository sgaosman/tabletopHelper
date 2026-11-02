# Deployment Guide

## Current Environment

> **Update (2026-11-01):** the Hetzner server and account have been closed, and the app is not hosted anywhere. The details below describe the last deployment and are kept for reference until a new host is chosen (D154's Hosting step).

**Active deployment:** Hetzner Cloud CPX22 VPS (2 vCPU, 4GB RAM, 80GB SSD) at ~EUR 23.39/month. Falkenstein datacentre.

**Server IP:** 167.233.219.230
**Access:** SSH as root (Mac + home PC keys installed)
**Status:** Live, HTTP only (no domain yet)

The entire stack (Caddy, Spring Boot, PostgreSQL) runs on a single VPS via Docker Compose.

## Future Migration Plan: DigitalOcean

The Hetzner CPX22 at ~EUR 24/month is overpowered for current needs. Plan is to migrate to DigitalOcean after the first month (covered by EUR 25 Hetzner credit):

| Provider | Spec | Monthly cost |
|----------|------|-------------|
| **DigitalOcean (target)** | 1 vCPU, 2GB RAM, 50GB | ~$12/month (~GBP 9.50) |
| **Hetzner (current)** | 2 vCPU, 4GB RAM, 80GB | ~EUR 24/month (~GBP 20) |

Migration steps: create DigitalOcean droplet, install Docker, clone repo, copy `.env`, `pg_dump` from Hetzner -> `psql` restore on DigitalOcean, update DNS/bookmarks, delete Hetzner server. The Docker Compose setup is provider-agnostic — no changes needed.

When a custom domain is purchased, update the Caddyfile to use the domain name instead of `:80` and Caddy will auto-provision HTTPS via Let's Encrypt.

## Production Architecture

```
Internet
    |
    v
+------------------------+
|   Caddy                |
|   :80 (HTTP)           |
|                        |
|   /         -> /srv    |
|   /api/*    -> :8080   |
|   /ws/*     -> :8080   |
|                        |
|   HTTPS: auto when     |
|   domain is configured |
+--------+---------------+
         |
    +----+----+
    |         |
    v         v
+--------+ +--------------+
|Frontend| | Spring Boot  |
|(static)| |   :8080      |
| served | |              |
| by     | | REST + WS    |
| Caddy  | +------+-------+
+--------+        |
           +------v-------+
           | PostgreSQL   |
           |   :5432      |
           +--------------+
```

## Files Overview

| File | Purpose |
|------|---------|
| `backend/Dockerfile` | Multi-stage build: Gradle compile -> JRE 21 runtime |
| `frontend/Dockerfile` | Multi-stage build: Node/Vite compile -> Caddy serves static |
| `frontend/Caddyfile` | Reverse proxy config (API + WebSocket) + static file server |
| `docker-compose.prod.yml` | Production orchestration: Caddy + Backend + PostgreSQL |
| `.env.example` | Template for production secrets |
| `deploy.sh` | Pull + build + deploy script |
| `V0__initial_schema.sql` | Creates all base tables on fresh database |

## Initial Server Setup

```bash
# 1. Create VPS (Ubuntu 24.04)
# 2. SSH in and install Docker
ssh root@YOUR_SERVER_IP
curl -fsSL https://get.docker.com | sh
```

## First Deployment

```bash
# 3. Clone the repo
git clone https://github.com/sgaosman/tabletopHelper.git
cd tabletopHelper

# 4. Create .env from template
cp .env.example .env

# 5. Generate secrets and edit .env
openssl rand -hex 64   # use output for JWT_SECRET (hex avoids special char issues)
nano .env              # fill in POSTGRES_PASSWORD, JWT_SECRET (32+ bytes), and CORS_ALLOWED_ORIGINS
                       # with the site's https:// origin (see Session and WebSocket Security below)

# 6. Deploy
chmod +x deploy.sh
./deploy.sh
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `POSTGRES_DB` | No | `tabletophelper` | Database name |
| `POSTGRES_USER` | No | `tabletophelper` | Database user |
| `POSTGRES_PASSWORD` | Yes | - | Database password |
| `JWT_SECRET` | Yes | - | JWT signing key of at least 32 bytes (use `openssl rand -hex 64`). Since R3 there is no fallback outside the dev profile: the backend refuses to start when it is missing or shorter, and `docker-compose.prod.yml` refuses to start without it (D156). |
| `CORS_ALLOWED_ORIGINS` | Yes, once HTTPS is on | Empty under compose | Comma-separated origins allowed for cross-origin HTTP requests and, since R3, for `/ws` (D157). Set it to the site's own origin, e.g. `https://tabletop.example.com`: see [Session and WebSocket Security (R3)](#session-and-websocket-security-r3). The backend's own default, `http://localhost:5173`, applies only when the variable isn't set at all. |
| `SPRING_PROFILES_ACTIVE` | Set by compose | `prod`, set in `docker-compose.prod.yml` rather than `.env` | Must be `prod`: without it the backend runs the `dev` profile, with the committed dev secret and a non-Secure cookie. See below. |

## SSL / HTTPS

Currently serving HTTP only (no custom domain). When a domain is purchased:

1. Buy a domain and point its A record to the server IP
2. Replace the Caddyfile content with:
```
yourdomain.com {
    encode gzip
    handle /api/* { reverse_proxy backend:8080 }
    handle /ws/* { reverse_proxy backend:8080 }
    handle { root * /srv; try_files {path} /index.html; file_server }
}
```
3. Rebuild frontend: `docker compose -f docker-compose.prod.yml up -d --build frontend`
4. Caddy auto-provisions Let's Encrypt SSL — no certbot, no cron jobs

## Runtime Hardening (R1)

- **Health endpoint:** the backend exposes `GET /actuator/health`, which reports status only, with no details. It needs no login, but Caddy doesn't proxy it, so it is reachable only inside the Docker network. `docker-compose.prod.yml` uses it as the backend healthcheck, with a 3-minute start period to allow for reference data seeding. The frontend waits for it, and `deploy.sh` polls it after deploying.
- **Non-root containers:** the backend runs as user `app`. Caddy also runs as `app`, relying on the official binary's permission to bind ports 80 and 443; its `/data` and `/config` volumes are owned by that user.
- **Production profile:** API docs and Swagger UI are turned off in `prod`.

## Session and WebSocket Security (R3)

- **HTTPS comes first.** Outside the dev profile the `refresh_token` cookie is Secure, and browsers only store a Secure cookie over HTTPS. On plain HTTP, which the current Caddyfile still serves (`auto_https off`, `:80`), every reload would sign the user out. The Hosting step's HTTPS must therefore be in place before R3 is deployed (D154, D156).
- **`JWT_SECRET` is required** and must be at least 32 bytes. `application.yml` has no default, and startup fails with `JWT_SECRET must be set to at least 32 bytes, but app.jwt.secret has N` (D156).
- **The production profile must be set.** `application.yml` activates `dev` unless told otherwise, and the dev profile signs tokens with the secret committed in `application-dev.yml`, sends the refresh cookie without Secure and returns raw error messages. `docker-compose.prod.yml` sets `SPRING_PROFILES_ACTIVE: prod`; any other way of running the backend in production must set it too.
- **`CORS_ALLOWED_ORIGINS` must include the site's origin** for `/ws`, e.g. `CORS_ALLOWED_ORIGINS=https://tabletop.example.com`. `/ws` accepts only the listed origins plus what the backend sees as its own origin. Behind Caddy's HTTPS the backend receives plain HTTP from Caddy, so it won't recognise the page's `https://` origin as its own, and an empty value would refuse the encounter socket (D157). The Hosting step should confirm this.
- **Everyone signs in once** after R3 is deployed: earlier tokens have no `type` claim, and no browser holds the refresh cookie yet.

## Updating

```bash
ssh root@167.233.219.230
cd tabletopHelper
./deploy.sh
```

This pulls the latest code, rebuilds containers, and restarts with zero-downtime for the database (volume persists).

## Manual Operations

```bash
# View logs
docker compose -f docker-compose.prod.yml logs -f
docker compose -f docker-compose.prod.yml logs backend    # backend only

# Restart a single service
docker compose -f docker-compose.prod.yml restart backend

# Stop everything
docker compose -f docker-compose.prod.yml down

# Stop and remove volumes (DESTROYS DATA)
docker compose -f docker-compose.prod.yml down -v

# Access database
docker compose -f docker-compose.prod.yml exec db psql -U tabletophelper -d tabletophelper
```

## Backups

```bash
# Manual database backup
docker compose -f docker-compose.prod.yml exec -T db \
  pg_dump -U tabletophelper tabletophelper > backup_$(date +%Y%m%d).sql

# Restore from backup
cat backup_20260717.sql | docker compose -f docker-compose.prod.yml exec -T db \
  psql -U tabletophelper tabletophelper

# Automated daily backup (add to crontab: crontab -e)
0 3 * * * cd /root/tabletopHelper && docker compose -f docker-compose.prod.yml exec -T db pg_dump -U tabletophelper tabletophelper | gzip > /backups/tabletophelper_$(date +\%Y\%m\%d).sql.gz
```

## Known Issues

- **Chrome HTTPS forcing:** Chrome may auto-upgrade `http://` to `https://` for the bare IP address. Workaround: use Safari, Firefox, or Edge InPrivate. Resolved permanently once a custom domain with SSL is configured.
- **Hetzner pricing:** Actual CPX22 cost is ~EUR 23.39/month (not EUR 4.50 as initially estimated from outdated pricing data). Plan to migrate to DigitalOcean $12/month.
