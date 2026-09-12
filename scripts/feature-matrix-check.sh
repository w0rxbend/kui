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
# script checks five documents -- and, since 2026-09-10, itself first:
#
#   self-check        the comparator every section below goes through, and the rounding rule the
#                     delivered percentage is stated with, each driven into both of its states
#                     before a document is read. Neither could previously be broken by anything in
#                     this file, and the comparator is the one line that makes every figure true
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
#   openapi-totals    the per-service and merged OpenAPI totals ADR-052, ADR-053 and ADR-054
#                     publish in their Consequences. Nothing read any of the three until
#                     2026-09-11, and ADR-053's merged figures had been stale for a whole wave
#   capability-claims the **sentences** in `README.md` that say which services this repository has
#                     and which of them the gateway routes, against `services/` on disk and against
#                     `ServiceContracts.byService`; and the frontend-package roster in
#                     `docs/FEATURE_MATRIX.md` against `frontend/packages/`. Every other section in
#                     this file compares a *figure*. This one exists because on 2026-09-11 all of
#                     them were green over a README saying **"No Kafka Connect, no ksqlDB"** against
#                     a tree that ships, routes and draws both — four milestones stale, in the first
#                     paragraph a newcomer reads, outside every marker
#   gate-table        the newcomer's overview, `docs/overview/README.md`, against the three figures
#                     this run derives without starting another process: its own claim total and
#                     section count, the merged OpenAPI triple, and the `DECISIONS.md` row count.
#                     That page held no marker of any kind until 2026-09-12, which is how it came
#                     to publish `390 claims over nine sections` about this script in the row that
#                     describes this script, in the wave whose subject that gate was, while the run
#                     printed 404
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
# a scope, a claim *kind* and, since 2026-09-10, the `claimed>fact` pair -- and the run reconciles
# that ledger against four independent fixtures:
#
#   1. the `claims:` list in each marked block's own marker, which is the document stating what it
#      expects checked about itself, in the document whose figures are at stake;
#   2. the `registry` table at the top of this script, which pins the claim kinds each section is
#      made of, independently of any document;
#   3. the self-check, which drives the one comparison function into both of its states before any
#      document is read; and
#   4. `audit_document_facts`, which re-derives every fact the merged-document section recorded and
#      compares it with what the ledger says that claim was measured against.
#
# A comparison that stops comparing therefore fails in *four* places at once and names itself in
# all of them: its kind is missing from its region's declared list, missing from its section's
# pinned kinds, its fact is missing from the audit, and -- for the two prose sections -- the figure
# it was reading is left unclaimed by the residue check below. Silencing it means editing this
# script, the marked block's marker and the published sentence, rather than one deleted line and a
# number nobody re-derives.
#
# WHY A KIND WAS NOT ENOUGH EITHER, WHICH IS FIXTURES 3 AND 4
# -----------------------------------------------------------
# Wave 6 rebuilt this file around the ledger and reported that a green false figure now cost five
# edits across three files. Measured at that wave's integration and re-measured on 2026-09-10, it
# cost **one**: changing `carries("X-Csrf-Token")` to `carries("X-Kui-Principal")` in the `jq` this
# section used to run let ADR-048 and `frontend/packages/api/README.md` publish `X-Csrf-Token on 52
# operations` with the run printing `216 claims checked, all true` and exiting 0. All three
# fixtures agreed, because all three read only the *kind* of a claim: one of kind `csrf-operations`
# had been made, the marker expected one, the count was right and the residue saw the figure struck
# out. **Nothing looked at what it was compared against, because nothing recorded it.**
#
# The fourth column, the self-check and the audit are that hole. Section 2 below carries the detail
# and the header block below carries the cheapest attack still standing, with its measured cost --
# because a claim about one's own gate is measured, not asserted, and this file is where the
# project learned that.
#
# WHY A LEDGER WAS NOT ENOUGH EITHER, WHICH IS SECTION 7
# ------------------------------------------------------
# Wave 7's rebuild -- the ledger, the fourth column and the audit -- was published as costing two
# edits to defeat. Re-measured on 2026-09-11 against the same file with the documents true, it cost
# **one**:
#
#   claim "$kind-operations" "$tok" "${BASH_REMATCH[2]}" "$fact"   ->   ... "$fact" "$fact"
#
# one call site in `check_document_region`, after which ADR-048 and
# `frontend/packages/api/README.md` published `X-Csrf-Token on 59 operations` against a document
# carrying it on 26, and the run
# printed `256 claims checked, all true` and exited 0. Every gate agreed and each was right to: the
# kind was `csrf-operations`, the marker declared it, the count was 256, the residue saw the figure
# struck out, and the ledger pair was `26>26` -- which is precisely what the audit re-derives from
# the document, because it *is* the document's figure. **A comparison of a fact with itself is a
# true statement about nothing, and every fixture in this file read one side of it.**
#
# Two things are new, and both are about the *claimed* side:
#
#   1. `claim` refuses a claimed figure that does not appear in the text it struck out of the
#      block, so the left-hand side of every comparison has to have been read out of the document;
#   2. `audit_document_facts` re-reads each block out of its file and requires every figure the
#      ledger says that block published to be in it -- a second reading of the claimed side that
#      does not go through `claim` at all.
#
# And the refusals themselves are now driven. Section 7 is there because six guards in wave 7's
# file were mutated one at a time on 2026-09-11 and the run stayed green for every one: a refusal
# whose failing case never arrives in this repository is a line nothing distinguishes from `true`.
# The failing cases are fixtures now.
#
# WHY EIGHT FIXTURES WERE NOT ENOUGH EITHER, WHICH IS SECTIONS 3 AND 6
# ---------------------------------------------------------------------
# Wave 8's eight fixtures all drive the *marked-document* machinery, and the two things in this
# file that carry no marker were therefore untouched by every one of them. Both were one line on
# 2026-09-11, both measured against the shipped script with the documents true:
#
#   `claim npm-version "" "$version" "${matched:-not in the cell}"` -> `... "$version" "$version"`
#      DEPENDENCY_MATRIX.md then published `vite` at `9.9.9` against a `frontend/package.json`
#      pinning `8.2.2`, and the run printed `283 claims checked, all true` and exited 0
#
#   `grep -c 'checked:'` over ADR-052, ADR-053 and ADR-054 answered 0, and ADR-053 had been
#      publishing `61 paths, 72 operations and 156 component schemas` about documents that are
#      65, 76 and 160 for a whole wave, because the one person who measured the correction did not
#      own the file
#
# Section 3 has a fixture and a second reading of the row now; section 6 brings the three ADRs
# inside markers. And attacking the result found three more one-line collapses of the same shape,
# every one of them a `claim` call whose `matched` argument was empty -- which skips the
# claimed-side refusal, so nothing required the claimed figure to have been read out of a
# document. `state-total` let the matrix paragraph publish `99 COMPLETE` against a table of 70;
# `adr-file` let `DECISIONS.md` point ADR-052's row at ADR-053's document; `milestone-rows` let
# the milestone table publish any row count. All three now pass the fragment they read, and the
# two comparisons that cannot -- the residue, and the Total line's split, whose fact is its own
# arithmetic -- have fixtures instead.
#
# The cheapest attack on the file as wave 9 left it was **two lines**, both in this file, measured
# on 2026-09-11: `dependency_version_fact` rewritten to `printf '%s' "$2"`, which answers the pin
# whatever the row says and so defeats the comparison and its audit together, plus fixture 9's
# disagreeing case compared with itself. With both, `vite` at `9.9.9` against a pin of `8.2.2`
# printed `348 claims checked, all true` and exited 0. Two is not a large number. It is the
# measured one, and it is published here rather than in a row nobody re-derives.
#
# WHAT SECTION 8 COSTS, MEASURED ON THE FINISHED FILE RATHER THAN ESTIMATED
# ------------------------------------------------------------------------
# House rule 17 asks the packet that adds a gate to attack it and publish the price. Attacked here,
# on this file, with `README.md` edited to move `connect` and `ksql` out of **Built and routed:**
# and into **Not built:** -- the exact sentence that stood in that file for four milestones:
#
#   one line, at the claim site:  `claim service-state "$tok" "$label" "$fact"` -> `"$label"
#      "$label"`. The claimed-side refusal cannot see it, because the label *is* in the fragment the
#      comparison struck out of the block. **RED anyway, 2 disagreements**, from `service-audit`.
#   one line, in the audit:  `"${declared_service_state[$name]}" "$(service_state_fact "$name")"` ->
#      the declared label twice. **RED, 2 disagreements**, from the claim site.
#   **both, which is two lines: `404 claims checked, all true`, exit 0**, with README publishing
#      *"Not built: `acl`, `quota`, `connect` and `ksql`"* over a tree that ships and routes both.
#
# So the price is **two**, the same as the rest of this file, and the shape is the same as the
# merged-document section's: the comparison inside the block and the re-derivation outside it are
# two statements, and one of them alone is a true statement about nothing. The one thing neither
# edit reaches is `service-roster`, which compares the names the block reached against
# `services/` on disk — deleting a service from the page rather than lying about it is one line
# and still red.
#
# WHAT THE SENTENCE HALF OF SECTION 8 COSTS, MEASURED THE SAME WAY ON 2026-09-12
# -----------------------------------------------------------------------------
# Every figure below was printed by the run beside it, with the sentence
# *"Neither Kafka Connect nor ksqlDB is built: KUI has no Connect screen and no ksqlDB screen."*
# standing inside `README.md`'s `capability-claims` block and the three lists left true:
#
#   the finished gate, nothing edited                                RED, 1 disagreement, exit 1
#   the claim site alone, `"${offences:-...}"` -> the literal        RED, 1 disagreement, from
#                                                                    `capability-prose-audit`
#   `audit_capability_prose` alone, its fact -> `"${pairs#*>}"`      RED, 1 disagreement, from the
#                                                                    claim site
#   **both, which is two lines**                                     416 claims, all true, exit 0
#
# Two, the same as the rest of this file, and for the same reason. Neither edit touches
# `capability_prose_offences` itself, because `verify_capability_prose_refusal` drives that
# function over the sentence directly and asserts it answers `connect ksql`: a body rewritten to
# answer nothing, and an alias emptied out of `service_aliases`, are each red in the fixture
# without any document changing.
#
# **And the green one, which is this gate's stated limit rather than a hole somebody missed.**
# Measured on the finished file: *"The event feed and the throughput cards were never built;
# nothing here reads a broker."* inside the same block prints `416 claims checked, all true` and
# exits 0. It names no service by its id, by a product alias or by `<id> service|screen|page|
# feature`, and nothing in this file knows that *the event feed* is `alerts`. Refusing a synonym
# needs a vocabulary this repository does not have, and a heuristic wide enough to catch one
# refuses honest prose: matching a bare `gateway` would refuse this block's own true sentence
# about `identity` having no proxied contract, which was measured before the narrow rule was
# chosen. The answer if that ever matters is a smaller checked region, not a wider match.
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
#   ./scripts/feature-matrix-check.sh --claims # and print the ledger: section, scope, kind and the
#                                              # `claimed>fact` pair every comparison put together
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
# Section 9's one file. The newcomer's overview held no marker of any kind until 2026-09-12, which
# is how it came to publish `390 claims over nine sections` about the run below in the very row
# that describes it, in the wave whose subject that gate was, and why a figure this script derives
# itself is now read back out of the page that publishes it.
overview="docs/overview/README.md"
# Section 6's three. Each publishes totals about a document the build generates, and until
# 2026-09-11 `grep -c 'checked:'` over all three answered 0.
adr052="docs/adr/ADR-052-metrics-endpoints.md"
adr053="docs/adr/ADR-053-alert-events.md"
adr054="docs/adr/ADR-054-connect-endpoints.md"

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
  frontend/packages/feature-connect/package.json
  frontend/packages/feature-consumers/package.json
  frontend/packages/feature-ksql/package.json
  frontend/packages/feature-messages/package.json
  frontend/packages/feature-schemas/package.json
  frontend/packages/feature-topics/package.json
  frontend/packages/kernel/package.json
  frontend/packages/shell/package.json
)

# Every input is named rather than globbed: a glob that matches nothing checks nothing and says so
# to nobody, which is the failure this script was written to end.
for required in "$matrix" README.md "$adr048" "$apireadme" "$deps" "$decisions" \
                "$adr052" "$adr053" "$adr054" "$overview" \
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
  [self-check]="comparator-agrees comparator-disagrees percent-rounding"
  [rows]="capability-rows delivered-percent in-scope-delivered out-of-scope-rows residue\
 state-total"
  [merged-document]="csrf-operations document-claim document-fact document-facts header-table\
 if-match-operations openapi-version paths-and-schemas principal-operations principal-paths\
 residue"
  [dependencies]="dependency-audit dependency-claim dependency-fact manifest npm-version"
  [milestones]="milestone-line milestone-p0 milestone-p1 milestone-rows total-line total-p0\
 total-p1 total-rows total-split"
  [adr-index]="adr-file adr-row"
  [guard-fixtures]="alias-roster-refused capability-prose-claim-site capability-prose-forms\
 claim-reads-whole-figure claim-refuses-noncomparison claimed-side-read\
 count-assertion debt-duplicate-refused dependency-audit-coverage dependency-audit-refuses\
 dependency-reader-independence\
 dependency-row-compared empty-block-refused fact-independence figure-published\
 header-kind-inverse marker-list-refused openapi-fact-independence quotation-empty-marker\
 quotation-path-claim-site quotation-sweep-reports residue-reports-unclaimed\
 total-split-compared"
  [openapi-totals]="merged-path-prefix openapi-document openapi-roster openapi-totals residue"
  [capability-claims]="capability-prose capability-prose-audit capability-prose-refusal\
 capability-prose-states package-count package-roster\
 residue service-alias-roster service-audit service-audit-coverage service-count\
 service-fact-independence service-fact-roster-independence service-roster service-routed-count\
 service-state"
  [gate-table]="gate-claim-total gate-decisions-rows gate-openapi-document gate-section-count\
 residue"
  [quotations]="quotation quotation-audit quotation-claim-site quotation-drive quotation-path\
 quotation-sweep residue"
  [debt-register]="debt-ids-unique debt-next-id residue"
)

# ---------------------------------------------------------------------------------------------
# The ledger.
# ---------------------------------------------------------------------------------------------
#
# Every comparison in this file records one line here. A line is
# `section<TAB>scope<TAB>kind<TAB>pairs`: the scope is the marked block, manifest or table row the
# comparison was made inside, so that a failure can name where a claim went missing rather than
# only that one did, and `pairs` is `claimed>fact`, joined with `|` when one claim compares more
# than one figure. Nothing else counts assertions; the count printed at the end is the length of
# this array.
#
# **The fourth column is new on 2026-09-10 and it is the whole of wave 6's finding.** The line
# stopped at the kind, and all three fixtures below read only the kind: the `claims:` list in a
# marker, the `registry` table above, and the per-section counts. Every one of them agreed that a
# claim of the right *kind* had been made, and not one of them looked at what it was compared
# *against* -- so repointing one `jq` expression published a false figure with the run green. The
# pairs are printed by `--claims` and re-derived against the document by `audit_document_facts`.

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
  ledger+=("$current_section"$'\t'"$current_scope"$'\t'"$1"$'\t'"${2--}")
}

# The single comparison in this file. `claim` is its only caller and `verify_comparator` puts
# `claim` itself into both states before any document is read, because the cheapest attack on a
# script of comparisons is not to repoint one of them -- it is to make all of them agree at once.
same() {
  [[ $1 == "$2" ]]
}

