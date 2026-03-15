#!/usr/bin/env bash
set -euo pipefail

specs=()
while IFS= read -r line; do
  specs+=("$line")
done < <(git ls-files 'openapi/*.yaml' 'api/openapi/*.yaml' 'src/main/resources/openapi/*.yaml' 2>/dev/null || true)

if [ "${#specs[@]}" -eq 0 ]; then
  echo "No OpenAPI specs found; skipping FAPI/DPoP guard."
  exit 0
fi

fail=0
for spec in "${specs[@]}"; do
  bn="$(basename "$spec")"

  if [[ "$bn" == "open-products-service.yaml" || "$bn" == "atm-directory-service.yaml" ]]; then
    if ! grep -q "security: \[\]" "$spec"; then
      echo "[fapi-guard] $spec should explicitly declare security: [] for public endpoint semantics"
      fail=1
    fi
    continue
  fi

  if grep -q '^paths:\s*{}\s*$' "$spec"; then
    echo "[fapi-guard] skipping meta spec with empty paths: $spec"
    continue
  fi
  if [[ "$bn" == "internal-jwt-lifecycle-service.yaml" ]]; then
    echo "[fapi-guard] skipping internal jwt lifecycle spec: $spec"
    continue
  fi

  if ! grep -qi 'x-fapi-interaction-id' "$spec"; then
    echo "[fapi-guard] missing x-fapi-interaction-id in $spec"
    fail=1
  fi

  if ! grep -qi 'dpop' "$spec"; then
    echo "[fapi-guard] missing DPoP elements in $spec"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "FAPI/DPoP contract guard failed."
  exit 1
fi

echo "FAPI/DPoP contract guard passed."
