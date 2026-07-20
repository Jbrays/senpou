#!/usr/bin/env bash
# Build release APKs and generate ./repo for Mihon.
set -euo pipefail
cd "$(dirname "$0")/.."

GITHUB_USER="${GITHUB_USER:-YOUR_GITHUB_USER}"
GITHUB_REPO="${GITHUB_REPO:-senpou}"
BRANCH="${BRANCH:-repo}"

if [[ -f .env.signing ]]; then
  # shellcheck disable=SC1091
  source .env.signing
fi

: "${ALIAS:=senpou}"
: "${KEY_STORE_PASSWORD:=senpou-dev-change-me}"
: "${KEY_PASSWORD:=senpou-dev-change-me}"

if [[ ! -f signingkey.jks ]]; then
  echo "Missing signingkey.jks — create one or copy from backup." >&2
  exit 1
fi

export ALIAS KEY_STORE_PASSWORD KEY_PASSWORD

echo "==> Building release APKs"
./gradlew \
  :src:es:manhwalatino:assembleRelease \
  :src:es:manhwaes:assembleRelease \
  :src:es:topcomicporno:assembleRelease \
  :src:es:ikigaimangas:assembleRelease

FP="$(
  keytool -list -v -keystore signingkey.jks -storepass "$KEY_STORE_PASSWORD" -alias "$ALIAS" 2>/dev/null \
    | python3 -c "import sys,re; t=sys.stdin.read(); m=re.search(r'SHA256:\s*([0-9A-Fa-f:]+)', t); print(m.group(1).replace(':','').lower() if m else '')"
)"

echo "==> Generating repo index"
python3 scripts/generate-repo-index.py \
  --github-user "$GITHUB_USER" \
  --github-repo "$GITHUB_REPO" \
  --branch "$BRANCH" \
  --signing-key-fingerprint "$FP"

echo
echo "Next: push the repo/ folder to branch '$BRANCH' on GitHub, or run:"
echo "  ./scripts/push-repo-branch.sh"
