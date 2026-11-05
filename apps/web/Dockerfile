FROM node:26.13.0-alpine3.24@sha256:c6ad9e9c1d80f6eb607c26b4b0a132ff7a2344fad1c78ea3baa2a7d4c8de6d94 AS dependencies
WORKDIR /workspace
RUN corepack enable && corepack prepare pnpm@11.19.0 --activate
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json
RUN pnpm install --frozen-lockfile

FROM node:26.13.0-alpine3.24@sha256:c6ad9e9c1d80f6eb607c26b4b0a132ff7a2344fad1c78ea3baa2a7d4c8de6d94 AS builder
WORKDIR /workspace
ARG NEXT_PUBLIC_APP_URL
ARG NEXT_PUBLIC_API_URL
ENV NEXT_TELEMETRY_DISABLED=1 \
    NEXT_PUBLIC_APP_URL=${NEXT_PUBLIC_APP_URL} \
    NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}
RUN corepack enable && corepack prepare pnpm@11.19.0 --activate
COPY --from=dependencies /workspace/node_modules ./node_modules
COPY --from=dependencies /workspace/apps/web/node_modules ./apps/web/node_modules
COPY . .
RUN pnpm --filter @educore/web build

FROM node:26.13.0-alpine3.24@sha256:c6ad9e9c1d80f6eb607c26b4b0a132ff7a2344fad1c78ea3baa2a7d4c8de6d94 AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3000
RUN apk add --no-cache --upgrade \
        musl=1.2.6-r3 musl-utils=1.2.6-r3 \
        libcrypto3=3.5.9-r0 libssl3=3.5.9-r0 zlib=1.3.2-r1 \
    && test -d /usr/local/lib/node_modules/npm \
    && test -L /usr/local/bin/npm && test -L /usr/local/bin/npx \
    && rm -rf /usr/local/lib/node_modules/npm \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx \
    && test -d /opt/yarn-v1.22.22 && test ! -L /opt/yarn-v1.22.22 \
    && test -f /opt/yarn-v1.22.22/lib/cli.js && test ! -L /opt/yarn-v1.22.22/lib/cli.js \
    && test -L /usr/local/bin/yarn && test -L /usr/local/bin/yarnpkg \
    && test "$(readlink /usr/local/bin/yarn)" = /opt/yarn-v1.22.22/bin/yarn \
    && test "$(readlink /usr/local/bin/yarnpkg)" = /opt/yarn-v1.22.22/bin/yarnpkg \
    && test "$(sha256sum /opt/yarn-v1.22.22/lib/cli.js | cut -d ' ' -f 1)" = 443ed69e76443b89afddccfc9faec1ff16eb5e500979cc079c696dec4c3d94ee \
    && rm -f /usr/local/bin/yarn /usr/local/bin/yarnpkg \
    && rm -rf /opt/yarn-v1.22.22 \
    && addgroup --system --gid 10001 educore \
    && adduser --system --uid 10001 --ingroup educore educore
COPY --from=builder --chown=educore:educore /workspace/apps/web/.next/standalone ./
COPY --from=builder --chown=educore:educore /workspace/apps/web/.next/static ./apps/web/.next/static
COPY --from=builder --chown=educore:educore /workspace/apps/web/public ./apps/web/public
USER educore
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
