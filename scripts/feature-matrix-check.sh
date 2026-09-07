#!/usr/bin/env bash
#
# Compares every count this repository publishes about itself with the thing it counts.
#
# WHY THIS SCRIPT EXISTS
# ----------------------
# `docs/FEATURE_MATRIX.md` opens with a paragraph of state totals and a delivered percentage, and
# `README.md` repeats the percentage. Both were correct when they were written and nothing could
# tell if they stopped being correct. Setting one row's State cell from COMPLETE to REVIEW makes
# four published statements false, and every command the matrix offered for checking itself still
# passed: three of them merely *print* the totals for a person to compare by eye, and the fourth
# grepped for the prose the same pass had just written, so it detected the prose being edited and
# never the prose being wrong. That is how the counts drifted at `25176c0`, which moved one row and
# left the paragraph alone.
#
# The same shape had already happened three times more, in three other documents, which is why this
# script checks four things rather than one:
#
#   rows              the State column of `docs/FEATURE_MATRIX.md` against the totals published in
#                     that file and in `README.md`
#   merged-document   `docs/api/openapi.json` against the operation, path and header figures
#                     published in ADR-048 and `frontend/packages/api/README.md`, which had gone
#                     stale by three paths and additionally called an operation count a path count
#   dependencies      every npm dependency pinned under `frontend/` against the rows of
#                     `DEPENDENCY_MATRIX.md`, whose own preamble requires a row per dependency and
#                     which nothing enforced
#   milestones        the Milestone and Priority columns of the same rows against the milestone
#                     table two paragraphs above the state totals, which sat outside every marker
#                     while the totals beside it were checked -- so moving one row's Milestone cell
#                     left the table wrong and this script green
#
# The name is the one the CI step uses (`.github/workflows/ci.yml`, the `generated` job). It was
# also the name the wave plan that commissioned this script used; that plan has since been deleted,
# as every wave plan is once its work has landed. One script means one CI step and one place to look
# when a number moves.
#
# HOW A DOCUMENT SAYS "CHECK ME"
# ------------------------------
# Prose is prose; the script does not guess which sentence is a claim. A document marks the block it
# wants checked with an HTML comment, invisible when rendered:
#
#   <!-- checked: rows -->
#   ... the paragraph ...
#   <!-- /checked -->
#
# A marked block that yields no assertion is a failure, not a pass, and a file that was expected to
# carry a block and does not is a failure too. A gate that can quietly check nothing is the thing
# this script exists to replace -- and for three waves this script could do it itself. Section 3 is
# where that first mattered: it globbed for its inputs, and a glob that matched nothing took the run
# from 49 claims to 45 and still printed "all true".
#
# WHY EACH SECTION CARRIES A NUMBER AND NOT A FLOOR
# -------------------------------------------------
# The repair for that was a per-section floor -- `counted > 0` -- and it reopened the same hole one
# line over, because a floor measures a section's liveness and not its coverage. Four deletions were
# measured against the floored script on 2026-09-07, one at a time, against these same documents.
# Every one of them exited 0 and printed "all true":
#
#   handing `jq` "${manifests[0]}" instead of "${manifests[@]}"   45 claims (dependencies 25 -> 21)
#   narrowing the dependencies selector to one key                29 claims (dependencies 25 -> 5)
#   deleting the `X-Csrf-Token` branch of check_document_region   47 claims (merged-document 9 -> 7)
#   deleting check_rows_region's other-direction loop             49 claims -- *no change at all*
#
# The first prints `feature-matrix-check: 45 claims checked, all true.` -- byte for byte the output
# the unmatched glob produced, which is the failure the floor was added to end. The last is the
# sharpest: that loop incremented `failures` and never `assertions`, so removing a whole check moved
# the number that exists to notice removals by nothing.
#
# So every section now closes with the count it published last time, not with a floor, and every
# loop that can fail also counts. A section that checks fewer things than it did is a failure with
# a number in it, which is the only shape that distinguishes "this claim went away" from "this
# claim is still true". The cost is that adding or removing a claim means editing the number beside
# the section -- one line, paid loudly, exactly like the manifest list above.
#
# USAGE
# -----
#   ./scripts/feature-matrix-check.sh          # exit 0 when every published count is true
#
# Every disagreement is printed with the file that carries it and the figure that would make it
# true, so the repair is a substitution rather than an investigation.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

