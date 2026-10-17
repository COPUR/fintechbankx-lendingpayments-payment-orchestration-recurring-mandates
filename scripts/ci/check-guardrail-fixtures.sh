#!/usr/bin/env bash
# Self-test for publication-guardrails.yml. Runs the path and secret scan steps from the workflow against fixture lines.
# Each fixture is written to a visible file and to a hidden file, and must be reported (hit) or not (pass) in both.
# Usage: bash scripts/ci/check-guardrail-fixtures.sh <publication-guardrails.yml>
# The key names below are built from parts, so this file does not contain the literal shapes it tests.
set -euo pipefail

wf=${1:?usage: check-guardrail-fixtures.sh <publication-guardrails.yml>}
work=$(mktemp -d)
fail=0

grep -A1 '^permissions:' "$wf" | grep -q 'contents: read' || { echo "the workflow needs a top-level permissions: contents: read"; exit 1; }

# step_body <job> <step name> <file>: the run body of a step, read from the workflow so the test runs the code CI runs.
step_body() {
  awk -v job="  $1:" -v step="$2" '
    $0 == job { in_job = 1; next }
    in_job && /^  [a-z]/ { exit }
    in_job && $0 == "      - name: " step { in_step = 1; next }
    in_step && /^      - name:/ { exit }
    in_step && /^        run: \|$/ { in_run = 1; next }
    in_run { sub(/^          /, ""); print }
  ' "$wf" > "$work/$3"
}
step_body secret-pattern-scan "Scan secret-like patterns" secret.sh
step_body local-path-leak-check "Detect local path or identity leakage" path.sh
[ -s "$work/secret.sh" ] && [ -s "$work/path.sh" ] || { echo "the scan steps were not found in $wf"; exit 1; }

