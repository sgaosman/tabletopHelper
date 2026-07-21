# Deployment Guide

## Target Environment

**Primary recommendation:** Hetzner Cloud CX22 VPS (2 vCPU, 4GB RAM, 40GB SSD) at ~EUR 4.50/month. London datacentre for low latency. 99.9% SLA.

The entire stack (Caddy, Spring Boot, PostgreSQL) runs on a single VPS via Docker Compose.

## Production Architecture

```
Internet
    |
    v
+------------------------+
|   Caddy                |
|   :443 (HTTPS)         |
|                        |
|   /         -> /srv    |
|   /api/*    -> :8080   |
|   /ws/*     -> :8080   |
|                        |
|   SSL: automatic       |
|   (Let's Encrypt)      |
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

## Initial Server Setup

```bash
# 1. Create a Hetzner CX22 VPS (Ubuntu 24.04, London DC)
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
openssl rand -base64 64  # use output for JWT_SECRET
nano .env                # fill in DOMAIN, POSTGRES_PASSWORD, JWT_SECRET

# 6. Deploy
./deploy.sh
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DOMAIN` | Yes | `localhost` | Your domain (e.g. `tabletop.example.com`). Caddy auto-provisions SSL. |
| `POSTGRES_DB` | No | `tabletophelper` | Database name |
| `POSTGRES_USER` | No | `tabletophelper` | Database user |
| `POSTGRES_PASSWORD` | Yes | - | Database password |
| `JWT_SECRET` | Yes | - | JWT signing key (min 64 chars) |
| `CORS_ALLOWED_ORIGINS` | No | - | Comma-separated origins. Leave empty when Caddy proxies everything. |

## SSL / HTTPS

Caddy handles SSL automatically. When `DOMAIN` is set to a real domain (not `localhost`):
- Caddy obtains a Let's Encrypt certificate on first request
- Certificates auto-renew before expiry
- HTTP automatically redirects to HTTPS

No certbot, no cron jobs, no manual renewal.

**Prerequisite:** Your domain's DNS A record must point to the server IP before deploying.

## Updating

```bash
ssh root@YOUR_SERVER_IP
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

## Scaling

| Stage | Hetzner spec | Cost | Handles |
|-------|-------------|------|---------|
| Small group | CX22 (2 vCPU, 4GB) | ~EUR 4.50/mo | Single game group |
| Multiple groups | CX32 (4 vCPU, 8GB) | ~EUR 7.50/mo | Hundreds of WebSocket connections |
| Multi-system platform | CX42 (8 vCPU, 16GB) | ~EUR 15/mo | Thousands of users |

Hetzner supports live resizing — scale the VPS up without reprovisioning.
