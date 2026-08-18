# syntax=docker/dockerfile:1.7
FROM node:22.22.0-bookworm-slim AS build

ARG VITE_OIDC_ENABLED=true
ARG VITE_LOCAL_DEMO=false
ENV VITE_OIDC_ENABLED=${VITE_OIDC_ENABLED}
ENV VITE_LOCAL_DEMO=${VITE_LOCAL_DEMO}

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /app/dist /usr/share/nginx/html
EXPOSE 8080
