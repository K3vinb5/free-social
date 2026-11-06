# Static site for freesocial.k3vinb5.dev
#
# Build (context = website/):
#   docker build -t ghcr.io/k3vinb5/free-social-website:latest website/
#
# The image is a plain nginx serving pre-built static files; nothing is compiled
# at image build time and there is no runtime dependency beyond nginx itself.

FROM nginx:1.27-alpine

LABEL org.opencontainers.image.source="https://github.com/K3vinb5/free-social" \
      org.opencontainers.image.title="free-social-website" \
      org.opencontainers.image.description="Static marketing site for Free Social" \
      org.opencontainers.image.licenses="MIT"

# Site content. Served from the nginx document root.
COPY index.html /usr/share/nginx/html/index.html
COPY styles.css /usr/share/nginx/html/styles.css
COPY assets/   /usr/share/nginx/html/assets/
COPY downloads/ /usr/share/nginx/html/downloads/

# Server block: gzip, cache policy, security headers, /healthz.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Fail the build early if the site or the config is missing.
RUN test -f /usr/share/nginx/html/index.html \
 && nginx -t

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/healthz >/dev/null 2>&1 || exit 1

STOPSIGNAL SIGQUIT
