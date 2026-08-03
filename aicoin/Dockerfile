# syntax=docker/dockerfile:1

# --- build stage -------------------------------------------------------
FROM golang:1.24-alpine AS build
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
#   docker run -p 9944:9944 aicoin -role=primary -keyfile=/data/aicoin-node.key -dynamodb-table=aicoin-chain
# The relevant flags for roles/signing are -role=primary|follower,
# -keyfile=<path> (primary only), and -trusted-pubkey=<hex> (required for
# -role=follower) — see CONTRACT.md's "Roles & signing" section. AWS
# region/credentials for -dynamodb-table come from the standard SDK
# environment (AWS_REGION, an instance role, AWS_ACCESS_KEY_ID/
# AWS_SECRET_ACCESS_KEY, etc.) — no separate flags for those. None of these
# are baked into the image; they're plain CLI args/env at run time, same as
# every other flag.
EXPOSE 9944
ENTRYPOINT ["/usr/local/bin/aicoind"]
CMD ["-http=:9944"]
