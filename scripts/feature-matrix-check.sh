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
# script checks five things rather than one:
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
#   adr-index         every `docs/adr/ADR-*.md` against the rows of `DECISIONS.md`, and every row
#                     against a file. ADR-052 was accepted, referenced by four documents and had no
#                     row in the index for a whole wave; it was repaired by hand, and a
#                     `grep -rn 'DECISIONS.md'` over every `.sh`, `.yml`, `.mill` and `.scala` in
#                     the tree returned nothing, so no script, workflow, build target or suite read
#                     the index. ADR-053 was written next and would have walked into the same hole.
#
# The name is the one the CI step uses (`.github/workflows/ci.yml`, the `generated` job). It was
# also the name the wave plan that commissioned this script used; that plan has since been deleted,
# as every wave plan is once its work has landed. One script means one CI step and one place to look
# when a number moves.
#
# HOW A DOCUMENT SAYS "CHECK ME"
# ------------------------------
# Prose is prose; the script does not guess which sentence is a claim. A document marks the block it
# wants checked with an HTML comment, invisible when rendered, and names in that comment the claims
# it expects to be checked inside it:
#
#   <!-- checked: rows -- claims: capability-rows, delivered-percent -->
#   ... the paragraph ...
#   <!-- /checked -->
#
# A marked block that yields no assertion is a failure, not a pass; a file that was expected to
# carry a block and does not is a failure too; and a block whose `claims:` list is missing is a
# failure, because a block that does not say what it expects checked cannot notice a check that
# stopped happening.
#
# WHY A COUNT WAS NOT ENOUGH, AND WHAT REPLACED IT
# ------------------------------------------------
# This script has now closed the same hole three times, one level up each time.
#
# It began by globbing for its inputs, and a glob that matched nothing took the run from 49 claims
# to 45 and still printed "all true". The repair was a per-section floor -- `counted > 0` -- which
# reopened the hole one line over, because a floor measures a section's liveness and not its
# coverage. The repair for *that* was a per-section count, and four deletions were measured against
# the floored script, one at a time, every one of which exited 0 printing "all true":
#
#   handing `jq` "${manifests[0]}" instead of "${manifests[@]}"   45 claims (dependencies 25 -> 21)
#   narrowing the dependencies selector to one key                29 claims (dependencies 25 -> 5)
#   deleting the `X-Csrf-Token` branch of check_document_region   47 claims (merged-document 9 -> 7)
#   deleting check_rows_region's other-direction loop             49 claims -- *no change at all*
#
# The count caught the first three. It could not catch the fourth, and it could not catch four more
# that were measured against the counted script on 2026-09-07, each of which printed
# `105 claims checked, all true` and exited 0:
#
#   replacing the bare call `reconcile_manifests` with `:`        105 claims -- no change
#   neutering one comparison and leaving its increment in place   105 claims -- no change
#   deleting the milestone table's `total_line_seen` guard        105 claims -- no change
#   flooring close_section, then deleting that comparison whole   103 claims, still "all true"
#
# The second of those was run with ADR-048 publishing `X-Csrf-Token on 99 operations` against a
# document that has it on 20, and the fourth with the same false figure standing. Both exited 0.
# Every one of the four is red against this file now, and the four reds were measured the same day
# against the same documents; the numbers above are what the script printed, not what was expected.
#
# Every one of those is the same defect: **a count of how many times a variable was incremented
# cannot see a comparison that stopped comparing.** `reconcile_manifests` contributed no assertion
# at all, so deleting the call moved nothing; a comparison whose body is deleted but whose
# `assertions=$(( assertions + 1 ))` is left behind keeps the number and loses the check, after
# which ADR-048 could publish `X-Csrf-Token on 99 operations` with the run green; and a floored
# `close_section` makes the number itself advisory.
#
# So the number is no longer the gate. Every comparison **records what it compared** -- a section,
# a scope and a claim *kind* -- and the run reconciles that ledger against two independent fixtures:
#
#   1. the `claims:` list in each marked block's own marker, which is the document stating what it
#      expects checked about itself, in the document whose figures are at stake; and
#   2. the `registry` table at the top of this script, which pins the claim kinds each section is
#      made of, independently of any document.
#
# A comparison that stops comparing therefore fails in *three* places at once and names itself in
# all three: its kind is missing from its region's declared list, missing from its section's pinned
# kinds, and -- for the two prose sections -- the figure it was reading is left unclaimed by the
# residue check below. Silencing it means editing this script, the marked block's marker and the
# published sentence, which is three deliberate edits naming the claim in English rather than one
# deleted line and a number nobody re-derives.
#
# The per-section counts are kept beside the registry rather than instead of it. A count still says
# something the kinds do not -- that a section compared *fewer instances* of a kind it still
# compares, such as one npm dependency's row going missing -- and it is the cheaper half of the two.
#
# THE RESIDUE CHECK: A FIGURE NOBODY CLAIMED
# ------------------------------------------
# The two prose sections consume the text they match: each comparison deletes the sentence fragment
# it read out of a working copy of the block. Whatever digits are left when every comparison has run
# are figures the block publishes that nothing checks, and those are a failure with the leftover
# printed. Dates, `ADR-nnn` references and `wave-n` are not figures about the thing being counted
# and are struck out before the residue is read; everything else has to be claimed by a comparison.
#
# That is the gate that does not route through the ledger at all, so it holds even if the registry
# and a marker are edited to agree with a neutered comparison: publishing
# `X-Csrf-Token on 99 operations` with the `csrf-operations` comparison gone leaves `99` unclaimed.
#
# USAGE
# -----
#   ./scripts/feature-matrix-check.sh          # exit 0 when every published count is true
#   ./scripts/feature-matrix-check.sh --claims # and print the ledger, one compared claim per line
#
# Every disagreement is printed with the file that carries it and the figure that would make it
# true, so the repair is a substitution rather than an investigation.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

