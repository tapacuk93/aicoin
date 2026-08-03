# syntax=docker/dockerfile:1

# --- build stage -------------------------------------------------------
FROM golang:1.22-alpine AS build
WORKDIR /src

# Cache module downloads separately from source changes.
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# Static binaries (CGO disabled) so they run unmodified on a minimal/
# distroless runtime base with no libc.
ENV CGO_ENABLED=0
RUN go build -trimpath -ldflags="-s -w" -o /out/aicoind ./cmd/aicoind
RUN go build -trimpath -ldflags="-s -w" -o /out/wallet ./cmd/wallet

# --- runtime stage -------------------------------------------------------
FROM gcr.io/distroless/static-debian12:nonroot AS runtime

COPY --from=build /out/aicoind /usr/local/bin/aicoind
COPY --from=build /out/wallet /usr/local/bin/wallet

# Defaults match CONTRACT.md's CLI flag defaults; override any of them at
# `docker run` time via extra args (they are appended to the ENTRYPOINT,
# not baked in), e.g.:
#   docker run -p 9944:9944 -p 9945:9945 aicoin -difficulty=2 -redis=redis:6379
EXPOSE 9944 9945
ENTRYPOINT ["/usr/local/bin/aicoind"]
CMD ["-http=:9944", "-p2p=:9945"]
