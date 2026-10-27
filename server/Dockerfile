FROM golang:1.27.3-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/assetboard-server ./cmd/assetboard-server

FROM alpine:3.20
RUN addgroup -S -g 10001 app \
    && adduser -S -D -H -u 10001 -G app app \
    && mkdir -p /data \
    && chown app:app /data
COPY --from=build /out/assetboard-server /usr/local/bin/assetboard-server
USER app
ENV ASSETBOARD_ADDR=:8090 \
    ASSETBOARD_DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8090/healthz || exit 1
ENTRYPOINT ["assetboard-server"]
CMD ["serve"]