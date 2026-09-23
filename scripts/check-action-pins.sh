#!/usr/bin/env bash

# A GitHub Action tag is a mutable Git ref. If an upstream repository or maintainer account is
# compromised, `@v4` can silently become different code with the permissions of this workflow.
# Keep the readable release beside the ref, but execute only an immutable 40-character commit.

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "${ROOT_DIR}"

failures=0
entries=0
checkouts=0

checkout_discards_credentials() {
  local file="$1"
  local line="$2"
  local content="$3"
  local indentation
  indentation=${content%%-*}
  indentation=${#indentation}

  awk -v start="${line}" -v indentation="${indentation}" '
    NR <= start { next }
    {
      first = match($0, /[^[:space:]]/)
      current = first == 0 ? length($0) : first - 1
      if (current == indentation && substr($0, current + 1, 1) == "-") exit
      if (current == indentation + 2 && $0 ~ /^[[:space:]]*with:[[:space:]]*$/) {
        in_with = 1
        next
      }
      if (current <= indentation + 2 && $0 !~ /^[[:space:]]*(#|$)/) in_with = 0
      if (in_with && current == indentation + 4 &&
          $0 ~ /^[[:space:]]*persist-credentials:[[:space:]]*false([[:space:]#]|$)/) {
        found = 1
        exit
      }
    }
    END { exit found ? 0 : 1 }
  ' "${file}"
}

while IFS=: read -r file line content; do
  entry=${content#*uses:}
  entry=${entry#"${entry%%[![:space:]]*}"}
  reference=${entry%%[[:space:]#]*}
  entries=$((entries + 1))

  if [[ "${reference%%@*}" == "actions/checkout" ]]; then
    checkouts=$((checkouts + 1))
    if ! checkout_discards_credentials "${file}" "${line}" "${content}"; then
      printf '%s:%s: actions/checkout must set persist-credentials: false\n' \
        "${file}" "${line}" >&2
      failures=$((failures + 1))
    fi
  fi

  # Repository-local actions are part of this checkout and therefore already fixed by its commit.
  if [[ "${reference}" == ./* ]]; then
    continue
  fi

  # Container actions have no Git commit to select. Their equivalent immutable identity is the
  # registry digest; tags such as `docker://alpine:3.22` are mutable for the same reason action tags
  # are. Keep both accepted forms explicit so a novel reference syntax fails closed.
  if [[ "${reference}" =~ ^docker://[^@[:space:]]+@sha256:[0-9a-f]{64}$ ]]; then
    continue
  fi

  if [[ ! "${reference}" =~ ^[^@[:space:]]+@[0-9a-f]{40}$ ]]; then
    printf '%s:%s: action is not pinned to a full commit SHA or image digest: %s\n' \
      "${file}" "${line}" "${reference}" >&2
    failures=$((failures + 1))
  fi
done < <(
  grep -REn \
    --include='*.yml' \
    --include='*.yaml' \
    '^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*[^[:space:]#]+' \
    .github
)

if ((entries == 0)); then
  echo "no GitHub Action references were found; the policy check is not reading the workflows" >&2
  exit 1
fi

if ((failures > 0)); then
  echo "fix the CI supply-chain errors above before merging workflow changes" >&2
  exit 1
fi

printf 'CI supply chain: %d action references checked; every remote action is immutable; %d checkout steps discard credentials\n' \
  "${entries}" "${checkouts}"
