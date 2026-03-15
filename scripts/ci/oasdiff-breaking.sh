#!/usr/bin/env bash
set -euo pipefail

BASE_REF="${BASE_REF:-origin/main}"

if ! command -v oasdiff >/dev/null 2>&1; then
  echo "oasdiff binary is required but not found in PATH"
  exit 1
fi

if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  git fetch origin main --depth=1
fi

specs=()
while IFS= read -r line; do
  specs+=("$line")
done < <(git ls-files 'openapi/*.yaml' 'api/openapi/*.yaml' 'src/main/resources/openapi/*.yaml' 2>/dev/null || true)

if [ "${#specs[@]}" -eq 0 ]; then
  echo "No OpenAPI specs found; skipping breaking-change check."
  exit 0
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

fail=0
for spec in "${specs[@]}"; do
  if ! git cat-file -e "$BASE_REF:$spec" 2>/dev/null; then
    echo "[oasdiff] new spec file in branch, skipping baseline diff: $spec"
    continue
  fi

  base_file="$tmpdir/base-$(basename "$spec")"
  cp_file="$tmpdir/rev-$(basename "$spec")"

  git show "$BASE_REF:$spec" > "$base_file"
  cp "$spec" "$cp_file"

  echo "[oasdiff] checking $spec"
  if ! oasdiff breaking --fail-on ERR "$base_file" "$cp_file"; then
    echo "[oasdiff] breaking change detected in $spec"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "OpenAPI breaking-change check failed."
  exit 1
fi

echo "OpenAPI breaking-change check passed."
