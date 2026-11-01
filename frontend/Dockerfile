FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Type-checks before bundling (tsc -b && vite build)
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /app/dist /srv
COPY Caddyfile /etc/caddy/Caddyfile
# Run as an unprivileged user; the official caddy binary can still bind ports 80 and 443
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /data /config \
    && chown -R app:app /data /config
USER app
EXPOSE 80 443
