# Session setup

- Before starting work, run `./scripts/preflight.sh` (from repo root) to get current git state, open PRs, and a fast `cargo check`. This takes ~15s and gives you the same context a human would get from looking at the repo.
- Before committing, run `./scripts/ensure-hooks.sh` (idempotent — only installs when missing) so that `cargo fmt --check` and pre-commit checks gate every commit. The hook auto-installs on first use in new clones and worktrees.

# Rust

- The Cargo workspace root is `crates/`. Run all `cargo` commands from that directory (e.g. `cd crates && cargo check`).
- Use `cargo check` for quick verification, restrict further (e.g. `cargo check --package tensorzero-core`) if appropriate. For complex changes, you might want to run `cargo check --all-targets --all-features`. Test suite compilation is slow.
- If you update Rust types or functions used in TypeScript, regenerate bindings with `pnpm build-bindings` (from root), then rebuild the NAPI bindings with `pnpm --filter=@tensorzero/tensorzero-node build`. Run `cargo check` first to catch compilation errors.
- If you change a signature of a struct, function, and so on, use `grep` to find all instances in the codebase. For example, search for `StructName {` when updating struct fields.
- Place crate imports at the top of the file or module using `use crate::...`. Avoid imports inside functions or tests. Avoid long inline crate paths.
- Once you're done with your work, make sure to:
  - Run `cargo fmt`.
  - Run `cargo clippy --all-targets --all-features -- -D warnings` to catch warnings and errors.
  - Run unit tests with `cargo test-unit-fast` which uses `nextest` under the hood.
- Use `#[expect(clippy::...)]` instead of `#[allow(clippy::...)]`.
- Prefer early returns over nested `match`/`if` blocks. For example, use `let ... else { return Err(...) };` or `if !condition { return Err(...) }` to reduce nesting.

## Feedback loop tiering

Choose the lightest verification that catches the class of change. CI is the enforcement point for heavyweight tests; don't run them locally unless you're touching the paths they cover.

| Change scope                            | Local verification                                                                        | ~Time |
| --------------------------------------- | ----------------------------------------------------------------------------------------- | ----- |
| Pure types / structs / trait defs / doc | `cargo check-all`                                                                         | 5s    |
| Business logic (no DB, no I/O)          | `cargo test-unit-fast`                                                                    | 10s   |
| sqlx queries / DB interface             | `cargo test-unit-fast` + `cargo sqlx prepare --workspace -- --all-features --all-targets` | 30s   |
| Gateway boot / config / app state       | `cargo build-e2e` (compiles the e2e binary)                                               | 60s   |
| Provider / inference pipeline           | unit tests locally; CI runs e2e                                                           | —     |
| TypeScript bindings (ts-rs / napi)      | `pnpm build-bindings && pnpm -r typecheck`                                                | 20s   |
| Python client / schemas                 | `cd crates/tensorzero-python && uv run pyright`                                           | 15s   |
| UI (tensorzero-ui)                      | `pnpm --filter=tensorzero-ui run typecheck`                                               | 15s   |

When in doubt, run `cargo test-unit-fast` + `cargo clippy --all-targets --all-features -- -D warnings`. Let CI catch the rest.

- For internally-tagged enums (`#[serde(tag = "...")]`) without lifetimes, use `TensorZeroDeserialize` instead of `Deserialize` for better error messages via `serde_path_to_error`.
- When converting between `Stored*` types and core types, use explicit match-based conversions (e.g. `From` impls or helper functions). Do not round-trip through `serde_json::to_value`/`serde_json::from_value` for type conversions — `serde_json` is only appropriate when the source is already a `serde_json::Value`.

## Rust Testing

- Run tests with `cargo nextest`.
- Use `googletest` for new Rust tests.
- Annotate new tests with `#[gtest]` (googletest crate).
- Include descriptive messages: use `.expect("why")` over `.unwrap()`, and add custom messages to key assertions.
- Prefer `expect_that!` to collect all failure messages; use `assert_that!` when subsequent code depends on the assertion.
- To check a string is non-empty, use `not(eq(""))`.
- Prefer `matches_pattern!` to assert on multiple struct fields at once rather than separate assertions per field.
- Use `matches_json!` and `matches_json_literal!` from the `googletest_matchers` crate for JSON assertions.
- Never compare serialized JSON strings directly — Postgres JSONB does not preserve key order, so parse to `serde_json::Value` and use `matches_json_literal!` instead.

## For APIs

