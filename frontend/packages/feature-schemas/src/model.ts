/**
 * The schema registry screens' rules, with no DOM under them.
 *
 * ## Why these four live together
 *
 * Every one of them is a decision about what a *row* says, and a row is where this feature's whole
 * value sits. The subject list used to draw a name and nothing else, which is why a wire change
 * that replaced the names with objects rendered `[object Object]` in a link for a day: there was
 * nothing on the row that a test could be wrong about. A row that carries facts is a row whose
 * facts can be asserted, and these are the assertions.
 *
 * The rule they share: a fact the registry did not give is said in words, never drawn as a zero and
 * never drawn as the default the protocol happens to have. `versionCount` absent is not "0
 * versions" — a subject with no versions cannot exist — and a compatibility level absent is not
 * `BACKWARD`, which is merely what Confluent ships with.
 */
import type { Compatibility, CompatibilityLevel, SubjectRow } from "./data.js";

/**
 * The tone a format badge takes.
 *
 * The design asks for `AVRO` blue, `JSON` teal and `PROTO` amber. Those are the kernel's `info`,
 * `success` and `warning` tags — mapped onto tones that already exist rather than onto three new
 * custom properties, because the tone is decoration and the word inside the badge is the fact. A
 * format nobody here has heard of is `neutral` and still shows its own name: the registry knows
 * about schema languages this browser does not, and inventing a colour for one would say more than
 * we know.
 */
export function formatTone(format: string | undefined): "info" | "success" | "warning" | "neutral" {
  const upper = (format ?? "").toUpperCase();
  if (upper === "AVRO") return "info";
  if (upper === "JSON" || upper === "JSONSCHEMA" || upper === "JSON_SCHEMA") return "success";
  if (upper.startsWith("PROTO")) return "warning";
  return "neutral";
}

/**
 * How many versions this subject has, or the sentence saying it is not known.
 *
 * Never `0 versions`. A subject exists because something was registered under it, so a zero here
 * could only ever mean KUI failed to ask — and the endpoint's own documentation says the
 * per-subject call filling this field is allowed not to answer while the row is still returned.
 */
export function versionCountSentence(count: number | undefined): string {
  if (typeof count !== "number") return "version count not read";
  return count === 1 ? "1 version" : `${count.toLocaleString()} versions`;
}

/**
 * Where a subject's compatibility level comes from, in words.
 *
 * This is the distinction the screen exists to make. A subject either has a level of its own or
 * follows the registry's global one, and the second group moves — silently, for every subject in it
 * — the next time anybody changes the global level. Showing the inherited level as though it were
 * the subject's own is the defect: it tells an operator that changing the global setting is safe
 * for this subject when it is the one thing that will change it.
 */
export function levelSourceSentence(compatibility: Compatibility | undefined): string | undefined {
  if (compatibility === undefined) return undefined;
  return compatibility.inherited
    ? "inherited from the registry's global level"
    : "set on this subject";
}

/** The short form of the same fact, for a row caption where the long sentence will not fit. */
export function levelSourceWord(compatibility: Compatibility | undefined): string | undefined {
  if (compatibility === undefined) return undefined;
  return compatibility.inherited ? "inherited" : "on this subject";
}

/**
 * What a row says about its level when the registry did not give one.
 *
 * Not blank, and not `BACKWARD`. A blank reads as "no compatibility checking", which is what `NONE`
 * means and is the most dangerous configuration a registry can have; `BACKWARD` is Confluent's
 * shipping default and stating it here would be a guess presented as a setting.
 */
export const LEVEL_NOT_READ = "level not read";

/**
 * A level the registry named and this browser does not know.
 *
 * Distinct from {@link LEVEL_NOT_READ}: the registry answered, and the answer was a word KUI has no
 * meaning for. Drawing it as though it were one of the seven levels would put an unexplained value
 * in front of somebody about to decide from it; drawing it as "not read" would blame the network
 * for something the registry said.
 */
export const LEVEL_NOT_RECOGNISED = "level KUI does not recognise";

/**
 * The line under the page title.
 *
 * The design's own sentence is `6 subjects. Backward compatible, unlike your last migration.` The
 * aside is kept only while the registry is actually checking something: under `NONE` there is
 * nothing to be wry about, and a joke sitting above a registry that accepts schemas which break
 * every existing reader reads as approval of it.
 */
export function registryVoice(input: {
  readonly subjectCount: number | undefined;
  readonly globalLevel: CompatibilityLevel | null | undefined;
}): string {
  if (typeof input.subjectCount !== "number") {
    return "The registry did not say how many subjects it holds.";
  }
  const subjects =
    input.subjectCount === 1 ? "1 subject" : `${input.subjectCount.toLocaleString()} subjects`;
  if (input.globalLevel === "NONE") {
    return `${subjects}. Nothing is checked: the registry will accept anything.`;
  }
  if (input.globalLevel === null || input.globalLevel === undefined) return `${subjects}.`;
  // "BACKWARD" → "Backward". The registry's own spelling is shouted; a sentence is not.
  const rest = input.globalLevel.slice(1).toLowerCase().replace(/_/g, " ");
  const spoken = input.globalLevel.charAt(0) + rest;
  return `${subjects}. ${spoken} compatible, unlike your last migration.`;
}

/**
 * Why `Register schema` will not press.
 *
 * The design draws the action and the product does not have it: the gateway serves reads, two
 * compatibility settings and a check that registers nothing — there is no endpoint that writes a
 * schema, so nothing this button could call exists. `Button` refuses a disabled control without a
 * reason, and this is that reason. It is stated as an absence in KUI rather than as a permission
 * problem on purpose: an operator told "you may not do this" goes and asks for a grant that would
 * change nothing.
 *
 * When a registration endpoint lands, this constant and the `disabled` it justifies are what gets
 * deleted; nothing else on the screen has to move.
 */
export const REGISTER_UNAVAILABLE_REASON =
  "KUI cannot register a schema yet: the gateway serves no endpoint that writes one. Register it " +
  "with your registry client and it will appear in this list.";

/** The caption under a subject's name: how many versions, and which level it follows and whose. */
export function rowCaption(row: SubjectRow): string {
  return `${versionCountSentence(row.versionCount)} \u00b7 ${levelPhrase(row.compatibility)}`;
}

/** The level half of a row's caption. Three different absences, three different sentences. */
export function levelPhrase(compatibility: Compatibility | undefined): string {
  if (compatibility === undefined) return LEVEL_NOT_READ;
  if (compatibility.level === null) return LEVEL_NOT_RECOGNISED;
  return `${compatibility.level}, ${levelSourceWord(compatibility) ?? ""}`;
}
