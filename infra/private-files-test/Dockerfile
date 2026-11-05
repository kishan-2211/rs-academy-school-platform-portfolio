# Test-only reproduction of the repository's existing MinIO releases.
# Upstream registry images were withdrawn; use official release assets, not a mirror.
# These historical binaries are NOT an approved production storage deployment.
FROM alpine:3.22 AS verified-binaries
RUN apk add --no-cache ca-certificates curl
RUN curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
      https://github.com/minio/minio/releases/download/RELEASE.2025-04-22T22-12-26Z/minio.linux-amd64.RELEASE.2025-04-22T22-12-26Z \
      --output /minio \
    && echo '53e2a2cb16c5366ea6fbbc479c19ddb4c6a0948273e752f740fb1fbf27bb817c  /minio' | sha256sum -c - \
    && curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
      https://github.com/minio/mc/releases/download/RELEASE.2025-04-16T18-13-26Z/mc.linux-amd64.RELEASE.2025-04-16T18-13-26Z \
      --output /mc \
    && echo 'ac90da87a35641be5a0ac75d49de5161ddb47d629b5ba01261b0ae9e00aea15f  /mc' | sha256sum -c - \
    && chmod 0555 /minio /mc

FROM alpine:3.22 AS runtime
RUN apk add --no-cache ca-certificates
ENV HOME=/tmp

FROM runtime AS server
COPY --from=verified-binaries /minio /usr/local/bin/minio
RUN mkdir -p /data && chown 65532:65532 /data
USER 65532:65532
VOLUME ["/data"]
ENTRYPOINT ["/usr/local/bin/minio"]

FROM runtime AS client
COPY --from=verified-binaries /mc /usr/local/bin/mc
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/mc"]
