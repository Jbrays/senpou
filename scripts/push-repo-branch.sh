#!/usr/bin/env bash
# Publish ./repo contents to the orphan/tracking branch used by Mihon.
# Requires: git remote "origin" pointing to your GitHub repo, and ./repo already generated.
set -euo pipefail
cd "$(dirname "$0")/.."

BRANCH="${BRANCH:-repo}"

if [[ ! -f repo/index.min.json ]]; then
  echo "repo/index.min.json missing. Run ./scripts/build-and-publish-local.sh first." >&2
  exit 1
fi

if ! git remote get-url origin >/dev/null 2>&1; then
  echo "No 'origin' remote. Add it first:" >&2
  echo "  git remote add origin git@github.com:USER/senpou.git" >&2
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Copy generated repo into a clean worktree of the repo branch
git fetch origin "$BRANCH" 2>/dev/null || true

if git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
  git worktree add --force "$TMP/wt" "origin/$BRANCH"
  cd "$TMP/wt"
  git checkout -B "$BRANCH"
else
  git worktree add --force --detach "$TMP/wt"
  cd "$TMP/wt"
  git checkout --orphan "$BRANCH"
  git rm -rf . >/dev/null 2>&1 || true
fi

# Replace branch content with generated repo
find . -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
cp -a "$OLDPWD/repo/." .

git add -A
if git diff --cached --quiet; then
  echo "No changes to publish on branch $BRANCH"
  exit 0
fi

git -c user.email="${GIT_EMAIL:-senpou@users.noreply.github.com}" \
    -c user.name="${GIT_NAME:-Senpou}" \
    commit -m "Update Senpou extension repo"

git push -u origin "HEAD:$BRANCH"
echo "Pushed branch '$BRANCH'."
echo "Mihon URL: https://raw.githubusercontent.com/$(git remote get-url origin | sed -E 's#.*github.com[:/](.+)(\.git)?#\1#' | sed 's/\.git$//')/$BRANCH/index.min.json"
