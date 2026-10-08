#!/usr/bin/env bash
# AsyncAPI breaking-change gate: compares $ASYNCAPI_DIR/*.yaml (default asyncapi) with BASE_REF (default origin/main).
# `npx @asyncapi/cli@2.13.0 diff` does not support AsyncAPI 3.0, so the rules live in asyncapi-breaking.mjs.
# Requires full history (actions/checkout fetch-depth: 0). Status: Proposed.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export BASE_REF="${BASE_REF:-origin/main}"
node scripts/ci/asyncapi-breaking.mjs
