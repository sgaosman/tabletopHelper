#!/usr/bin/env bash
set -euo pipefail

if [ ! -f .env ]; then
    echo "ERROR: .env file not found. Copy .env.example to .env and fill in values."
    exit 1
fi

echo "==> Pulling latest code..."
git pull --ff-only

echo "==> Building and starting containers..."
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d

echo "==> Waiting for backend health..."
for i in $(seq 1 30); do
    if docker compose -f docker-compose.prod.yml exec -T backend curl -sf http://localhost:8080/api/auth/login > /dev/null 2>&1; then
        echo "==> Backend is healthy."
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "WARNING: Backend health check timed out. Check logs with: docker compose -f docker-compose.prod.yml logs backend"
    fi
    sleep 2
done

echo "==> Done. Containers running:"
docker compose -f docker-compose.prod.yml ps
