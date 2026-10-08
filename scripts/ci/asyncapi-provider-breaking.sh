#!/usr/bin/env bash
# Provider gate for this repo's AsyncAPI file (ADR-019 section 5): runs the AsyncAPI catalog's breaking check
# (scripts/ci/asyncapi-breaking.mjs and lib/asyncapi-model.mjs, copied unchanged; source in README.md) against
# BASE_REF (default origin/main).
#
# The catalog's check reads asyncapi/*.yaml at the repository root; this repo keeps its spec in api/asyncapi/.
# Rather than edit the copied script, this wrapper stages both sides in a scratch git repository: one commit
# with api/asyncapi/ at the merge base of BASE_REF and HEAD, and the working tree's api/asyncapi/ on top,
# both under asyncapi/. A spec that is not on the base yet is reported as a new file (pre-release, stays 1.0.0).
# Requires full history (actions/checkout fetch-depth: 0) and the yaml package next to the scripts
# (npm install --no-save yaml@2.9.1, the catalog's pinned version).
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
BASE_REF="${BASE_REF:-origin/main}"

if ! git rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null; then
  echo "BASE_REF ${BASE_REF} is not available. Fetch full history (actions/checkout fetch-depth: 0) or set BASE_REF." >&2
  exit 2
fi
base="$(git merge-base "$BASE_REF" HEAD 2>/dev/null || git rev-parse "${BASE_REF}^{commit}")"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
git -C "$stage" init -q
mkdir -p "$stage/asyncapi"
if git cat-file -e "${base}:api/asyncapi" 2>/dev/null; then
  git archive "$base" api/asyncapi | tar -x -C "$stage" --strip-components=1
fi
git -C "$stage" add -A
git -C "$stage" -c user.name=ci -c user.email=ci@localhost -c commit.gpgsign=false \
  commit -q --allow-empty -m "api/asyncapi at ${base}"
staged_base="$(git -C "$stage" rev-parse HEAD)"

rm -rf "$stage/asyncapi"
cp -R api/asyncapi "$stage/asyncapi"

echo "asyncapi provider check: api/asyncapi/ against ${BASE_REF} (merge base ${base:0:12})"
cd "$stage"
BASE_REF="$staged_base" node "$root/scripts/ci/asyncapi-breaking.mjs"
