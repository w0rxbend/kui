#!/usr/bin/env bash

# A GitHub Action tag is a mutable Git ref. If an upstream repository or maintainer account is
# compromised, `@v4` can silently become different code with the permissions of this workflow.
# Keep the readable release beside the ref, but execute only an immutable 40-character commit.

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "${ROOT_DIR}"

failures=0
entries=0

while IFS=: read -r file line content; do
  entry=${content#*uses:}
  entry=${entry#"${entry%%[![:space:]]*}"}
  reference=${entry%%[[:space:]#]*}
  entries=$((entries + 1))

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
  echo "pin every remote action to its full commit SHA (or image digest) and retain the release as a comment" >&2
  exit 1
fi

printf 'CI supply chain: %d action references checked; every remote action is immutable\n' "${entries}"