- Use `_` instead of `-` in API routes.
- Use `#[derive(ts_rs::TS)]` for ts-rs exports.
- For any `Option` types visible from the frontend, include `#[ts(export, optional_fields)]` and `#[serde(skip_serializing_if = "Option::is_none")]` so `None` values are not returned over the wire. In very rare cases we may decide do return `null`s, but in general we want to omit them.
- Some tests make HTTP requests to the gateway; to start the gateway, you can run `cargo run-e2e`. (This gateway has dependencies on some docker containers, and it's appropriate to ask the user to run `docker compose -f crates/tensorzero-core/tests/e2e/docker-compose.yml up`.)
- We use RFC 3339 as the standard format for datetime.

## The responsibility between API handlers and database interfaces

- API handler will be a thin function that handles properties injected by Axum and calls a function to perform business logic.
- Business logic layer will generate all data that TensorZero is responsible for (e.g. UUIDs for new datapoints, `staled_at` timestamps).
- Database layer (ClickHouse and/or Postgres) will insert data as-is into the backing database, with the only exception of `updated_at` timestamps which we insert by calling native functions in the database.

## Parallel worktree topology

When dispatching multiple agents via `Agent({ isolation: "worktree" })`, check file-domain overlap first. Agents that touch disjoint crate sets can run in parallel. Agents that share any coordination point must be serialized or pre-merged on `main`.

### Shared coordination points (serialize on these)

```
crates/Cargo.toml                        — workspace dependencies, features, lints
crates/Cargo.lock                        — lockfile
crates/.cargo/config.toml               — cargo aliases, rustflags
crates/deny.toml                         — cargo-deny bans/licenses
crates/clippy.toml                       — disallowed-types/methods
crates/rust-toolchain.toml              — MSRV pin
crates/tensorzero-core/src/db/          — DB schema, migrations, sqlx query cache
crates/tensorzero-stored-config/        — stored config types (multi-crate dependency)
crates/tensorzero-types/                — shared types (multi-crate dependency)
crates/tensorzero-derive/               — proc macros (compiler-facing)
crates/tensorzero-inference-types/      — inference protocol types
ui/                                      — frontend
.github/workflows/                       — CI definitions
```

### Independently parallelizable crates

```
autopilot-client / autopilot-tools / autopilot-worker
tensorzero-auth
tensorzero-http
tensorzero-mcp
tensorzero-node
tensorzero-overhead
evaluations
provider-proxy
reqwest-sse-stream
config-applier
durable-tools / durable-tools-spawn
tensorzero-config-paths
tensorzero-error
tensorzero-unsafe-helpers
tensorzero-optimizers
ts-executor-pool
minijinja-utils
gateway (if not touching DB/config types)
```

### Dispatch format

Before launching parallel worktrees, write a one-line topology summary:

```
Dispatching:
- A: <task>  @ feat/<branch>  (touches: <crates>)
- B: <task>  @ feat/<branch>  (touches: <crates>)
```

If any coordination point appears in both, serialize or pre-edit the shared file on `main` first.

## For Postgres (sqlx)

- **Do not use `format!` for SQL queries.** Use `sqlx::QueryBuilder` for dynamic queries.
  - Use `.push()` for trusted SQL fragments (table names, SQL keywords).
  - Use `.push_bind()` for user-provided values (prevents SQL injection, handles types).
  - Use `.build_query_scalar()` for scalar results, `.build()` for row results.
- **Prefer `sqlx::query!` for static queries** (queries where only values change, not structure). This provides compile-time verification and typed field access (`row.field_name` instead of `row.get("field_name")`).
  - Use `QueryBuilder` only when the query structure is dynamic (e.g., optional WHERE clauses, dynamic table names, conditional JOINs, pagination with optional before/after).
  - For columns that sqlx infers as nullable but are guaranteed non-null by your query logic, use type overrides: `SELECT column as "column!"` to get a non-optional type.
  - For aggregates that should be non-null, use the same pattern: `SELECT COUNT(*)::BIGINT as "total!"`.
- After adding or modifying `sqlx::query!` / `sqlx::query_as!` / `sqlx::query_scalar!` macros, run `cargo sqlx prepare --workspace -- --all-features --all-targets` to regenerate the query cache. This requires a running Postgres database with up-to-date migrations. The generated `.sqlx` directory must be committed to version control.
- Prefer "Postgres" instead of "PostgreSQL" in comments, error messages, docs, etc.
- **Do not run `COUNT(*)` or other aggregations over full inference tables** (`chat_inferences`, `json_inferences`). These tables can be very large and full scans are expensive. Use pre-aggregated rollup tables (e.g. `inference_by_function_statistics`) or filtered partial indexes instead.

# Python Dependencies

We use `uv` to manage Python dependencies.

# Type generation for TypeScript

We use `ts-rs` and `n-api` for TypeScript-Rust interoperability.

- To generate TypeScript type definitions from Rust types, run `pnpm build-bindings`. Then, rebuild `tensorzero-node` with `pnpm -r build`. The generated type definitions will live in `crates/tensorzero-node/lib/bindings/`.
- To generate implementations for `n-api` functions to be called in TypeScript, and package types in `crates/tensorzero-node` for UI, run `pnpm --filter=@tensorzero/tensorzero-node run build`.
- Remember to run `pnpm -r typecheck` to make sure TypeScript and Rust implementations agree on types. Prefer to maintain all types in Rust.

# CI/CD

- Most GitHub Actions workflows run on Unix only, but some also run on Windows and macOS. For workflows that run on multiple operating systems, ensure any bash scripts are compatible with all three platforms. You can check which OS a workflow uses by looking at the `runs-on` field. Setting `shell: bash` in the job definition is often sufficient.

# Misc

- `CONTRIBUTING.md` has additional context on working on this codebase.
- Prefer backticks (`) instead of ticks (') to wrap technical terms in comments, error messages, READMEs, etc.

# Completion report format

When a task is complete and CI is green, report exactly:

```
1. PR: <url>
2. CI: <N> checks pass
3. 验收: I verified <what you actually tested>; you can reproduce by <steps>
```

Do not paste diffs or explain the implementation — diffs are on GitHub, intent is in commit messages. Verify what you can verify: if the change is testable via `curl`, run the `curl` and report the output. If the output doesn't match expectations, fix the code — do not reword the report to hide the discrepancy.