# The distinct kinds the ledger holds for one section (or, with a scope, for one block), sorted and
# space separated so that two of them can be compared as strings.
kinds_of() {
  local section=$1 scope_filter=${2-}
  local line
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c _ <<< "$line"
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

# Every marked block of one kind in one file, in one place. Sections 1 and 2 ran two copies of this
# loop, and the three refusals inside it -- an empty block, a marker with no `claims:` list, and a
# file carrying no block at all -- had to be mutated twice to be silenced, which is a discount and
# not a gate. There is one copy now, and `verify_marked_block_refusals` drives *this* function over
# fixture files that carry each of those shapes, because no real document does: the comment above
# the emptiness test conceded "No file has carried an empty marked region yet", which is precisely
# why `if [[ -z ${text// /} ]]` -> `if false` was green in both loops on 2026-09-11.
#
# `blocks` counts before the emptiness test and not after it. Until 2026-09-10 an empty marked
# block was skipped without counting, so `declared_lists` -- which `region_claims` yields one entry
# per *marker*, empty or not -- was read one index short from that point on, and every later block
# in the same file was reconciled against the previous block's `claims:` list.
check_marked_file() {
  local kind=$1 file=$2 handler=$3 blocks=0 text
  local -a declared_lists=()
  mapfile -t declared_lists < <(region_claims "$kind" "$file")
  while IFS= read -r text; do
    blocks=$(( blocks + 1 ))
    if [[ -z ${text// /} ]]; then
      fail "$file: the \`checked: $kind\` block #$blocks publishes nothing, so no comparison can" \
           "be made inside it; a marked block that yields no assertion is a failure, not a pass."
      continue
    fi
    scope "$kind" "$file#$blocks"
    "$handler" "$file (checked: $kind #$blocks)" "$text"
    reconcile_region "$file (checked: $kind #$blocks)" "${declared_lists[$(( blocks - 1 ))]:-}"
  done < <(regions "$kind" "$file")
  (( blocks > 0 )) ||
    fail "$file carries no \`<!-- checked: $kind -->\` block; its figures are unguarded."
}

# The residue: digits the block publishes that no comparison consumed. `consume` is how a
# comparison says which fragment it read.
# A section that reads a table rather than a paragraph has no residue check and therefore no
# `text` to strike anything out of. It still has a claimed side that was read out of a document,
# and `claim`'s refusal below is the gate on that, so `matched` is passed there too and this is a
# no-op rather than an error. Written as an existence test and not as a `${text-}` default because
# a nameref to an unset variable under `set -u` ends the run with nothing printed.
consume() {
  [[ -n ${!1+set} ]] || return 0
  local -n text_ref=$1
  text_ref=${text_ref/"$2"/}
}

# Is this figure one the text actually publishes? A whole token, so that `26` is not found inside
# `126` and a claim cannot be satisfied by a digit belonging to another sentence. Defined here
# rather than beside the audit that named it because `claim`'s own refusal calls it, and `claim`
# runs in section 0 before any document is read.
figure_is_published() {
  if printf '%s' "$1" | grep -qE "(^|[^0-9.])$(printf '%s' "$2" | sed 's/[.]/[.]/g')([^0-9%]|%|$)"
  then printf 'published'
  else printf 'not published'
  fi
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
  local kind=$1 matched=$2 pairs=""
  shift 2
  if [[ -n $matched ]]; then consume text "$matched"; fi
  if (( $# == 0 || $# % 3 != 0 )); then
    record "$kind"
    fail "${where-a comparison}: the \`$kind\` claim recorded a claim and struck a figure out of" \
         "the block without comparing anything. A claim that does not compare is not a claim."
    return
  fi
  # `>= 3` and `> 0` are the same condition on every input the refusal above lets through, and the
  # difference is what a fixture sees when that refusal is the thing being mutated: with `> 0`, a
  # two-argument call reaches `fail "$3"` and `set -u` ends the run with nothing printed, which is a
  # red that says nothing. With `>= 3` the mutated refusal simply compares nothing, and
  # `verify_claim_refuses_noncomparison` names it.
  # **The claimed side has to have been read out of the document.** Measured on 2026-09-11 against
  # this file with all six fixtures above in place: rewriting one call site from
  # `claim "$kind-operations" "$tok" "${BASH_REMATCH[2]}" "$fact"` to
  # `claim "$kind-operations" "$tok" "$fact" "$fact"` -- **one line** -- let ADR-048 and
  # `frontend/packages/api/README.md` publish `X-Csrf-Token on 59 operations` against a document
  # carrying it on 26, with the run printing `264 claims checked, all true` and exiting 0. Nothing
  # could see it: the kind was right, the marker declared it, the count was right, the figure was
  # struck out of the residue, and the ledger pair `26>26` is exactly what `audit_document_facts`
  # re-derives from the document, because it *is* the document's own figure. A comparison of the
  # fact with itself is a true statement about nothing.
  #
  # The one thing that attack cannot fake is the text it struck out: `$matched` is the fragment the
  # regular expression took from the block, and the sentence the document actually published is
  # inside it. So every claimed value has to appear there. A caller that wants to compare something
  # no document said passes an empty `matched`, and then the residue check is the gate instead --
  # the figure it did not strike out is left unclaimed and printed.
  if [[ -n $matched ]]; then
    local -a groups=("$@")
    local at
    for (( at = 0; at < ${#groups[@]}; at += 3 )); do
      # A figure is matched whole and anything else as a substring. The substring test alone let a
      # claimed `8` be satisfied by the `18` in the cell beside it, which is the difference between
      # a refusal and a refusal that can be argued with; `figure_is_published` is the same whole-
      # token reader the claimed-side audit is made of, and fixture 8 drives it in both directions.
      if [[ ${groups[$at]} =~ ^[0-9]+(\.[0-9]+)*%?$ ]]; then
        [[ $(figure_is_published "$matched" "${groups[$at]}") == published ]] && continue
      else
        [[ $matched == *"${groups[$at]}"* ]] && continue
      fi
      fail "${where-a comparison}: the \`$kind\` claim compared \`${groups[$at]}\` as the figure" \
           "the document publishes, and the text it struck out of the block --" \
           "\`$matched\` -- does not contain it. The claimed side of a comparison has to be read" \
           "out of the document, or a comparison of the fact with itself passes while the" \
           "sentence beside it says whatever it likes."
    done
  fi
  while (( $# >= 3 )); do
    pairs+="${pairs:+|}$1>$2"
    same "$1" "$2" || fail "$3"
    shift 3
  done
  record "$kind" "$pairs"
}

# The residue records a claim of its own, and did not until 2026-09-10. Its whole body could be
# replaced by `return 0` with the run green at 217 claims: the one gate that deliberately does not
# route through the ledger was itself unledgered, so nothing anywhere noticed it going quiet. It
# routes its own answer through `claim` now -- `no unclaimed figure` against what is left -- so the
# kind is pinned in `registry`, declared in every marked block's `claims:` list, and counted.
# The number words the residue below will recognise when a document publishes one in bold. Held
# here so the expression that uses it stays inside 100 columns and so that a reader can see the
# whole list without unpicking a regular expression.
number_words='one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen'
number_words+='|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty'

report_unclaimed_figures() {
  local where=$1 rest=$2 leftovers="" words
  # A date, an `ADR-nnn` reference, a `wave-n` and a `W<wave>-<packet>` report id are identifiers
  # rather than figures about the thing being counted. The last of the four is new on 2026-09-12,
  # because section 9's block in `docs/FEATURE_MATRIX.md` names three verification reports by path
  # and `W8-07.md` is not a document publishing the figures 8 and 7.
  rest=$(printf '%s' "$rest" | sed -E 's/[0-9]{4}-[0-9]{2}-[0-9]{2}//g; s/ADR-[0-9]+//g;
                                       s/wave-[0-9]+//g; s/\bW[0-9]+-A?[0-9]+//g')
  if [[ $rest =~ [0-9] ]]; then
    leftovers=$(printf '%s' "$rest" | grep -oE '[0-9][0-9.%]*' | sort -u | tr '\n' ' ')
    leftovers=${leftovers% }
  fi
  # **A figure spelled as a word is still a figure, when it is published as one.** `ADR-054` carried
  # `**Four**` -- the merged document's connect-path count -- inside a marker on 2026-09-11, and
  # rewriting it to `**Nine**` left the run green, because every comparison and the residue itself
  # read digits only. The narrow shape is what is refused: a number word in **bold** is a document
  # publishing a figure, and it has to be written as a digit so that a comparison can read it.
  # A number word in ordinary prose -- "one operation per path", "the three health probes" -- is
  # explanation rather than publication and is deliberately still invisible here; that limit is
  # stated rather than hidden, because the next attack on this file should know where it is.
  # `|| true` because a block with no bold number word is the ordinary case, and a `grep` that
  # matches nothing exits 1 -- which, under `pipefail` inside an assignment, ends the run with
  # nothing printed at all. That failure mode has cost this file an hour before.
  words=$(printf '%s' "$rest" \
    | { grep -oiE "\\*\\*($number_words)\\*\\*" || true; } | sort -u | tr '\n' ' ')
  words=${words% }
  [[ -n $words ]] && leftovers="${leftovers:+$leftovers }$words"
  local complaint="$where publishes figures no comparison read: $leftovers. A figure inside a\
 checked block that nothing compares is the state this script exists to end; either a comparison\
 reads it or it does not belong inside the markers. A number word in bold counts as a published\
 figure and has to be written as a digit."
  claim residue "" "no unclaimed figure" "${leftovers:-no unclaimed figure}" "$complaint"
}

# ---------------------------------------------------------------------------------------------
# 0. The self-check: this script's own two rules, before it applies either to a document.
# ---------------------------------------------------------------------------------------------
#
# Two rules this file applies that nothing in it could previously break, and they are the two
# cheapest attacks on a script whose whole job is comparing.
#
# **The comparator.** Every claim goes through `claim`, so `[[ $1 == "$2" ]]` -> `[[ -n $1 ]]` is
# one line that makes every published figure true at once. The ledger still fills, every marker
# still reconciles, every count still matches and every section still closes: the three fixtures
# below all read the *kind* of a claim and none of them can tell a comparison from an agreement.
# `verify_comparator` drives `claim` itself -- not `same` underneath it -- with a pair that must
# disagree and a pair that must agree, over a shadowed `failures`, and stops the run if it gets
# either wrong. It is stated as an exit rather than a `fail` because a script that cannot compare
# cannot report on itself.
#
# **The rounding rule.** `percent` is published as "about N%" and is rounded, not truncated. No row
# count in this repository has yet landed on a `.5` boundary, so truncation agrees with rounding on
# every number this file has ever printed and would disagree the first time it mattered -- README
# and the matrix would then publish two different percentages of the same rows, each true of its
# own arithmetic. `5 of 8` and `3 of 8` are exactly that boundary: 62.5 and 37.5, where rounding
# answers 63 and 38 and truncation answers 62 and 37.

die() {
  printf 'feature-matrix-check: %s\n' "$*" >&2
  exit 2
}

# Rounded to the nearest whole percent, the way the prose states it. A function rather than an
# expression so that `verify_rounding` can put the shipped arithmetic -- and not a copy of it --
# on both sides of a `.5`.
rounded_percent() {
  printf '%s' $(( ($1 * 200 + $2) / ($2 * 2) ))
}

verify_comparator() {
  local failures=0 disagreed agreed
  # The complaint this pair provokes is the point of the case and not something to print: it is
  # sent nowhere so that a green run says nothing about a comparison that worked.
  claim comparator-disagrees "" "1" "2" "an unequal pair must be reported" 2>/dev/null
  disagreed=$failures
  failures=0
  claim comparator-agrees "" "2" "2" "an equal pair must be passed"
  agreed=$failures

  (( disagreed == 1 )) ||
    die "the comparator accepted an unequal pair: \`claim\` compared 1 with 2 and reported" \
        "$disagreed disagreement(s). Every figure this run would print as true is unchecked."
  (( agreed == 0 )) ||
    die "the comparator refused an equal pair: \`claim\` compared 2 with 2 and reported" \
        "$agreed disagreement(s). Nothing this run reports can be believed either."
}

verify_rounding() {
  local failures=0 answered complaint
  local -a cases=("1 2 50" "1 3 33" "2 3 67" "5 8 63" "3 8 38")
  local one
  for one in "${cases[@]}"; do
    set -- $one
    answered=$(rounded_percent "$1" "$2")
    complaint="the rounding rule answers $answered% for $1 of $2 and the prose is written against\
 $3%: truncation and rounding part company on a .5 boundary, and $1 of $2 is one."
    claim percent-rounding "" "$answered" "$3" "$complaint"
  done
  (( failures == 0 )) ||
    die "the percentage this script publishes is no longer rounded to the nearest whole percent;" \
        "README and $matrix would print two different percentages of the same rows."
}

scope self-check "the comparator"
verify_comparator
scope self-check "the rounding rule"
verify_rounding
close_section self-check 7

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
percent=$(rounded_percent "$complete" "$in_scope")

# Checks one marked block of prose about the rows. Every figure it recognises is a claim and every
# figure it does not recognise is a failure, so a state total cannot be added to the paragraph
# without a comparison being added beside it.
check_rows_region() {
  local where=$1 text=$2 rest tok n name
  declare -A claimed=() claimed_token=()

  # The state totals, in both directions at once. This used to be two loops, the second of which
  # -- the one that notices a state present in the rows and named nowhere in the prose --
  # incremented `failures` and never `assertions`, so deleting it whole changed the printed total
  # by nothing at all. One pass over the union of what is claimed and what the rows hold cannot be
  # half-deleted: a state the prose stopped naming still reaches the comparison as "absent".
  rest=$text
  while [[ $rest =~ ([0-9]+)\ \`([A-Z][A-Z\ ,]*[A-Z])\` ]]; do
    tok=${BASH_REMATCH[0]}; n=${BASH_REMATCH[1]}; name=${BASH_REMATCH[2]}
    claimed[$name]=$n
    # The fragment the figure was read out of, kept so that the comparison below can be handed it.
    # Without it `claim` sees an empty `matched`, its claimed-side refusal is skipped, and the
    # whole of section 1 is one line from a comparison of the fact with itself -- measured on
    # 2026-09-11 on this file with sections 3 and 6 finished: `"${claimed[$name]}"` rewritten to
    # `"${actual[$name]:-0}"` let this paragraph publish `99 COMPLETE` against a table holding 70
    # with the run printing `346 claims checked, all true` and exiting 0.
    claimed_token[$name]=$tok
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
      claim state-total "${claimed_token[$name]:-}" \
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
  check_marked_file rows "$file" check_rows_region
done

close_section rows 30

# ---------------------------------------------------------------------------------------------
# 2. The merged OpenAPI document against the figures published about it.
# ---------------------------------------------------------------------------------------------
#
# `docs/api/openapi.json` is generator output regenerated by the build, so it is the fact and the
# prose is the claim. The prose had been wrong in two ways at once: stale by three paths, and
# reporting a count of *operations* as a count of *paths*, which understates how much of the
# contract carries the internal principal header.

# WHAT A CLAIM WAS COMPARED AGAINST (2026-09-10)
# ---------------------------------------------
# This block used to read eight values out of one `jq` program, positionally, and hand each to the
# comparison that published it. Measured at wave 6's integration and again here: changing
# `carries("X-Csrf-Token")` to `carries("X-Kui-Principal")` in that program -- one line -- let
# ADR-048 and `frontend/packages/api/README.md` publish `X-Csrf-Token on 52 operations` with the
# run printing `216 claims checked, all true` and exiting 0. The claim registry saw that a claim of
# kind `csrf-operations` had been made; each marker saw its block had been checked; `close_section`
# counted it; and the residue check saw the figure struck out. **None of the four looked at what
# the claim was compared against**, because nothing recorded it.
#
# Three things changed, and each is a separate gate:
#
#   1. **There is no per-header expression left to repoint.** The header table below is derived
#      once, generically, for every header parameter the document declares, and a claim is looked
#      up in it *by the header name the published sentence itself names*. `csrf-operations` is
#      compared against `X-Csrf-Token` because the sentence says `X-Csrf-Token`; there is no
#      constant in this file that says which header that claim is about. Renaming the header in
#      the prose renames the claim (`kind_for_header`), and a header the document does not declare
#      answers `absent`, which is equal to no figure.
#   2. **The ledger records the pair**, `claimed>fact`, and `audit_document_facts` re-derives every
#      one of this section's facts from the document after the section has closed. A comparison
#      edited to compare a figure with itself -- the cheapest attack left once (1) is in place --
#      records the false figure as the fact and is named here.
#   3. **The table is reconciled against the document a second way**, header by header and then in
#      total, by an expression that is not the one that built it. A value written into the table to
#      make one published figure true is caught by the per-header comparison and by the total, and
#      an audit derivation taught to answer that same false figure is caught by the per-header
#      comparison too, because its two sides come from different places.
#
# The cheapest attack still standing is published in this file's own header with its measured
# cost, because house rule 17 of the wave that wrote this asks for the attack and not for the
# assertion -- and because a cost kept in another document is a figure nobody re-derives.

read -r doc_paths doc_ops doc_schemas doc_version < <(jq -r '
  def ops: [.paths | to_entries[] as $p | $p.value | to_entries[] as $o
            | {path: $p.key, op: $o.value}];
  [ (.paths | length),
    (ops | length),
    (.components.schemas | length),
    .openapi
  ] | @tsv' docs/api/openapi.json)

# Every header parameter the document declares, with the operations and the distinct paths that
# carry it. One expression for all of them: weakening it moves every header's figure at once, so
# the true claims in the same block go red beside the one the attack was aimed at.
declare -A header_ops=() header_paths=()
while IFS=$'\t' read -r header ops paths; do
  [[ -z $header ]] && continue
  header_ops[$header]=$ops
  header_paths[$header]=$paths
done < <(jq -r '
  [ .paths | to_entries[] as $p | $p.value | to_entries[] as $o
    | (($o.value.parameters // [])[] | select(.in == "header") | .name) as $n
    | {name: $n, path: $p.key} ]
  | group_by(.name)[]
  | [ .[0].name, length, ([.[].path] | unique | length) ] | @tsv' docs/api/openapi.json)

# One header's figures, read out of the document by an expression of its own. The audit does not
# read `header_ops`: a table this script filled is not independent of the claims it filled, and the
# whole finding is that a claim compared against something other than the document cannot be seen
# by a fixture that reads the same something.
# The third argument is the document, defaulted rather than fixed, so that
# `verify_fact_independence` can put this function over a fixture document whose answer no table in
# this run holds. A body rewritten to read `header_ops` -- the one mutation this function exists to
# make impossible, and green on 2026-09-11 -- answers the real document's figure for a fixture that
# contains one operation, and is named there.
header_fact_from_document() {
  jq -r --arg name "$1" --arg mode "$2" '
    [ .paths | to_entries[] as $p | $p.value | to_entries[] as $o
      | select([($o.value.parameters // [])[] | select(.name == $name)] | length > 0)
      | $p.key ] as $hits
    | if $mode == "paths" then ($hits | unique | length) else ($hits | length) end
    | if . == 0 then "absent" else tostring end
  ' "${3-docs/api/openapi.json}"
}

# The claim kind a published sentence about a header makes, and the header a kind is about. The
# two are inverses and the audit below needs both. A sentence naming a header this pair does not
# know records `unknown-header-operations`, which `registry` does not pin, so it fails the run and
# names itself rather than passing as some other claim.
kind_for_header() {
  case $1 in
    X-Kui-Principal) printf 'principal' ;;
    X-Csrf-Token) printf 'csrf' ;;
    If-Match) printf 'if-match' ;;
    *) printf 'unknown-header' ;;
  esac
}

header_for_kind() {
  case $1 in
    principal) printf 'X-Kui-Principal' ;;
    csrf) printf 'X-Csrf-Token' ;;
    if-match) printf 'If-Match' ;;
    *) printf 'no header this script knows' ;;
  esac
}

header_parameter_total=$(jq -r '
  [ .paths[] | .[] | (.parameters // [])[] | select(.in == "header") ] | length
  ' docs/api/openapi.json)

# Summed when it is claimed and not when it is built, so that a row written into the table after
# the loop above -- the cheapest way to make one header's figure whatever you like -- is inside the
# number this compares. Measured on 2026-09-10: with the sum taken at build time,
# `header_ops[X-Csrf-Token]=${header_ops[X-Kui-Principal]}` on the next line published
# `X-Csrf-Token on 56 operations` in two documents with the run green at 232 claims.
sum_header_table() {
  local header total=0
  for header in "${!header_ops[@]}"; do
    total=$(( total + header_ops[$header] ))
  done
  printf '%s' "$total"
}

# The table against the document, header by header and then in total, by an expression that is not
# the one that built it. Two of the three gates on this section read the table and one reads the
# document; a value written into the table to make one published figure true is caught here, and a
# `header_fact_from_document` taught to answer that same false figure is caught here too, because
# the two sides of this comparison come from different places.
scope merged-document "the header table"
for header in "${!header_ops[@]}"; do
  claim header-table "" \
    "${header_ops[$header]}" "$(header_fact_from_document "$header" operations)" \
    "the header table says $header is on ${header_ops[$header]} operations and\
 docs/api/openapi.json says $(header_fact_from_document "$header" operations)." \
    "${header_paths[$header]}" "$(header_fact_from_document "$header" paths)" \
    "the header table says $header is on ${header_paths[$header]} paths and docs/api/openapi.json\
 says $(header_fact_from_document "$header" paths)."
done
claim header-table "" \
  "$(sum_header_table)" "$header_parameter_total" \
  "the header table this script derived holds $(sum_header_table) header parameters and\
 docs/api/openapi.json declares $header_parameter_total; a count in the table did not come from\
 the document."

check_document_region() {
  local where=$1 text=$2 rest tok header="" kind fact complaint

  # `X-Kui-Principal` on N of its M operations. The header is read out of the sentence and the
  # fact is looked up by that name, so the claim is compared against the header it names.
  if [[ $text =~ \`([A-Za-z][A-Za-z-]*)\`\ on\ ([0-9]+)\ of\ its\ ([0-9]+)\ operations ]]; then
    header=${BASH_REMATCH[1]}
    kind=$(kind_for_header "$header")
    fact=${header_ops[$header]:-absent}
    claim "$kind-operations" "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[2]}" "$fact" \
      "$where: $header on ${BASH_REMATCH[2]} operations; the document carries it on $fact." \
      "${BASH_REMATCH[3]}" "$doc_ops" \
      "$where says the document has ${BASH_REMATCH[3]} operations; it has $doc_ops."
  fi

  # "across P of its Q paths" is the second half of that same sentence and has no header of its
  # own, so it is compared against the paths of the header the block has just named. It used to be
  # compared against a variable filled positionally from a `jq` program, which is how a figure
  # about one header could be checked against another's count and nothing could say so.
  if [[ $text =~ across\ ([0-9]+)\ of\ its\ ([0-9]+)\ paths ]]; then
    if [[ -z $header ]]; then
      fail "$where says a header is carried across ${BASH_REMATCH[1]} paths without naming the" \
           "header anywhere before it, so nothing states whose paths were counted."
    else
      kind=$(kind_for_header "$header")
      fact=${header_paths[$header]:-absent}
      claim "$kind-paths" "${BASH_REMATCH[0]}" \
        "${BASH_REMATCH[1]}" "$fact" \
        "$where: $header over ${BASH_REMATCH[1]} paths; the document carries it over $fact." \
        "${BASH_REMATCH[2]}" "$doc_paths" \
        "$where says the document has ${BASH_REMATCH[2]} paths; it has $doc_paths."
    fi
  fi

  # `X-Csrf-Token` on N operations, and `If-Match` on N operations -- one shape, one loop, one
  # lookup. `If-Match` is claimed twice in ADR-048 (once beside the principal header and once in
  # the list of what the browser view keeps), and a claim made twice is compared twice.
  rest=$text
  while [[ $rest =~ \`([A-Za-z][A-Za-z-]*)\`\ on\ ([0-9]+)\ operations ]]; do
    tok=${BASH_REMATCH[0]}
    header=${BASH_REMATCH[1]}
    kind=$(kind_for_header "$header")
    fact=${header_ops[$header]:-absent}
    complaint="$where says $header is on ${BASH_REMATCH[2]} operations; the document carries it\
 on $fact."
    claim "$kind-operations" "$tok" "${BASH_REMATCH[2]}" "$fact" "$complaint"
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

# Fixture 4, and the only one that reads the fourth column: this section's recorded facts, against
# the document, after every block has been read.
#
# The three fixtures that existed before all read the *kind* of a claim. This one reads what the
# claim was compared with. A comparison edited to compare a published figure with itself -- the
# cheapest attack left once the facts are looked up by the name the prose gives them -- records the
# false figure in the fact column, and is named here with both sides printed.
audit_document_facts() {
  local line a b c pairs pair fact expected header index complaint audited=0
  local recorded
  local -a want=()
  recorded=$(count_of merged-document)
  # Every `document-fact` this loop records is appended to the ledger it is reading. `for` expands
  # the array once, so the loop sees the section as it stood when the section closed, and the count
  # compared at the foot of this function is the one taken here.
  scope merged-document "the merged-document ledger"
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c pairs <<< "$line"
    [[ $a == merged-document ]] || continue
    audited=$(( audited + 1 ))
    case $c in
      # The residue's fact is prose and the header table's claims are already this script's own
      # table against the document by a second expression; neither is a figure a document
      # published, so there is nothing here to re-derive. Both are counted, so an audit narrowed
      # to skip lines is still visible in the comparison at the foot of this function.
      residue | header-table) continue ;;
      paths-and-schemas) want=("$doc_paths" "$doc_schemas") ;;
      openapi-version) want=("$doc_version") ;;
      *-operations)
        header=$(header_for_kind "${c%-operations}")
        want=("$(header_fact_from_document "$header" operations)" "$doc_ops")
        ;;
      *-paths)
        header=$(header_for_kind "${c%-paths}")
        want=("$(header_fact_from_document "$header" paths)" "$doc_paths")
        ;;
      *)
        fail "the merged-document ledger records a \`$c\` claim in $b and this audit has no" \
             "fact to re-derive for that kind, so what it was compared against is unknown."
        continue
        ;;
    esac
    index=0
    while IFS= read -r pair; do
      [[ -z $pair ]] && continue
      fact=${pair#*>}
      expected=${want[$index]:-}
      complaint="the \`$c\` claim in $b was compared against \`$fact\`; docs/api/openapi.json\
 says \`$expected\`. A claim of the right kind compared against the wrong fact is what three\
 fixtures reading only the kind could not see."
      claim document-fact "" "$fact" "$expected" "$complaint"
      index=$(( index + 1 ))
    done < <(printf '%s\n' "${pairs//|/$'\n'}")
  done

  # The claimed side, against the block it was read out of, by a path that does not go through
  # `claim`'s own bookkeeping. `claim` refuses a claimed figure absent from the fragment it struck
  # out; a caller that strikes the fragment itself and then passes an empty fragment escapes that
  # refusal, which was the cheapest attack left on 2026-09-11 at two lines. This is the second
  # reading: whatever the ledger says a document claimed, the document's own block has to publish.
  local region_file region_index block
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c pairs <<< "$line"
    [[ $a == merged-document ]] || continue
    [[ $c == residue ]] && continue
    # Only the scopes that are a block of a document: `the header table` and this ledger's own
    # scope are this script reading itself, and neither is a sentence anybody published.
    [[ $b == *#* ]] || continue
    region_file=${b%#*}
    region_index=${b##*#}
    # Read out of the file again rather than kept from the first pass. A copy taken while the
    # block was being read is one assignment away from being widened -- measured on 2026-09-11:
    # storing the block text with this script's own header table appended to it, one line, made
    # every figure in the table count as published and took the cheapest attack back to two lines.
    # Re-reading has no such line. Widening `regions` instead puts the extra digits into the text
    # the comparisons run over, where the residue check reports them as figures nothing claimed.
    block=$(regions merged-document "$region_file" | sed -n "${region_index}p")
    while IFS= read -r pair; do
      [[ -z $pair ]] && continue
      claim document-claim "" \
        "$(figure_is_published "$block" "${pair%%>*}")" "published" \
        "the \`$c\` claim in $b says the block publishes \`${pair%%>*}\`, and the block does not\
 contain that figure. A comparison whose claimed side was not read out of the document is a true\
 statement about nothing, beside a sentence that can say anything."
    done < <(printf '%s\n' "${pairs//|/$'\n'}")
  done

  claim document-facts "" \
    "$audited" "$recorded" \
    "this audit re-derived the facts behind $audited of the merged-document section's $recorded\
 claims; a claim it does not reach is one nothing checks the fact of."
}

for file in "$adr048" "$apireadme"; do
  check_marked_file merged-document "$file" check_document_region
done

audit_document_facts
close_section merged-document 50

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
    if [[ -f $manifest ]]; then present_manifest=$manifest; else present_manifest=missing; fi
    claim manifest "" \
      "$manifest" "$present_manifest" \
      "this script names $manifest and frontend/ does not hold it."
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
      IFS=$'\t' read -r a b c _ <<< "$line"
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

# The manifests, read a second time by an expression that is not the one above: `dep_rows` selects
# the two dependency objects by key and takes their entries; this adds the two objects together
# first. `audit_dependency_rows` compares the claimed side of every `npm-version` claim against
# this table, so a comparison rewritten to put the *document's* own cell on both sides is caught by
# a reader that never looked at the document.
declare -A manifest_pins=()
while IFS=$'\t' read -r pinned_name pinned_version; do
  [[ -z $pinned_name ]] && continue
  manifest_pins[$pinned_name]="${manifest_pins[$pinned_name]:+${manifest_pins[$pinned_name]} }\
$pinned_version"
done < <(jq -r '[(.dependencies // {}), (.devDependencies // {})] | add // {}
                | to_entries[] | "\(.key)\t\(.value)"' "${manifests[@]}" | sort -u)

# The version cell `DEPENDENCY_MATRIX.md` publishes for one package, by the column positions every
# table in that file shares: the name is the second cell and the version the third. Lifted out of
# the loop below so that a fixture can drive the shipped lookup over a row written for the
# occasion, which is the whole of why section 3's comparison was the last one in this file with no
# fixture behind it.
dependency_cell() {
  awk -F'|' -v n="$2" '
    NF >= 6 {
      f = $2; sub(/^[ \t`]+/, "", f); sub(/[ \t`]+$/, "", f)
      v = $3; sub(/^[ \t]+/, "", v); sub(/[ \t]+$/, "", v)
      if (f == n) { print v; exit }
    }' "$1"
}

# The same row, read again without awk and without awk's field splitting. `audit_dependency_rows`
# compares against this and not against `dependency_cell`, for the reason
# `header_fact_from_document` exists: a fact re-derived by the expression that produced it is one
# reading wearing two hats.
dependency_cell_again() {
  local file=$1 name=$2 line name_cell version_cell
  while IFS= read -r line; do
    [[ $line == \|* ]] || continue
    IFS='|' read -r _ name_cell version_cell _ <<< "$line"
    name_cell=${name_cell//[\` ]/}          # an npm package name carries no space
    [[ $name_cell == "$name" ]] || continue
    version_cell=${version_cell#"${version_cell%%[![:space:]]*}"}
    printf '%s' "${version_cell%"${version_cell##*[![:space:]]}"}"
    return
  done < "$file"
}

# The version *token* out of a cell that equals the pin, or `not in the cell`. A cell may name more
# than one version -- axe-core is pinned differently at the root and in two packages -- so the fact
# is the token that matched rather than the whole cell. It used to be `[[ $row == *"$version"* ]]`,
# whose one-line weakening to `[[ -n $row ]]` let DEPENDENCY_MATRIX.md record any version at all.
dependency_version_fact() {
  local matched
  matched=$(printf '%s' "$1" | grep -oE '[^ ,|`()]+' | grep -Fx -- "$2" || true)
  printf '%s' "${matched:-not in the cell}"
}

# One dependency row, compared -- and the comparison is a function rather than four lines inside a
# loop for one reason: **a fixture can drive a function.** Every refusal section 7 drives is driven
# because the input that exercises it does not exist in this repository, and a row publishing a
# version nothing pins is exactly that kind of input.
compare_dependency_row() {
  local file=$1 name=$2 pinned=$3 row
  row=$(dependency_cell "$file" "$name")
  if [[ -z $row ]]; then
    fail "$file has no row for the npm dependency \`$name\` (pinned at $pinned under frontend/)."
    return
  fi
  scope dependencies "npm:$name"
  claim npm-version "" \
    "$pinned" "$(dependency_version_fact "$row" "$pinned")" \
    "$file records \`$name\` as $row; frontend/ pins $pinned."
}

# Fixture 4's shape, applied to the one section the fixtures skipped.
#
# `audit_document_facts` re-derives both sides of every merged-document claim out of the files
# rather than out of this script's own variables. Section 3 had no such reading, and what that
# cost was measured on 2026-09-11 against the shipped file: rewriting one call site from
# `claim npm-version "" "$version" "${matched:-not in the cell}"` to `... "$version" "$version"`
# -- **one line** -- let `DEPENDENCY_MATRIX.md` publish `vite` at `9.9.9` against a
# `frontend/package.json` pinning `8.2.2`, with the run printing `283 claims checked, all true` and
# exiting 0. Every gate agreed and each was right to: the kind was `npm-version`, the registry
# pinned it, `close_section` counted 37 and the manifest roster reconciled over all twelve
# manifests. **Not one of them read the row a second time.**
#
# This does, from both ends, by paths the claim did not take:
#
#   `dependency-fact`  the fact the ledger recorded, against the row re-read without awk. The
#                      attack above records the *pinned* version as the fact, and the row it came
#                      from does not contain it.
#   `dependency-claim` the claimed side, against `manifest_pins`, which was built by a second `jq`
#                      program. The mirror-image attack -- both sides taken from the *cell* -- puts
#                      a version no manifest pins on the claimed side.
#   `dependency-audit` how many claims this reached, against how many rows the **manifests** asked
#                      for, so an audit narrowed with a `continue` is a figure that moves.
#
# **That last one used to be a tautology, and saying so is the repair.** It compared `audited`, a
# count taken by this loop, with `recorded`, a count taken by an identical predicate over the same
# array immediately above it: the two cannot differ for any input, so rewriting the call site to
# `"$recorded" "$recorded"` changed nothing observable and no fixture could ever drive it.
# `expected_npm_claims` is counted in section 3's own loop, off the manifests, before a single row
# is compared -- a second source rather than a second reading -- so the pair is now a real
# comparison: a dependency the manifests pin whose row is missing returns early from
# `compare_dependency_row`, makes no claim, and is named here as well as there.
audit_dependency_rows() {
  local line a b c pairs name claimed fact row recheck pinned_state audited=0
  local recorded=${expected_npm_claims:-0}
  # `for` expands the array once, so this loop sees the section as it stood when it began and the
  # `dependency-fact` lines it appends are not themselves audited.
  scope dependencies "the dependency ledger"
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c pairs <<< "$line"
    [[ $a == dependencies && $c == npm-version ]] || continue
    audited=$(( audited + 1 ))
    name=${b#npm:}
    claimed=${pairs%%>*}
    fact=${pairs#*>}
    row=$(dependency_cell_again "$deps" "$name")
    recheck=$(dependency_version_fact "$row" "$claimed")
    claim dependency-fact "" \
      "$fact" "$recheck" \
      "the \`npm-version\` claim for \`$name\` was compared against \`$fact\`, and re-reading\
 $deps's own row -- \`${row:-no row at all}\` -- answers \`$recheck\`. A claim of the right kind\
 compared against a fact the document does not carry is what the kind, the count, the registry and\
 the manifest roster all pass over."
    if [[ " ${manifest_pins[$name]:-} " == *" $claimed "* ]]; then
      pinned_state=pinned
    else
      pinned_state="not pinned under frontend/"
    fi
    claim dependency-claim "" \
      "pinned" "$pinned_state" \
      "the \`npm-version\` claim for \`$name\` says frontend/ pins \`$claimed\`, and no manifest\
 under frontend/ pins that. The claimed side of this comparison is the pin, so a comparison that\
 takes both of its sides out of $deps is a true statement about nothing."
  done
  claim dependency-audit "" \
    "$audited" "$recorded" \
    "this audit re-read the rows behind $audited \`npm-version\` claims and the manifests under\
 frontend/ pin $recorded dependencies that each need one; a row this audit does not reach is one\
 nothing reads $deps again for, and a dependency that made no claim at all is one $deps has no row\
 for."
}

# Counted here, off the manifests, and not off the ledger the comparisons fill. It is the
# denominator `audit_dependency_rows` compares its own coverage against, and the whole point of it
# is that it is derived before any row is read.
expected_npm_claims=0
while IFS=$'\t' read -r name version; do
  [[ -z $name ]] && continue
  [[ $name == @kui/* ]] && continue
  expected_npm_claims=$(( expected_npm_claims + 1 ))
  compare_dependency_row "$deps" "$name" "$version"
done <<< "$dep_rows"

audit_dependency_rows
reconcile_manifest_claims "closing \`dependencies\`"
close_section dependencies 88

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

# The Total line's split, and it is a function for the reason section 3's row comparison is one.
# `189 (150 from research + 39 KUI-new)` is a claim about where the rows came from, and the only
# thing in this repository that can check it is that the two parts add up to the row count -- so
# this comparison's *fact* is derived from its own claim, and the claimed-side refusal that gates
# every other figure in this section cannot see it collapse. Measured on 2026-09-11 with every
# other gate in this file finished: `claim total-split "" "$(( ... ))" "$rows"` rewritten to
# `... "$rows" "$rows"`, **one line**, let the Total line publish a split that does not add up with
# the run green. A fixture is the only reading left, so there is one.
compare_total_split() {
  local cell=$1 total=$2
  if [[ $cell =~ \(([0-9]+)\ from\ research\ \+\ ([0-9]+)\ KUI-new\) ]]; then
    claim total-split "" \
      "$(( BASH_REMATCH[1] + BASH_REMATCH[2] ))" "$total" \
      "$matrix (checked: milestones): the Total line splits the rows as ${BASH_REMATCH[1]} +\
 ${BASH_REMATCH[2]}, which is not $total."
  else
    fail "$matrix (checked: milestones): the Total line no longer says how the rows split" \
         "between research and KUI-new; that claim has gone rather than become false."
  fi
}

# **Every comparison below is handed the cell it read its figure out of.** Section 4 reads a table
# and has no residue check, so until 2026-09-11 every one of its nine claim kinds passed an empty
# `matched` and `claim`'s claimed-side refusal never ran over any of them: one line -- a claimed
# figure rewritten to the fact beside it -- let this table publish any row count with the run
# green. The same hole was measured in section 1 and in section 5 on the same day, and it is one
# hole and not three: a `claim` call with an empty `matched` is a comparison whose claimed side
# nothing has to have read out of a document. The cell is not struck out of anything here -- there
# is no `text` in this section -- and `consume` is a no-op for exactly that case.
#
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
    claim total-rows "$claimed_rows" \
      "${BASH_REMATCH[1]}" "$rows" \
      "$matrix (checked: milestones): the Total line says ${BASH_REMATCH[1]} rows; the table has\
 $rows."

    compare_total_split "$claimed_rows" "$rows"

    claim total-p0 "$claimed_p0" \
      "$claimed_p0" "$milestone_p0_total" \
      "$matrix (checked: milestones): the Total line says $claimed_p0 P0 rows; the table has\
 $milestone_p0_total."
    claim total-p1 "$claimed_p1" \
      "$claimed_p1" "$milestone_p1_total" \
      "$matrix (checked: milestones): the Total line says $claimed_p1 P1 rows; the table has\
 $milestone_p1_total."
    continue
  fi

  # "— (rejected)" names the milestone cell of the rejected rows, which is the em dash alone.
  key=${label%% *}
  milestone_claimed[$key]=1
  milestone_lines=$(( milestone_lines + 1 ))

  claim milestone-rows "$claimed_rows" \
    "$claimed_rows" "${milestone_rows[$key]:-0}" \
    "$matrix (checked: milestones): $label says $claimed_rows rows; ${milestone_rows[$key]:-0}\
 rows name that milestone."
  claim milestone-p0 "$claimed_p0" \
    "$claimed_p0" "${milestone_p0[$key]:-0}" \
    "$matrix (checked: milestones): $label says $claimed_p0 P0 rows; it has\
 ${milestone_p0[$key]:-0}."
  claim milestone-p1 "$claimed_p1" \
    "$claimed_p1" "${milestone_p1[$key]:-0}" \
    "$matrix (checked: milestones): $label says $claimed_p1 P1 rows; it has\
 ${milestone_p1[$key]:-0}."
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
  total_line_state="a Total line"
else
  total_line_state="no Total line"
fi
claim total-line "" \
  "a Total line" "$total_line_state" \
  "$matrix (checked: milestones): the table has no Total line, so its grand totals are no longer\
 claimed."

# The other direction, and it counts: a milestone the rows use with no line in the table.
for key in "${!milestone_rows[@]}"; do
  if [[ -n ${milestone_claimed[$key]+set} ]]; then
    line_state="a line for $key"
  else
    line_state="no line"
  fi
  claim milestone-line "" \
    "a line for $key" "$line_state" \
    "$matrix (checked: milestones): the table has no line for milestone \`$key\`;\
 ${milestone_rows[$key]} row(s) name it."
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
  # The claimed side is what the index links `id` to and the fact is where the decision actually
  # is, so a missing row and a row pointing at the wrong document are the same comparison rather
  # than two branches, and the pair is in the ledger either way.
  claim adr-file "${adr_row_link[$id]:-}" \
    "${adr_row_link[$id]:-no row in $decisions}" "$file" \
    "$decisions links \`$id\` to ${adr_row_link[$id]:-nothing}; the decision is at $file. A\
 missing row is the omission ADR-052 shipped with for a whole wave."
done

for id in "${!adr_row_link[@]}"; do
  scope adr-index "$id"
  if [[ -f ${adr_row_link[$id]} ]]; then
    resolved=${adr_row_link[$id]}
  else
    resolved="not a file in this repository"
  fi
  claim adr-row "" \
    "${adr_row_link[$id]:-nothing}" "$resolved" \
    "$decisions has a row for \`$id\` pointing at ${adr_row_link[$id]:-nothing}, which is not a\
 file in this repository."
done

close_section adr-index 112

# ---------------------------------------------------------------------------------------------
# 6. The OpenAPI totals three ADRs publish about documents the build generates.
# ---------------------------------------------------------------------------------------------
#
# ADR-052, ADR-053 and ADR-054 each state in their Consequences how large the document their
# service's endpoints generate is, and ADR-053 states the merged document's totals as well.
# `grep -c 'checked:'` over the three answered **0** on 2026-09-11: four documents' figures were
# compared by this script and these three were not. What that cost is measured rather than
# imagined. ADR-053 published `61 paths, 72 operations and 156 component schemas` about documents
# that had been 65, 76 and 160 since the eleventh service landed; the packet that measured the
# correction could not make it, because the file belonged to a packet that had already frozen, and
# the line stayed wrong for a whole wave. A figure inside a marker does not need an owner with a
# free hand -- it fails the build, and whoever is holding the build fixes it.
#
# **The document a claim is about is read out of the sentence that publishes it**, the way section
# 2 reads a header's name out of the prose. There is no constant in this file saying which document
# ADR-053's first figure is about, so renaming the document in the prose renames what the claim is
# compared against; and a path that is not a document in this repository answers so rather than
# being skipped. That is also what makes `openapi_fact` hard to point elsewhere: the three blocks
# name three documents with three different answers, so a body rewritten to read any one of them
# reddens the other two in the same run.

openapi_fact() {
  local file=$1 mode=$2
  if [[ ! -f $file ]]; then printf 'not a document in this repository'; return; fi
  jq -r --arg mode "$mode" '
    if $mode == "paths" then (.paths | length)
    elif $mode == "operations" then ([.paths[] | keys[]] | length)
    else (.components.schemas | length) end | tostring' "$file"
}

# One sentence shape, and the ADRs are written to it: ``<document>` is **N paths, M operations and
# K component schemas**`. Three figures in one `claim` because they are one sentence about one
# document -- a block that publishes two of the three leaves the third unmatched, the sentence
# unstruck, and the residue check reports every figure in it.
# Held in a variable rather than written inline so that the sentence shape stays inside 100
# columns and stays readable: a regular expression that has to be read in two halves is one a
# document can drift away from without anybody noticing.
openapi_totals_shape='`([A-Za-z0-9_./-]+\.json)` is \*\*([0-9]+) paths, ([0-9]+) operations'
openapi_totals_shape+=' and ([0-9]+) component schemas\*\*'

# How many of one document's paths begin with one prefix. The counterpart to `openapi_fact` for the
# one figure ADR-054 publishes about a document that is not its own service's.
path_prefix_fact() {
  if [[ ! -f $1 ]]; then printf 'not a document in this repository'; return; fi
  jq -r --arg prefix "$2" \
    '[.paths | keys[] | select(startswith($prefix) or (index($prefix) != null))] | length
     | tostring' "$1"
}

prefix_paths_shape='\*\*([0-9]+)\*\* of `([A-Za-z0-9_./-]+\.json)`'
prefix_paths_shape+="'s paths are under \`([^\`]+)\`"

check_openapi_totals_region() {
  local where=$1 text=$2 rest tok file present paths operations schemas
  rest=$text
  while [[ $rest =~ $openapi_totals_shape ]]; do
    tok=${BASH_REMATCH[0]}
    file=${BASH_REMATCH[1]}
    if [[ -f $file ]]; then present=$file; else present="not a document in this repository"; fi
    claim openapi-document "" \
      "$file" "$present" \
      "$where publishes totals about \`$file\`, which is $present. A figure about a document\
 nothing generates is a figure nothing can move when the contract does."
    paths=$(openapi_fact "$file" paths)
    operations=$(openapi_fact "$file" operations)
    schemas=$(openapi_fact "$file" schemas)
    claim openapi-totals "$tok" \
      "${BASH_REMATCH[2]}" "$paths" \
      "$where says $file is ${BASH_REMATCH[2]} paths; it is $paths." \
      "${BASH_REMATCH[3]}" "$operations" \
      "$where says $file is ${BASH_REMATCH[3]} operations; it is $operations." \
      "${BASH_REMATCH[4]}" "$schemas" \
      "$where says $file is ${BASH_REMATCH[4]} component schemas; it is $schemas."
    rest=${rest#*"$tok"}
  done

  # `**N** of `<document>`'s paths are under `<prefix>``. ADR-054 published this figure as the word
  # `**Four**` until 2026-09-11, where neither a comparison nor the residue could see it; a figure
  # about the merged document that sits in a service's own ADR is exactly the shape that goes stale
  # when a twelfth service moves the merged totals. The document and the prefix are both read out of
  # the sentence, for the reason `check_document_region` reads a header's name out of the prose:
  # there is no constant in this file saying which document or which prefix a claim is about.
  rest=$text
  while [[ $rest =~ $prefix_paths_shape ]]; do
    tok=${BASH_REMATCH[0]}
    file=${BASH_REMATCH[2]}
    claim merged-path-prefix "$tok" \
      "${BASH_REMATCH[1]}" "$(path_prefix_fact "$file" "${BASH_REMATCH[3]}")" \
      "$where says ${BASH_REMATCH[1]} of $file's paths are under ${BASH_REMATCH[3]}; it has\
 $(path_prefix_fact "$file" "${BASH_REMATCH[3]}")."
    rest=${rest#*"$tok"}
  done

  report_unclaimed_figures "$where" "$text"
}

# Section 6's roster is **read off the ADR tree**, and was a hard-coded three until 2026-09-11.
# Section 5 globs `docs/adr/ADR-*.md` and section 6 named `$adr052 $adr053 $adr054`, so a marker
# added to any other ADR was decoration: appending a `<!-- checked: openapi-totals -->` block to
# `ADR-055` publishing `999 paths` left the run green, because no loop ever read that file. The
# twelfth service's ADR would have been outside this gate on the day it was written. Globbing makes
# the marker itself the roster, and the three claims below are the other direction: an ADR that
# published totals and has had its marker removed is a claim that has gone rather than become false,
# which is the failure section 7 exists after.
openapi_total_adrs=()
while IFS= read -r file; do openapi_total_adrs+=("$file"); done < <(
  grep -l '<!-- checked: openapi-totals' docs/adr/ADR-*.md | sort)

for file in ${openapi_total_adrs+"${openapi_total_adrs[@]}"}; do
  check_marked_file openapi-totals "$file" check_openapi_totals_region
done

for file in "$adr052" "$adr053" "$adr054"; do
  scope openapi-totals "$file"
  if printf '%s\n' ${openapi_total_adrs+"${openapi_total_adrs[@]}"} | grep -qxF -- "$file"; then
    marker_state="carries an openapi-totals marker"
  else
    marker_state="carries no openapi-totals marker"
  fi
  claim openapi-roster "" \
    "carries an openapi-totals marker" "$marker_state" \
    "$file published its service document's totals inside a marker and no longer carries one; the\
 figures it publishes are unguarded again, which is the state all three of these ADRs were in until\
 2026-09-11."
done

close_section openapi-totals 15

# ---------------------------------------------------------------------------------------------
# 7. The fixtures: refusals this file makes that nothing in it could drive.
# ---------------------------------------------------------------------------------------------
#
# Sections 0 to 6 check documents. This one checks the refusals, and it exists because on
# 2026-09-11 six of them were mutated one at a time against the green tree and the run stayed at
# `all true`, exit 0:
#
#   `header_fact_from_document`'s body -> a read of `header_ops`       green -- and the audit and
#                                                                     the header table become one
#                                                                     expression checking itself
#   `claim`'s `if (( $# == 0 || $# % 3 != 0 ))` -> `if false`          green
#   the empty-block refusal, `if [[ -z ${text// /} ]]` -> `if false`   green, in both loops
#   `reconcile_region`'s no-`claims:`-list refusal                     green
#   `close_section`'s `==` -> `<=`, one character                      green
#   `header_for_kind`'s `csrf)` -> `X-Kui-Principal`                   green, and with one edit at
#                                                                     the claim site it published
#                                                                     `X-Csrf-Token on 59
#                                                                     operations` in two documents
#
# Every one of them is a refusal, and a refusal is only ever exercised by input this repository
# does not contain: no document has ever carried an empty marked block, no marker has ever lacked
# its `claims:` list, no comparison has ever been written without a pair, and no section has ever
# closed at the wrong count -- because the moment one did, a person repaired the document rather
# than leaving it there for the script to be tested against. A guard whose failing case never
# arrives is a guard nothing distinguishes from `true`. So the failing cases are built here.
#
# Each fixture drives the **shipped** function -- not a copy of its logic -- over input written for
# the occasion, and asserts the number of disagreements it reports. Asserting the number rather
# than merely "it failed" is what catches the opposite mutation: a guard rewritten to refuse
# everything fails the agreeing case in the same claim.

fixtures=$(mktemp -d)
trap 'rm -rf "$fixtures"' EXIT

# Runs one of this file's guards over a fixture and answers how many disagreements it reported.
# A command substitution, so the subshell keeps a fixture's complaints off the terminal and a
# fixture's claims out of the ledger, and `failures` is shadowed besides: a guard firing here is
# the fixture passing, not the run failing.
drive() {
  local failures=0
  "$@" >/dev/null 2>&1 || true
  printf '%s' "$failures"
}

# The same drive, reading what the guard *said* rather than how many times it said something. Two
# refusals in `reconcile_region` answer the same count for the same fixture -- a marker with no
# `claims:` list fails either as "names no claims: list" or, once that branch is gone, as "declares
# [] and compared [residue]" -- so a count alone cannot tell the refusal from its absence. This
# reads the sentence.
drive_says() {
  local pattern=$1
  shift
  local failures=0 output
  output=$("$@" 2>&1 >/dev/null || true)
  if [[ $output == *"$pattern"* ]]; then printf 'reported'; else printf 'not reported'; fi
}

# Fixture 1: `header_fact_from_document` reads the document, and not the table it exists to be
# independent of. The fixture document declares `X-Kui-Principal` on exactly one operation of
# exactly one path; the real document carries it on 59 over 48, and so does `header_ops`. A body
# rewritten to read the table therefore answers 59 here, and a body rewritten to read
# `docs/api/openapi.json` regardless of its argument answers 59 too. Both are named below.
verify_fact_independence() {
  local doc=$fixtures/one-operation.json ops paths absent
  cat > "$doc" <<'JSON'
{
  "openapi": "3.1.0",
  "paths": {
    "/fixture": {
      "get": {
        "parameters": [ { "in": "header", "name": "X-Kui-Principal", "required": true } ]
      }
    }
  },
  "components": { "schemas": {} }
}
JSON
  ops=$(header_fact_from_document X-Kui-Principal operations "$doc")
  paths=$(header_fact_from_document X-Kui-Principal paths "$doc")
  absent=$(header_fact_from_document X-Csrf-Token operations "$doc")
  scope guard-fixtures "header_fact_from_document"
  claim fact-independence "" \
    "$ops" "1" \
    "header_fact_from_document answers $ops operations for a fixture document with one; it is\
 reading something other than the document it was handed, which makes the header table and the\
 audit two readings of one place." \
    "$paths" "1" \
    "header_fact_from_document answers $paths paths for a fixture document with one; the audit\
 and the header table are no longer independent of each other." \
    "$absent" "absent" \
    "header_fact_from_document answers $absent for a header the fixture document does not\
 declare; an absent header must be equal to no figure, or a claim about one can be answered by\
 another's count."
}

# Fixture 2: `claim` refuses to record and consume without comparing. This is the refusal that
# makes every other comparison in the file safe to write as one call, so it is driven in all three
# of its states: no group at all, a group that is not a whole triple, and a well-formed agreeing
# pair that must be allowed through.
verify_claim_refuses_noncomparison() {
  local none partial agreeing
  none=$(drive claim fixture-kind "")
  partial=$(drive claim fixture-kind "" 1 2)
  agreeing=$(drive claim fixture-kind "" 1 1 "a fixture pair that agrees")
  scope guard-fixtures "claim"
  claim claim-refuses-noncomparison "" \
    "$none" "1" \
    "\`claim\` called with no group at all reported $none disagreement(s); recording a claim and\
 striking a figure out of a block without comparing anything is the defect this refusal exists\
 for." \
    "$partial" "1" \
    "\`claim\` called with two arguments where a group is three reported $partial\
 disagreement(s); a truncated group would otherwise compare nothing and count as a claim." \
    "$agreeing" "0" \
    "\`claim\` refused a well-formed agreeing pair, reporting $agreeing disagreement(s); a\
 refusal that refuses everything reports nothing about the run."
}

# Fixture 3: the empty marked block, in both of the kinds `check_marked_file` is called with. The
# comment this refusal used to carry -- "No file has carried an empty marked region yet" -- was the
# whole reason `if false` was green in both loops: the branch had no input in this repository.
# It has one now.
verify_marked_block_refusals() {
  local rows_block=$fixtures/empty-rows.md doc_block=$fixtures/empty-merged.md rows_seen doc_seen
  printf '%s\n' '<!-- checked: rows -- claims: residue -->' '' '<!-- /checked -->' > "$rows_block"
  printf '%s\n' '<!-- checked: merged-document -- claims: residue -->' '' '<!-- /checked -->' \
    > "$doc_block"
  rows_seen=$(drive check_marked_file rows "$rows_block" check_rows_region)
  doc_seen=$(drive check_marked_file merged-document "$doc_block" check_document_region)
  scope guard-fixtures "the empty marked block"
  claim empty-block-refused "" \
    "$rows_seen" "1" \
    "a \`checked: rows\` block that publishes nothing drew $rows_seen disagreement(s); a marked\
 block that yields no assertion is a failure, not a pass, and a block emptied of its prose is how\
 a paragraph of figures stops being checked without a marker moving." \
    "$doc_seen" "1" \
    "a \`checked: merged-document\` block that publishes nothing drew $doc_seen disagreement(s);\
 the same refusal has to hold for every kind \`check_marked_file\` is called with."
}

# Fixture 4: a marker with no `claims:` list, and one with a list, through the same function. A
# block that does not say what it expects checked cannot notice a check that stopped happening,
# which is fixture 1 of the four this file reconciles against going quiet.
verify_marker_list_refusal() {
  local bare=$fixtures/no-claims.md declared=$fixtures/with-claims.md
  local bare_seen bare_said declared_seen
  local prose='This fixture block publishes no figure at all.'
  printf '%s\n' '<!-- checked: rows -->' "$prose" '<!-- /checked -->' > "$bare"
  printf '%s\n' '<!-- checked: rows -- claims: residue -->' "$prose" '<!-- /checked -->' \
    > "$declared"
  bare_seen=$(drive check_marked_file rows "$bare" check_rows_region)
  bare_said=$(drive_says 'marker names no `claims:` list' \
                check_marked_file rows "$bare" check_rows_region)
  declared_seen=$(drive check_marked_file rows "$declared" check_rows_region)
  scope guard-fixtures "the marker's claims: list"
  claim marker-list-refused "" \
    "$bare_said" "reported" \
    "a marked block whose marker names no \`claims:\` list was $bare_said as one. Deleting this\
 refusal leaves the block failing for a different reason with the same count -- [] against\
 [residue] -- so the count cannot tell the refusal from its absence and the sentence has to." \
    "$bare_seen" "1" \
    "a marked block whose marker names no \`claims:\` list drew $bare_seen disagreement(s); the\
 list is the document's own statement of what it expects compared inside it, and without one a\
 comparison that stopped comparing is invisible from the document's side." \
    "$declared_seen" "0" \
    "a marked block whose marker declares exactly the claims the run made drew $declared_seen\
 disagreement(s); this refusal must pass the case it is written to allow."
}

# Fixture 5: `close_section`'s count assertion, driven in all three directions at once. Two claims
# are made and the section is closed claiming two, three and one: `==` answers 0, 1, 1, while the
# one-character weakening to `<=` answers 0, 0, 1 and `>=` answers 0, 1, 0. A single figure cannot
# tell those apart, which is why there are three.
close_section_fixture() {
  local made=$1 published=$2 index
  scope guard-fixtures-count "the close_section fixture"
  for (( index = 0; index < made; index++ )); do
    claim fixture-claim "" "1" "1" "a fixture claim that agrees"
  done
  close_section guard-fixtures-count "$published"
}

verify_count_assertion() {
  local exact over under
  exact=$(drive close_section_fixture 2 2)
  over=$(drive close_section_fixture 2 3)
  under=$(drive close_section_fixture 2 1)
  scope guard-fixtures "close_section"
  claim count-assertion "" \
    "$exact" "0" \
    "a section that compared two claims and published two drew $exact disagreement(s);\
 \`close_section\` is refusing the case it exists to allow." \
    "$over" "1" \
    "a section that compared two claims and published three drew $over disagreement(s); a\
 section count weakened from \`==\` to \`<=\` accepts a comparison that has been deleted, which is\
 the mutation that made every number in this file advisory once before." \
    "$under" "1" \
    "a section that compared two claims and published one drew $under disagreement(s); a count\
 weakened to \`>=\` accepts a comparison added and never declared anywhere."
}

# Fixture 6: `kind_for_header` and `header_for_kind` are inverses, and this run proves it rather
# than assuming it. They are what makes a claim about a header be compared against *that* header:
# the prose names the header, `kind_for_header` turns it into the claim kind, and the audit turns
# the kind back into a header to re-derive the fact. Editing `header_for_kind` alone -- `csrf)` ->
# `X-Kui-Principal` -- made the audit re-derive the principal header's count for a claim about the
# CSRF header, and with one edit at the claim site two documents published `X-Csrf-Token on 59
# operations` with the run green. Editing both consistently instead renames the claim kind, which
# `reconcile_registry` and both markers report -- measured here on 2026-09-11 by swapping `csrf` and
# `principal` in both functions at once: **4 disagreements**, two from the markers and two from the
# registry, in both of its call sites. This fixture closes the half that was not covered.
verify_header_kind_inverse() {
  local kind header round_trip
  scope guard-fixtures "the header/kind pair"
  for kind in principal csrf if-match; do
    header=$(header_for_kind "$kind")
    round_trip=$(kind_for_header "$header")
    claim header-kind-inverse "" \
      "$round_trip" "$kind" \
      "\`header_for_kind $kind\` answers \`$header\` and \`kind_for_header $header\` answers\
 \`$round_trip\`; the two are no longer inverses, so the fact the audit re-derives for a\
 \`$kind\` claim is not about the header the published sentence names."
  done
}

# Fixture 7: `claim` refuses a claimed figure that is not in the text it struck out. This is the
# refusal that closes the one-line attack recorded above `claim`'s loop, and it has the same
# problem every other refusal in this file has: no call site in this repository has ever violated
# it, so nothing distinguishes it from `true`. Both states are driven -- a figure that is in the
# struck text, and one that is not.
verify_claimed_side_is_read() {
  local read_from_text invented
  # `claim` strikes the matched fragment out of the block it is reading, and the block is the
  # caller's `text`. A fixture that hands `claim` a fragment has to hand it a block to strike it
  # out of, so the drive below is a faithful one: shadowed here, restored by the next assignment.
  local text='`X-Csrf-Token` on 26 operations, `If-Match` on 2 operations'
  read_from_text=$(drive claim fixture-kind '`X-Csrf-Token` on 26 operations' \
                     26 26 'a fixture pair that agrees')
  text='`X-Csrf-Token` on 59 operations, `If-Match` on 2 operations'
  invented=$(drive claim fixture-kind '`X-Csrf-Token` on 59 operations' \
               26 26 'a fixture pair that agrees')
  scope guard-fixtures "the claimed side"
  claim claimed-side-read "" \
    "$read_from_text" "0" \
    "a claim whose claimed figure is the one the struck text publishes drew $read_from_text\
 disagreement(s); this refusal must pass the case every real call site is." \
    "$invented" "1" \
    "a claim that struck out a sentence publishing 59 and compared 26 with 26 drew $invented\
 disagreement(s); that is the one-line attack this refusal exists for -- a comparison of the\
 document's own fact with itself, beside a sentence that says something else."
}

verify_fact_independence
verify_claim_refuses_noncomparison
verify_marked_block_refusals
verify_marker_list_refusal
verify_count_assertion
# Fixture 8: `figure_is_published` reads a whole figure and not a digit inside another. It is the
# comparison the claimed-side audit is made of, and its "not published" answer is, like every other
# refusal in this section, one no document in this repository has ever produced.
verify_figure_is_published() {
  local block='`X-Csrf-Token` on 26 operations, 126 schemas, about 39% delivered'
  scope guard-fixtures "figure_is_published"
  claim figure-published "" \
    "$(figure_is_published "$block" 26)" "published" \
    "a figure the block publishes was read as absent; the claimed-side audit would then report\
 every true claim as unread." \
    "$(figure_is_published "$block" 59)" "not published" \
    "a figure the block does not publish was read as present; the claimed-side audit would then\
 pass a comparison whose claimed side came from this script rather than from the document." \
    "$(figure_is_published "$block" 12)" "not published" \
    "\`12\` was found inside \`126\`; a figure has to be matched whole or a claim can be\
 satisfied by digits belonging to another sentence." \
    "$(figure_is_published "$block" 39)" "published" \
    "a percentage the block publishes was read as absent because of the \`%\` beside it."
}

# Fixture 9: the dependency row, which is the one comparison in this file that had no fixture
# behind it -- and the hole that left is not a hypothesis. Measured on 2026-09-11 against the
# shipped script: `claim npm-version "" "$version" "${matched:-not in the cell}"` rewritten to
# `... "$version" "$version"`, one line, and `DEPENDENCY_MATRIX.md` publishing `vite` at `9.9.9`
# against a `frontend/package.json` pinning `8.2.2` left the run printing `283 claims checked, all
# true` and exiting 0. The eight fixtures above all drive the marked-document machinery; section 3
# has no markers, so not one of them reached it.
#
# The fixture drives the **shipped** `compare_dependency_row` over a table written for the
# occasion, in all three of its states: a row that agrees, a row that publishes a version nothing
# pins, and a package with no row at all. Asserting the count in each state is what catches the
# opposite mutation too -- a comparison rewritten to refuse everything fails the agreeing case in
# the same claim.
verify_dependency_row_comparison() {
  local table=$fixtures/dependency-matrix.md agreeing disagreeing absent
  cat > "$table" <<'MD'
| Package | Version | Scope | Where | ADR |
| --- | --- | --- | --- | --- |
| fixture-pinned | 8.2.2 | npm (dev) | workspace root | ADR-048 |
| fixture-stale | 9.9.9 | npm (dev) | workspace root | ADR-048 |
MD
  agreeing=$(drive compare_dependency_row "$table" fixture-pinned 8.2.2)
  disagreeing=$(drive compare_dependency_row "$table" fixture-stale 8.2.2)
  absent=$(drive compare_dependency_row "$table" fixture-unlisted 8.2.2)
  scope guard-fixtures "the dependency row"
  claim dependency-row-compared "" \
    "$agreeing" "0" \
    "a dependency row naming the version the manifest pins drew $agreeing disagreement(s); this\
 comparison must pass the case every true row in DEPENDENCY_MATRIX.md is." \
    "$disagreeing" "1" \
    "a dependency row publishing 9.9.9 against a pin of 8.2.2 drew $disagreeing disagreement(s);\
 that is the one-line attack this fixture exists for -- a comparison of the pin with itself,\
 beside a row that can then record any version at all." \
    "$absent" "1" \
    "a package with no row in the table at all drew $absent disagreement(s); \"adding a\
 dependency requires a row here\" is the half of DEPENDENCY_MATRIX.md's own preamble this section\
 was written to enforce."
}

# Fixture 10: the residue, which is the one gate in this file that deliberately does not route
# through the ledger -- and was therefore the last one nothing drove. Measured on 2026-09-11
# against this file with every other fixture in place: adding `s/[0-9]+//g` to the list of terms
# `report_unclaimed_figures` strikes out before it looks, **one line**, let the checked paragraph
# in `docs/FEATURE_MATRIX.md` publish `KUI draws 999 screens` -- a figure no comparison reads --
# with the run printing `346 claims checked, all true` and exiting 0. The residue still recorded
# its claim, so the kind, the marker, the count and the registry all stood.
#
# Three states, because the strike-out list has to keep doing its job as well as stopping: a block
# whose every figure was consumed, a block with one that was not, and a block whose only digits are
# the date and the `ADR-nnn` reference that are not figures about the thing being counted.
verify_residue_reports_unclaimed() {
  local consumed unclaimed exempt spelled prose
  consumed=$(drive report_unclaimed_figures "a fixture block" "every figure here was struck out")
  unclaimed=$(drive report_unclaimed_figures "a fixture block" "and 999 screens nothing reads")
  exempt=$(drive report_unclaimed_figures "a fixture block" \
             "written 2026-09-11 under ADR-048 in wave-8")
  spelled=$(drive report_unclaimed_figures "a fixture block" "and **Four** is a different figure")
  prose=$(drive report_unclaimed_figures "a fixture block" "one operation per path, three probes")
  scope guard-fixtures "the residue"
  claim residue-reports-unclaimed "" \
    "$consumed" "0" \
    "a block every comparison had consumed drew $consumed disagreement(s); the residue must pass\
 the case it is written to allow, or every true paragraph in this repository fails." \
    "$unclaimed" "1" \
    "a block publishing a figure no comparison read drew $unclaimed disagreement(s); that is the\
 whole of what the residue is for, and widening the list of terms it strikes out first is one\
 line -- after which a marked block can publish anything with the count and the marker standing." \
    "$exempt" "0" \
    "a block whose only digits are a date, an ADR reference and a wave number drew $exempt\
 disagreement(s); those are not figures about the thing being counted and striking them out is\
 why the residue can be strict about everything else." \
    "$spelled" "1" \
    "a block publishing \`**Four**\` drew $spelled disagreement(s); that is the shape ADR-054\
 carried inside a marker until 2026-09-11, where rewriting it to \`**Nine**\` moved no comparison\
 and no residue, because both read digits only." \
    "$prose" "0" \
    "a block whose number words are ordinary prose drew $prose disagreement(s); \"one operation per\
 path\" explains a figure rather than publishing one, and a residue that refused it would put every\
 sentence in these ADRs outside the markers. **This is the published limit of the rule**: a figure\
 spelled as a word and not emphasised is still invisible here."
}

verify_header_kind_inverse
verify_claimed_side_is_read
verify_figure_is_published
# Fixture 11: the Total line's split, driven in all three of its states. The comparison behind it
# is the one figure in this file whose fact comes from its own claim, so no second reading of the
# document can catch it going quiet -- which leaves exactly this.
verify_total_split() {
  local adds_up short gone
  adds_up=$(drive compare_total_split "189 (150 from research + 39 KUI-new)" 189)
  short=$(drive compare_total_split "189 (100 from research + 39 KUI-new)" 189)
  gone=$(drive compare_total_split "189 rows" 189)
  scope guard-fixtures "the Total line's split"
  claim total-split-compared "" \
    "$adds_up" "0" \
    "a Total line whose split adds up to the row count drew $adds_up disagreement(s); this\
 comparison must pass the case the table is." \
    "$short" "1" \
    "a Total line splitting 189 rows as 100 + 39 drew $short disagreement(s); that is the\
 one-line collapse this fixture exists for, and the claimed-side refusal cannot see it because\
 the sum being compared is the claim's own arithmetic rather than a figure read off the page." \
    "$gone" "1" \
    "a Total line that no longer says how the rows split drew $gone disagreement(s); a claim that\
 has gone rather than become false is the failure this whole section was written after."
}

# Fixture 12: the branch that routes a *numeric* claimed figure to the whole-token reader. Fixture 8
# drives `figure_is_published` directly and never through `claim`, so on 2026-09-11
# `if [[ ${groups[$at]} =~ ^[0-9]+(\.[0-9]+)*%?$ ]]` -> `if false` left the run green: every numeric
# claim fell back to the substring test, and `70` was then "published" by a paragraph saying
# `700 \`COMPLETE\``. That mutation plus one self-compare at the `state-total` call site was a
# measured two-line attack on the file this whole script exists for. It drives the shipped `claim`.
verify_claim_reads_whole_figure() {
  local inside_another agrees
  # `claim` strikes its matched fragment out of the caller's `text`, so a faithful drive gives it
  # one; shadowed here and restored by the next assignment, as fixture 7 does.
  local text='`X-Csrf-Token` on 18 operations'
  inside_another=$(drive claim fixture-kind '`X-Csrf-Token` on 18 operations' \
                     8 8 'a fixture pair that agrees')
  text='`X-Csrf-Token` on 8 operations'
  agrees=$(drive claim fixture-kind '`X-Csrf-Token` on 8 operations' \
             8 8 'a fixture pair that agrees')
  scope guard-fixtures "the whole-figure branch of claim"
  claim claim-reads-whole-figure "" \
    "$inside_another" "1" \
    "a claim of \`8\` against a block publishing \`18\` drew $inside_another disagreement(s); the\
 digits of one figure satisfying another's claim is how a paragraph publishing \`700\` can be\
 compared against a table holding \`70\` with every other gate in this file standing." \
    "$agrees" "0" \
    "a claim of \`8\` against a block publishing \`8\` drew $agrees disagreement(s); the\
 whole-token\
 branch must pass the case every real numeric call site is."
}

# Fixture 13: `dependency_cell_again` reads the row without awk, and is the reader
# `audit_dependency_rows` compares against for the reason `header_fact_from_document` exists. On
# 2026-09-11 its body could be replaced by a call to `dependency_cell` -- the very awk it exists to
# be independent of -- with the run green. A four-cell row discriminates: `dependency_cell` requires
# `NF >= 6` and answers nothing for it, and the reader that is genuinely separate answers the cell.
verify_dependency_reader_independence() {
  local table=$fixtures/four-cell-row.md by_awk without_awk
  printf '%s\n' '| Package | Version |' '| --- | --- |' '| fixture-narrow | 8.2.2 |' > "$table"
  by_awk=$(dependency_cell "$table" fixture-narrow)
  without_awk=$(dependency_cell_again "$table" fixture-narrow)
  scope guard-fixtures "dependency_cell_again"
  claim dependency-reader-independence "" \
    "$without_awk" "8.2.2" \
    "\`dependency_cell_again\` answered \`${without_awk:-nothing}\` for a four-cell row; it is\
 reading through the awk it exists to be independent of, which makes the dependency comparison and\
 its audit one reading wearing two hats." \
    "${by_awk:-nothing}" "nothing" \
    "\`dependency_cell\` answered \`$by_awk\` for a four-cell row it should not match at all; the\
 two readers are no longer distinguishable and this fixture can no longer tell them apart."
}

# Fixtures 14 and 15: the dependency audit's two refusals and its own coverage, driven over a ledger
# and a matrix written for the occasion. Three of wave 9's ten filed findings are here, and all
# three are the same shape -- a gate inside the auditor no input in this repository can make fire:
#   `recheck=$(dependency_version_fact "$row" "$claimed")` -> `recheck=$fact`   (the second reader
#      compared with itself, which is the precise defect the auditor was written against)
#   the whole `manifest_pins` test -> `pinned_state=pinned`                     (the mirror-image
#      collapse: both sides of the comparison taken out of DEPENDENCY_MATRIX.md)
#   `claim dependency-audit "" "$audited" "$recorded"` -> `"$recorded" "$recorded"`
# The third of those is repaired rather than fixtured: see `audit_dependency_rows`, whose
# denominator now comes off the manifests instead of off the array it is reading.
dependency_audit_fixture_table() {
  local table=$fixtures/audit-matrix.md
  cat > "$table" <<'MD'
| Package | Version | Scope | Where | ADR |
| --- | --- | --- | --- | --- |
| fixture-stale | 9.9.9 | npm (dev) | workspace root | ADR-048 |
| fixture-true | 8.2.2 | npm (dev) | workspace root | ADR-048 |
MD
  printf '%s' "$table"
}

#
# **The fixture ledger is set inside the drive's own subshell and never as a local of the fixture
# function.** `record` appends to `ledger`, so a `local -a ledger` around these drives takes every
# claim the fixture itself makes down with it when the function returns -- which is a fixture that
# silently checks nothing, in the section written to stop exactly that.
verify_dependency_audit_refusals() {
  local deps stale_fact unpinned_claim agreeing
  deps=$(dependency_audit_fixture_table)
  local -A manifest_pins=([fixture-stale]="8.2.2" [fixture-true]="8.2.2")
  local expected_npm_claims=1

  # The fact recorded is the pin, and the row does not carry it: `dependency-fact` must fire.
  stale_fact=$(
    ledger=("dependencies"$'\t'"npm:fixture-stale"$'\t'"npm-version"$'\t'"8.2.2>8.2.2")
    drive audit_dependency_rows)
  # Both sides taken out of the cell: nothing under frontend/ pins 9.9.9, so `dependency-claim` must
  # fire while `dependency-fact` agrees.
  unpinned_claim=$(
    ledger=("dependencies"$'\t'"npm:fixture-stale"$'\t'"npm-version"$'\t'"9.9.9>9.9.9")
    drive audit_dependency_rows)
  agreeing=$(
    ledger=("dependencies"$'\t'"npm:fixture-true"$'\t'"npm-version"$'\t'"8.2.2>8.2.2")
    drive audit_dependency_rows)

  scope guard-fixtures "the dependency audit's refusals"
  claim dependency-audit-refuses "" \
    "$stale_fact" "1" \
    "an \`npm-version\` claim recording the pin as the fact, over a row that carries a different\
 version, drew $stale_fact disagreement(s); re-reading the row is the only thing that sees a\
 comparison of the pin with itself." \
    "$unpinned_claim" "1" \
    "an \`npm-version\` claim whose claimed version no manifest pins drew $unpinned_claim\
 disagreement(s); the claimed side of that comparison is the pin, so a comparison taking both of\
 its sides out of the matrix is a true statement about nothing." \
    "$agreeing" "0" \
    "an \`npm-version\` claim whose row and pin agree drew $agreeing disagreement(s); both refusals\
 must pass the case every true row in DEPENDENCY_MATRIX.md is."
}

verify_dependency_audit_coverage() {
  local deps short exact
  local one_row="dependencies"$'\t'"npm:fixture-true"$'\t'"npm-version"$'\t'"8.2.2>8.2.2"
  deps=$(dependency_audit_fixture_table)
  local -A manifest_pins=([fixture-true]="8.2.2")
  local expected_npm_claims=2
  short=$(ledger=("$one_row"); drive audit_dependency_rows)
  expected_npm_claims=1
  exact=$(ledger=("$one_row"); drive audit_dependency_rows)
  scope guard-fixtures "the dependency audit's coverage"
  claim dependency-audit-coverage "" \
    "$short" "1" \
    "an audit that re-read one row where the manifests pin two dependencies drew $short\
 disagreement(s); a dependency whose row is missing makes no claim at all, and a narrowed audit\
 reaches fewer, and neither is visible from inside the loop that does the narrowing." \
    "$exact" "0" \
    "an audit that re-read every row the manifests asked for drew $exact disagreement(s); this\
 comparison must pass the case a true run is."
}

# Fixture 16: `openapi_fact` reads the document it is handed, and answers a sentence rather than a
# figure for a path that is not in this repository. Both halves were green under mutation on
# 2026-09-11: `printf 'not a document in this repository'` -> `printf '0'` let an ADR publish totals
# about a document nothing generates and be compared against `0/0/0`. The fixture document's answers
# -- 1, 1 and 0 -- are held by no other reading in this run, so a body rewritten to read
# `docs/api/openapi.json` regardless of its argument answers 65 here and is named.
verify_openapi_fact_independence() {
  local doc=$fixtures/one-operation.json paths operations schemas missing
  paths=$(openapi_fact "$doc" paths)
  operations=$(openapi_fact "$doc" operations)
  schemas=$(openapi_fact "$doc" schemas)
  missing=$(openapi_fact "$fixtures/no-such-document.json" paths)
  scope guard-fixtures "openapi_fact"
  claim openapi-fact-independence "" \
    "$paths" "1" \
    "\`openapi_fact\` answered $paths paths for a fixture document with one; it is reading a\
 document other than the one it was handed." \
    "$operations" "1" \
    "\`openapi_fact\` answered $operations operations for a fixture document with one." \
    "$schemas" "0" \
    "\`openapi_fact\` answered $schemas component schemas for a fixture document with none." \
    "$missing" "not a document in this repository" \
    "\`openapi_fact\` answered \`$missing\` for a path that is not in this repository; a figure\
 about a document nothing generates has to be refused rather than compared against a zero."
}

verify_dependency_row_comparison
verify_residue_reports_unclaimed
verify_total_split
verify_claim_reads_whole_figure
verify_dependency_reader_independence
verify_dependency_audit_refusals
verify_dependency_audit_coverage
verify_openapi_fact_independence

# THIS SECTION DOES NOT CLOSE HERE ANY MORE. Three of its fixtures drive functions declared in section
# 8, and bash defines a function when the parser reaches its line: called from here they are
# `command not found`. So the three live at the foot of section 8, under a banner that says they are
# section 6's, and `close_section guard-fixtures` moved down with them. Nothing else about the section
# changed -- a fixture's claims are recorded against `guard-fixtures` by `scope`, not by where in the
# file it sits, and `count_of` reads the ledger.

# ---------------------------------------------------------------------------------------------
# 8. The capability claims: sentences naming services and packages, against the tree itself.
# ---------------------------------------------------------------------------------------------
#
# Sections 0 to 7 compare **figures**. This one compares a **sentence**, and it exists because on
# 2026-09-11 every one of them was green over a `README.md` whose status banner said *milestones 0
# to 5* and whose *What is not built* list said **"No Kafka Connect, no ksqlDB"** -- against a tree
# that ships both services, draws both screens, routes both through the gateway and has seven
# passing browser cases over them. The banner was four milestones stale, `./scripts/feature-matrix-\
# check.sh` printed `348 claims checked, all true`, and it was right to: **the false sentence sat
# outside every marker, and no gate in this repository read prose at all.** That is the failure
# `docs/ROADMAP-SOLID.md` was retired for, committed by the machinery built to prevent it.
#
# The comparison is the same one every other section makes; what is new is the fact. A sentence
# saying a service is or is not built is compared against `services/` on disk, and a sentence saying
# it is or is not reachable is compared against `ServiceContracts.byService` -- the gateway's own
# map, the single place that association is declared, read out of the Scala rather than out of a
# list kept here. A claim is therefore impossible to satisfy by editing this script's own roster,
# because this script keeps none.
#
# Both directions, for section 4's reason. A service named in the prose that is not on disk is the
# drift that happens when a document is written ahead of the code; a service on disk that the prose
# does not name is the drift that happened here, and it is the one a table of fixed rows cannot see.

service_contracts_file=services/gateway/api/src/kui/gateway/api/routing/ServiceContracts.scala

for required in "$service_contracts_file"; do
  if [[ ! -f $required ]]; then
    echo "feature-matrix-check: $required is missing; the capability claims cannot be checked." >&2
    exit 2
  fi
done

# The service ids the gateway holds a contract for. Newlines are removed first because the map is
# written for a hundred-column rule and one of its eleven entries wraps `ServiceId.unsafe(` onto
# three lines -- a grep that assumed one line answered eight where the map holds nine, which is
# precisely the class of quiet miscount this section is here to refuse.
routed_service_names() {
  tr -d ' \n' < "${1-$service_contracts_file}" \
    | { grep -oE 'ServiceId\.unsafe\("[a-z][a-z-]*"\)' || true; } \
    | sed -E 's/.*"([a-z][a-z-]*)".*/\1/' | sort -u
}

service_directory_names() {
  find "${1-services}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort
}

# One service's state, in the vocabulary the prose uses, so that the claimed side of the comparison
# is the label the document actually printed rather than a code this script invented. The two
# sources are defaulted rather than fixed, for the reason `header_fact_from_document` takes its
# document as an argument: a fixture has to be able to drive the shipped function over a tree whose
# answer no reading in this run holds.
service_state_fact() {
  local name=$1 root=${2-services} contracts=${3-$service_contracts_file} built=no routed=no
  [[ -d "$root/$name" ]] && built=yes
  if routed_service_names "$contracts" | grep -qxF -- "$name"; then routed=yes; fi
  if [[ $built == yes && $routed == yes ]]; then printf 'Built and routed'
  elif [[ $built == yes ]]; then printf 'Built, not routed'
  elif [[ $routed == yes ]]; then printf 'Not built but routed'
  else printf 'Not built'
  fi
}

package_directory_names() {
  find "${1-frontend/packages}" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort
}

# The backticked tokens of one labelled list -- `**Built and routed:** \`a\`, \`b\`.` -- out of a
# block. The label is the claimed side, so it is returned with them.
service_labels=("Built and routed" "Built, not routed" "Not built")

# THE SENTENCE HALF OF THIS SECTION, AND WHY THE THREE LISTS WERE NOT ENOUGH
# --------------------------------------------------------------------------
# Everything above compares the three labelled lists. Measured on 2026-09-12 against the shipped
# script, with no edit to it at all, one sentence appended inside `README.md`'s own
# `capability-claims` block:
#
#   Neither Kafka Connect nor ksqlDB is built: KUI has no Connect screen and no ksqlDB screen.
#
#   $ ./scripts/feature-matrix-check.sh
#   feature-matrix-check: 404 claims checked, all true.     exit 0
#
# That is the exact sentence that kept definition-of-done item 4 open for four milestones, sitting
# inside the region built to refuse it, with every section count unchanged. Nothing read it:
# `check_capability_region` matches backticked tokens that follow one of three literal
# `**<label>:**` markers, and `report_unclaimed_figures` refuses digits and bold number words. Free
# prose in the block was struck out by nothing and compared by nothing.
#
# The rule this adds is narrow on purpose, and it is a rule about **where** a state may be stated
# rather than an attempt to parse English: the three labelled lists are the only place in a checked
# block where a service's state may be declared, because they are the only place a comparison
# reads. Any other sentence that names a service identity together with a negation token is
# refused, with the identity printed -- because a sentence like the one above is a claim about the
# tree that nothing in this file can check, and the repair is to move it into the lists where it is
# checked, or out of the markers where it is not claimed to be.
#
# **The specified shape was the residue -- the block's text once the three lists are consumed --
# and it does not hold.** A list token is `\*\*<label>:\*\*[^*]*`, so it runs to the next emphasis
# marker, and the block's last list has none after it: appended at the end of the block, the
# sentence above is consumed *as part of the `**Not built:**` list* and never reaches the residue
# at all. Measured here before this was written. So the scan below runs over the block as it
# arrived, sentence by sentence, and it is the sentence rather than the block that is exempted:
# a sentence carrying a `**<label>:**` marker has that marker and its backticked names struck out
# -- those are the claimed side of a comparison that already ran -- and whatever prose is left in
# it is read like any other. A false clause appended to a list line is therefore read, which is
# the one thing the residue shape could not do.

# The identities prose uses for a service, held beside `service_labels` and keyed by every service
# id the block lists and every directory under `services/` -- `service-alias-roster` below compares
# those two sets, so a twelfth service is a decision taken here rather than a name that silently
# escapes the scan.
#
# An alias is matched **case-sensitively** as a whole word, and most services have none. That is
# not an omission and it is the honest limit of this gate: `topic`, `message`, `cluster`,
# `consumer`, `schema`, `metrics`, `alerts`, `gateway`, `identity`, `acl` and `quota` are the nouns
# this documentation uses for Kafka's own concepts on nearly every line, and an alias for any of
# them would refuse honest prose more often than a false claim. For those the identity form is the
# backticked id -- `` `topic` `` -- which is how the lists name them and how a sentence making a
# claim about the service rather than the concept names them too. `connect` and `ksql` are the two
# that carry a product name a reader would recognise, they are the two the false sentence above was
# written about, and their aliases are therefore the ones that exist.
declare -A service_aliases=(
  [acl]=""
  [alerts]=""
  [cluster]=""
  [connect]="Kafka Connect|Connect"
  [consumer]=""
  [gateway]=""
  [identity]=""
  [ksql]="ksqlDB|KSQL"
  [message]=""
  [metrics]=""
  [quota]=""
  [schema]=""
  [topic]=""
)

# A negation in a sentence about a service is a claim about whether it exists. `not` is here
# despite being the commonest word in the list because `Built, not routed` is a label and labels
# are struck out before this runs; a sentence that says "not" about a *named* service is making the
# claim this gate exists for whichever word it uses.
negation_tokens='no|not|neither|nor|without|never'

# THE POSITIVE-VOICE HALF, AND THE FALSE-POSITIVE DECISION IT FORCED
# -----------------------------------------------------------------
# `negation_tokens` alone reads one grammatical mood. Measured on 2026-09-12 against the shipped
# script, each sentence appended on its own inside `README.md`'s `capability-claims` block and
# nothing else changed:
#
#   Neither Kafka Connect nor ksqlDB is built: KUI has no Connect screen and no ksqlDB screen.
#                                                                    exit 1, 1 disagreement
#   KUI ships without a Kafka Connect screen and never built ksqlDB.  exit 1, 1 disagreement
#   Kafka Connect remains unimplemented, and ksqlDB is a stub.        exit 0, all true
#   The Connect screen is a placeholder and the ksqlDB page is empty. exit 0, all true
#   KUI has no topic detail page and no consumer lag chart.           exit 0, all true
#
# Rows 3 and 4 are restatements of row 1 in the positive voice, and they escaped for want of a
# negation word. The vocabulary below is the other half: a service named beside a word that puts it
# in a state is making the same claim about the tree as a service named beside a negation, and the
# gate has to read both or an author who is told "do not say it that way" writes it the other way
# in one line.
#
# **The false-positive decision, said out loud, because this is the rule that could make the README
# unwritable.** *"The Connect screen is the placeholder for a worker that is not configured"* is
# honest prose about a **configured** state and this rule refuses it. That is deliberate, and it is
# the same rule the negation half already carries rather than a new one: inside a checked block a
# service's state is stated in the three labelled lists, where it is compared against `services/`
# and `ServiceContracts.byService`, or it is not stated inside the markers at all. The refusal is
# not *"that sentence is false"* -- it is *"nothing here can check that sentence, so do not make it
# where the markers promise everything is checked"*. The escape is four lines long and costs
# nothing: the sentence goes below the `<!-- /checked -->`, which is where `README.md` already keeps
# every paragraph of that shape. The alternative considered and rejected was qualifying the rule --
# refusing `placeholder` only when no `configured` appears nearby -- which is a heuristic about
# meaning, is defeated by the next synonym, and buys an author the right to make an unchecked claim
# inside the markers. The markers here are small on purpose.
#
# `not implemented` is listed even though `not` already catches it, because a reader looking for the
# vocabulary should find the phrase they were about to write rather than have to know that the first
# word of it is in the other list.
state_tokens='placeholder|placeholders|stub|stubs|stubbed|unimplemented|not implemented'
state_tokens+='|coming soon|empty|todo'

# The two lists are read as one alternation and held separately so that a reader can see both and so
# that a mutation deleting either is visible as a deleted list rather than as a shortened regex.
capability_claim_tokens="$negation_tokens|$state_tokens"

# The block, split into sentences. The block arrives flattened to one line -- `regions` joins its
# lines with spaces -- so a paragraph break is invisible here and a sentence terminator is the only
# boundary left. A colon is deliberately not one: `**Not built:**` ends with one, and the sentence
# this gate was written for hides its second clause behind one.
capability_prose_sentences() {
  printf '%s' "$1" | sed -E 's/([.!?]) +/\1\n/g'
}

# The service identities one block names outside the lists' own vocabulary, sorted and space
# separated. Empty is the answer for a block that makes no such claim, and that is the value the
# comparison expects.
capability_prose_offences() {
  local text=$1 sentence stripped id label found offences="" tick='`'
  # `|| [[ -n $sentence ]]` because the last sentence of a block carries no newline after it, and
  # `read` answers non-zero on an unterminated line -- which silently dropped the one sentence this
  # scan was written for, since a block's last line is exactly where it was appended.
  while IFS= read -r sentence || [[ -n $sentence ]]; do
    [[ -z ${sentence// /} ]] && continue
    stripped=$sentence
    for label in "${service_labels[@]}"; do
      [[ $stripped == *"**$label:**"* ]] || continue
      stripped=${stripped//"**$label:**"/}
      # The names in a labelled list are the claimed side of a `service-state` comparison that has
      # already run against the tree, so they are not a claim this scan can add anything to. Only
      # they are struck: prose written after them on the same line is not a list entry and is read.
      stripped=$(printf '%s' "$stripped" | sed -E "s/${tick}[^${tick}]*${tick}//g")
    done
    printf '%s' "$stripped" \
      | grep -qiE "(^|[^A-Za-z])($capability_claim_tokens)([^A-Za-z]|\$)" || continue
    for id in "${!service_aliases[@]}"; do
      found=""
      [[ $stripped == *"$tick$id$tick"* ]] && found=$id
      if [[ -z $found && -n ${service_aliases[$id]} ]]; then
        printf '%s' "$stripped" \
          | grep -qE "(^|[^A-Za-z0-9-])(${service_aliases[$id]})([^A-Za-z0-9-]|\$)" && found=$id
      fi
      # The one form in which a bare id is unambiguously the service rather than Kafka's noun:
      # qualified by what a capability sentence is about. `no alerts screen` and `the metrics
      # service is a stub` are caught by this and were green without it, measured on 2026-09-12;
      # `the gateway's own map` and `a topic's partitions` still are not, which is the limit
      # written out above.
      if [[ -z $found ]]; then
        printf '%s' "$stripped" \
          | grep -qiE "(^|[^A-Za-z0-9-])$id (service|screen|page|feature)([^A-Za-z0-9-]|\$)" \
          && found=$id
      fi
      [[ -n $found ]] && offences+="$found "
    done
  done < <(capability_prose_sentences "$text")
  printf '%s' "$offences" | tr ' ' '\n' | sort -u | tr '\n' ' ' | sed 's/^ *//; s/ *$//'
}

# What each block said about each service, filled by the handler below and read by
# `audit_service_states` after every block has been read. It is the `header table` pattern: the
# comparison inside the block and the audit outside it are two statements rather than one, so the
# cheapest attack on this section — comparing the label a document printed with itself, which the
# claimed-side refusal cannot see because the label *is* in the text it struck out — has to be
# applied twice before a false sentence goes green. That price is measured and published in
# `TECH_DEBT.md`'s TD-023 rather than asserted here.
declare -A declared_service_state=()

check_capability_region() {
  local where=$1 text=$2 rest tok label names name fact listed="" declared_packages=""
  local existing="" wanted=""
  # Taken before a single comparison has struck anything out. `text` is what the comparisons above
  # consume from; the prose scan at the foot of this function reads the block as the document
  # wrote it, for the reason written out beside `capability_prose_offences`.
  local whole=$2 offences=""

  if [[ $text =~ \*\*([0-9]+)\ services\*\* ]]; then
    claim service-count "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$(service_directory_names | wc -l | tr -d ' ')" \
      "$where says KUI is ${BASH_REMATCH[1]} services; \`services/\` holds\
 $(service_directory_names | wc -l | tr -d ' ')."
  fi

  if [[ $text =~ \*\*([0-9]+)\ of\ them\ routed\*\* ]]; then
    claim service-routed-count "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$(routed_service_names | wc -l | tr -d ' ')" \
      "$where says ${BASH_REMATCH[1]} services are routed; \`ServiceContracts.byService\` holds\
 $(routed_service_names | wc -l | tr -d ' ')."
  fi

  # Each labelled list, name by name. `claim` is handed the label fragment, so the claimed side has
  # to have been read out of the document: a comparison rewritten to put the derived state on both
  # sides fails the claimed-side refusal, because the derived state is not in the fragment unless
  # the document printed it.
  #
  # A list runs from its label to the next emphasis marker, so a list whose prose uses `*italics*`
  # ends early and the names after it go unread. That is not silent: `service-roster` below compares
  # the names this block reached against every directory under `services/`, so a truncated list is
  # reported as a service the block does not name, which is the same complaint and the right one.
  for label in "${service_labels[@]}"; do
    rest=$text
    while [[ $rest =~ \*\*${label}:\*\*([^*]*) ]]; do
      tok=${BASH_REMATCH[0]}
      names=${BASH_REMATCH[1]}
      while [[ $names =~ \`([a-z][a-z-]*)\` ]]; do
        name=${BASH_REMATCH[1]}
        names=${names#*"$name\`"}
        listed+="$name"$'\n'
        declared_service_state[$name]=$label
        fact=$(service_state_fact "$name")
        claim service-state "$tok" \
          "$label" "$fact" \
          "$where lists \`$name\` under **$label:** and the tree says it is \`$fact\` --\
 \`services/$name\` and \`ServiceContracts.byService\` are what this was compared against, not a\
 roster kept in this script."
      done
      rest=${rest#*"$tok"}
      consume text "$tok"
    done
  done

  if [[ -n $listed ]]; then
    # The names this block listed that really are directories, against every directory there is.
    # A service on disk the block does not name leaves the two unequal, which is the direction a
    # list of fixed names cannot see -- and is how `connect` and `ksql` were published as not built
    # for a whole wave beside a `services/` holding both.
    while IFS= read -r name; do
      [[ -z $name ]] && continue
      [[ -d services/$name ]] && existing+="$name"$'\n'
    done < <(printf '%s' "$listed" | sort -u)
    wanted=$(service_directory_names | tr '\n' ' ')
    claim service-roster "" \
      "$(printf '%s' "$existing" | sort -u | tr '\n' ' ')" "$wanted" \
      "$where names the services [$(printf '%s' "$existing" | sort -u | tr '\n' ' ')] and\
 \`services/\` holds [$wanted]. Every directory under \`services/\` has to be named in one of the\
 three lists, or a service can ship with this page saying nothing about it."
  fi

  # The frontend packages, the same way. The matrix reserves three names for milestones that have
  # not started, so the claim is *how many of the names exist* rather than that all of them do.
  if [[ $text =~ \*\*([0-9]+)\ of\ the\ ([0-9]+)\ names\ above\ exist ]]; then
    # Taken before the loop below runs: every `[[ =~ ]]` overwrites `BASH_REMATCH`, and reading it
    # after the loop is an unbound-variable death under `set -u` on the first block with no names.
    local pkg_tok=${BASH_REMATCH[0]} pkg_present=${BASH_REMATCH[1]} pkg_named=${BASH_REMATCH[2]}
    rest=$text
    local named=0 present=0 seen_packages=""
    # Counted once per **distinct** name: the key names each reserved package again in the sentence
    # that explains why it is reserved, and a roster that counts a name twice is a count that moves
    # when somebody adds a sentence.
    while [[ $rest =~ \`(shell|kernel|feature-[a-z-]+)\` ]]; do
      tok=${BASH_REMATCH[0]}
      name=${BASH_REMATCH[1]}
      rest=${rest#*"$tok"}
      [[ $'\n'$seen_packages == *$'\n'"$name"$'\n'* ]] && continue
      seen_packages+="$name"$'\n'
      named=$(( named + 1 ))
      if package_directory_names | grep -qxF -- "$name"; then
        present=$(( present + 1 ))
        declared_packages+="$name"$'\n'
      fi
    done
    claim package-count "$pkg_tok" \
      "$pkg_present" "$present" \
      "$where says $pkg_present of the named packages exist; $present of them are directories\
 under \`frontend/packages/\`." \
      "$pkg_named" "$named" \
      "$where says it names $pkg_named packages; it names $named."
    # `api` owns no screen and is deliberately outside the list, so it is the one exemption and it
    # is written here rather than inferred from the list being short.
    claim package-roster "" \
      "$(package_directory_names | grep -vx api | tr '\n' ' ')" \
      "$(printf '%s' "$declared_packages" | sort -u | tr '\n' ' ')" \
      "$where's package roster and \`frontend/packages/\` disagree; every package but \`api\` owns\
 screens and has to be named, or a feature package can ship with no row pointing at it."
  fi

  # The sentence half. `claim` is handed no fragment, the way the residue is handed none: there is
  # no figure to strike out and the claimed side is this file's own statement that a checked block
  # states a service's state in the three lists and nowhere else.
  offences=$(capability_prose_offences "$whole")
  claim capability-prose "" \
    "no capability sentence outside the lists" \
    "${offences:-no capability sentence outside the lists}" \
    "$where publishes a sentence outside the three labelled lists that names [$offences] together\
 with a negation or with a word that puts the service in a state. A service's state is stated in\
 the lists, where it is compared against\
 \`services/\` and \`ServiceContracts.byService\`, or it is not stated inside the markers at all --\
 because free prose here is the exact shape of *\"Neither Kafka Connect nor ksqlDB is built\"*,\
 which stood in this repository for four milestones with every gate green."

  report_unclaimed_figures "$where" "$text"
}

# The fact readers, driven over a tree whose answers no real service has. `service_state_fact` is
# the only thing standing between a published sentence and a roster kept in this file, so a body
# rewritten to read `services/` regardless of its argument answers `Built and routed` for a fixture
# service that exists nowhere, and is named here.
verify_service_fact_independence() {
  local root=$fixtures/services contracts=$fixtures/Contracts.scala both only_built only_routed
  mkdir -p "$root/fixture-both" "$root/fixture-built"
  printf '%s\n' 'ServiceId.unsafe(' '  "fixture-both"' ') -> Nil,' \
                'ServiceId.unsafe("fixture-routed") -> Nil' > "$contracts"
  both=$(service_state_fact fixture-both "$root" "$contracts")
  only_built=$(service_state_fact fixture-built "$root" "$contracts")
  only_routed=$(service_state_fact fixture-routed "$root" "$contracts")
  scope capability-claims "service_state_fact"
  claim service-fact-independence "" \
    "$both" "Built and routed" \
    "\`service_state_fact\` answered \`$both\` for a fixture service that is a directory and is in\
 the fixture contract map; it is reading something other than the two it was handed." \
    "$only_built" "Built, not routed" \
    "\`service_state_fact\` answered \`$only_built\` for a fixture service on disk that no contract\
 map names; the two halves of the answer are no longer independent." \
    "$only_routed" "Not built but routed" \
    "\`service_state_fact\` answered \`$only_routed\` for a fixture service that is routed and is\
 not a directory. The map is read across line breaks, which is why this case is here: the entry it\
 is modelled on wraps \`ServiceId.unsafe(\` onto three lines in the shipped file."
}

# The second reading, outside the block, by a statement that is not the one that made the claim.
# `declared_service_state` is filled beside each `service-state` comparison and read here; the
# coverage figure below compares how many services this audit reached with how many `service-state`
# claims the section actually recorded, which are two different places and so cannot be brought into
# agreement by one edit.
audit_service_states() {
  local name audited=0 recorded=0 line a b c
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c _ <<< "$line"
    [[ $a == capability-claims && $c == service-state ]] && recorded=$(( recorded + 1 ))
  done
  scope capability-claims "the service roster ledger"
  for name in ${!declared_service_state[@]}; do
    audited=$(( audited + 1 ))
    claim service-audit "" \
      "${declared_service_state[$name]}" "$(service_state_fact "$name")" \
      "the roster says \`$name\` is \`${declared_service_state[$name]}\` and the tree says it is\
 \`$(service_state_fact "$name")\`. This is the second reading: the comparison inside the block and\
 this one are two statements, so a label compared with itself at the claim site is still caught."
  done
  claim service-audit-coverage "" \
    "$audited" "$recorded" \
    "this audit re-derived the state of $audited services and the capability-claims section\
 recorded $recorded \`service-state\` comparisons; a service the audit does not reach is one only\
 the claim site speaks for."
  # The alias map against the services there are. A name with no entry is a name the prose scan
  # cannot see, so a twelfth service under `services/` -- or a name this block lists that the map
  # has never heard of -- fails here rather than escaping the sentence gate in silence.
  claim service-alias-roster "" \
    "$(printf '%s\n' "${!service_aliases[@]}" | sort -u | tr '\n' ' ' | sed 's/ $//')" \
    "$( { printf '%s\n' ${!declared_service_state[@]}; service_directory_names; } \
         | sort -u | tr '\n' ' ' | sed 's/ $//')" \
    "\`service_aliases\` and the services this run knows about disagree. Every id under\
 \`services/\` and every id a checked block lists has to have an entry -- an empty one where prose\
 names the service only as its backticked id -- or a sentence about it is invisible to the prose\
 scan."
}

# The last thing this section does, and it runs *after* the audit on purpose.
# `verify_service_fact_independence` above proves `service_state_fact` reads its two arguments --
# but it runs at the top of the section, before any document has been read, so `declared_service_
# state` is empty and it proves that independence only in the state where there is nothing to
# depend on. Measured on 2026-09-12 against the shipped script: inserting one line as the second
# line of `service_state_fact` --
#
#   if [[ -n ${declared_service_state[$name]+x} ]]; then
#     printf '%s' "${declared_service_state[$name]}"; return
#   fi
#
# -- and then moving `connect` and `ksql` into **Not built:** in `README.md` left the run printing
# `404 claims checked, all true`, exit 0, every section count unchanged. Both readings agreed
# because the fact had become the claim. This one seeds the roster first and requires the fact to
# contradict it.
verify_service_fact_roster_independence() {
  local root=$fixtures/services contracts=$fixtures/Contracts.scala answer
  mkdir -p "$root/fixture-both" "$root/fixture-built"
  printf '%s\n' 'ServiceId.unsafe(' '  "fixture-both"' ') -> Nil,' \
                'ServiceId.unsafe("fixture-routed") -> Nil' > "$contracts"
  declared_service_state[fixture-routed]='Built and routed'
  answer=$(service_state_fact fixture-routed "$root" "$contracts")
  scope capability-claims "service_state_fact against the roster it fills"
  claim service-fact-roster-independence "" \
    "$answer" "Not built but routed" \
    "\`service_state_fact\` answered \`$answer\` for a fixture service the roster declares \`Built\
 and routed\` and the fixture tree says is routed and absent. It is reading\
 \`declared_service_state\`, which is the roster it is supposed to be the independent check on --\
 after which the claim site and the audit are one statement and a false sentence is green."
}

# The second reading of the sentence gate, and it is here for the reason every second reading in
# this file is here. `capability_prose_offences` is driven by a fixture, so a body rewritten to
# answer nothing is red -- but the *claim site* is one line, and
# `"${offences:-no capability sentence outside the lists}"` rewritten to the literal on both sides
# is a comparison of a statement with itself that the fixture cannot see, because the fixture never
# goes through the claim site. So this re-reads each block out of its own file and requires the
# fact the ledger says that block was measured against to be the answer a fresh read gives. The
# claim site and this are two statements; silencing the gate means editing both, which is the price
# the rest of this file is written to.
audit_capability_prose() {
  local line a b c pairs file index text fresh
  scope capability-claims "the capability-prose ledger"
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c pairs <<< "$line"
    [[ $a == capability-claims && $c == capability-prose ]] || continue
    file=${b%#*}
    index=${b##*#}
    text=$(regions capability-claims "$file" | sed -n "${index}p")
    fresh=$(capability_prose_offences "$text")
    claim capability-prose-audit "" \
      "${pairs#*>}" "${fresh:-no capability sentence outside the lists}" \
      "the ledger says the \`capability-prose\` claim for \`$b\` was measured against\
 \`${pairs#*>}\`, and re-reading that block out of \`$file\` answers\
 \`${fresh:-no capability sentence outside the lists}\`. The comparison inside the block and this\
 reading are two statements; a claim site rewritten to compare its own answer with itself is\
 caught here."
  done
}

# Both directions over the sentence gate, in section 7's shape but inside the section that owns the
# function: an honest block with all three lists must offend nothing, and the sentence that stood
# in `README.md` for four milestones must name both services it lies about. Asserting *which*
# identities come back rather than merely that something did is what catches the opposite
# mutation -- a scan widened until it refuses every sentence fails the honest half in the same
# claim.
verify_capability_prose_refusal() {
  local honest attacked none named
  honest='KUI is **11 services** under `services/`, **9 of them routed** through the gateway. '
  honest+='Every name below is compared against a directory on disk, so a sentence here that says '
  honest+='a service is not built fails the build. **Built and routed:** `connect`, `ksql`. '
  honest+='**Built, not routed:** `gateway`, which has no proxied contract by design. '
  honest+='**Not built:** `acl` and `quota`.'
  attacked="$honest Neither Kafka Connect nor ksqlDB is built: KUI has no Connect screen and no"
  attacked+=" ksqlDB screen."
  none=$(capability_prose_offences "$honest")
  named=$(capability_prose_offences "$attacked")
  scope capability-claims "capability_prose_offences"
  claim capability-prose-refusal "" \
    "${none:-nothing}" "nothing" \
    "\`capability_prose_offences\` named [$none] in a block whose only claims about a service are\
 inside its three labelled lists. A scan that refuses honest prose is a scan a writer deletes." \
    "$named" "connect ksql" \
    "\`capability_prose_offences\` answered [$named] for the sentence that kept item 4 open for\
 four milestones -- *\"Neither Kafka Connect nor ksqlDB is built\"* -- appended to the end of a\
 block whose lists are true. It has to name \`connect\` and \`ksql\`."
}

# One pair per word in `state_tokens`, and the honest control that says where the line is.
#
# W11-01's verifier found that half of `negation_tokens` -- `without` and `never` -- was asserted by
# nothing until a fixture was written for it, and a vocabulary a synonym defeats is this gate's
# named failure mode. So every word in the positive-voice list is driven here, one sentence each,
# each naming both `connect` and `ksql` so that a word that has stopped being read shows up as its
# own line rather than as a count that moved.
#
# The last pair is the control and it is the important one. `Kafka Connect is configured per cluster
# and ksqlDB is reached through the gateway.` names both services, is honest, and carries none of
# the words -- a rule widened until it refuses that is a rule the next writer deletes, and the
# claim fails on it in the same call it fails the seven attacks in.
verify_capability_prose_state_tokens() {
  local placeholder stub unimplemented notimpl soon empty todo honest
  placeholder='The Connect screen is a placeholder and the ksqlDB page is empty.'
  stub='Kafka Connect is a stub here and ksqlDB is stubbed out.'
  unimplemented='Kafka Connect remains unimplemented, and ksqlDB is unimplemented too.'
  notimpl='Kafka Connect is not implemented and ksqlDB is not implemented either.'
  soon='Kafka Connect is coming soon, and ksqlDB is coming soon as well.'
  empty='The Kafka Connect screen is empty and the ksqlDB screen is empty.'
  todo='Kafka Connect is a TODO, and ksqlDB is a TODO.'
  honest='Kafka Connect is configured per cluster and ksqlDB is reached through the gateway.'
  scope capability-claims "capability_prose_offences, the positive voice"
  claim capability-prose-states "" \
    "$(capability_prose_offences "$placeholder")" "connect ksql" \
    "the sentence the wave-12 plan names as green on the shipped script -- *\"$placeholder\"* --\
 answered [$(capability_prose_offences "$placeholder")]. It is a direct restatement of the sentence\
 that kept item 4 open for four milestones, in the positive voice." \
    "$(capability_prose_offences "$stub")" "connect ksql" \
    "*\"$stub\"* answered [$(capability_prose_offences "$stub")]." \
    "$(capability_prose_offences "$unimplemented")" "connect ksql" \
    "*\"$unimplemented\"* answered [$(capability_prose_offences "$unimplemented")]." \
    "$(capability_prose_offences "$notimpl")" "connect ksql" \
    "*\"$notimpl\"* answered [$(capability_prose_offences "$notimpl")]. This one is caught by\
 \`not\` as well, and is listed in \`state_tokens\` so that a reader looking for the phrase finds\
 it rather than having to know the first word of it is in the other list." \
    "$(capability_prose_offences "$soon")" "connect ksql" \
    "*\"$soon\"* answered [$(capability_prose_offences "$soon")]." \
    "$(capability_prose_offences "$empty")" "connect ksql" \
    "*\"$empty\"* answered [$(capability_prose_offences "$empty")]." \
    "$(capability_prose_offences "$todo")" "connect ksql" \
    "*\"$todo\"* answered [$(capability_prose_offences "$todo")]." \
    "$(capability_prose_offences "$honest")" "" \
    "*\"$honest\"* answered [$(capability_prose_offences "$honest")]. It names both services,\
 states nothing about whether they are built, and carries none of the vocabulary. A scan that\
 refuses it refuses honest prose about every service in the roster."
}

verify_service_fact_independence
verify_capability_prose_refusal
verify_capability_prose_state_tokens

for file in README.md "$matrix"; do
  check_marked_file capability-claims "$file" check_capability_region
done

audit_capability_prose
audit_service_states
verify_service_fact_roster_independence
close_section capability-claims 43

# ---------------------------------------------------------------------------------------------
# 6, CONTINUED. The fixtures over the sentence gate.
# ---------------------------------------------------------------------------------------------
#
# These three belong to section 6 and say so: every claim below is scoped `guard-fixtures`, they are
# counted by `close_section guard-fixtures` at the foot of this block, and their kinds are pinned in
# `registry[guard-fixtures]`. They sit here for one mechanical reason -- `capability_prose_offences`,
# `service_aliases` and `audit_service_states` are declared a hundred lines above, and bash defines a
# function when the parser reaches it, so a fixture written where section 6 ends is `command not
# found`. The alternative was a copy of each function next to its fixture, which is the one thing a
# fixture may never be: `drive` exists so that the SHIPPED body is what fails.
#
# Each of the three closes a rule that was measured green under a one-line mutation of the shipped
# script on 2026-09-12, by this wave's verification pass over W11-01. The mutation is quoted with the
# fixture it now reddens.

# The sentence scan's three remaining shapes, and the honest control that stops it being widened.
#
# `verify_capability_prose_refusal` above drives exactly one attacking sentence -- the `neither/nor`
# one that kept item 4 open -- and it is a sentence appended AFTER the lists. Everything else the scan
# does was asserted by nothing:
#
#   `[[ $stripped == *"**$label:**"* ]] || continue`  ->  `... && continue 2`      416 all true, exit 0
#       (exempt the whole labelled sentence instead of striking only the marker and its backticked
#       names). A false clause written onto the end of a `**Not built:**` line is then green, and that
#       line is where an author who is editing the lists is already typing.
#
#   the qualified-noun block deleted                                               416 all true, exit 0
#       `$id (service|screen|page|feature)` is the ONLY thing that can see the nine services with no
#       alias -- topic, message, cluster, consumer, schema, metrics, alerts, gateway, identity, acl,
#       quota. Without it `KUI has no alerts screen` is a sentence about a service that no comparison
#       in this file reads.
#
#   `negation_tokens` -> `'no|not|neither|nor'`                                    416 all true, exit 0
#       Half the vocabulary. `without` and `never` were exercised by nothing.
#
# The fourth pair is the one that catches the opposite mutation. A scan widened until it refuses
# honest prose is a scan the next writer deletes, and `**Built, not routed:** \`gateway\`, which has
# no proxied contract by design.` is the honest sentence closest to the line: a label containing
# `not`, a backticked id, and a real negation in the clause after it.
verify_capability_prose_forms() {
  local clause qualified vocabulary honest
  clause='**Not built:** `acl` and `quota`, and neither Kafka Connect nor ksqlDB is built here.'
  qualified='KUI has no alerts screen, and the metrics service is a stub.'
  vocabulary='KUI ships without a Kafka Connect screen and never built ksqlDB.'
  honest='**Built, not routed:** `gateway`, which has no proxied contract by design.'
  scope guard-fixtures "capability_prose_offences, the forms the block above does not drive"
  claim capability-prose-forms "" \
    "$(capability_prose_offences "$clause")" "connect ksql" \
    "a false clause on the end of a \`**Not built:**\` list line answered\
 [$(capability_prose_offences "$clause")]. Only the marker and its backticked names are struck out;\
 prose written after them on the same line is a claim like any other." \
    "$(capability_prose_offences "$qualified")" "alerts metrics" \
    "a sentence naming two of the nine services that have no alias, in the one form where a bare id\
 is unambiguously the service -- \`alerts screen\`, \`metrics service\` -- answered\
 [$(capability_prose_offences "$qualified")]." \
    "$(capability_prose_offences "$vocabulary")" "connect ksql" \
    "a sentence negating with \`without\` and \`never\` answered\
 [$(capability_prose_offences "$vocabulary")]. Half of \`negation_tokens\` was driven by nothing, and\
 a vocabulary a synonym defeats is the failure mode this gate was warned about." \
    "$(capability_prose_offences "$honest")" "" \
    "the honest sentence closest to the line -- a label carrying \`not\`, a backticked id, and a real\
 negation in the clause after it -- answered [$(capability_prose_offences "$honest")]. A scan that\
 refuses this is a scan a writer deletes."
}

# The claim site's CLAIMED half, which is the half `audit_capability_prose` does not read.
#
# That audit compares `${pairs#*>}` -- the fact -- against a fresh read of the block. So the cheapest
# attack of all survived it, measured, one line:
#
#   claim capability-prose "" "no capability sentence outside the lists" "${offences:-...}"
#     ->  claim capability-prose "" "${offences:-...}" "${offences:-...}"        416 all true, exit 0
#
# Both sides become the offences, the fact half still agrees with a fresh read, and the gate is dead
# for every block. The claimed side of this comparison is not a figure read out of a document -- it is
# this file's own statement of the rule -- so the one thing that pins it is the literal, read back off
# the ledger by something that is not the claim site. `matched` is empty at that call, which is why
# `claim`'s own claimed-side refusal cannot see this.
#
# Two readings, because the ledger alone cannot see it on a tree whose documents are honest: with no
# offence anywhere, `${offences:-$rule}` IS the literal and the mutated call site records exactly the
# pair the shipped one does. Measured -- the ledger check below passed under the mutation. So the
# claim site is also DRIVEN, over a fixture block that really does offend, and required to report.
# That is the pair the mutation cannot survive, and the ledger check is what pins the literal itself
# on every real block so that the rule cannot be restated into something weaker.
audit_capability_prose_claim_site() {
  local line a b c pairs sites=0 audits=0 wrong="" offending honest named quiet pattern
  local rule="no capability sentence outside the lists"
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c pairs <<< "$line"
    [[ $a == capability-claims ]] || continue
    case $c in
      capability-prose)
        sites=$(( sites + 1 ))
        [[ ${pairs%%>*} == "$rule" ]] || wrong+="$b "
        ;;
      capability-prose-audit) audits=$(( audits + 1 )) ;;
    esac
  done
  # The claim site itself, over a block written for the occasion. No figures, no labelled lists and
  # no package sentence, so the only comparison this block can reach is the sentence one.
  offending='KUI has no alerts screen.'
  honest='Every claim about a service is stated in one of the three lists.'
  pattern='publishes a sentence outside the three labelled lists that names'
  named=$(drive_says "$pattern" check_capability_region "a fixture block" "$offending")
  quiet=$(drive_says "$pattern" check_capability_region "a fixture block" "$honest")

  scope guard-fixtures "the capability-prose claim site"
  claim capability-prose-claim-site "" \
    "$named" "reported" \
    "the \`capability-prose\` claim $named a block whose one sentence says a service is not built.\
 Driving the claim site rather than \`capability_prose_offences\` is what catches the site rewritten\
 to compare the measured offences with themselves -- on an honest tree both sides are then the\
 literal below and the ledger reads exactly as it should." \
    "$quiet" "not reported" \
    "the \`capability-prose\` claim $quiet a block that states nothing about a service outside the\
 lists. A gate that refuses honest prose is a gate the next writer deletes." \
    "${wrong:-every capability-prose claim states the rule}" \
    "every capability-prose claim states the rule" \
    "the \`capability-prose\` claim for [$wrong] compares something other than \`$rule\` as its\
 claimed side. That side is this file's statement of the rule and never a figure out of a document,\
 so a claim site rewritten to put the measured offences on BOTH sides is a comparison of an answer\
 with itself -- green over every block, for every sentence, in one line." \
    "$sites" "$audits" \
    "$sites blocks made a \`capability-prose\` claim and $audits were re-read by\
 \`audit_capability_prose\`. The two are filled by different loops over different inputs, so a claim\
 site that stops recording leaves this reading over nothing -- which is the state in which the check\
 above is vacuously true."
}

# `service-alias-roster`, whose claimed side is the alias map and whose fact is the services this run
# knows about. `matched` is empty there too, so the same one-line attack applies and nothing drove it:
# replacing the claimed side with the fact expression left the run at exit 0. A twelfth DIRECTORY under
# `services/` is still caught by `service-count` and `service-roster` -- measured, `mkdir
# services/fixture-twelfth` is red with or without the mutation -- so what this pins is the direction
# that claim uniquely guards: a checked block listing an id the alias map has never heard of, which is
# a service the sentence scan is blind to. Seeded into the roster rather than onto the disk, because
# the disk half already has two gates and this half has none.
#
# The roster is REBUILT from the alias map rather than taken as this run left it, and both directions
# are driven through the same rebuild. Measured while this was being written: driving the shipped
# `audit_service_states` over the live roster reports the disagreement in BOTH directions, because
# `verify_service_fact_roster_independence` seeds `fixture-routed` into it four lines above and never
# takes it out -- so the agreeing half would have been asserting the residue of another fixture. A
# fixture builds its own input; that is the whole of section 6.
capability_alias_roster_fixture() {
  local extra=${1-} id
  declared_service_state=()
  for id in "${!service_aliases[@]}"; do
    declared_service_state[$id]=$(service_state_fact "$id")
  done
  [[ -n $extra ]] && declared_service_state[$extra]='Not built'
  audit_service_states
}

verify_service_alias_roster_refusal() {
  local pattern seeded unseeded
  pattern='and the services this run knows about disagree'
  seeded=$(drive_says "$pattern" capability_alias_roster_fixture fixture-twelfth)
  unseeded=$(drive_says "$pattern" capability_alias_roster_fixture)
  scope guard-fixtures "audit_service_states"
  claim alias-roster-refused "" \
    "$seeded" "reported" \
    "\`audit_service_states\` $seeded the alias-roster disagreement for a roster carrying an id\
 \`service_aliases\` has never heard of. Every id a checked block lists has to have an entry -- an\
 empty one where the prose names it only as its backticked id -- or a sentence about that service is\
 invisible to \`capability_prose_offences\`." \
    "$unseeded" "not reported" \
    "\`audit_service_states\` $unseeded that disagreement over this run's own roster, which agrees\
 with the map. A guard that fires on everything is a guard whose failing case says nothing."
}

verify_capability_prose_forms
audit_capability_prose_claim_site
verify_service_alias_roster_refusal

# `close_section guard-fixtures` is NOT here, and this is the second time it has moved for the same
# reason. Four more fixtures drive `check_quotation_region`, `audit_quotations_in_every_block` and
# `check_debt_register_region`, which sections 9 and 10 declare below this line; bash defines a
# function when the parser reaches its line, so a fixture written here is `command not found`. The
# section is a *name* and not a line range -- `scope` decides which section a claim belongs to and
# `count_of` reads the ledger -- so those four are section 6's where they sit, and the close runs
# at the foot of section 10 where the last of them has been counted.

# ---------------------------------------------------------------------------------------------
# 9. The quotation: a sentence one file attributes to another, against the file it names.
# ---------------------------------------------------------------------------------------------
#
# Sections 0 to 7 compare a **figure**. Section 8 compares a **sentence** against the tree. This one
# compares a **quotation**, and it is the narrowest of the three: a string a document attributes to
# a named file either occurs in that file or it does not, which is decidable by `grep -F` and needs
# no parser, no vocabulary and no heuristic about what the sentence means.
#
# It exists because wave 11 repaired `docs/operations/masking.md` -- correctly, in the open, with a
# scope -- and two documents went on quoting, in the present tense, a sentence that page no longer
# contained. `README.md` and `docs/FEATURE_MATRIX.md` both published the page's old, unscoped
# statement of the length bound as an invariant an operator was invited to rely on; the repaired
# page states that bound of the `mask` kind alone. Measured on 2026-09-12, on the tree wave 11 left
# green: two hits for the old sentence outside `docs/plan/`, and `grep -c` for it over the page it
# was attributed to answered **0**. The sentence is not written out here, because this file is
# inside the `grep` that has to answer nothing once the repair lands, and a gate that has to be
# excluded from its own acceptance command is a gate with a hole cut in it for its own convenience.
#
# Both sentences were true when they were written. Both were made false inside the wave whose whole
# subject was documents going stale, by that wave's own repair, and every gate in this repository
# was green over both -- because a figure is compared against the thing that produces it and a
# sentence against the tree, and **nothing here had ever compared a quotation of one file against
# that file**. That is house rule 25, and this section is it.
#
# THE SCOPE, IN ONE SENTENCE, AND WHY IT IS THE CHECKED BLOCK AND NOT THE DOCUMENT
# -------------------------------------------------------------------------------
# *Inside a checked region, a backticked or italicised span of **eight words or more** is a
# quotation of the nearest backticked repository path that precedes it, and it has to occur in that
# file.*
#
# The scope is the marked region, not the file, and that is a measurement rather than a preference.
# Run this reader over every line of the 113 markdown files this repository tracks outside
# `docs/plan/` and it answers **1,154 attributions, 1,148 of them absent** -- because a fenced code
# block is full of `*`-delimited spans, an ASCII architecture diagram is one span eight hundred
# words long, and a path named in a table's first row sticks to every cell below it. Run it over the
# twelve marked regions this repository carries and it answers **12 blocks, 0 attributions, 0
# absent**: a marked region is short, hand-written and opted into, and a sentence inside one is a
# claim its author asked to have checked. The document-wide reader is not a stricter version of this
# one, it is a different and useless tool, and the number above is why this file does not ship it.
#
# **So on the tree this section landed on it measured nothing**, and that is stated here rather than
# discovered later: the gate is only as wide as the regions documents put around their quotations,
# and the two sentences that kept item 4 open had to be moved inside a region before it could see
# them. A packet that wants to say what another file says puts the quotation inside a region, or
# names the file without quoting it. Both are honest; only the first is checked.
#
# The eight-word floor is what keeps code out without a rule about code. `\`mask\``, `\`replace\``
# and `\`{"name":"<redacted>"}\`` are one whitespace-separated word each, so a JSON payload, an
# identifier and a shell fragment never reach the comparison, and no exception list is needed for
# them.

# The six extensions a backticked token has to end in before this section will treat it as naming a
# file. Held as a variable because three expressions below read it and a fourth prints it.
quotation_extensions='md|scala|ts|tsx|yaml|sh'

# The repository path a backticked token names, or nothing when the token is not shaped like one.
# `:124` and `:124-133` are stripped: a document citing a line range is citing the file, and a
# citation that carries the range is the one most likely to be quoting something out of it.
quotation_path_shape() {
  local token=$1
  [[ $token =~ ^[A-Za-z0-9_./@-]+\.($quotation_extensions)(:[0-9]+(-[0-9]+)?)?$ ]] || return 0
  printf '%s' "${token%%:*}"
}

# The `path<TAB>quotation` attributions one block makes, in the order it makes them.
#
# `**bold**` is struck out first. A document in bold is emphasising its own words rather than
# quoting somebody else's, and `*` is the same character in both, so `**Not built:**` would
# otherwise be read as an italic span. That is the only normalisation: a quotation is taken exactly
# as the document wrote it, minus one pair of straight quotes when the author wrapped the italics
# around them -- `*"..."*` is the shape `docs/FEATURE_MATRIX.md` used and the quotes are the
# document's punctuation, not the quoted file's.
quotation_attributions() {
  local text=$1 span content path="" candidate words
  text=$(printf '%s' "$text" | sed -E 's/\*\*[^*]*\*\*//g')
  while IFS= read -r span; do
    case $span in
      '`'*) content=${span#\`}; content=${content%\`} ;;
      '*'*) content=${span#\*}; content=${content%\*} ;;
      *) continue ;;
    esac
    candidate=$(quotation_path_shape "$content")
    if [[ -n $candidate ]]; then path=$candidate; continue; fi
    content=${content#\"}; content=${content%\"}
    words=$(printf '%s' "$content" | wc -w)
    (( words >= 8 )) || continue
    # A span with no path before it in this block is not an attribution. It is somebody's italics,
    # and refusing it would make the markers unwritable for the sake of a claim nobody made.
    [[ -n $path ]] || continue
    printf '%s\t%s\n' "$path" "$content"
  done < <(printf '%s' "$text" | { grep -oE '`[^`]*`|\*[^*]+\*' || true; })
}

# The comparison, and it is one `grep -F`.
#
# The whitespace on both sides is collapsed before the match, because a line break is not a
# difference of wording: the quoting document wraps at a hundred columns and the quoted file wraps
# somewhere else, and a quotation that spans two lines in either of them is the ordinary case rather
# than the exception. Nothing else is normalised. A changed word, a changed article and a changed
# tense are all changed quotations, which is the whole point -- the sentence this section was
# written for differs from the page's own by four words.
quotation_is_present() {
  local path=$1 quote=$2 haystack needle
  [[ -f $path ]] || { printf 'no such file'; return 0; }
  haystack=$(tr '\n' ' ' < "$path" | tr -s ' ')
  needle=$(printf '%s' "$quote" | tr -s ' ')
  if printf '%s' "$haystack" | grep -qF -- "$needle"
  then printf 'present'
  else printf 'absent'
  fi
}

# The dangling half, and it is the class the plan's integrator named without a mechanism for it.
# `docs/FEATURE_MATRIX.md` cites three files under `docs/plan/verification/` that `docs/plan/\
# README.md` says are deleted when the plan closes. A citation whose file has gone is not caught by
# the reader above at all -- a token that does not resolve is simply not treated as a path, so the
# quotation after it attaches to whatever path came before, or to nothing. So the shape is refused
# on its own: inside a checked region, a backticked token that looks like a repository path and is
# not one is reported with the token printed.
quotation_dangling_paths() {
  local text=$1 span content candidate missing=""
  text=$(printf '%s' "$text" | sed -E 's/\*\*[^*]*\*\*//g')
  while IFS= read -r span; do
    [[ $span == '`'* ]] || continue
    content=${span#\`}
    content=${content%\`}
    candidate=$(quotation_path_shape "$content")
    [[ -n $candidate && ! -f $candidate ]] && missing+="$candidate "
  done < <(printf '%s' "$text" | { grep -oE '`[^`]*`' || true; })
  printf '%s' "$missing" | tr ' ' '\n' | sort -u | tr '\n' ' ' | sed 's/^ *//; s/ *$//'
}

check_quotation_region() {
  local where=$1 text=$2 path quote fact found=0 dangling span content
  while IFS=$'\t' read -r path quote; do
    [[ -z $path ]] && continue
    found=$(( found + 1 ))
    fact=$(quotation_is_present "$path" "$quote")
    claim quotation "" \
      "present" "$fact" \
      "$where quotes *\"$quote\"* and attributes it to \`$path\`. \`grep -F\` over that file,\
 with the line breaks on both sides collapsed to spaces, answers \`$fact\`. A quotation is a\
 dependency on the file it names: either the sentence is repaired to what that file says, or it\
 stops being a quotation and names the file instead."
  done < <(quotation_attributions "$text")

  dangling=$(quotation_dangling_paths "$text")
  claim quotation-path "" \
    "every path resolves" "${dangling:-every path resolves}" \
    "$where names [$dangling] as files in this repository and they are not there. A citation whose\
 file has gone is invisible to the quotation reader above -- an unresolved token is not treated as\
 a path at all -- so it is refused here, where it is still a sentence a reader would follow."

  (( found > 0 )) ||
    fail "$where carries no quotation at all. A \`checked: quotations\` marker says the passage" \
         "inside it attributes a string to a file in this repository; a block that attributes" \
         "none is a marker over nothing, which is the state this whole file exists to refuse."

  # The paths are struck out before the residue reads the block, for the reason a labelled list's
  # names are struck out in section 8: they are the claimed side of the two comparisons above. A
  # path is also the one kind of published token that carries digits belonging to nothing --
  # `frontend/e2e/brokers.spec.ts` publishes the figure `2` to a reader that counts characters, and
  # a residue that reports it teaches the next author to write the path outside the markers.
  local residue_text=$text
  while IFS= read -r span; do
    content=${span#\`}
    content=${content%\`}
    [[ -n $(quotation_path_shape "$content") ]] && residue_text=${residue_text//"$span"/}
  done < <(printf '%s' "$text" | { grep -oE '`[^`]*`' || true; })

  report_unclaimed_figures "$where" "$residue_text"
}

# The sweep: house rule 25 says *a checked region*, not *a region of this one kind*, so every marked
# block in this repository is read for attributions and not only the blocks written for them.
#
# On the tree this landed on the sweep found **zero** attributions over twelve blocks, so the number
# of blocks it read is claimed as well as the attributions it found. Without that, a reader that
# stopped reading -- a renamed marker, a file dropped from the roster, an `awk` that matches
# nothing -- would answer "no broken quotation anywhere" and be believed, which is the exact failure
# this file catalogues under `verify_dependency_reader_independence` and refuses to repeat.
#
# The roster is written out rather than globbed, for the reason every input in this file is written
# out: a glob that matches nothing checks nothing and says so to nobody.
declare -A quotation_swept_kinds=(
  [README.md]="capability-claims rows"
  [$matrix]="capability-claims rows milestones"
  [$overview]="gate-table"
  [$apireadme]="merged-document"
  [$adr048]="merged-document"
  [$adr052]="openapi-totals"
  [$adr053]="openapi-totals"
  [$adr054]="openapi-totals"
)

audit_quotations_in_every_block() {
  local file kind text index path quote fact blocks=0 broken=""
  for file in "${!quotation_swept_kinds[@]}"; do
    for kind in ${quotation_swept_kinds[$file]}; do
      index=0
      while IFS= read -r text; do
        index=$(( index + 1 ))
        blocks=$(( blocks + 1 ))
        while IFS=$'\t' read -r path quote; do
          [[ -z $path ]] && continue
          fact=$(quotation_is_present "$path" "$quote")
          [[ $fact == present ]] && continue
          broken+="$file (checked: $kind #$index) -> $path: \"$quote\" is $fact. "
        done < <(quotation_attributions "$text")
      done < <(regions "$kind" "$file")
    done
  done
  scope quotations "every marked block that is not a quotations block"
  claim quotation-sweep "" \
    "no broken attribution" "${broken:-no broken attribution}" \
    "a marked block of another kind attributes a quotation to a file that does not contain it:\
 $broken House rule 25 is about a checked region and not about one kind of region, so every block\
 in this repository is read for attributions." \
    "$blocks" "12" \
    "the sweep read $blocks marked blocks and this script was last edited over 12. A reader that\
 has stopped reading answers \`no broken attribution\` for every document at once, and on the tree\
 this section landed on that is exactly the answer the honest reader gives -- so the count it read\
 is claimed beside the answer it gave."
}

# The second reading, for `capability-prose`'s reason and with `capability-prose`'s shape. The claim
# site above hands `claim` no matched fragment, so its claimed side is this file's own literal
# `present` rather than anything read out of a document, and `claim`'s claimed-side refusal cannot
# see a call site rewritten to `claim quotation "" "$fact" "$fact"`. So each block that made a
# `quotation` claim is re-read here from disk by a loop that is not the claim site, and the answers
# the ledger recorded for it have to be the answers a fresh read gives.
#
# One claim per block rather than per attribution, and joined in the order the reader yields them:
# a block quoting two files records two ledger lines, and comparing a line at a time would compare
# the first attribution's answer with the whole block's.
audit_quotations() {
  local line a b c pairs blocks="" at file index text path quote fresh ledgered
  scope quotations "the quotation ledger"
  for line in ${ledger+"${ledger[@]}"}; do
    IFS=$'\t' read -r a b c pairs <<< "$line"
    [[ $a == quotations && $c == quotation ]] || continue
    [[ $'\n'$blocks == *$'\n'"$b"$'\n'* ]] || blocks+="$b"$'\n'
  done
  while IFS= read -r at; do
    [[ -z $at ]] && continue
    file=${at%#*}
    index=${at##*#}
    text=$(regions quotations "$file" | sed -n "${index}p")
    fresh=""
    while IFS=$'\t' read -r path quote; do
      [[ -z $path ]] && continue
      fresh+="$(quotation_is_present "$path" "$quote") "
    done < <(quotation_attributions "$text")
    ledgered=""
    for line in ${ledger+"${ledger[@]}"}; do
      IFS=$'\t' read -r a b c pairs <<< "$line"
      [[ $a == quotations && $c == quotation && $b == "$at" ]] || continue
      ledgered+="${pairs#*>} "
    done
    claim quotation-audit "" \
      "${ledgered% }" "${fresh% }" \
      "the ledger says the \`quotation\` claims for \`$at\` were measured against\
 [${ledgered% }], and re-reading that block out of \`$file\` answers [${fresh% }]. The comparison\
 inside the block and this reading are two statements, so a claim site rewritten to compare its own\
 answer with itself is caught here rather than believed."
  done <<< "$blocks"
}

# Section 7's shape, inside the section that owns the functions, because section 7 is the closer's
# on this packet's freeze and a gate that ships undriven is a gate nothing distinguishes from
# `true`. All three of the pieces above are driven over a page written for the occasion: the
# comparison in both directions and over a file that is not there, the reader that finds the
# attribution, the floor that keeps a short span from becoming one, and the dangling-path refusal in
# both directions.
verify_quotation_reader() {
  local page=$fixtures/quoted-page.md present absent missing wrapped short attributed dangling ok
  printf '%s\n' 'The engine writes the replacement literal' \
                'verbatim and with no length bound at all.' > "$page"
  # Written on one line here and wrapped in the fixture page, which is the ordinary case and the
  # one reason this comparison normalises anything.
  wrapped='the replacement literal verbatim and with no length bound'
  present=$(quotation_is_present "$page" "$wrapped")
  absent=$(quotation_is_present "$page" "the replacement literal verbatim and with a length bound")
  missing=$(quotation_is_present "$fixtures/no-such-page.md" "$wrapped")
  attributed=$(quotation_attributions "see \`$page\` and *$wrapped* for the bound")
  short=$(quotation_attributions "see \`$page\` and *no length bound* for the bound")
  dangling=$(quotation_dangling_paths "\`$fixtures/no-such-page.md\` and \`$page\`")
  ok=$(quotation_dangling_paths "\`$page\` and \`services/gateway\` and \`replace\`")
  scope quotations "quotation_is_present, quotation_attributions, quotation_dangling_paths"
  claim quotation-drive "" \
    "$present" "present" \
    "\`quotation_is_present\` answered \`$present\` for a sentence the fixture page carries across\
 two lines. A comparison that a line break defeats would refuse every honest quotation in this\
 repository, because every document here wraps at a hundred columns." \
    "$absent" "absent" \
    "\`quotation_is_present\` answered \`$absent\` for a sentence the fixture page does not carry,\
 one word different from one it does. A comparison that accepts an approximation is not a\
 comparison." \
    "$missing" "no such file" \
    "\`quotation_is_present\` answered \`$missing\` for a page that is not on disk. An absent file\
 has to be a distinct answer from an absent sentence, or a citation that has been deleted reads as\
 a quotation that has been repaired." \
    "$attributed" "$(printf '%s\t%s' "$page" "$wrapped")" \
    "\`quotation_attributions\` read [$attributed] out of a passage naming one path and quoting\
 eight words or more after it. This is the reader the whole section is, and until this fixture\
 nothing in this repository drove it over input it was guaranteed to have to refuse." \
    "$short" "" \
    "\`quotation_attributions\` read [$short] out of a passage whose italics are three words long.\
 The eight-word floor is what keeps a backticked identifier and a JSON payload out of the\
 comparison without a rule about either, and a floor that has stopped applying makes every\
 emphasised word in a marked block a quotation." \
    "$dangling" "$fixtures/no-such-page.md" \
    "\`quotation_dangling_paths\` named [$dangling] for a passage citing one file that exists and\
 one that does not." \
    "$ok" "" \
    "\`quotation_dangling_paths\` named [$ok] for a passage citing a file that exists, a directory\
 and a backticked word. A refusal that fires on a directory or on an ordinary identifier is a\
 refusal the next writer turns off."
}

# The claim site itself, driven, and it is here because the audit above cannot see the cheapest
# attack on this section. Measured on 2026-09-12 against this file with `audit_quotations` in place:
#
#   claim quotation "" "present" "$fact" ...   ->   claim quotation "" "$fact" "$fact" ...
#     433 claims checked, all true, exit 0
#
# The audit re-derives the *fact* and compares it with the fact the ledger recorded, and the
# mutation changes only the claimed side, so both readings still answer `present` -- and on a tree
# whose documents are honest the claimed side IS `present`, so reading the literal back off the
# ledger cannot see it either. The one thing the mutation cannot survive is a block that really does
# quote a sentence its file does not carry: there the two sides are `absent` and `absent`, the
# comparison agrees with itself, and the refusal never fires. So the shipped
# `check_quotation_region` is driven over a fixture block in both directions and required to report
# on one and stay quiet on the other. This is W11-A2's lesson about `capability-prose` applied
# before it had to be learned again: drive the function, do not read the ledger.
verify_quotation_claim_site() {
  local page=$fixtures/claim-site.md pattern honest broken said_broken said_honest
  printf '%s\n' 'the replacement literal verbatim and with no length bound' > "$page"
  pattern='and attributes it to'
  honest="see \`$page\`, which says *the replacement literal verbatim and with no length bound*."
  broken="see \`$page\`, which says *the replacement literal verbatim and with a length bound*."
  said_broken=$(drive_says "$pattern" check_quotation_region "a fixture block" "$broken")
  said_honest=$(drive_says "$pattern" check_quotation_region "a fixture block" "$honest")
  scope quotations "the quotation claim site"
  claim quotation-claim-site "" \
    "$said_broken" "reported" \
    "\`check_quotation_region\` $said_broken a block quoting eight words its own named file does\
 not contain. Driving the claim site rather than \`quotation_is_present\` is what catches the site\
 rewritten to compare its own answer with itself -- on a tree whose documents are honest both sides\
 of that comparison are \`present\`, and every reading of the ledger agrees with every other." \
    "$said_honest" "not reported" \
    "\`check_quotation_region\` $said_honest a block quoting eight words its named file does\
 contain. A gate that refuses an honest quotation is a gate that teaches the next writer to stop\
 quoting, which is the opposite of what this section is for."
}

verify_quotation_reader
verify_quotation_claim_site
for file in README.md "$matrix"; do
  check_marked_file quotations "$file" check_quotation_region
done
audit_quotations_in_every_block
audit_quotations
close_section quotations 11

# ---------------------------------------------------------------------------------------------
# 10. The debt register's own ids, against the table that holds them.
# ---------------------------------------------------------------------------------------------
#
# `TECH_DEBT.md` had never been opened by this script. It carries a sentence telling the next writer
# which id to use -- *"the next free id after this pass is TD-049"* -- and that sentence was read by
# nobody, which is how the register grew two rows numbered `TD-035` for two different defects and
# closed neither. The preamble's own account of it is worth keeping: a register that accepts a
# draft's id without reading its own table produces two rows for one defect.
#
# Two comparisons, and both are arithmetic over the first column rather than a judgement about the
# rows: the id the page publishes as free has to be one past the highest id the table holds, and no
# two rows may carry the same id. Neither can be satisfied by editing a roster in this file, because
# this file keeps none -- the fact is `TECH_DEBT.md`'s own table, read row by row.

debt="TECH_DEBT.md"

if [[ ! -f $debt ]]; then
  echo "feature-matrix-check: $debt is missing; the debt register cannot be checked." >&2
  exit 2
fi

# The id of every row of the register's table, in the order the table holds them. Anchored on the
# line start and on the two pipes, so an id named in a row's prose -- and nearly every row names one
# -- is not counted as a row of its own.
debt_row_ids() {
  sed -nE 's/^\| (TD-[0-9]+) \|.*/\1/p' "${1-$debt}"
}

check_debt_register_region() {
  local where=$1 text=$2 highest next duplicates
  local -a ids=()
  mapfile -t ids < <(debt_row_ids)

  if (( ${#ids[@]} == 0 )); then
    fail "$where: \`$debt\` holds no row this reader can see, so both comparisons below are true" \
         "of nothing. The reader anchors on \`| TD-nnn |\` at the start of a line; a table" \
         "rewritten out of that shape has to be noticed here rather than passed over."
    return
  fi

  highest=$(printf '%s\n' "${ids[@]}" | sed 's/TD-//' | sort -n | tail -1)
  next=$(printf 'TD-%03d' "$(( 10#$highest + 1 ))")
  duplicates=$(printf '%s\n' "${ids[@]}" | sort | uniq -d | tr '\n' ' ')
  duplicates=${duplicates% }

  if [[ $text =~ (TD-[0-9]+) ]]; then
    claim debt-next-id "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$next" \
      "$where publishes ${BASH_REMATCH[1]} as the next free id and the highest id in the table is\
 TD-$highest, so the next free one is $next. A writer told to use an id that is already taken\
 files a second row for a defect that already has one, which is how this register came to hold two\
 \`TD-035\`s and close neither."
  else
    fail "$where: the block publishes no \`TD-nnn\` figure, so the id the next writer is told to" \
         "use is read by nothing -- which is the state this section was added to end."
  fi

  claim debt-ids-unique "" \
    "no repeated id" "${duplicates:-no repeated id}" \
    "\`$debt\` carries [$duplicates] on more than one row. Two rows with one id are two defects\
 with one exit condition, and the row a reader searches for is the one they then fail to find."

  report_unclaimed_figures "$where" "$text"
}

check_marked_file debt-register "$debt" check_debt_register_region
close_section debt-register 3

# ---------------------------------------------------------------------------------------------
# 6, continued. The four guards sections 9 and 10 shipped without a failing case.
# ---------------------------------------------------------------------------------------------
#
# Every fixture below drives the **shipped** function over input written for the occasion and
# asserts what it *said*, not how many times it said something, for `drive_says`'s reason: two
# different refusals in one handler answer the same count for the same fixture.
#
# Each closes a one-line mutation that was applied to the shipped script on 2026-09-12, run through
# `./scripts/feature-matrix-check.sh`, and measured **green at 434 claims, exit 0** -- and each was
# re-applied after the fixture was written and measured red. The mutation is named above the
# fixture that closes it, so the next reader can re-apply it rather than believe this paragraph.

# Fixture 22: the sweep reports what it found, and not only how many blocks it read.
#
# MUTATION CLOSED: in `audit_quotations_in_every_block`, the two lines
#   `[[ $fact == present ]] && continue` and `broken+="$file (checked: $kind #$index) -> ..."`
# replaced by a bare `continue`. The sweep makes two statements -- *how many marked blocks it read*
# and *what it found in them* -- and only the first was driven. On a tree where every swept block is
# honest the mutant's answer and the reader's answer are the same string, `no broken attribution`,
# for every document at once; that is the `verify_dependency_reader_independence` failure class in
# the section whose own comment cites it, with the *stopped reading* half guarded and the *stopped
# reporting* half open.
#
# The roster is a `local -A`, which shadows the file-scope one for the duration of the call: the
# comparison is the shipped comparison and only its input is written here.
sweep_one_marked_file() {
  local file=$1 kind=$2
  local -A quotation_swept_kinds=(["$file"]="$kind")
  audit_quotations_in_every_block
}

verify_quotation_sweep_reports() {
  local source=$fixtures/swept-source.md page=$fixtures/swept-page.md
  local pattern said_broken said_honest
  printf '%s\n' 'The engine writes the replacement literal verbatim' \
                'and with no length bound at all.' > "$source"
  pattern='attributes a quotation to a file that does not contain it'
  # A `rows` block and not a `quotations` block, because the sweep exists for exactly the blocks
  # that were not written to be read for attributions.
  {
    printf '%s\n' '<!-- checked: rows -- claims: residue -->'
    printf 'See `%s`, which says *a masked value is never longer than the value it replaced*.\n' \
      "$source"
    printf '%s\n' '<!-- /checked -->'
  } > "$page"
  said_broken=$(drive_says "$pattern" sweep_one_marked_file "$page" rows)
  {
    printf '%s\n' '<!-- checked: rows -- claims: residue -->'
    printf 'See `%s`, which says *the replacement literal verbatim and with no length bound*.\n' \
      "$source"
    printf '%s\n' '<!-- /checked -->'
  } > "$page"
  said_honest=$(drive_says "$pattern" sweep_one_marked_file "$page" rows)
  scope guard-fixtures "audit_quotations_in_every_block"
  claim quotation-sweep-reports "" \
    "$said_broken" "reported" \
    "the sweep $said_broken a \`checked: rows\` block that attributes ten words to a file which\
 does not carry them. Its block count was right in both directions and its answer was \`no broken\
 attribution\` in both, which is what a reader that has stopped reporting says about a whole\
 repository." \
    "$said_honest" "not reported" \
    "the sweep $said_honest a \`checked: rows\` block whose attribution its named file does carry.\
 A sweep that refuses an honest attribution refuses every document that quotes anything, which is\
 the state the eight-word floor and the path-before-the-quote rule exist to avoid."
}

# Fixture 23: the `quotation-path` claim site, driven, for the reason the `quotation` claim site is.
#
# MUTATION CLOSED: `claim quotation-path "" "every path resolves" "${dangling:-every path resolves}"`
# -> `claim quotation-path "" "${dangling:-...}" "${dangling:-...}"`. This is the identical
# one-line self-comparison the section closed for the `quotation` claim and did not extend to the
# claim beside it: `verify_quotation_claim_site` drives the handler with
# `pattern='and attributes it to'`, which can only ever match the other message. `quotation-path` is
# the half of this section that refuses a citation whose file has been deleted, so it is the half
# that decides whether `docs/plan/verification/` can be emptied silently.
verify_quotation_path_claim_site() {
  local page=$fixtures/path-claim-site.md pattern dangling resolved said_dangling said_resolved
  printf '%s\n' 'the replacement literal verbatim and with no length bound' > "$page"
  pattern='as files in this repository and they are not there'
  # Both blocks carry an honest quotation as well, so the only difference between them is the
  # citation -- a fixture that changed two things at once would not say which one was read.
  dangling="cited in \`$fixtures/no-such-cited-page.md\` and \`$page\`, which says *the\
 replacement literal verbatim and with no length bound*."
  resolved="cited in \`$page\`, which says *the replacement literal verbatim and with no length\
 bound*."
  said_dangling=$(drive_says "$pattern" check_quotation_region "a fixture block" "$dangling")
  said_resolved=$(drive_says "$pattern" check_quotation_region "a fixture block" "$resolved")
  scope guard-fixtures "the quotation-path claim site"
  claim quotation-path-claim-site "" \
    "$said_dangling" "reported" \
    "\`check_quotation_region\` $said_dangling a block citing a repository path that is not on\
 disk. A citation whose file has gone is invisible to the quotation reader itself -- an unresolved\
 token is not treated as a path at all -- so this is the only place it is refused, and a claim site\
 comparing its own answer with itself refuses nothing while reporting a claim." \
    "$said_resolved" "not reported" \
    "\`check_quotation_region\` $said_resolved a block whose every backticked path resolves. The\
 refusal has to be quiet over the ordinary case or the next writer stops citing files by name."
}

# Fixture 24: the marker over nothing.
#
# MUTATION CLOSED: `(( found > 0 )) ||` -> `(( found >= 0 )) ||`. A `checked: quotations` marker
# says the passage inside it attributes a string to a file in this repository; a block that
# attributes none is a marker over nothing. No real block can drive this -- both blocks in the tree
# quote something -- which is why it shipped asserted by nobody. Disclosed by W12-01 as handoff
# item (3) and confirmed by mutation rather than taken on trust.
verify_quotation_empty_marker() {
  local page=$fixtures/empty-quotation.md pattern said_empty said_quoting
  printf '%s\n' 'the replacement literal verbatim and with no length bound' > "$page"
  pattern='carries no quotation at all'
  # Three words of italics, which is under the eight-word floor: the block names a file and
  # emphasises something, and attributes nothing.
  said_empty=$(drive_says "$pattern" check_quotation_region "a fixture block" \
    "see \`$page\` and *no length bound* for the bound.")
  said_quoting=$(drive_says "$pattern" check_quotation_region "a fixture block" \
    "see \`$page\`, which says *the replacement literal verbatim and with no length bound*.")
  scope guard-fixtures "the empty quotations marker"
  claim quotation-empty-marker "" \
    "$said_empty" "reported" \
    "\`check_quotation_region\` $said_empty a \`checked: quotations\` block whose only emphasised\
 span is under the eight-word floor. A marked region that yields no comparison is the state this\
 whole file exists to refuse, and the floor is what makes an emphasised phrase not a quotation --\
 so the two together can make a marker mean nothing without the marker moving." \
    "$said_quoting" "not reported" \
    "\`check_quotation_region\` $said_quoting that refusal over a block that does attribute eight\
 words or more. A refusal that fires on every block reports nothing about any of them."
}

# Fixture 25: the register's duplicate-id rule, which is the reason section 10 exists.
#
# MUTATION CLOSED: `duplicates=$(printf '%s\n' "${ids[@]}" | sort | uniq -d | tr '\n' ' ')` ->
# `duplicates=`. Deleting the `debt-ids-unique` claim outright is caught by `close_section
# debt-register 3`; emptying its **fact** is not, and the emptied fact equals the literal on the
# claimed side on every honest register, which is every register that has not yet grown the second
# `TD-035` this section was written for.
#
# `debt` is shadowed rather than the reader being re-implemented: `debt_row_ids` defaults its
# argument to `$debt` and `check_debt_register_region` calls it with none, so the shipped handler
# reads a fixture table through its own reader.
drive_debt_register() {
  local debt=$1
  shift
  check_debt_register_region "$@"
}

verify_debt_duplicate_refused() {
  local dup=$fixtures/debt-duplicate.md sound=$fixtures/debt-unique.md
  local pattern text said_duplicate said_unique
  printf '%s\n' '| TD-001 | a row | open |' \
                '| TD-002 | another row | open |' \
                '| TD-002 | a third row filed under an id already taken | open |' > "$dup"
  printf '%s\n' '| TD-001 | a row | open |' \
                '| TD-002 | another row | open |' > "$sound"
  # The same prose over both, so the `debt-next-id` half agrees in both directions and the only
  # thing that differs between the two drives is the table.
  text='the next free id after this pass is TD-003.'
  pattern='on more than one row'
  said_duplicate=$(drive_says "$pattern" drive_debt_register "$dup" "a fixture register" "$text")
  said_unique=$(drive_says "$pattern" drive_debt_register "$sound" "a fixture register" "$text")
  scope guard-fixtures "check_debt_register_region"
  claim debt-duplicate-refused "" \
    "$said_duplicate" "reported" \
    "\`check_debt_register_region\` $said_duplicate a register carrying \`TD-002\` on two rows.\
 Two rows with one id are two defects with one exit condition, and the row a reader searches for is\
 the one they then fail to find -- which is what this register did with \`TD-035\` and why section\
 10 was written." \
    "$said_unique" "not reported" \
    "\`check_debt_register_region\` $said_unique that refusal over a register whose ids are all\
 distinct. The mutant that empties the duplicate expression passes this fixture's second half and\
 fails its first, which is the difference between a comparison and an assertion that it ran."
}

verify_quotation_sweep_reports
verify_quotation_path_claim_site
verify_quotation_empty_marker
verify_debt_duplicate_refused
close_section guard-fixtures 25

# ---------------------------------------------------------------------------------------------
# 11. The newcomer's overview, against the figures this script derives itself.
# ---------------------------------------------------------------------------------------------
#
# `docs/overview/README.md` is the document definition-of-done item 4 names by function -- *"an
# overview a newcomer can read"* -- and on 2026-09-12 `grep -c 'checked:'` over it answered **0**.
# It published `390 claims over nine sections` about the run below, in the row of its own gate
# table that describes this script, written by the wave that owned this script, while the run
# printed 404. Four of that table's ten rows were stale against the tree they describe.
#
# This section takes the three figures the run produces without starting another process, because
# those are the ones that have no excuse: the claim total is this file's own ledger, the merged
# OpenAPI triple is read in section 2, and the `DECISIONS.md` row count in section 5. The five
# figures that need another build -- the Scala case count, the browser case count, and the three
# Mill task totals -- stay a dated snapshot outside the markers, and the page says so in a sentence
# rather than implying that a date is a check.
#
# The claim total is the one figure here that includes itself, which is why the count this section
# adds is a variable rather than a literal: the fact is the ledger as it stands when the block is
# opened plus the claims this section is about to make, and `close_section` below pins the same
# number from the other side. A comparison added here moves both, and the page's figure with them.
gate_table_claims=5

check_gate_table_region() {
  local where=$1 text=$2 total_fact
  total_fact=$(( ${#ledger[@]} + gate_table_claims ))

  if [[ $text =~ \*\*([0-9]+)\ claims\*\* ]]; then
    claim gate-claim-total "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$total_fact" \
      "$where says this script compares ${BASH_REMATCH[1]} claims; this run compared\
 $total_fact. The figure is the length of this script's own ledger, so a page that is wrong about\
 it is wrong about the run that is reading it."
  else
    fail "$where: the block publishes no \`**N claims**\` figure, so the total this script" \
         "compares is unread by the page that describes it -- which is how \`390\` survived the" \
         "wave whose subject it was."
  fi

  if [[ $text =~ \*\*([0-9]+)\ sections\*\* ]]; then
    claim gate-section-count "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "${#registry[@]}" \
      "$where says this script is ${BASH_REMATCH[1]} sections; its registry pins\
 ${#registry[@]}."
  else
    fail "$where: the block publishes no \`**N sections**\` figure."
  fi

  # Held in a variable so the expression stays inside a hundred columns, and written as one match
  # rather than three because the three figures are one sentence in the page and a claim that
  # struck out only part of it would leave the rest to the residue.
  local openapi_triple='\*\*([0-9]+)\ paths\*\*,\ \*\*([0-9]+)\ operations\*\*\ and'
  openapi_triple+='\ \*\*([0-9]+)\ component\ schemas\*\*'
  if [[ $text =~ $openapi_triple ]]; then
    claim gate-openapi-document "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "$doc_paths" \
      "$where says the merged document is ${BASH_REMATCH[1]} paths; \`docs/api/openapi.json\` has\
 $doc_paths." \
      "${BASH_REMATCH[2]}" "$doc_ops" \
      "$where says the merged document is ${BASH_REMATCH[2]} operations; it has $doc_ops." \
      "${BASH_REMATCH[3]}" "$doc_schemas" \
      "$where says the merged document is ${BASH_REMATCH[3]} component schemas; it has\
 $doc_schemas."
  else
    fail "$where: the block publishes no \`**N paths**, **N operations** and **N component" \
         "schemas**\` figure for the merged document."
  fi

  if [[ $text =~ \*\*([0-9]+)\ rows\*\* ]]; then
    claim gate-decisions-rows "${BASH_REMATCH[0]}" \
      "${BASH_REMATCH[1]}" "${#adr_row_link[@]}" \
      "$where says \`$decisions\` carries ${BASH_REMATCH[1]} rows; section 5 read\
 ${#adr_row_link[@]} of them."
  else
    fail "$where: the block publishes no \`**N rows**\` figure for \`$decisions\`."
  fi

  report_unclaimed_figures "$where" "$text"
}

check_marked_file gate-table "$overview" check_gate_table_region
close_section gate-table "$gate_table_claims"

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
  printf 'feature-matrix-check: %d compared claims, as section, scope, kind, claimed>fact.\n' \
    "$assertions"
  printf '%s\n' "${ledger[@]}" | sort | sed 's/^/  /'
fi

if (( failures > 0 )); then
  printf 'feature-matrix-check: %d disagreement(s) over %d compared claims.\n' \
    "$failures" "$assertions" >&2
  exit 1
fi

printf 'feature-matrix-check: %d claims checked, all true.\n' "$assertions"
printf '  self-check: %d, rows: %d, merged-document: %d, milestones: %d, adr-index: %d,' \
  "${section_counts[self-check]}" "${section_counts[rows]}" \
  "${section_counts[merged-document]}" \
  "${section_counts[milestones]}" "${section_counts[adr-index]}"
printf ' openapi-totals: %d, guard-fixtures: %d, capability-claims: %d,' \
  "${section_counts[openapi-totals]}" "${section_counts[guard-fixtures]}" \
  "${section_counts[capability-claims]}"
printf ' quotations: %d, debt-register: %d, gate-table: %d,' \
  "${section_counts[quotations]}" "${section_counts[debt-register]}" \
  "${section_counts[gate-table]}"
printf ' dependencies: %d over %d named manifests.\n' \
  "${section_counts[dependencies]}" "${#manifests[@]}"
printf '  %s: %d rows, %d COMPLETE, %d in scope, %d%% delivered.\n' \
  "$matrix" "$rows" "$complete" "$in_scope" "$percent"
printf '  docs/api/openapi.json: %d paths, %d operations, %d schemas;' \
  "$doc_paths" "$doc_ops" "$doc_schemas"
printf ' X-Kui-Principal on %s operations over %s paths.\n' \
  "${header_ops[X-Kui-Principal]:-no}" "${header_paths[X-Kui-Principal]:-no}"
printf '  %s: %d rows over %d ADRs in docs/adr.\n' \
  "$decisions" "${#adr_row_link[@]}" "${#adr_files[@]}"
