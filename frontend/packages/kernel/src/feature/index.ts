/**
 * How a feature is registered and how its chunk is fetched — the two halves of ADR-012.
 *
 * The static half ({@link FeatureRegistration}) is data the shell links against normally. The dynamic
 * half is a bare `import()` behind {@link createLazyModule}, whose whole job is that the wait for it
 * is bounded and its failure is recoverable.
 */
export {
  type FeatureComponent,
  type FeatureId,
  type FeatureRegistration,
  type ServiceId,
} from "./registration.js";

export {
  type LazyModule,
  type LazyModuleOptions,
  type LoadState,
  DefaultLoadTimeoutMs,
  createLazyModule,
  timedOutMessage,
} from "./lazyFeature.js";