print_claims=0
for argument in "$@"; do
  case $argument in
    --claims) print_claims=1 ;;
    *)
      echo "feature-matrix-check: unknown argument $argument (only --claims is understood)." >&2
      exit 2
      ;;
  esac
done

if ! command -v jq >/dev/null 2>&1; then
  echo "feature-matrix-check: jq is required to read the OpenAPI and package manifests." >&2
  exit 2
fi

matrix="docs/FEATURE_MATRIX.md"
adr048="docs/adr/ADR-048-solidjs-typescript-vite-frontend.md"
apireadme="frontend/packages/api/README.md"
deps="DEPENDENCY_MATRIX.md"
decisions="DECISIONS.md"

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
  frontend/packages/feature-alerts/package.json
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
for required in "$matrix" README.md "$adr048" "$apireadme" "$deps" "$decisions" \
                docs/api/openapi.json "${manifests[@]}"; do
  if [[ ! -f $required ]]; then
    echo "feature-matrix-check: $required is missing; the check cannot run." >&2
    exit 2
  fi
done

failures=0

# ---------------------------------------------------------------------------------------------
# The registry: what each section is made of.
# ---------------------------------------------------------------------------------------------
#
# One line per section, naming the claim *kinds* that section compares, space separated and sorted.
# This is the fixture that does not live in a document: a comparison deleted from this file has to
# be deleted from here too, and deleting it from here is a line that says in English which claim
# stopped being made. The `claims:` list in each marked block is the second, independent fixture,
# and the per-section counts below are the third figure -- they see a kind that is still compared
# but compared fewer times, which neither set of kinds can.

declare -A registry=(
  [rows]="capability-rows delivered-percent in-scope-delivered out-of-scope-rows state-total"
  [merged-document]="csrf-operations if-match-operations openapi-version paths-and-schemas\
 principal-operations principal-paths"
  [dependencies]="manifest npm-version"
  [milestones]="milestone-line milestone-p0 milestone-p1 milestone-rows total-line total-p0\
 total-p1 total-rows total-split"
  [adr-index]="adr-file adr-row"
)

# ---------------------------------------------------------------------------------------------
# The ledger.
# ---------------------------------------------------------------------------------------------
#
# Every comparison in this file records one line here before or after it compares. A line is
# `section<TAB>scope<TAB>kind`: the scope is the marked block, manifest or table row the comparison
# was made inside, so that a failure can name where a claim went missing rather than only that one
# did. Nothing else counts assertions; the count printed at the end is the length of this array.

declare -a ledger=()
current_section=""
current_scope=""