# check <script> <hit|pass> <line>...: one run of the script over every line, in a visible and a hidden file.
check() {
  local script=$1 want=$2 dir out hits n f got
  shift 2
  local cases=("$@")
  dir=$(mktemp -d -p "$work")
  mkdir -p "$dir/.hidden"
  for ((n = 1; n <= ${#cases[@]}; n++)); do
    printf '%s\n' "${cases[n-1]}" > "$dir/c$n.txt"
    printf '%s\n' "${cases[n-1]}" > "$dir/.hidden/h$n.txt"
  done
  out="$dir.out"
  (cd "$dir" && bash -eo pipefail "$work/$script" > "$out" 2>&1) || true
  hits=$(grep -o '^\./[^:]*' "$out" | sort -u) || true
  for ((n = 1; n <= ${#cases[@]}; n++)); do
    for f in "./c$n.txt" "./.hidden/h$n.txt"; do
      if grep -qxF "$f" <<<"$hits"; then got=hit; else got=pass; fi
      if [ "$got" != "$want" ]; then echo "FAIL $script want=$want got=$got: ${cases[n-1]}"; fail=1; fi
    done
  done
}

# exempt_check <listed line> <other line>: the listed line is exempt, the same shape on another line is still reported.
exempt_check() {
  local dir out
  dir=$(mktemp -d -p "$work")
  mkdir -p "$dir/.github"
  out="$dir.out"
  printf 'c.txt\t%s\tfixture: listed by the exemption test\n' "$1" > "$dir/.github/guardrail-exemptions.txt"
  printf '%s\n%s\n' "$1" "$2" > "$dir/c.txt"
  (cd "$dir" && bash -eo pipefail "$work/secret.sh" > "$out" 2>&1) || true
  if grep -q '^\./c\.txt:1:' "$out"; then echo "FAIL exemption: the listed line was reported: $1"; fail=1; fi
  if ! grep -q '^\./c\.txt:2:' "$out"; then echo "FAIL exemption: the unlisted line was not reported: $2"; fail=1; fi
  if grep -q '^\./\.github/guardrail-exemptions\.txt:' "$out"; then echo "FAIL exemption: the exemption list is scanned"; fail=1; fi
}

PW=pass'word'
SK=secret'_'key
BG=BEGIN
U=Users
H=home

hit_cases=(
  "${PW}: hunter2"
  "DB_${PW^^}: hunter2"
  "DB_${PW^^}=hunter2"
  "db${PW^}: hunter2"
  "db-${PW}: \"hunter2\""
  "spring.datasource.${PW}=hunter2"
  "PG${PW^^}=hunter2"
  "${PW^} = \"s3cret\""
  "RUNTIME_${PW^^} = \"abc\""
  "SPRING_DATASOURCE_${PW^^}=abc"
  "--DB_MIGRATION_${PW^^}=abc"
  "db.${PW}=\${X:inline}"
  "${PW}: \${X:hunter2}"
  "${PW}: \${DB_X:-hunter2}"
  "${PW}: \${X:-y}"
  "${PW}: \${X:=hunter2}"
  "${PW}: \${NAME:-hunter2}"
  "${PW}: \${DB_${PW^^}}abc"
  "${PW}: \${X:?msg} hunter2"
  "${PW}: \${X:?msg}hunter2"
  "${PW}: <hunter2>real"
  "${PW}: trueSecret"
  "${PW}: \"true-hunter\""
  "\"${PW}\": \"hunter2\""
  "'${PW^^}': 'hunter2'"
  "${PW}: ;hunter2"
  "${PW}: ,hunter2"
  "${PW}: #hunter2"
  "${PW}: \`hunter2\`"
  "${PW}: env.lowercase_hunter"
  "${PW}: env(\"A\", \"default\")"
  "${PW}: env(X)hunter2"
  "${PW} = env.X + \"abc\""
  "${PW} = x.getPassword() + \"abc\""
  "${PW}: x.get(\"hunter2\")"
  "${PW}: Random_Password.Master.Result"
  "${PW}: my.secret.value"
  "${PW}: random_${PW}"
  "${SK} = abc"
  "${BG} RSA PRIVATE KEY"
)

pass_cases=(
  "${PW}: <db-${PW}>"
  "${PW}: \${DB_${PW^^}}"
  "${PW}: \${X:}"
  "${PW}: \${A:\${b.c:}}"
  "${PW}: \${DB_MIGRATION_${PW^^}:\${spring.datasource.${PW}:}}"
  "${PW}: var.db_${PW}"
  "${PW}: \"false\""
  "${PW}: <db-${PW}>  # placeholder"
  "${PW}: true"
  "${PW}:"
  "${PW}: \${X:-}"
  "docker login -p \"\${DOCKER_${PW^^}:-}\""
  "${PW}: \${POSTGRES_SUPERUSER_CRED:?run init-env.sh first}"
  "${PW}: \${POSTGRES_SUPERUSER_CRED:?}"
  "{ ${PW}: env.DRILL_PSU_PASSWORD }"
  "const psu${PW^} = env.X"
  "PG${PW^^}: env.FBX_DB_CRED_CUS_PROFILE_KYC"
  "master_${PW} = random_${PW}.master.result"
  "manage_master_user_${PW} = true\`"
  "${PW}: \"\${cred}\""
  "PG${PW^^}=\"\${cred}\""
  "owner${PW^} = env(X)"
  "${PW}: env(DB_${PW^^})"
  "${PW} = System.getenv(\"DB_${PW^^}\");"
  "${PW} = x.getPassword()"
  "${PW}: this.creds.getPassword()"
  "\"${PW}\": \"<db-${PW}>\""
  "\"${PW}\": \"\${DB_${PW^^}}\""
  "'${PW}': ''"
  "${PW}: {{ .Values.flyway.${PW} }}"
  "${PW}: {{.Values.db.${PW}}}"
  "SPRING_FLYWAY_${PW^^}:"
  "clean line"
)

check secret.sh hit "${hit_cases[@]}"
check secret.sh pass "${pass_cases[@]}"
check path.sh hit "see /${U}/alice/x" "cd /${H}/me/code"
check path.sh pass "clean line"
exempt_check "${PW}: hunter2" "${PW}: hunter3"

if [ "$fail" -eq 0 ]; then echo "fixtures passed"; else echo "fixtures failed"; fi
exit "$fail"
