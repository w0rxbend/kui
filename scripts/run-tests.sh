#!/usr/bin/env bash
#
# Runs every test suite in the repository and prints how many test cases actually ran.
#
# WHY THIS SCRIPT EXISTS
# ----------------------
# Two separate things used to make "the tests passed" mean much less than it read.
#
# 1. `./mill a.test b.test` does not run two test modules. Mill's `.test` is a *command* that takes
#    arguments, so the second name is passed to the first module's test framework as a test-name
#    filter. MUnit then matches no test, ignores every suite, and the run is green having executed
#    nothing. The CI workflow used exactly that form. The correct way to name several modules in one
#    invocation is Mill's selector syntax -- `./mill '{a.test,b.test}'` or `./mill __.test` -- which
#    is what this script uses.
#
# 2. The number Mill prints at the end of a run ("8226/8226, SUCCESS") is its *task* count: how many
#    build steps it evaluated, most of which are compiles, downloads and link steps. It is not a
#    count of test cases, and reading it as one overstates the suite by roughly a factor of two.
#    This script therefore ignores that number and counts `<testcase>` elements in the JUnit XML
#    reports Mill writes, which is the only honest source.
#
# USAGE
# -----
#   ./scripts/run-tests.sh            # every suite except the browser end-to-end one
#   ./scripts/run-tests.sh --with-e2e # everything, including e2e (needs Docker and a browser)
#
# `e2e.test` is excluded by default because it builds container images and drives a real Chromium.
# CI runs it as its own job, with its own timeout and its own failure artifacts.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

with_e2e=false
if [[ "${1:-}" == "--with-e2e" ]]; then
  with_e2e=true
  shift
fi

# Ask Mill which test modules exist rather than listing them here. A list in this file would go
# stale the first time somebody adds a module, and going stale silently is the exact failure this
# script was written to end.
mapfile -t modules < <(./mill resolve '__.test' | grep -E '^[a-zA-Z][a-zA-Z0-9._-]*\.test$' | sort)

if [[ "$with_e2e" == false ]]; then
  filtered=()
  for m in "${modules[@]}"; do
    [[ "$m" == "e2e.test" ]] || filtered+=("$m")
  done
  modules=("${filtered[@]}")
fi

if (( ${#modules[@]} == 0 )); then
  echo "run-tests.sh: resolved no test modules; that cannot be right." >&2
  exit 1
fi

echo "run-tests.sh: ${#modules[@]} test modules"
printf '  %s\n' "${modules[@]}"
echo

# One invocation, so Mill compiles the shared upstream modules once and runs what it can in
# parallel. The braces are Mill's selector syntax; see the note at the top of this file for why the
# space-separated form is a trap.
selector="{$(IFS=,; echo "${modules[*]}")}"
./mill "$selector"

echo
echo "run-tests.sh: counting test cases in the JUnit reports"
# `testForked.dest` is where Mill writes `test-report.xml` for a `.test` module. The module id maps
# onto the path under `out/` by replacing dots with slashes.
total=0
missing=()
for m in "${modules[@]}"; do
  report="out/${m//./\/}/testForked.dest/test-report.xml"
  if [[ ! -f "$report" ]]; then
    missing+=("$m")
    continue
  fi
  # Count `<testcase` openings. The `tests=` attribute on `<testsuites>` would be shorter, but it is
  # written by Mill rather than by the framework and counting the elements themselves cannot drift
  # from what is in the file.
  n="$(grep -c '<testcase' "$report" || true)"
  printf '  %6d  %s\n' "$n" "$m"
  total=$(( total + n ))
done

if (( ${#missing[@]} > 0 )); then
  echo
  # A `.test` module whose directory holds no sources runs, passes, and writes no report. That is
  # not a failure -- there is nothing to fail -- but it must be said out loud, because "the suite is
  # green" reads very differently once you know three of the modules in it are empty. They are
  # counted as the zero they are, and named here so that nobody has to go looking for the
  # difference between the module count and the number of reports.
  echo "run-tests.sh: these modules wrote no report because they contain no test sources:" >&2
  printf '  %s\n' "${missing[@]}" >&2
  echo "run-tests.sh: they count as 0 test cases. Either give them tests or delete the module." >&2
fi

echo
counted=$(( ${#modules[@]} - ${#missing[@]} ))
echo "run-tests.sh: ${#modules[@]} modules (${counted} with tests), ${total} test cases, all passing."