# Takes the message in as many arguments as it needs to stay inside 100 columns here; they are
# joined with a space so a wrapped call still prints one sentence.
fail() {
  printf 'feature-matrix-check: %s\n' "$*" >&2
  failures=$((failures + 1))
}

scope() {
  current_section=$1
  current_scope=$2
}

record() {
  ledger+=("$current_section"$'\t'"$current_scope"$'\t'"$1")
}

# The distinct kinds the ledger holds for one section (or, with a scope, for one block), sorted and
# space separated so that two of them can be compared as strings.
kinds_of() {
  local section=$1 scope_filter=${2-}
  local line
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c <<< "$line"
    [[ $a == "$section" ]] || continue
    [[ -z $scope_filter || $b == "$scope_filter" ]] || continue
    printf '%s\n' "$c"
  done | sort -u | tr '\n' ' ' | sed 's/ $//'
}

count_of() {
  local section=$1 line total=0
  for line in ${ledger+"${ledger[@]}"}; do
    [[ ${line%%$'\t'*} == "$section" ]] && total=$(( total + 1 ))
  done
  printf '%s' "$total"
}

# Fixture 2, applied per section: the kinds observed against the kinds pinned in `registry`. Called
# once as each section closes and again over the whole ledger at the foot of the file, because a
# gate with one call site is a gate one deleted line disables -- which is exactly how the floored
# `close_section` used to make every number advisory.
reconcile_registry() {
  local where=$1 only=${2-} section observed expected
  for section in "${!registry[@]}"; do
    [[ -n $only && $section != "$only" ]] && continue
    expected=$(printf '%s\n' ${registry[$section]} | sort -u | tr '\n' ' ' | sed 's/ $//')
    observed=$(kinds_of "$section")
    [[ $observed == "$expected" ]] && continue
    fail "$where: section \`$section\` compared [$observed] and this script's registry pins" \
         "[$expected]; a comparison has been added or has stopped comparing."
  done
}

declare -A section_counts=()

