/**
 * What sits between a feature's route and the feature itself.
 *
 * ## The promise this component keeps
 *
 * ADR-012's central claim is that an unavailable, forbidden or unconfigured feature is **never
 * downloaded**. That is not an optimisation — it is what stops a broken service from also costing
 * every user a failed request and a stalled route. The claim is only true if exactly one place
 * decides when the import starts, and this is that place: the import is triggered when the capability
 * state is `ready` or `degraded`, and at no other time. Clicking a dimmed navigation entry lands here
 * and renders the fallback panel without a byte being fetched.
 *
 * ## And never a blank frame
 *
 * Every combination of capability state and load state maps to something visible. The state machine
 * has a gap in it the moment one does not — the classic one being "the import is in flight", which
 * without a branch of its own renders as an empty content area for as long as the network takes.
 * Users read a blank page as a broken page, and reload, which throws away everything the application
 * had. So loading has a *named* spinner: not "loading…" but "Loading Topics…", because a user on a
 * slow connection otherwise cannot tell whether the thing they clicked is the thing that is loading.
 *
 * ## A failed import is a network failure, not an unhealthy service
 *
 * The difference decides whether retrying is worth the user's time, and it decides what the retry
 * *does*: a failed download retries the download, where an unavailable service asks the gateway to
 * probe it again. Getting that backwards gives the user a button that cannot help.
 */
import { Switch, Match, createEffect, type Accessor } from "solid-js";
import {
  createLazyModule,
  type LoadState,
  Spinner,
  type FeatureComponent,
  type FeatureModule,
  type FeatureRegistration,
  type FeatureState,
  type LazyModuleOptions,
} from "@kui/kernel";
import { FallbackPanel } from "./FallbackPanel.jsx";
import { explanation, loadingLabel, moduleFailed, notConfiguredNotice, notPermitted } from "../messages.js";

export type FeatureGateProps = {
  readonly registration: FeatureRegistration;
  readonly state: Accessor<FeatureState>;
  /** Asks the gateway to re-check the service. The shell owns the call; the panel knows only that somebody wants it retried. */
  readonly onProbe: () => void;
  readonly probing?: Accessor<boolean> | undefined;
  readonly probeError?: Accessor<string | undefined> | undefined;
  readonly stillWorking: Accessor<readonly string[]>;
  /** Test seam for the download's deadline. The product passes nothing. */
  readonly loadOptions?: LazyModuleOptions | undefined;
};

export function FeatureGate(props: FeatureGateProps) {
  const module = createLazyModule<FeatureModule>(
    () => props.registration.load(),
    props.loadOptions ?? {},
  );

  /* The one place the import is started. `load()` is idempotent — ten calls import once — which is
   * what makes it safe to call from a computation that re-runs on every capability change. */
  createEffect(
    () => props.state().kind,
    (kind) => {
      if (kind === "ready" || kind === "degraded") module.load();
    },
  );

  const label = () => props.registration.label;

  return (
    <Switch>
      <Match when={props.state().kind === "not_configured"}>
        <Notice
          label={label()}
          message={notConfiguredNotice()}
        />
      </Match>

      {/* No retry offered: a permission decision does not change because the user pressed a button,
          and a button that cannot help is worse than no button. */}
      <Match when={props.state().kind === "forbidden"}>
        <Notice label={label()} message={notPermitted(label())} />
      </Match>

      <Match when={unavailableOf(props.state())}>
        {(down) => (
          <FallbackPanel
            featureLabel={label()}
            reason={explanation(down(), label()) ?? ""}
            code={down().code}
            since={down().since}
            onRetry={props.onProbe}
            retrying={props.probing?.() ?? false}
            retryError={props.probeError?.()}
            stillWorking={props.stillWorking()}
          />
        )}
      </Match>

      <Match when={true}>
        <Switch>
          <Match when={module.state().kind === "loading" || module.state().kind === "not-loaded"}>
            <Loading label={label()} />
          </Match>

          <Match when={failureOf(module.state())}>
            {(cause) => (
              <FallbackPanel
                featureLabel={label()}
                reason={moduleFailed(label(), cause())}
                /* The retry re-runs the *import*, not a gateway probe. */
                onRetry={module.retry}
                stillWorking={props.stillWorking()}
              />
            )}
          </Match>

          <Match when={loadedOf(module.state())}>
            {(component) => {
              const Feature = component();
              return <Feature />;
            }}
          </Match>

          <Match when={arrivedWithoutRoot(module.state())}>
            <Notice
              label={label()}
              message={`${label()} is part of this build, but its code does not yet provide a screen to show here.`}
            />
          </Match>
        </Switch>
      </Match>
    </Switch>
  );
}

function Loading(props: { readonly label: string }) {
  return (
    <div
      class="kui-shell__feature-loading"
      data-testid="feature-loading"
      /* `role="status"` announces the label once it appears, without stealing focus from wherever
         the user was. */
      role="status"
      aria-live="polite"
    >
      <Spinner />
      <span class="kui-shell__feature-loading-label">{loadingLabel(props.label)}</span>
    </div>
  );
}

function Notice(props: { readonly label: string; readonly message: string }) {
  return (
    <section class="kui-shell__fallback" data-testid="feature-notice" aria-label={props.label}>
      <h1 class="kui-shell__fallback-title">{props.label}</h1>
      <p class="kui-shell__fallback-reason">{props.message}</p>
    </section>
  );
}

/* Narrowing helpers, so each `<Match>` hands its branch a value that is already the right shape.
 * `Show`/`Match` call their child with the truthy value, which is what keeps these branches free of
 * casts and free of a second, unchecked `state.kind === …` test. */

function unavailableOf(state: FeatureState): Extract<FeatureState, { kind: "unavailable" }> | undefined {
  return state.kind === "unavailable" ? state : undefined;
}

type ChunkState = LoadState<FeatureModule>;

function failureOf(state: ChunkState): string | undefined {
  return state.kind === "failed" ? state.cause : undefined;
}

function loadedOf(state: ChunkState): FeatureComponent | undefined {
  return state.kind === "loaded" ? state.value.default : undefined;
}

/** A chunk that arrived without a root component. See `FeatureModule`. */
function arrivedWithoutRoot(state: ChunkState): boolean {
  return state.kind === "loaded" && state.value.default === undefined;
}
