# syntax=docker/dockerfile:1.7
FROM node:22.22.0-bookworm-slim AS build

# Empty bases keep requests same-origin; the ingress routes /api through api-gateway.
ARG VITE_API_BASE=
ARG VITE_TOOLS_BASE=
ENV VITE_API_BASE=${VITE_API_BASE}
ENV VITE_TOOLS_BASE=${VITE_TOOLS_BASE}

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=101:101 /app/dist /usr/share/nginx/html
EXPOSE 8080