close_section() {
  local name=$1 expected=$2 counted
  counted=$(count_of "$name")
  section_counts[$name]=$counted
  (( counted == expected )) ||
    fail "section \`$name\` compared $counted claims and published $expected the last time this" \
         "script was edited; a comparison has been added, deleted or silenced." \
         "If the change is deliberate, the number beside \`close_section $name\` moves with it."
  reconcile_registry "closing \`$name\`" "$name"
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

# Fixture 1: the `claims:` list out of each region's own opening marker, one line per region, in the
# order `regions`/`region_lines` yield them. A marker with no list prints the empty string, which
# `reconcile_region` reports rather than passes over.
region_claims() {
  local kind=$1 file=$2
  awk -v kind="$kind" '
    index($0, "<!-- checked: " kind) > 0 {
      line = $0
      if (match(line, /claims:.*/)) {
        list = substr(line, RSTART + 7)
        sub(/-->[ \t]*$/, "", list)
        gsub(/,/, " ", list)
        gsub(/^[ \t]+|[ \t]+$/, "", list)
        print list
      } else print ""
    }
  ' "$file"
}

reconcile_region() {
  local where=$1 declared=$2 observed expected
  if [[ -z ${declared// /} ]]; then
    fail "$where: the block's marker names no \`claims:\` list, so nothing states which" \
         "comparisons it expects; a comparison that stopped comparing would not be noticed here."
    return
  fi
  expected=$(printf '%s\n' $declared | sort -u | tr '\n' ' ' | sed 's/ $//')
  observed=$(kinds_of "$current_section" "$current_scope")
  [[ $observed == "$expected" ]] && return
  fail "$where: the block declares claims [$expected] and this run compared [$observed]." \
       "A claim in the marker that was not compared is a check that has gone silent; a claim" \
       "compared but not declared is a check the document does not know about."
}

# The residue: digits the block publishes that no comparison consumed. `consume` is how a
# comparison says which fragment it read.
consume() {
  local -n text_ref=$1
  text_ref=${text_ref/"$2"/}
}

# One comparison, as one call. It records the claim, strikes the figure it read out of the block
# so the residue check below cannot see it, and compares -- and those three are one call and not
# three statements on purpose. The mutation this file exists to stop is a comparison whose body is
# deleted and whose bookkeeping is left behind: with `record`, `consume` and the comparison written
# separately, deleting the middle three lines kept the ledger, kept the figure struck out and kept
# the run green at 216 claims while ADR-048 published `X-Csrf-Token on 99 operations`. Measured
# here on 2026-09-07, which is why the shape below exists.
#
# Arguments: the claim kind, the text to strike out (empty when the caller has already struck it),
# then one or more groups of three -- what the document said, what the fact is, and the sentence to
# print when they differ. A call with no group at all is itself a failure: recording and consuming
# without comparing is the defect, so it is refused in the one place every comparison goes through.
claim() {
  local kind=$1 matched=$2
  shift 2
  record "$kind"
  if [[ -n $matched ]]; then consume text "$matched"; fi
  if (( $# == 0 || $# % 3 != 0 )); then
    fail "$where: the \`$kind\` claim recorded a claim and struck a figure out of the block" \
         "without comparing anything. A claim that does not compare is not a claim."
    return
  fi
  while (( $# > 0 )); do
    [[ $1 == "$2" ]] || fail "$3"
    shift 3
  done
}

report_unclaimed_figures() {
  local where=$1 rest=$2
  # A date, an `ADR-nnn` reference and a `wave-n` are not figures about the thing being counted.
  rest=$(printf '%s' "$rest" | sed -E 's/[0-9]{4}-[0-9]{2}-[0-9]{2}//g; s/ADR-[0-9]+//g;
                                       s/wave-[0-9]+//g')
  [[ $rest =~ [0-9] ]] || return 0
  local leftovers
  leftovers=$(printf '%s' "$rest" | grep -oE '[0-9][0-9.%]*' | sort -u | tr '\n' ' ')
  fail "$where publishes figures no comparison read: $leftovers." \
       "A figure inside a checked block that nothing compares is the state this script exists" \
       "to end; either a comparison reads it or it does not belong inside the markers."
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

# Checks one marked block of prose about the rows. Every figure it recognises is a claim and every
# figure it does not recognise is a failure, so a state total cannot be added to the paragraph
# without a comparison being added beside it.
check_rows_region() {
  local where=$1 text=$2 rest tok n name
  declare -A claimed=()

  # The state totals, in both directions at once. This used to be two loops, the second of which
  # -- the one that notices a state present in the rows and named nowhere in the prose --
  # incremented `failures` and never `assertions`, so deleting it whole changed the printed total
  # by nothing at all. One pass over the union of what is claimed and what the rows hold cannot be
  # half-deleted: a state the prose stopped naming still reaches the comparison as "absent".
  rest=$text
  while [[ $rest =~ ([0-9]+)\ \`([A-Z][A-Z\ ,]*[A-Z])\` ]]; do
    tok=${BASH_REMATCH[0]}; n=${BASH_REMATCH[1]}; name=${BASH_REMATCH[2]}
    claimed[$name]=$n
    rest=${rest#*"$tok"}
    consume text "$tok"
  done

  # "and no `BLOCKED` row" is a claim that the count is zero, and has to be read as one or the
  # completeness check below would accept a state vanishing from the prose entirely.
  rest=$text
  while [[ $rest =~ no\ \`([A-Z][A-Z\ ,]*[A-Z])\`\ row ]]; do
    tok=${BASH_REMATCH[0]}; name=${BASH_REMATCH[1]}
    claimed[$name]=0
    rest=${rest#*"$tok"}
  done

  # A block that names no state at all is not silently claiming every state: the union is only
  # read for a block that makes at least one state claim.
  local -A union=()
  local -a union_names=()
  if (( ${#claimed[@]} > 0 )); then
    for name in "${!claimed[@]}"; do union[$name]=1; done
    for name in "${!actual[@]}"; do union[$name]=1; done
    union_names=("${!union[@]}")            # a state name carries spaces: `SERVICE DONE, NO UI`
  fi
  for name in ${union_names+"${union_names[@]}"}; do
    if [[ -z ${claimed[$name]+set} ]]; then
      claim state-total "" \
        "named" "unnamed" \
        "$where names no total for \`$name\`; ${actual[$name]:-0} row(s) are in that state."
    else
      claim state-total "" \
        "${claimed[$name]}" "${actual[$name]:-0}" \
        "$where says ${claimed[$name]} \`$name\` rows; $matrix has ${actual[$name]:-0}."
    fi
  done

  if [[ $text =~ \(([0-9]+)\ capability\ rows\) ]]; then
    claim capability-rows "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$rows" \
      "$where says ${BASH_REMATCH[1]} capability rows; $matrix has $rows."
  fi

  if [[ $text =~ the\ ([0-9]+)\ deferred\ and\ rejected\ rows ]]; then
    claim out-of-scope-rows "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$out_of_scope" \
      "$where says ${BASH_REMATCH[1]} deferred and rejected rows; $matrix has $out_of_scope."
  fi

  if [[ $text =~ ([0-9]+)\ of\ ([0-9]+)\ in-scope\ capabilities ]]; then
    claim in-scope-delivered "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$complete" \
      "$where says ${BASH_REMATCH[1]} delivered; $matrix has $complete COMPLETE." \
      "${BASH_REMATCH[2]}" "$in_scope" \
      "$where says ${BASH_REMATCH[2]} in-scope capabilities; $matrix has $in_scope."
  fi

  if [[ $text =~ ([0-9]+)% ]]; then
    claim delivered-percent "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$percent" \
      "$where says $complete of $in_scope is ${BASH_REMATCH[1]}%; it is $percent%."
  fi

  report_unclaimed_figures "$where" "$text"
}

for file in "$matrix" README.md; do
  blocks=0
  mapfile -t declared_lists < <(region_claims rows "$file")
  while IFS= read -r text; do
    [[ -z ${text// /} ]] && continue
    blocks=$(( blocks + 1 ))
    scope rows "$file#$blocks"
    check_rows_region "$file (checked: rows #$blocks)" "$text"
    reconcile_region "$file (checked: rows #$blocks)" "${declared_lists[$(( blocks - 1 ))]:-}"
  done < <(regions rows "$file")
  (( blocks > 0 )) ||
    fail "$file carries no \`<!-- checked: rows -->\` block; its totals are unguarded."
done

close_section rows 15

# ---------------------------------------------------------------------------------------------
# 2. The merged OpenAPI document against the figures published about it.
# ---------------------------------------------------------------------------------------------
#
# `docs/api/openapi.json` is generator output regenerated by the build, so it is the fact and the
# prose is the claim. The prose had been wrong in two ways at once: stale by three paths, and
# reporting a count of *operations* as a count of *paths*, which understates how much of the
# contract carries the internal principal header.

read -r doc_paths doc_ops doc_schemas principal_ops principal_paths csrf_ops if_match_ops \
        doc_version \
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
    ([ops[] | select(.op | carries("If-Match"))] | length),
    .openapi
  ] | @tsv' docs/api/openapi.json)

check_document_region() {
  local where=$1 text=$2 rest tok

  if [[ $text =~ \`X-Kui-Principal\`\ on\ ([0-9]+)\ of\ its\ ([0-9]+)\ operations ]]; then
    claim principal-operations "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$principal_ops" \
      "$where: X-Kui-Principal on ${BASH_REMATCH[1]} operations; the document has $principal_ops." \
      "${BASH_REMATCH[2]}" "$doc_ops" \
      "$where says the document has ${BASH_REMATCH[2]} operations; it has $doc_ops."
  fi

  if [[ $text =~ across\ ([0-9]+)\ of\ its\ ([0-9]+)\ paths ]]; then
    claim principal-paths "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$principal_paths" \
      "$where: X-Kui-Principal over ${BASH_REMATCH[1]} paths; the document has $principal_paths." \
      "${BASH_REMATCH[2]}" "$doc_paths" \
      "$where says the document has ${BASH_REMATCH[2]} paths; it has $doc_paths."
  fi

  if [[ $text =~ \`X-Csrf-Token\`\ on\ ([0-9]+)\ operations ]]; then
    claim csrf-operations "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$csrf_ops" \
      "$where says X-Csrf-Token is on ${BASH_REMATCH[1]} operations; the document has $csrf_ops."
  fi

  # `If-Match` is claimed twice in ADR-048 -- once beside the principal header and once in the list
  # of what the browser view keeps -- so this one loops. A claim made twice is compared twice.
  rest=$text
  while [[ $rest =~ \`If-Match\`\ on\ ([0-9]+)\ operations ]]; do
    tok=${BASH_REMATCH[0]}
    claim if-match-operations "$tok" \
      "${BASH_REMATCH[1]}" "$if_match_ops" \
      "$where says If-Match is on ${BASH_REMATCH[1]} operations; the document has $if_match_ops."
    rest=${rest#*"$tok"}
  done

  if [[ $text =~ ([0-9]+)\ paths\ and\ ([0-9]+)\ schemas ]]; then
    claim paths-and-schemas "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$doc_paths" \
      "$where says ${BASH_REMATCH[1]} paths; the document has $doc_paths." \
      "${BASH_REMATCH[2]}" "$doc_schemas" \
      "$where says ${BASH_REMATCH[2]} schemas; the document has $doc_schemas."
  fi

  # The document's own version. It reached this block as an unclaimed figure once the residue check
  # existed, which is the residue check doing its job: `OpenAPI 3.1.0` is a statement about the
  # committed document exactly as the path count is, and it was published and never compared.
  if [[ $text =~ OpenAPI\ ([0-9]+\.[0-9]+\.[0-9]+) ]]; then
    claim openapi-version "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$doc_version" \
      "$where says the document is OpenAPI ${BASH_REMATCH[1]}; it declares $doc_version."
  fi

  report_unclaimed_figures "$where" "$text"
}

for file in "$adr048" "$apireadme"; do
  blocks=0
  mapfile -t declared_lists < <(region_claims merged-document "$file")
  while IFS= read -r text; do
    [[ -z ${text// /} ]] && continue
    blocks=$(( blocks + 1 ))
    scope merged-document "$file#$blocks"
    check_document_region "$file (checked: merged-document #$blocks)" "$text"
    reconcile_region "$file (checked: merged-document #$blocks)" \
                     "${declared_lists[$(( blocks - 1 ))]:-}"
  done < <(regions merged-document "$file")
  (( blocks > 0 )) ||
    fail "$file carries no \`<!-- checked: merged-document -->\` block; its figures are unguarded."
done

close_section merged-document 11

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
#
# Each reconciled manifest records a claim of its own. Until 2026-09-07 this function contributed
# no assertion at all, so replacing the bare call below with `:` printed the same 105 claims and
# exited 0 -- the disk-versus-named-list reconciliation was deletable with no number moving, and
# with it gone a package could be dropped from `manifests` and `sort -u` made its dependencies
# indistinguishable from its siblings'. The claims are what `reconcile_manifest_claims` compares
# against the named list, so the call cannot go quiet now.
reconcile_manifests() {
  local named present manifest
  named=$(printf '%s\n' "${manifests[@]}" | sort)
  present=$(printf '%s\n' frontend/package.json frontend/packages/*/package.json | sort)
  for manifest in "${manifests[@]}"; do
    scope dependencies "$manifest"
    record manifest
  done
  scope dependencies "the manifest roster"
  [[ $named == "$present" ]] && return 0
  local only
  only=$(comm -3 <(printf '%s\n' "$named") <(printf '%s\n' "$present") | tr -d '\t' | tr '\n' ' ')
  fail "this script names ${#manifests[@]} npm manifests and frontend/ holds a different set;" \
       "the disagreement is over: $only"
}

# The other half of the same gate, and a separate call site on purpose: the manifests that were
# reconciled, against the manifests this script names. Called once beside section 3 and again at
# the foot of the file.
reconcile_manifest_claims() {
  local where=$1 line reconciled expected
  reconciled=$(
    for line in ${ledger+"${ledger[@]}"}; do
      IFS=$'\t' read -r a b c <<< "$line"
      # An `if` and not an `&&`: `pipefail` is on, and a false `&&` at the end of the loop body
      # would make this whole pipeline exit 1 and `set -e` end the run with nothing printed.
      if [[ $a == dependencies && $c == manifest ]]; then printf '%s\n' "$b"; fi
    done | sort | tr '\n' ' '
  )
  expected=$(printf '%s\n' "${manifests[@]}" | sort | tr '\n' ' ')
  [[ $reconciled == "$expected" ]] && return 0
  fail "$where: the manifest roster was reconciled for [$reconciled] and this script names" \
       "[$expected]; the disk-versus-named-list reconciliation did not run over every manifest."
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
  scope dependencies "npm:$name"
  record npm-version
  [[ $row == *"$version"* ]] ||
    fail "$deps records \`$name\` as $row; frontend/ pins $version."
done <<< "$dep_rows"

reconcile_manifest_claims "closing \`dependencies\`"
close_section dependencies 35

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

scope milestones "$matrix#1"
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
    record total-rows

    if [[ $claimed_rows =~ \(([0-9]+)\ from\ research\ \+\ ([0-9]+)\ KUI-new\) ]]; then
      (( BASH_REMATCH[1] + BASH_REMATCH[2] == rows )) ||
        fail "$matrix (checked: milestones): the Total line splits the rows as" \
             "${BASH_REMATCH[1]} + ${BASH_REMATCH[2]}, which is not $rows."
      record total-split
    else
      fail "$matrix (checked: milestones): the Total line no longer says how the rows split" \
           "between research and KUI-new; that claim has gone rather than become false."
    fi

    (( claimed_p0 == milestone_p0_total )) ||
      fail "$matrix (checked: milestones): the Total line says $claimed_p0 P0 rows;" \
           "the table has $milestone_p0_total."
    record total-p0
    (( claimed_p1 == milestone_p1_total )) ||
      fail "$matrix (checked: milestones): the Total line says $claimed_p1 P1 rows;" \
           "the table has $milestone_p1_total."
    record total-p1
    continue
  fi

  # "— (rejected)" names the milestone cell of the rejected rows, which is the em dash alone.
  key=${label%% *}
  milestone_claimed[$key]=1
  milestone_lines=$(( milestone_lines + 1 ))

  (( claimed_rows == ${milestone_rows[$key]:-0} )) ||
    fail "$matrix (checked: milestones): $label says $claimed_rows rows;" \
         "${milestone_rows[$key]:-0} rows name that milestone."
  record milestone-rows
  (( claimed_p0 == ${milestone_p0[$key]:-0} )) ||
    fail "$matrix (checked: milestones): $label says $claimed_p0 P0 rows;" \
         "it has ${milestone_p0[$key]:-0}."
  record milestone-p0
  (( claimed_p1 == ${milestone_p1[$key]:-0} )) ||
    fail "$matrix (checked: milestones): $label says $claimed_p1 P1 rows;" \
         "it has ${milestone_p1[$key]:-0}."
  record milestone-p1
done < <(region_lines milestones "$matrix")

if (( milestone_lines == 0 )); then
  fail "$matrix carries no \`<!-- checked: milestones -->\` table; the milestone totals are" \
       "unguarded, which is the state they were in until 2026-09-07."
fi

# This guard used to be the only thing that noticed the Total line going missing, and deleting the
# guard moved no number, so the guard itself was unguarded. It records a claim now: the
# `total-line` kind is pinned in `registry` and declared in the table's own marker, so deleting
# these four lines fails the run in two places and names the claim in both.
if (( total_line_seen == 1 )); then
  record total-line
else
  fail "$matrix (checked: milestones): the table has no Total line, so its grand totals are" \
       "no longer claimed."
fi

# The other direction, and it counts: a milestone the rows use with no line in the table.
for key in "${!milestone_rows[@]}"; do
  if [[ -z ${milestone_claimed[$key]+set} ]]; then
    fail "$matrix (checked: milestones): the table has no line for milestone \`$key\`;" \
         "${milestone_rows[$key]} row(s) name it."
  fi
  record milestone-line
done

reconcile_region "$matrix (checked: milestones #1)" "$(region_claims milestones "$matrix")"
close_section milestones 49

# ---------------------------------------------------------------------------------------------
# 5. The ADR index against the ADRs on disk.
# ---------------------------------------------------------------------------------------------
#
# `DECISIONS.md` is the index of every architecture decision this project has taken, and until
# 2026-09-07 nothing read it. ADR-052 was written, accepted, cited by four other documents and
# left out of the index for a whole wave; the omission was found by a person reading the file and
# repaired by hand. `grep -rn 'DECISIONS.md'` over every `.sh`, `.yml`, `.mill` and `.scala` in the
# tree returned nothing at all, so there was no script, workflow, build target or suite that could
# have noticed, and ADR-053 was being written into the same hole.
#
# Both directions, because each has its own failure: an ADR on disk with no row is the omission
# that happened, and a row naming a file that does not exist is the one that happens when an ADR is
# renamed or withdrawn. The link in the row must resolve to the file the id belongs to -- a row
# whose link points at the wrong document is how an index goes quietly wrong.

adr_files=()
while IFS= read -r file; do adr_files+=("$file"); done < <(
  find docs/adr -maxdepth 1 -name 'ADR-*.md' -type f | sort)

if (( ${#adr_files[@]} == 0 )); then
  fail "docs/adr holds no ADR-*.md files; the index has nothing to be checked against."
fi

# The rows: id -> the path its link names.
declare -A adr_row_link=()
while IFS=$'\t' read -r id link; do
  [[ -z $id ]] && continue
  if [[ -n ${adr_row_link[$id]+set} ]]; then
    fail "$decisions lists \`$id\` more than once; an index with two rows for one decision" \
         "cannot say which of them is current."
  fi
  adr_row_link[$id]=$link
done < <(awk -F'|' '
  NF >= 5 && $2 ~ /\[ADR-[0-9]+\]/ {
    cellone = $2
    if (match(cellone, /ADR-[0-9]+/)) id = substr(cellone, RSTART, RLENGTH)
    link = ""
    if (match(cellone, /\(docs\/adr\/[^)]+\)/)) link = substr(cellone, RSTART + 1, RLENGTH - 2)
    if (id != "") print id "\t" link
  }' "$decisions")

for file in ${adr_files+"${adr_files[@]}"}; do
  id=$(basename "$file" | grep -oE '^ADR-[0-9]+')
  scope adr-index "$file"
  record adr-file
  if [[ -z ${adr_row_link[$id]+set} ]]; then
    fail "$decisions has no row for \`$id\`, which exists at $file." \
         "That is the omission ADR-052 shipped with for a whole wave."
  elif [[ ${adr_row_link[$id]} != "$file" ]]; then
    fail "$decisions links \`$id\` to ${adr_row_link[$id]:-nothing}; the decision is at $file."
  fi
done

for id in "${!adr_row_link[@]}"; do
  scope adr-index "$id"
  record adr-row
  [[ -f ${adr_row_link[$id]} ]] ||
    fail "$decisions has a row for \`$id\` pointing at ${adr_row_link[$id]:-nothing}," \
         "which is not a file in this repository."
done

close_section adr-index 106

# ---------------------------------------------------------------------------------------------

assertions=$(( ${#ledger[@]} ))

if (( assertions == 0 )); then
  fail "no claim was compared at all; every marked block has gone missing."
fi

# The second call site of both whole-run gates. `close_section` runs them section by section as
# each one finishes, which is where the local message belongs; these two run over the finished
# ledger. A gate called from one place is a gate one deleted line disables, and the mutation that
# made that concrete was flooring `close_section` back to `(( counted >= 0 ))` -- after which the
# named deletion printed 101 claims and "all true".
reconcile_registry "the finished run"
reconcile_manifest_claims "the finished run"

if (( print_claims == 1 )); then
  printf 'feature-matrix-check: %d compared claims.\n' "$assertions"
  printf '%s\n' "${ledger[@]}" | sort | sed 's/^/  /'
fi

if (( failures > 0 )); then
  printf 'feature-matrix-check: %d disagreement(s) over %d compared claims.\n' \
    "$failures" "$assertions" >&2
  exit 1
fi

printf 'feature-matrix-check: %d claims checked, all true.\n' "$assertions"
printf '  rows: %d, merged-document: %d, milestones: %d, adr-index: %d,' \
  "${section_counts[rows]}" "${section_counts[merged-document]}" \
  "${section_counts[milestones]}" "${section_counts[adr-index]}"
printf ' dependencies: %d over %d named manifests.\n' \
  "${section_counts[dependencies]}" "${#manifests[@]}"
printf '  %s: %d rows, %d COMPLETE, %d in scope, %d%% delivered.\n' \
  "$matrix" "$rows" "$complete" "$in_scope" "$percent"
printf '  docs/api/openapi.json: %d paths, %d operations, %d schemas;' \
  "$doc_paths" "$doc_ops" "$doc_schemas"
printf ' X-Kui-Principal on %d operations over %d paths.\n' \
  "$principal_ops" "$principal_paths"
printf '  %s: %d rows over %d ADRs in docs/adr.\n' \
  "$decisions" "${#adr_row_link[@]}" "${#adr_files[@]}"
