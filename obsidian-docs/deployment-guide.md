# Deployment Guide

## Target Environment

**Primary recommendation:** Hetzner Cloud CX22 VPS (2 vCPU, 4GB RAM, 40GB SSD) at ~EUR 4.50/month. London datacentre for low latency. 99.9% SLA.

**Alternative:** Railway hobby plan ($5-10/month) — push-to-deploy, managed SSL, less server admin.

The entire stack (Nginx, Spring Boot, PostgreSQL) runs on a single VPS.

## Production Architecture

```
Internet
    │
    ▼
┌────────────────────────┐
│   Nginx                │
│   :443 (HTTPS)         │
│                        │
│   /         → frontend │
│   /api/*    → :8080    │
│   /ws/*     → :8080    │
│                        │
│   SSL: Let's Encrypt   │
│   (Certbot auto-renew) │
└────────┬───────────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌────────┐ ┌──────────────┐
│Frontend│ │ Spring Boot  │
│(static)│ │   :8080      │
│ Nginx  │ │              │
│ serves │ │ REST + WS    │
└────────┘ └──────┬───────┘
                  │
           ┌──────▼───────┐
           │ PostgreSQL   │
           │   :5432      │
           └──────────────┘
```

## Docker Deployment

### Backend Dockerfile

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

# Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Frontend Dockerfile

```dockerfile
# Build stage
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Serve stage
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

### docker-compose.prod.yml

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_DB: questkeeper
      POSTGRES_USER: ${DATABASE_USERNAME}
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD}
    volumes:
      - questkeeper-data:/var/lib/postgresql/data
    restart: unless-stopped

  backend:
    build: ./backend
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DATABASE_URL: jdbc:postgresql://db:5432/questkeeper
      DATABASE_USERNAME: ${DATABASE_USERNAME}
      DATABASE_PASSWORD: ${DATABASE_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
    depends_on:
      - db
    restart: unless-stopped

  frontend:
    build: ./frontend
    ports:
      - "80:80"
      - "443:443"
    depends_on:
      - backend
    restart: unless-stopped

volumes:
  questkeeper-data:
```

## SSL Setup (Let's Encrypt)

```bash
# Install Certbot
sudo apt install certbot python3-certbot-nginx

# Obtain certificate
sudo certbot --nginx -d yourdomain.com

# Auto-renewal is set up by Certbot (cron job)
# Test renewal:
sudo certbot renew --dry-run
```

## Nginx Configuration

```nginx
server {
    listen 443 ssl;
    server_name yourdomain.com;

    ssl_certificate /etc/letsencrypt/live/yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/yourdomain.com/privkey.pem;

    # Frontend (static files)
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    # Backend API
    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # WebSocket
    location /ws/ {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400;
    }
}
```

## Backups

```bash
# Manual database backup
docker exec questkeeper-db pg_dump -U questkeeper questkeeper > backup_$(date +%Y%m%d).sql

# Restore from backup
cat backup_20260717.sql | docker exec -i questkeeper-db psql -U questkeeper questkeeper

# Automated daily backup (add to crontab)
0 3 * * * docker exec questkeeper-db pg_dump -U questkeeper questkeeper | gzip > /backups/questkeeper_$(date +\%Y\%m\%d).sql.gz
```
