# The AniMix server image. npm gives up on a stalled download after 20 s and retries instead of
# waiting 5 minutes (seen on the VPS provider's network).
FROM node:22-alpine AS build
WORKDIR /app
ENV NPM_CONFIG_FETCH_TIMEOUT=20000 NPM_CONFIG_FETCH_RETRIES=6 \
    NPM_CONFIG_FETCH_RETRY_MINTIMEOUT=2000 NPM_CONFIG_FETCH_RETRY_MAXTIMEOUT=20000 \
    NPM_CONFIG_AUDIT=false NPM_CONFIG_FUND=false
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8080
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
COPY schema ./schema
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/health || exit 1
CMD ["node", "dist/server.js"]
