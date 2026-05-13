#!/usr/bin/env bash
# Idempotent — only installs when the hook is missing or stale.
# New clones and worktrees get the hook the first time any Gradle/cargo task runs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOOK_PATH="$REPO_ROOT/.git/hooks/pre-commit"
HOOK_SOURCE="$SCRIPT_DIR/pre-commit.sh"

if [ ! -f "$HOOK_SOURCE" ]; then
  echo "ensure-hooks: skipping — pre-commit.sh not found at $HOOK_SOURCE" >&2
  exit 0
fi

if [ ! -d "$REPO_ROOT/.git" ]; then
  # CI tarball / no .git directory — skip silently
  exit 0
fi

if [ -x "$HOOK_PATH" ] && diff -q "$HOOK_PATH" "$HOOK_SOURCE" >/dev/null 2>&1; then
  exit 0
fi

echo "ensure-hooks: installing pre-commit hook" >&2
cp "$HOOK_SOURCE" "$HOOK_PATH"
chmod +x "$HOOK_PATH"
echo "ensure-hooks: pre-commit hook installed" >&2
