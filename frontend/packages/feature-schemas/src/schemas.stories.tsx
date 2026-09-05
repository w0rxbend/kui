import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { SubjectList } from "./SubjectList.jsx";
import { SubjectPage } from "./SubjectPage.jsx";
import { CompatibilityCheck } from "./CompatibilityCheck.jsx";
import type { Compatibility, SchemaVersion } from "./data.js";

/**
 * The subject list, whose headline is the registry's global compatibility level.
 *
 * `GlobalNone` is the story to look at. `NONE` means the registry checks nothing at all — it will
 * accept a schema that breaks every existing reader — and it is the setting somebody switches on
 * during an incident and never switches back. It has to be legible as a *warning*, not merely as a
 * value, and it has to be legible without colour.
 */
const listMeta: Meta<typeof SubjectList> = {
  title: "Screens/Schema registry",
  component: SubjectList,
  parameters: { layout: "padded" },
};

export default listMeta;
type ListStory = StoryObj<typeof listMeta>;

const SUBJECTS = [
  "orders.payments.v2-value",
  "orders.payments.v2-key",
  "analytics.pageviews-value",
  "inventory.stock-levels-value",
];

const idle = { kind: "idle" } as const;

const listArgs = {
  subjects: SUBJECTS,
  search: "",
  onSearch: () => undefined,
  page: 1,
  pageSize: 50,
  totalItems: SUBJECTS.length,
  onPage: () => undefined,
  hrefFor: (subject: string) => `#${subject}`,
  onSetGlobal: () => undefined,
  state: idle,
};

const backward: Compatibility = { level: "BACKWARD", inherited: false };

export const Listed: ListStory = {
  args: { ...listArgs, global: backward },
};

/** The registry checks nothing. Said in words, because a colour is not a distinction to everyone. */
export const GlobalNone: ListStory = {
  args: { ...listArgs, global: { level: "NONE", inherited: false } },
};

/**
 * A level the registry reported and this browser does not know.
 *
 * Deliberately not drawn as a level: this value decides whether tomorrow's schema is accepted, and
 * showing an unrecognised word as though it were a setting is worse than admitting it.
 */
export const UnknownLevel: ListStory = {
  args: { ...listArgs, global: { level: null, inherited: false } },
};

/** An operator who may look but not change. The control is absent and its reason is not. */
export const CannotChangeGlobal: ListStory = {
  args: {
    ...listArgs,
    global: backward,
    onSetGlobal: undefined,
    setGlobalDisabledReason: "You do not have permission to change the registry's compatibility level.",
  },
};

/** No registry on this cluster, which is an ordinary state and not a fault. */
export const NotConfigured: ListStory = {
  args: {
    ...listArgs,
    subjects: [],
    totalItems: 0,
    global: undefined,
    failure: {
      message: "This cluster has no schema registry configured, so there are no subjects to list.",
    },
  },
};

/** An empty registry. Not the same screen as a search that matched nothing. */
export const NoSubjects: ListStory = {
  args: { ...listArgs, subjects: [], totalItems: 0, global: backward },
};

export const SearchMatchedNothing: ListStory = {
  args: { ...listArgs, subjects: [], totalItems: 0, global: backward, search: "nothing-like-this" },
};

/* ------------------------------------------------------------------------------------------------
 * One subject
 * ---------------------------------------------------------------------------------------------- */

const SCHEMA: SchemaVersion = {
  subject: "orders.payments.v2-value",
  version: 3,
  // Deliberately different from the version in every fixture here: they are different numbers, and
  // it is this one that a record's header carries.
  id: 41,
  schemaType: "AVRO",
  definition: `{
  "type": "record",
  "name": "Payment",
  "namespace": "orders.payments",
  "fields": [
    { "name": "id", "type": "string" },
    { "name": "amountMinor", "type": "long" },
    { "name": "currency", "type": "string" },
    { "name": "capturedAt", "type": ["null", "long"], "default": null }
  ]
}`,
  references: [],
};

const subjectArgs = {
  subject: "orders.payments.v2-value",
  versions: [3, 2, 1],
  current: SCHEMA,
  listHref: "#subjects",
  hrefForVersion: (version: number) => `#v${version}`,
  onSetCompatibility: () => undefined,
  state: idle,
};

type SubjectStory = StoryObj<typeof SubjectPage>;

/** The level is this subject's own. Changing the registry's global level will not move it. */
export const SubjectWithOwnLevel: SubjectStory = {
  render: (args) => <SubjectPage {...args} />,
  args: { ...subjectArgs, compatibility: { level: "FULL", inherited: false } },
};

/**
 * The level is inherited, and that is the sentence that matters: this subject moves the next time
 * anybody changes the registry's global level.
 */
export const SubjectInheritsLevel: SubjectStory = {
  render: (args) => <SubjectPage {...args} />,
  args: { ...subjectArgs, compatibility: { level: "BACKWARD", inherited: true } },
};

/** A schema built out of others. The references are named so they can be followed. */
export const SubjectWithReferences: SubjectStory = {
  render: (args) => <SubjectPage {...args} />,
  args: {
    ...subjectArgs,
    compatibility: { level: "BACKWARD", inherited: true },
    current: {
      ...SCHEMA,
      references: [
        { name: "orders.common.Address", subject: "orders.common.Address", version: 2 },
        { name: "orders.common.Money", subject: "orders.common.Money", version: 1 },
      ],
    },
  },
};