if ! command -v jq >/dev/null 2>&1; then
  echo "feature-matrix-check: jq is required to read the OpenAPI and package manifests." >&2
  exit 2
fi

matrix="docs/FEATURE_MATRIX.md"
adr048="docs/adr/ADR-048-solidjs-typescript-vite-frontend.md"
apireadme="frontend/packages/api/README.md"
deps="DEPENDENCY_MATRIX.md"

# The npm manifests section 3 reads, written out one per line. The sentence below used to be false
# of this very list, and the way it was false is worth keeping: section 3 fed `jq` the glob
# `frontend/packages/*/package.json`, and an unmatched glob reaches `jq` as a path it cannot open.
# jq's complaint goes to stderr from inside a process substitution, where neither `set -e` nor `$?`
# can see it, so the run simply read fewer manifests. Measured on 2026-09-06 by pointing that glob
# at a directory that does not exist: 45 claims checked instead of 49, "all true", exit 0. Naming
# the manifests costs one line when a package is added, and `reconcile_manifests` below fails the
# run when that line is forgotten, so the cost is paid loudly rather than silently.
manifests=(
  frontend/package.json
  frontend/packages/api/package.json
  frontend/packages/feature-clusters/package.json
  frontend/packages/feature-consumers/package.json
  frontend/packages/feature-messages/package.json
  frontend/packages/feature-schemas/package.json
  frontend/packages/feature-topics/package.json
  frontend/packages/kernel/package.json
  frontend/packages/shell/package.json
)

# Every input is named rather than globbed: a glob that matches nothing checks nothing and says so
# to nobody, which is the failure this script was written to end.
for required in "$matrix" README.md "$adr048" "$apireadme" "$deps" docs/api/openapi.json \
                "${manifests[@]}"; do
  if [[ ! -f $required ]]; then
    echo "feature-matrix-check: $required is missing; the check cannot run." >&2
    exit 2
  fi
done

failures=0
assertions=0

# ---------------------------------------------------------------------------------------------
# Per-section counts.
# ---------------------------------------------------------------------------------------------
#
# The `assertions == 0` check at the foot of this file is a floor over the whole run, and a floor
# over the whole run cannot see a section going quiet: sections 1, 2 and 4 read marked blocks that
# are always present, so the total never reaches zero however much of section 3 disappears. Each
# section therefore closes with the number of claims it made last time this script was edited, and
# a section that makes a different number is a failure with the section's name and both figures in
# it.
#
# These four numbers are not a configuration. They are the measurement, written down: change what a
# marked block claims, run the script, and put the number it prints here. That is the whole cost,
# and it is what makes a deleted assertion a red run rather than a shorter list of true things.

declare -A section_claims=()
section_floor=0

close_section() {
  local name=$1 expected=$2 counted=$(( assertions - section_floor ))
  section_claims[$name]=$counted
  (( counted == expected )) ||
    fail "section \`$name\` checked $counted claims and published $expected the last time this" \
         "script was edited; an assertion has been added, deleted or silenced." \
         "If the change is deliberate, the number beside \`close_section $name\` moves with it."
  section_floor=$assertions
}

# Takes the message in as many arguments as it needs to stay inside 100 columns here; they are
# joined with a space so a wrapped call still prints one sentence.
fail() {
  printf 'feature-matrix-check: %s\n' "$*" >&2
  failures=$((failures + 1))
}

# ---------------------------------------------------------------------------------------------
# Marked regions.
# ---------------------------------------------------------------------------------------------

# Prints the lines of every marked region of one kind, unflattened. A table is rows and a paragraph
# is prose: section 4 reads a table, where a line is the unit and joining them would destroy it.
region_lines() {
  local kind=$1 file=$2
  awk -v kind="$kind" '
    index($0, "<!-- checked: " kind) > 0 { inside = 1; next }
    index($0, "<!-- /checked -->")  > 0 { inside = 0; next }
    inside { print }
  ' "$file"
}

