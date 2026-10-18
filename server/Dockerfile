FROM node:24-alpine AS build
WORKDIR /app
COPY web/package*.json web/
RUN npm --prefix web ci --ignore-scripts
COPY package.json ./
COPY protocol/ protocol/
COPY web/ web/
RUN npm --prefix web run build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=8080 STATIC_ROOT=/app/web/dist
COPY --from=build /app/web/dist web/dist
COPY server/serve.mjs server/serve.mjs
USER node
EXPOSE 8080
CMD ["node","server/serve.mjs"]