/* ------------------------------------------------------------------------------------------------
 * Check a schema
 *
 * The panel that answers the question an operator actually has, which is not "what is this
 * subject's compatibility setting" but "will my change be accepted". The four stories below are the
 * four answers it can give, and the two that matter most are `CheckRefusedWithoutReason` and
 * `CheckUnderLevelNone` — both are states where a naive panel shows nothing or shows something
 * cheerful, and both are real responses this gateway gives.
 * ---------------------------------------------------------------------------------------------- */

const PROPOSED = `{
  "type": "record",
  "name": "Payment",
  "namespace": "orders.payments",
  "fields": [
    { "name": "id", "type": "string" },
    { "name": "amountMinor", "type": "string" },
    { "name": "currency", "type": "string" },
    { "name": "channel", "type": "string" }
  ]
}`;

const checkArgs = {
  subject: "orders.payments.v2-value",
  level: "BACKWARD" as const,
  initialSchemaType: "AVRO",
  initialDefinition: PROPOSED,
  onCheck: () => undefined,
  state: idle,
};

type CheckStory = StoryObj<typeof CompatibilityCheck>;

/** Nothing asked yet. The sentence saying the panel registers nothing is the point of this one. */
export const CheckNotRunYet: CheckStory = {
  render: (args) => <CompatibilityCheck {...args} />,
  args: checkArgs,
};

/** The registry would take it. It says so, and says what it compared against. */
export const CheckAccepted: CheckStory = {
  render: (args) => <CompatibilityCheck {...args} />,
  args: {
    ...checkArgs,
    state: { kind: "done", value: { compatible: true, messages: [] } },
  },
};

/**
 * The registry refused it, in its own words.
 *
 * These five messages are the ones a real Confluent registry returned for a schema that changed a
 * field's type and added a required field — reproduced verbatim, braces and all, because the field
 * path and the two type names are what say what to change. The fourth entry carries the whole of
 * the older schema, which is why the block scrolls instead of wrapping.
 */
export const CheckRefused: CheckStory = {
  render: (args) => <CompatibilityCheck {...args} />,
  args: {
    ...checkArgs,
    state: {
      kind: "done",
      value: {
        compatible: false,
        messages: [
          "{errorType:'TYPE_MISMATCH', description:'The type (path '/fields/1/type') of a field in the new schema does not match with the old schema', additionalInfo:'reader type: STRING not compatible with writer type: LONG'}",
          "{errorType:'READER_FIELD_MISSING_DEFAULT_VALUE', description:'The field 'channel' at path '/fields/3' in the new schema has no default value and is missing in the old schema', additionalInfo:'channel'}",
          "{oldSchemaVersion: 3}",
          "{oldSchema: '{\"type\":\"record\",\"name\":\"Payment\",\"namespace\":\"orders.payments\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"amountMinor\",\"type\":\"long\"},{\"name\":\"currency\",\"type\":\"string\"},{\"name\":\"capturedAt\",\"type\":[\"null\",\"long\"],\"default\":null}]}'}",
          "{validateFields: 'false', compatibility: 'BACKWARD'}",
        ],
      },
    },
  },
};

/**
 * Refused, with nothing said about why — which is what the quickstart's Apicurio registry returns,
 * because it words its explanation under a key KUI's registry client does not read.
 *
 * The story exists so that this can be compared with `CheckRefused` side by side: an absence has to
 * be said out loud, never drawn as the empty space where five messages would have been.
 */
export const CheckRefusedWithoutReason: CheckStory = {
  render: (args) => <CompatibilityCheck {...args} />,
  args: {
    ...checkArgs,
    state: { kind: "done", value: { compatible: false, messages: [] } },
  },
};

/**
 * A schema that is not JSON, caught before the round trip.
 *
 * The check button is disabled and carries the parser's own message as its reason, which names the
 * position. The gateway's answer for the same text — "Could not execute compatibility rule on
 * invalid Avro schema" — names nothing and arrives a second later.
 */
export const CheckOfInvalidJson: CheckStory = {
  render: (args) => <CompatibilityCheck {...args} />,
  args: { ...checkArgs, initialDefinition: '{ "type": "record", "name": ' },
};

/**
 * The subject's level is NONE, so the registry would accept anything.
 *
 * The recorded documents show the trap: the verdict for a schema that breaks every reader is
 * byte-for-byte the verdict for the schema already registered. Running the check here would hand
 * somebody a green pill as evidence for a change that is about to break production, so the control
 * is disabled with that as its reason and the warning is above the box rather than below it.
 */
export const CheckUnderLevelNone: CheckStory = {
  render: (args) => <CompatibilityCheck {...args} />,
  args: { ...checkArgs, level: "NONE" },
};

/** The registry did not answer at all. Not the same screen as a refusal, and it does not pretend to be. */
export const CheckFailed: CheckStory = {
  render: (args) => <CompatibilityCheck {...args} />,
  args: {
    ...checkArgs,
    state: {
      kind: "failed",
      message: "The schema registry did not answer within 10 seconds.",
      code: "KUI-UPSTREAM-TIMEOUT",
    },
  },
};