# Prints every marked region of one kind in one file, flattened to a single line per region so that
# a claim that wraps across two lines is still one string to match against.
regions() {
  local kind=$1 file=$2
  awk -v kind="$kind" '
    index($0, "<!-- checked: " kind) > 0 { inside = 1; next }
    index($0, "<!-- /checked -->")  > 0 { if (inside) { print ""; inside = 0 } next }
    inside { printf "%s ", $0 }
    END { if (inside) print "" }
  ' "$file"
}

# ---------------------------------------------------------------------------------------------
# 1. The feature matrix rows against the totals published about them.
# ---------------------------------------------------------------------------------------------
#
# A row is a table line whose first cell is an `AREA-NNN` id. State is the ninth column, which is
# `$10` once awk's field splitting has produced an empty first field from the leading pipe. The
# recount command this file used to publish read `$8` -- the Milestone column -- while the
# paragraph it was offered to recompute is about State, which is the exact column confusion the
# matrix warns its readers about.

declare -A actual=()
rows=0
while IFS= read -r state; do
  actual["$state"]=$(( ${actual["$state"]:-0} + 1 ))
  rows=$(( rows + 1 ))
done < <(awk -F'|' '
  NF >= 12 && $2 ~ /^ *[A-Z][A-Z]-[0-9]/ {
    s = $10
    sub(/^[ \t]+/, "", s)
    sub(/[ \t]+$/, "", s)
    sub(/\(.*\)$/, "", s)      # DEFERRED(reason) and REJECTED(reason) are the one state, not many
    sub(/[ \t]+$/, "", s)
    if (s != "") print s
  }' "$matrix")

if (( rows == 0 )); then
  fail "$matrix: no capability rows matched; the table shape changed and this script is now blind."
  exit 1
fi

complete=${actual[COMPLETE]:-0}
deferred=${actual[DEFERRED]:-0}
rejected=${actual[REJECTED]:-0}
out_of_scope=$(( deferred + rejected ))
in_scope=$(( rows - out_of_scope ))
if (( in_scope == 0 )); then
  fail "$matrix: every row is deferred or rejected, so there is no in-scope total to check."
  exit 1
fi
# Rounded to the nearest whole percent, the way the prose states it.
percent=$(( (complete * 200 + in_scope) / (in_scope * 2) ))

# Checks one marked block of prose about the rows. Every pattern it recognises is a claim; a block
# that matches none of them is reported rather than passed over.
check_rows_region() {
  local where=$1 text=$2 rest tok n name
  local found=0
  declare -A claimed=()

  rest=$text
  while [[ $rest =~ ([0-9]+)\ \`([A-Z][A-Z\ ,]*[A-Z])\` ]]; do
    tok=${BASH_REMATCH[0]}; n=${BASH_REMATCH[1]}; name=${BASH_REMATCH[2]}
    claimed[$name]=$n
    found=$(( found + 1 ))
    rest=${rest#*"$tok"}
  done

  # "and no `BLOCKED` row" is a claim that the count is zero, and has to be read as one or the
  # completeness check below would accept a state vanishing from the prose entirely.
  rest=$text
  while [[ $rest =~ no\ \`([A-Z][A-Z\ ,]*[A-Z])\`\ row ]]; do
    tok=${BASH_REMATCH[0]}; name=${BASH_REMATCH[1]}
    claimed[$name]=0
    found=$(( found + 1 ))
    rest=${rest#*"$tok"}
  done

  for name in "${!claimed[@]}"; do
    if (( claimed[$name] != ${actual[$name]:-0} )); then
      fail "$where says ${claimed[$name]} \`$name\` rows; $matrix has ${actual[$name]:-0}."
    fi
    assertions=$(( assertions + 1 ))
  done

  # The other direction: a state that exists in the rows and is named nowhere in the paragraph.
  #
  # This loop used to increment `failures` and never `assertions`, so deleting it whole changed the
  # printed total by nothing at all and the run stayed green -- a check that could be removed
  # without moving the number that is supposed to notice removals. It counts now, one claim per
  # state the rows carry, and only for a block that names states at all: a block claiming none of
  # them is not silently claiming all of them.
  if (( ${#claimed[@]} > 0 )); then
    for name in "${!actual[@]}"; do
      if [[ -z ${claimed[$name]+set} ]]; then
        fail "$where names no total for \`$name\`; ${actual[$name]} row(s) are in that state."
      fi
      assertions=$(( assertions + 1 ))
      found=$(( found + 1 ))
    done
  fi

  if [[ $text =~ \(([0-9]+)\ capability\ rows\) ]]; then
    (( BASH_REMATCH[1] == rows )) ||
      fail "$where says ${BASH_REMATCH[1]} capability rows; $matrix has $rows."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  if [[ $text =~ the\ ([0-9]+)\ deferred\ and\ rejected\ rows ]]; then
    (( BASH_REMATCH[1] == out_of_scope )) ||
      fail "$where says ${BASH_REMATCH[1]} deferred and rejected rows; $matrix has $out_of_scope."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  if [[ $text =~ ([0-9]+)\ of\ ([0-9]+)\ in-scope\ capabilities ]]; then
    (( BASH_REMATCH[1] == complete )) ||
      fail "$where says ${BASH_REMATCH[1]} in-scope capabilities are delivered;" \
           "$matrix has $complete COMPLETE."
    (( BASH_REMATCH[2] == in_scope )) ||
      fail "$where says ${BASH_REMATCH[2]} in-scope capabilities; $matrix has $in_scope."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  if [[ $text =~ ([0-9]+)%\ *\** ]]; then
    (( BASH_REMATCH[1] == percent )) ||
      fail "$where says $complete of $in_scope is ${BASH_REMATCH[1]}%; it is $percent%."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  (( found > 0 )) || fail "$where: a block marked \`checked: rows\` asserts nothing."
}

for file in "$matrix" README.md; do
  blocks=0
  while IFS= read -r text; do
    [[ -z ${text// /} ]] && continue
    blocks=$(( blocks + 1 ))
    check_rows_region "$file (checked: rows #$blocks)" "$text"
  done < <(regions rows "$file")
  (( blocks > 0 )) ||
    fail "$file carries no \`<!-- checked: rows -->\` block; its totals are unguarded."
done

close_section rows 23

# ---------------------------------------------------------------------------------------------
# 2. The merged OpenAPI document against the figures published about it.
# ---------------------------------------------------------------------------------------------
#
# `docs/api/openapi.json` is generator output regenerated by the build, so it is the fact and the
# prose is the claim. The prose had been wrong in two ways at once: stale by three paths, and
# reporting a count of *operations* as a count of *paths*, which understates how much of the
# contract carries the internal principal header.

read -r doc_paths doc_ops doc_schemas principal_ops principal_paths csrf_ops if_match_ops \
  < <(jq -r '
  def ops: [.paths | to_entries[] as $p | $p.value | to_entries[] as $o
            | {path: $p.key, op: $o.value}];
  def carries($n): [(.parameters // [])[] | select(.name == $n)] | length > 0;
  [ (.paths | length),
    (ops | length),
    (.components.schemas | length),
    ([ops[] | select(.op | carries("X-Kui-Principal"))] | length),
    ([ops[] | select(.op | carries("X-Kui-Principal")) | .path] | unique | length),
    ([ops[] | select(.op | carries("X-Csrf-Token"))] | length),
    ([ops[] | select(.op | carries("If-Match"))] | length)
  ] | @tsv' docs/api/openapi.json)

check_document_region() {
  local where=$1 text=$2
  local found=0

  if [[ $text =~ \`X-Kui-Principal\`\ on\ ([0-9]+)\ of\ its\ ([0-9]+)\ operations ]]; then
    (( BASH_REMATCH[1] == principal_ops )) ||
      fail "$where says X-Kui-Principal is on ${BASH_REMATCH[1]} operations;" \
           "the document has $principal_ops."
    (( BASH_REMATCH[2] == doc_ops )) ||
      fail "$where says the document has ${BASH_REMATCH[2]} operations; it has $doc_ops."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  if [[ $text =~ across\ ([0-9]+)\ of\ its\ ([0-9]+)\ paths ]]; then
    (( BASH_REMATCH[1] == principal_paths )) ||
      fail "$where says X-Kui-Principal spans ${BASH_REMATCH[1]} paths;" \
           "the document has $principal_paths."
    (( BASH_REMATCH[2] == doc_paths )) ||
      fail "$where says the document has ${BASH_REMATCH[2]} paths; it has $doc_paths."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  if [[ $text =~ \`X-Csrf-Token\`\ on\ ([0-9]+)\ operations ]]; then
    (( BASH_REMATCH[1] == csrf_ops )) ||
      fail "$where says X-Csrf-Token is on ${BASH_REMATCH[1]} operations;" \
           "the document has $csrf_ops."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  if [[ $text =~ \`If-Match\`\ on\ ([0-9]+)\ operations ]]; then
    (( BASH_REMATCH[1] == if_match_ops )) ||
      fail "$where says If-Match is on ${BASH_REMATCH[1]} operations;" \
           "the document has $if_match_ops."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  if [[ $text =~ ([0-9]+)\ paths\ and\ ([0-9]+)\ schemas ]]; then
    (( BASH_REMATCH[1] == doc_paths )) ||
      fail "$where says ${BASH_REMATCH[1]} paths; the document has $doc_paths."
    (( BASH_REMATCH[2] == doc_schemas )) ||
      fail "$where says ${BASH_REMATCH[2]} schemas; the document has $doc_schemas."
    found=$(( found + 1 )); assertions=$(( assertions + 1 ))
  fi

  (( found > 0 )) || fail "$where: a block marked \`checked: merged-document\` asserts nothing."
}

for file in "$adr048" "$apireadme"; do
  blocks=0
  while IFS= read -r text; do
    [[ -z ${text// /} ]] && continue
    blocks=$(( blocks + 1 ))
    check_document_region "$file (checked: merged-document #$blocks)" "$text"
  done < <(regions merged-document "$file")
  (( blocks > 0 )) ||
    fail "$file carries no \`<!-- checked: merged-document -->\` block; its figures are unguarded."
done

close_section merged-document 9

# ---------------------------------------------------------------------------------------------
# 3. Every pinned npm dependency against DEPENDENCY_MATRIX.md.
# ---------------------------------------------------------------------------------------------
#
# The matrix's own preamble says adding a dependency requires a row here, and nothing read the
# manifests to find out. Workspace-internal `@kui/*` packages are not dependencies of the project;
# they are the project, and no row describes one. A version cell may legitimately name more than
# one version (axe-core is pinned differently at the root and in two packages), so the check is that
# the pinned version appears in the cell rather than that the cell equals it.

# The named list is the input; the workspace on disk is the fact. `frontend/pnpm-workspace.yaml`
# makes every directory under `packages/` a member, so the two must be the same set. A package
# added without a line in `manifests` fails here, and so does the mutation that started this: point
# a glob at a directory that does not exist and the disagreement is printed rather than absorbed.
reconcile_manifests() {
  local named present
  named=$(printf '%s\n' "${manifests[@]}" | sort)
  present=$(printf '%s\n' frontend/package.json frontend/packages/*/package.json | sort)
  [[ $named == "$present" ]] && return 0
  local only
  only=$(comm -3 <(printf '%s\n' "$named") <(printf '%s\n' "$present") | tr -d '\t' | tr '\n' ' ')
  fail "this script names ${#manifests[@]} npm manifests and frontend/ holds a different set;" \
       "the disagreement is over: $only"
}
reconcile_manifests

# Under `set -e` an assignment carries the failure out, which the process substitution this used to
# read from could not: a manifest that cannot be parsed now stops the run instead of shrinking it.
if ! dep_rows=$(jq -r 'to_entries[] | select(.key == "dependencies" or .key == "devDependencies")
                       | .value | to_entries[] | "\(.key)\t\(.value)"' \
                  "${manifests[@]}" | sort -u); then
  echo "feature-matrix-check: a package manifest under frontend/ could not be read." >&2
  exit 2
fi

if [[ -z ${dep_rows//[[:space:]]/} ]]; then
  fail "no npm dependency was found in any of the ${#manifests[@]} named manifests;" \
       "the dependency check has nothing to compare $deps against."
  dep_rows=""
fi

while IFS=$'\t' read -r name version; do
  [[ -z $name ]] && continue
  [[ $name == @kui/* ]] && continue
  row=$(awk -F'|' -v n="$name" '
    NF >= 6 {
      f = $2; sub(/^[ \t`]+/, "", f); sub(/[ \t`]+$/, "", f)
      v = $3; sub(/^[ \t]+/, "", v); sub(/[ \t]+$/, "", v)
      if (f == n) { print v; exit }
    }' "$deps")
  if [[ -z $row ]]; then
    fail "$deps has no row for the npm dependency \`$name\` (pinned at $version under frontend/)."
    continue
  fi
  assertions=$(( assertions + 1 ))
  [[ $row == *"$version"* ]] ||
    fail "$deps records \`$name\` as $row; frontend/ pins $version."
done <<< "$dep_rows"

close_section dependencies 25

# ---------------------------------------------------------------------------------------------
# 4. The milestone table against the Milestone and Priority columns of the rows it counts.
# ---------------------------------------------------------------------------------------------
#
# `docs/FEATURE_MATRIX.md` publishes two summaries of the same rows: the state totals at the top,
# which section 1 checks, and a milestone table further down -- eleven rows, three columns and a
# grand total -- which nothing checked. Both were recomputed by hand in the same pass, one was
# brought inside a marker and the other was not, and the consequence is exact: moving one row's
# Milestone cell left the table wrong and this whole script green.
#
# The rule is the same as section 1's, in both directions. Every line of the table must equal what
# the rows say, and every milestone the rows use must have a line -- a new milestone with no line
# is the drift that a table of eleven fixed rows cannot otherwise see.

declare -A milestone_rows=() milestone_p0=() milestone_p1=()
milestone_p0_total=0
milestone_p1_total=0
while IFS=$'\t' read -r milestone priority; do
  [[ -z $milestone ]] && continue
  milestone_rows[$milestone]=$(( ${milestone_rows[$milestone]:-0} + 1 ))
  milestone_p0[$milestone]=${milestone_p0[$milestone]:-0}
  milestone_p1[$milestone]=${milestone_p1[$milestone]:-0}
  if [[ $priority == P0 ]]; then
    milestone_p0[$milestone]=$(( milestone_p0[$milestone] + 1 ))
    milestone_p0_total=$(( milestone_p0_total + 1 ))
  fi
  if [[ $priority == P1 ]]; then
    milestone_p1[$milestone]=$(( milestone_p1[$milestone] + 1 ))
    milestone_p1_total=$(( milestone_p1_total + 1 ))
  fi
done < <(awk -F'|' '
  NF >= 12 && $2 ~ /^ *[A-Z][A-Z]-[0-9]/ {
    m = $8; sub(/^[ \t]+/, "", m); sub(/[ \t]+$/, "", m)
    p = $5; sub(/^[ \t]+/, "", p); sub(/[ \t]+$/, "", p)
    if (m != "") print m "\t" p
  }' "$matrix")

# Reads one cell: leading and trailing space and the bold markers the total line uses.
cell() {
  local value=$1
  value=${value//\*/}
  value=${value#"${value%%[![:space:]]*}"}
  value=${value%"${value##*[![:space:]]}"}
  printf '%s' "$value"
}

declare -A milestone_claimed=()
milestone_lines=0
total_line_seen=0
while IFS= read -r line; do
  [[ $line == \|* ]] || continue
  IFS='|' read -r _ raw_label raw_rows raw_p0 raw_p1 _ <<< "$line"
  label=$(cell "${raw_label:-}")
  [[ $label == "Milestone" || $label == --* || -z $label ]] && continue

  claimed_rows=$(cell "${raw_rows:-}")
  claimed_p0=$(cell "${raw_p0:-}")
  claimed_p1=$(cell "${raw_p1:-}")

  if [[ $label == "Total" ]]; then
    total_line_seen=1
    # "189 (150 from research + 39 KUI-new)": the total and the split that has to add up to it.
    [[ $claimed_rows =~ ^([0-9]+) ]] || {
      fail "$matrix (checked: milestones): the Total line names no row count."
      continue
    }
    (( BASH_REMATCH[1] == rows )) ||
      fail "$matrix (checked: milestones): the Total line says ${BASH_REMATCH[1]} rows;" \
           "the table has $rows."
    assertions=$(( assertions + 1 ))

    if [[ $claimed_rows =~ \(([0-9]+)\ from\ research\ \+\ ([0-9]+)\ KUI-new\) ]]; then
      (( BASH_REMATCH[1] + BASH_REMATCH[2] == rows )) ||
        fail "$matrix (checked: milestones): the Total line splits the rows as" \
             "${BASH_REMATCH[1]} + ${BASH_REMATCH[2]}, which is not $rows."
      assertions=$(( assertions + 1 ))
    else
      fail "$matrix (checked: milestones): the Total line no longer says how the rows split" \
           "between research and KUI-new; that claim has gone rather than become false."
    fi

    (( claimed_p0 == milestone_p0_total )) ||
      fail "$matrix (checked: milestones): the Total line says $claimed_p0 P0 rows;" \
           "the table has $milestone_p0_total."
    (( claimed_p1 == milestone_p1_total )) ||
      fail "$matrix (checked: milestones): the Total line says $claimed_p1 P1 rows;" \
           "the table has $milestone_p1_total."
    assertions=$(( assertions + 2 ))
    continue
  fi

  # "— (rejected)" names the milestone cell of the rejected rows, which is the em dash alone.
  key=${label%% *}
  milestone_claimed[$key]=1
  milestone_lines=$(( milestone_lines + 1 ))

  (( claimed_rows == ${milestone_rows[$key]:-0} )) ||
    fail "$matrix (checked: milestones): $label says $claimed_rows rows;" \
         "${milestone_rows[$key]:-0} rows name that milestone."
  (( claimed_p0 == ${milestone_p0[$key]:-0} )) ||
    fail "$matrix (checked: milestones): $label says $claimed_p0 P0 rows;" \
         "it has ${milestone_p0[$key]:-0}."
  (( claimed_p1 == ${milestone_p1[$key]:-0} )) ||
    fail "$matrix (checked: milestones): $label says $claimed_p1 P1 rows;" \
         "it has ${milestone_p1[$key]:-0}."
  assertions=$(( assertions + 3 ))
done < <(region_lines milestones "$matrix")

if (( milestone_lines == 0 )); then
  fail "$matrix carries no \`<!-- checked: milestones -->\` table; the milestone totals are" \
       "unguarded, which is the state they were in until 2026-09-07."
fi

(( total_line_seen == 1 )) ||
  fail "$matrix (checked: milestones): the table has no Total line, so its grand totals are" \
       "no longer claimed."

# The other direction, and it counts: a milestone the rows use with no line in the table.
for key in "${!milestone_rows[@]}"; do
  if [[ -z ${milestone_claimed[$key]+set} ]]; then
    fail "$matrix (checked: milestones): the table has no line for milestone \`$key\`;" \
         "${milestone_rows[$key]} row(s) name it."
  fi
  assertions=$(( assertions + 1 ))
done

close_section milestones 48

# ---------------------------------------------------------------------------------------------

if (( assertions == 0 )); then
  fail "no claim was checked at all; every marked block has gone missing."
fi

if (( failures > 0 )); then
  printf 'feature-matrix-check: %d disagreement(s) over %d checked claims.\n' \
    "$failures" "$assertions" >&2
  exit 1
fi

printf 'feature-matrix-check: %d claims checked, all true.\n' "$assertions"
printf '  rows: %d, merged-document: %d, milestones: %d,' \
  "${section_claims[rows]}" "${section_claims[merged-document]}" "${section_claims[milestones]}"
printf ' dependencies: %d over %d named manifests.\n' \
  "${section_claims[dependencies]}" "${#manifests[@]}"
printf '  %s: %d rows, %d COMPLETE, %d in scope, %d%% delivered.\n' \
  "$matrix" "$rows" "$complete" "$in_scope" "$percent"
printf '  docs/api/openapi.json: %d paths, %d operations, %d schemas;' \
  "$doc_paths" "$doc_ops" "$doc_schemas"
printf ' X-Kui-Principal on %d operations over %d paths.\n' \
  "$principal_ops" "$principal_paths"
