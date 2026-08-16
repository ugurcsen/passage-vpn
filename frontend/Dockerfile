# ---- Build stage ----
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci
COPY . .
RUN npm run build \
    # Pre-compress assets so nginx can serve them with gzip_static (no runtime CPU).
    && find dist -type f \( -name "*.js" -o -name "*.css" -o -name "*.json" -o -name "*.svg" \) -exec gzip -9k {} \;

# ---- Runtime stage ----
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
