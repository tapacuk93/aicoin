#!/bin/bash
# EC2 user data for a *hosted* Anyphone phone: one the AICoin proxy launches in the operator's
# AWS account (no CloudFormation stack, no Secrets Manager/SSM). The proxy fills in the
# settings block below - every value on one line, KEY=value, no quotes or spaces (the token is
# base64url) - and passes the whole file as UserData. See infra/aws/README.md, "Hosted phones".
#
# On an image from bake-ami.sh whose bootstrap is ANYPHONE_VERSION, nothing is downloaded
# before setup starts; on a plain Ubuntu 24.04 image (or another version) the setup script of
# ANYPHONE_VERSION comes from the release bucket, sha256-checked.
set -eu
umask 077                      # stack.env holds the token: root only until setup narrows it
install -d -m 755 /etc/anyphone /var/lib/anyphone
cat > /etc/anyphone/stack.env <<'EOF'
ANYPHONE_HOSTED=1
ANYPHONE_TOKEN=__TOKEN__
ANYPHONE_PHONE_ID=__PHONE_ID__
ANYPHONE_VERSION=__VERSION__
ANYPHONE_REPO=https://anyphone-cfn-k7q2m9.s3.amazonaws.com/releases
AWS_REGION=__REGION__
ANYPHONE_RESTORE=__RESTORE__
ANYPHONE_IDLE_MINUTES=0
EOF
umask 022
# shellcheck disable=SC1091
. /etc/anyphone/stack.env
status() { printf '{"state":"%s","message":"%s","time":"%s"}\n' "$1" "$2" "$(date -u +%FT%TZ)" > /var/lib/anyphone/status.json; }

baked=$(cat /usr/local/lib/anyphone/bootstrap.version 2>/dev/null || true)
if [[ ! -x /usr/local/sbin/anyphone-bootstrap || "$baked" != "$ANYPHONE_VERSION" ]]; then
  status booting "Downloading the setup script"
  base="${ANYPHONE_REPO%/}/$ANYPHONE_VERSION"
  get() { curl -fsSL --retry 10 --retry-all-errors --retry-delay 3 --connect-timeout 10 "$@"; }
  if ! sums=$(get "$base/SHA256SUMS") || ! get -o /usr/local/sbin/anyphone-bootstrap.new "$base/bootstrap.sh" ||
     ! echo "$(awk '$2 == "bootstrap.sh" {print $1}' <<<"$sums")  /usr/local/sbin/anyphone-bootstrap.new" | sha256sum -c --quiet -; then
    status error "Could not download the setup script for $ANYPHONE_VERSION"
    echo "anyphone: could not download $base/bootstrap.sh" | tee /dev/console >&2
    exit 1
  fi
  chmod 755 /usr/local/sbin/anyphone-bootstrap.new
  mv -f /usr/local/sbin/anyphone-bootstrap.new /usr/local/sbin/anyphone-bootstrap
fi
/usr/local/sbin/anyphone-bootstrap --install-unit
