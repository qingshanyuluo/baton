#!/usr/bin/env bash
# Pre-commit hook — runs fast checks only.
# Keep this under 5 seconds to avoid developer friction.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== pre-commit: cargo fmt --check ==="
cd "$REPO_ROOT/crates"
cargo fmt --all --check

echo "=== pre-commit: pre-commit run ==="
cd "$REPO_ROOT"
uv run --with pre-commit pre-commit run --all-files
