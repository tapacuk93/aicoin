#!/bin/bash
# User data for a hosted Anyphone instance, launched by aicoin-proxy (HostedPhones).
#
# The one place this script lives: the proxy fills in the placeholders (a NAME in double curly
# braces) and passes the result to RunInstances. Override it without a rebuild with
# anyphone.userDataTemplate (a file path). Placeholders: ANYPHONE_TOKEN, ANYPHONE_PHONE_ID,
# ANYPHONE_VERSION, ANYPHONE_REPO, AWS_REGION. An unknown one fails the launch; every value is
# checked to be shell-safe before it is filled in.
#
# The instance is launched from a prebaked AMI and is thrown away on suspend: the phone's state
# comes back from the iPhone over the anyphoned connection, not from anything kept in AWS.
set -eu
install -d -m 755 /etc/anyphone /var/lib/anyphone
# The token is in this file, so it is root's alone; the bootstrap hands it to anyphoned.
umask 077
cat > /etc/anyphone/stack.env <<'ENV'
ANYPHONE_HOSTED=1
ANYPHONE_TOKEN={{ANYPHONE_TOKEN}}
ANYPHONE_PHONE_ID={{ANYPHONE_PHONE_ID}}
ANYPHONE_VERSION={{ANYPHONE_VERSION}}
ANYPHONE_REPO={{ANYPHONE_REPO}}
AWS_REGION={{AWS_REGION}}
ENV
umask 022
status() { printf '{"state":"%s","message":"%s","time":"%s"}\n' "$1" "$2" "$(date -u +%FT%TZ)" > /var/lib/anyphone/status.json; }
# The AMI normally carries the bootstrap; fetch it from the release only if it does not.
if [[ ! -x /usr/local/sbin/anyphone-bootstrap ]]; then
  status booting "Downloading the setup script"
  url="https://raw.githubusercontent.com/{{ANYPHONE_REPO}}/{{ANYPHONE_VERSION}}/infra/aws/bootstrap.sh"
  if ! curl -fsSL --retry 10 --retry-all-errors --retry-delay 5 -o /usr/local/sbin/anyphone-bootstrap "$url"; then
    status error "Could not download $url"
    echo "anyphone: could not download $url" | tee /dev/console >&2
    exit 1
  fi
  chmod 755 /usr/local/sbin/anyphone-bootstrap
fi
/usr/local/sbin/anyphone-bootstrap --install-unit
