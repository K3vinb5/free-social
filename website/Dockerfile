# Static site for freesocial.k3vinb5.dev
#
# Build (context = website/):
#   docker build -t ghcr.io/k3vinb5/free-social-website:latest website/
#
# Multi-stage: the React source is prerendered to plain files in stage 1, and the
# runtime image is a stock nginx serving only those files. There is no Node/Bun
# and no build step at runtime — a JS-off visitor gets the full page because the
# markup is already in dist/index.html.

FROM oven/bun:1-alpine AS build

WORKDIR /site

# Dependencies first, so a source-only change reuses this layer.
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

COPY . .

# vite build (client) -> vite build --ssr (render) -> prerender into dist/index.html
# -> copy downloads/ next to it. Ends with the build assertions in scripts/check-build.mjs.
RUN bun run build


FROM nginx:1.27-alpine

LABEL org.opencontainers.image.source="https://github.com/K3vinb5/free-social" \
      org.opencontainers.image.title="free-social-website" \
      org.opencontainers.image.description="Static marketing site for Free Social" \
      org.opencontainers.image.licenses="MIT"

# The prerendered site: index.html, styles.css, assets/ and downloads/.
COPY --from=build /site/dist/ /usr/share/nginx/html/

# Server block: gzip, cache policy, security headers, /healthz.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Fail the build early if the output or the config is missing.
RUN test -f /usr/share/nginx/html/index.html \
 && test -f /usr/share/nginx/html/styles.css \
 && test -f /usr/share/nginx/html/downloads/versions.json \
 && nginx -t

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/healthz >/dev/null 2>&1 || exit 1

STOPSIGNAL SIGQUIT
