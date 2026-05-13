#!/usr/bin/env bash
# Preflight — run at the start of any new Claude session to get current state.
# Takes ~15-30 seconds. Safe to run repeatedly.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== git status ==="
cd "$REPO_ROOT"
git status --short

echo ""
echo "=== branch vs origin ==="
git branch -vv | grep '^\*'

echo ""
echo "=== recent commits ==="
git log --oneline -10

echo ""
echo "=== open PRs ==="
gh pr list --state open --limit 10 2>/dev/null || echo "(gh not available or no PRs)"

echo ""
echo "=== cargo check (fast) ==="
cd "$REPO_ROOT/crates"
cargo check-all 2>&1 | tail -5

echo ""
echo "=== preflight done ==="
