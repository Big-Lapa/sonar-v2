FROM node:22-slim AS build
WORKDIR /app
# npm ci installs exactly what package-lock.json pins (and fails if it's out of sync) — npm install
# with only package.json re-resolved every ^range at build time.
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json nest-cli.json ./
COPY src ./src
RUN npm run build

FROM node:22-slim
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
# Run as the image's unprivileged `node` user; data/ is the only place the app writes (klines cache).
RUN mkdir -p /app/data && chown node:node /app/data
USER node
EXPOSE 3000
CMD ["node", "dist/main.js"]
