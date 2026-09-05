import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { SubjectList } from "./SubjectList.jsx";
import { SubjectPage } from "./SubjectPage.jsx";
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
