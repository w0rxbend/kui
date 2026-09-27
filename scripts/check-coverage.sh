#!/usr/bin/env bash
#
# Builds the aggregate scoverage report for the whole build and fails if the totals fell below
# the committed baseline.
#
# WHY THIS SCRIPT EXISTS
# ----------------------
# Coverage that is measured but not asserted decays exactly as fast as no measurement at all: the
# `identity.api` module once shipped a declared test module with zero tests in it, and nothing
# noticed until somebody mutated the code by hand and watched 98/98 tests stay green. A number
# nobody gates on is a number nobody reads.
#
# The gate is a RATCHET, not a target. The baseline in `scripts/coverage-baseline.env` is the best
# measured value so far, minus a point of margin for instrument noise. A change that lowers
# coverage fails here; a change that raises it prints a reminder to move the baseline up, so the
# floor only ever moves in one direction. Per-module minimums are deliberately NOT set: with 80+
# modules a uniform floor punishes small pure modules and invites gaming, and the aggregate is the
# honest first answer. Read `out/scoverage/htmlReportAll.dest/index.html` for the per-module
# breakdown this script does not print.
#
# Cobertura rather than scoverage's own XML because it is the format every external coverage
# service ingests: wiring Codecov or Coveralls later is one upload step, no regeneration.
#
# USAGE
# -----
#   ./scripts/check-coverage.sh            # after any `./mill __.test` (or run-tests.sh) run

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

baseline="$root/scripts/coverage-baseline.env"
if [[ ! -f "$baseline" ]]; then
  echo "check-coverage: $baseline is missing; the ratchet has nothing to compare against" >&2
  exit 1
fi
# shellcheck disable=SC1090
source "$baseline"
: "${SCALA_LINE_RATE_MIN:?set in scripts/coverage-baseline.env}"
: "${SCALA_BRANCH_RATE_MIN:?set in scripts/coverage-baseline.env}"

# The brace selector, not two arguments: `./mill a b` hands `b` to `a` as an argument — the exact
# trap scripts/run-tests.sh was written against, and the reason these two are one selector.
./mill '{scoverage.xmlCoberturaReportAll,scoverage.htmlReportAll}'

cobertura="out/scoverage/xmlCoberturaReportAll.dest/cobertura.xml"
if [[ ! -f "$cobertura" ]]; then
  echo "check-coverage: $cobertura was not produced; the report task ran but wrote nothing" >&2
  exit 1
fi

# A data dir with a scoverage.coverage file but no measurements is a module that was compiled for
# coverage and then never measured — in practice: its tests were served from Mill's cache after
# somebody deleted out/ selectively (a full run never does this; a fresh CI checkout cannot). The
# aggregate above quietly prices those modules at 0%, so name them loudly rather than letting a
# local artifact read as a coverage collapse. `scoverage.workerModule` is the reporter's own
# plumbing and is never tested, so it is the one expected entry.
unmeasured=0
while IFS= read -r data_dir; do
  if ! ls "$data_dir" | grep -q 'scoverage\.measurements\.'; then
    case "$data_dir" in
      *scoverage/workerModule/*) ;;
      *)
        echo "check-coverage: WARNING: $data_dir was instrumented but never measured — its tests did not run against the instrumented classes this run" >&2
        unmeasured=1
        ;;
    esac
  fi
done < <(find out -path '*/scoverage/data.dest' -type d | sort)
if (( unmeasured )); then
  echo "check-coverage: re-run the suites (./scripts/run-tests.sh) so the modules above are measured before trusting the totals below" >&2
fi

# The root <coverage> element carries both rates as fractions ("0.7123"). Both are grepped out of
# the document with -m1 rather than `| head -1`: grep -o finds thousands of per-class rate
# attributes, head exits after one line, and grep's SIGPIPE death is a 141 under pipefail — which
# is exactly the failure this script would have reported as a coverage regression.
line_rate="$(grep -m1 -oE 'line-rate="[0-9.]+"' "$cobertura" | grep -oE '[0-9.]+')"
branch_rate="$(grep -m1 -oE 'branch-rate="[0-9.]+"' "$cobertura" | grep -oE '[0-9.]+')"

failed=0
for pair in "line:$line_rate:$SCALA_LINE_RATE_MIN" "branch:$branch_rate:$SCALA_BRANCH_RATE_MIN"; do
  IFS=: read -r kind actual minimum <<<"$pair"
  if [[ -z "$actual" ]]; then
    echo "check-coverage: no $kind rate found in $cobertura — the report shape changed, fix the extraction" >&2
    failed=1
    continue
  fi
  verdict="$(awk -v a="$actual" -v m="$minimum" 'BEGIN { print (a + 0 >= m) ? "ok" : (a + 0 >= m - 0.005 ? "within" : "below") }')"
  case "$verdict" in
    ok|within)
      printf 'check-coverage: %s rate %.4f (baseline floor %.4f)\n' "$kind" "$actual" "$minimum"
      if awk -v a="$actual" -v m="$minimum" 'BEGIN { exit !(a + 0 > m + 0.01) }'; then
        printf 'check-coverage: %s rate is more than a point above the floor — raise %s\n' "$kind" "$baseline"
      fi
      ;;
    below)
      printf 'check-coverage: %s rate %.4f FELL BELOW the baseline floor %.4f\n' "$kind" "$actual" "$minimum" >&2
      printf '  Either the change removed tests or added untested code. If the drop is deliberate, lower %s in the same commit and say why.\n' "$baseline" >&2
      failed=1
      ;;
  esac
done

if (( failed )); then
  exit 1
fi
echo "check-coverage: aggregate report at out/scoverage/htmlReportAll.dest/index.html"
