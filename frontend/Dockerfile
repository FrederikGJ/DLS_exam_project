# Static frontend served by nginx. No build step: plain HTML/CSS/JS.
FROM nginx:alpine
# apk upgrade: the base image lags behind Alpine's security fixes; Trivy scans the result in CI (dev plan DP-25).
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html silent-check-sso.html /usr/share/nginx/html/
COPY css /usr/share/nginx/html/css
COPY js  /usr/share/nginx/html/js
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
