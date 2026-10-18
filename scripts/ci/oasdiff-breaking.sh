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

# A waiver is fresh (belongs to this PR) when it is absent on the base branch, or when this PR replaced it with a
# new "# Accepted ..." header line (a new acceptance for a new change).
fresh_waiver() {
  git cat-file -e "$BASE_REF:$1" 2>/dev/null || return 0
  [ "$(head -n 1 "$1")" != "$(git show "$BASE_REF:$1" | head -n 1)" ]
}

# Waivers (<spec>.accepted-breaking.txt) cover one change only. Once the mirror that carried one merges, the file
# is stale on the base branch: it is never applied again, and any pull request that touches that spec or the
# waiver must delete it. Pull requests that touch neither only get a notice, so a stale waiver never blocks
# unrelated work; the weekly provider-drift report also lists stale waivers.
changed="$(git diff --name-only "$BASE_REF"...HEAD 2>/dev/null || git diff --name-only "$BASE_REF" HEAD)"
while IFS= read -r stale; do
  [ -n "$stale" ] || continue
  [ -f "$stale" ] || continue
  stale_spec="${stale%.accepted-breaking.txt}.yaml"
  if fresh_waiver "$stale"; then continue; fi
  if printf '%s\n' "$changed" | grep -qxF -e "$stale_spec" -e "$stale"; then
    echo "[oasdiff] $stale is a stale waiver from an earlier change and this PR touches $stale_spec; delete it in this PR" >&2
    fail=1
  else
    echo "[oasdiff] notice: stale waiver $stale is on $BASE_REF; delete it in a follow-up PR"
  fi
done < <(git ls-tree -r --name-only "$BASE_REF" -- openapi api/openapi src/main/resources/openapi 2>/dev/null | grep '\.accepted-breaking\.txt$' || true)

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
  # A reviewed, line-by-line list of accepted breaking changes for this spec (<spec>.accepted-breaking.txt next to
  # it), ported from the provider repos' check. Each line is one oasdiff error; anything not listed still fails.
  # The file must be added in this PR (absent on the base branch, or replaced with a new header line), start with a "# Accepted <date> ...: <reason>"
  # line, and come with a change to the spec itself. Copy it from the provider PR that accepted the change.
  ignore_args=()
  accepted="${spec%.yaml}.accepted-breaking.txt"
  if [ -f "$accepted" ] && fresh_waiver "$accepted"; then
    if ! head -n 1 "$accepted" | grep -q '^# Accepted '; then
      echo "[oasdiff] $accepted must start with a '# Accepted <date> ...: <reason>' line" >&2
      fail=1
      continue
    fi
    if cmp -s "$base_file" "$cp_file"; then
      echo "[oasdiff] $accepted waives changes but $spec is unchanged; remove the waiver" >&2
      fail=1
      continue
    fi
    echo "[oasdiff] applying accepted breaking changes from $accepted:"
    grep -v '^#' "$accepted" | sed 's/^/[oasdiff]   waived: /'
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
      { echo "### Accepted breaking changes in $spec"; echo; grep -v '^#' "$accepted" | sed 's/^/- /'; } >> "$GITHUB_STEP_SUMMARY"
    fi
    ignore_args=(--err-ignore "$accepted")
  fi
  if ! oasdiff breaking --fail-on ERR "${ignore_args[@]}" "$base_file" "$cp_file"; then
    echo "[oasdiff] breaking change detected in $spec"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "OpenAPI breaking-change check failed."
  exit 1
fi

echo "OpenAPI breaking-change check passed."
