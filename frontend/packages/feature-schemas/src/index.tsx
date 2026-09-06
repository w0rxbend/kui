/**
 * The schema registry feature.
 *
 * Everything a subject's compatibility level implies is a statement about *tomorrow's* schemas, so
 * the screens' job is to make the current setting impossible to miss and impossible to misread —
 * particularly `NONE`, which means the registry checks nothing at all.
 */
export { SchemaWorkspace, type SchemaWorkspaceProps } from "./SchemaWorkspace.jsx";
export { SubjectList, type SubjectListProps } from "./SubjectList.jsx";
export { SubjectPage, type SubjectPageProps } from "./SubjectPage.jsx";
export { CompatibilityCheck, type CompatibilityCheckProps } from "./CompatibilityCheck.jsx";
export {
  LEVEL_NOT_READ,
  LEVEL_NOT_RECOGNISED,
  REGISTER_UNAVAILABLE_REASON,
  formatTone,
  levelPhrase,
  levelSourceSentence,
  levelSourceWord,
  registryVoice,
  rowCaption,
  versionCountSentence,
} from "./model.js";
export {
  COMPATIBILITY_LEVELS,
  checkBlockedReason,
  checkCompatibility,
  checkIsMeaningful,
  proposedSchemaProblem,
  fetchSchema,
  fetchSubjects,
  fetchVersions,
  levelOf,
  setCompatibility,
  type Compatibility,
  type CompatibilityLevel,
  type CompatibilityVerdict,
  type ProposedSchema,
  type SchemaVersion,
  type SubjectListResult,
  type SubjectRow,
} from "./data.js";

export { default } from "./SchemasRoute.jsx";
