/**
 * The application, assembled.
 *
 * ## The order of start-up matters
 *
 * The session is fetched before anything mutating can work, because the only place a CSRF token ever
 * comes from is the body of `GET /api/v1/auth/me` and nothing else calls it. When start-up did not,
 * the token stayed absent for the life of the page and every non-`GET` was refused — including the
 * "Retry now" button on the degraded-feature panel, which therefore never worked.
 *
 * The capability stream is opened before the first paint, and nothing waits for it: an empty picture
 * renders as degraded-with-STARTING, which is the honest state — the features are usable and their
 * health has not been established yet.
 *
 * The router is built from the static route table, which is complete before a byte of any feature has
 * been downloaded, so a deep link resolves on the very first pass.
 *
 * ## The two full-screen states, and why their order is the whole rule
 *
 * A gateway that is not answering wins over the sign-in screen, because a sign-in form that cannot
 * reach a server is a form that can only fail, and the unreachable screen is the one that says why
 * and retries. Only when KUI *is* reachable is the sign-in question asked at all — and the default
 * answer to it is no, because authentication is disabled in every deployment until somebody
 * configures an identity provider.
 */
import {
  Show,
  createEffect,
  createMemo,
  createSignal,
  createStore,
  onCleanup,
  onSettled,
} from "solid-js";
import {
  createApiClient,
  createCsrfTokens,
  type CsrfTokens,
  readBootstrap,
  userMessage,
  type ApiError,
  type KuiApiClient,
} from "@kui/api";
import {
  Banner,
  ToastRegion,
  createCapabilities,
  createCurrentCluster,
  createSession,
  notify,
  openEventSource,
  type SseHandle,
  soleClusterChoice,
  type FeatureId,
  type FeatureRegistration,
  type FeatureState,
  KuiProvider,
  type KuiContextValue,
  accentPreference,
  densityPreference,
  themePreference,
} from "@kui/kernel";
import { AppFrame } from "./chrome/AppFrame.jsx";
import { EnvRail, type RailDestination } from "./chrome/EnvRail.jsx";
import { NavDrawer } from "./chrome/NavDrawer.jsx";
import { installSearchShortcut } from "./chrome/searchShortcut.js";
import { Overview } from "./overview/Overview.jsx";
import { fetchOverview, loadingData, toOverviewModel, type OverviewData } from "./overview/load.js";
import { TopBar, type ThemeMode } from "./chrome/TopBar.jsx";
import type { ClusterSummary, Crumb, NavDestination } from "./chrome/types.js";
import { featureRegistry } from "./features/registry.js";
import { FeatureGate } from "./features/FeatureGate.jsx";
import { createHealth, type CallScope } from "./health.js";
import { degradedBanner, StaleBanner } from "./messages.js";
import {
  navigationGroups,
  stillWorking,
  degradedLabels,
  type FeatureStatus,
} from "./nav/navigation.js";
import { ForbiddenPage, GatewayUnreachablePage, NotFoundPage } from "./pages/errorPages.jsx";
import { SignIn } from "./pages/SignIn.jsx";
import { SettingsPage, asPreference } from "./pages/SettingsPage.jsx";
import {
  clusterInUrl,
  createShellRouter,
  landingFor,
  UiPath,
  type ShellRouter,
} from "./routing/routes.jsx";
import { shellPaths } from "./routing/paths.js";
import type { RouteSectionProps } from "@solidjs/router";

export function App() {
  const bootstrap = readBootstrap();
  const origin = window.location.origin;
  /** Where the frontend is mounted, deployment prefix included. */
  const uiPrefix = `${bootstrap.basePath.replace(/\/$/, "")}${UiPath}`;

  const csrf = createCsrfTokens();
  const session = createSession({
    settleCsrf: (token) => csrf.settle(token),
    invalidateCsrf: () => csrf.invalidate(),
  });

  const api: KuiApiClient = createApiClient({
    bootstrap,
    origin,
    csrf,
    // The gateway said the session lapsed. Emptying it here rather than at the call site means no
    // write control survives that moment even for the length of a reload.
    onUnauthorized: () => session.markExpired(),
  });

  const health = createHealth({
    now: () => new Date(),
    schedule: (delayMs, run) => void setTimeout(run, delayMs),
    onRetry: () => void startUp(),
  });

  const report = (scope: CallScope, error: ApiError | undefined): void => {
    if (error === undefined) health.report(scope, "ok");
    // A 403 or a 404 is the gateway *answering*, and answering is the opposite of being unreachable.
    else
      health.report(
        scope,
        error.kind === "unreachable" || error.kind === "timeout" ? "transport-failure" : "answered",
      );
  };

  const capabilities = createCapabilities({
    /*
     * The capability stream waits for the session, like every other call — but it has to do it here
     * rather than in the client, because it is the one request that does not go through the client
     * at all. `openEventSource` uses the browser's native `EventSource`, which the client's
     * middleware never sees, so the gate in `@kui/api` cannot hold it.
     *
     * Left ungated it is usually the *first* request out, since it opens during construction while
     * `startUp` is still queued behind `onSettled`. Cookieless, it makes the gateway mint a session,
     * which `/auth/me` then races — and the loser's CSRF token is the one this client keeps. See
     * `isSessionCall` in `@kui/api` for what that costs.
     */
    openStream: (subscriber) =>
      deferUntilSession(
        () =>
          openEventSource(
            `${bootstrap.apiBase.replace(/\/$/, "")}/capabilities/stream`,
            subscriber,
          ),
        csrf,
      ),
    poll: () => api.get("/api/v1/capabilities", {}),
    notify: (notice) => notify(notice.title, { tone: notice.tone, message: notice.message }),
    schedule: (delayMs, action) => void setTimeout(action, delayMs),
  });

  const cluster = createCurrentCluster({ storage: safeLocalStorage() });
  // A cluster named in the URL wins over the stored selection, and is applied before anything reads
  // it: a pasted link has to show the recipient what the sender saw.
  const fromUrl = clusterInUrl(window.location.pathname, uiPrefix);
  if (fromUrl !== undefined) cluster.select(fromUrl);

  /**
   * The search input, once it exists, and the `⌘K` that focuses it.
   *
   * A plain binding rather than a signal: nothing renders from it, it is only read inside a key
   * handler, and making it reactive would add a dependency edge no computation wants. The listener
   * goes on `document` rather than on the frame, because the shortcut has to work with focus
   * anywhere — including inside a dialog rendered through a portal.
   */
  let searchInput: HTMLInputElement | undefined;
  onSettled(() =>
    installSearchShortcut(() => {
      // `select()` as well as `focus()`: pressing the shortcut with a query already in the box means
      // "search for something else", and leaving the old text with the caret at its end makes the
      // user delete it first. Every command palette and address bar selects.
      searchInput?.focus();
      searchInput?.select();
    }),
  );

  /**
   * The cluster overview's data.
   *
   * It is fetched here rather than in the screen so that `Overview` takes a finished view model and
   * fetches nothing — which is what lets every state of that screen, including the ones that only
   * happen when a service is down, be rendered in a story and a test with no server.
   */
  const [overview, setOverview] = createStore<{ data: OverviewData }>({ data: loadingData() });

  createEffect(
    () => cluster.selected(),
    (selected) => {
      if (selected === undefined) return undefined;
      let cancelled = false;
      void (async () => {
        const next = await fetchOverview(api, selected);
        if (cancelled) return;
        setOverview((draft) => {
          draft.data = next;
        });
      })();
      // Switching cluster while five requests are in flight must not let the old cluster's answers
      // land on the new cluster's screen — the most convincing kind of wrong number there is.
      return () => {
        cancelled = true;
      };
    },
  );

  /**
   * Whether the notifications panel is showing.
   *
   * Owned here rather than by the top bar, so that Escape and a click elsewhere can close it — a
   * panel whose open state lives inside the control that opens it is a panel nothing else can
   * dismiss.
   */
  const [noticesOpen, setNoticesOpen] = createSignal(false);

  const [probing, setProbing] = createSignal<ReadonlySet<string>>(new Set<string>());
  const [probeErrors, setProbeErrors] = createSignal<ReadonlyMap<string, string>>(new Map());

  /**
   * The "Retry now" button's other half: asking the gateway to re-check one service.
   *
   * A second press while the first is outstanding does nothing. Without that, a user watching a slow
   * service can queue up a dozen probes, each of which makes the gateway call an upstream that is
   * already struggling. The recomputed state reaches the navigation through the capability stream
   * like every other transition — nothing here writes into the store, because two writers to one
   * picture is how a picture ends up disagreeing with itself.
   */
  const probe = (service: string): void => {
    if (probing().has(service)) return;
    setProbing(new Set([...probing(), service]));
    setProbeErrors(new Map([...probeErrors()].filter(([key]) => key !== service)));

    void api
      .post("/api/v1/capabilities/{service}/probe", { params: { path: { service } } })
      .then((answer) => {
        setProbing(new Set([...probing()].filter((id) => id !== service)));
        // The capability endpoints are the shell's own, so a failure here is evidence about the
        // gateway itself and is reported as such.
        report("shell", answer.ok ? undefined : answer.error);
        if (!answer.ok) {
          setProbeErrors(new Map([...probeErrors(), [service, userMessage(answer.error)]]));
        }
      });
  };

  /**
   * The start-up calls, re-run by the connectivity tracker's retry.
   *
   * `/auth/me` goes first and **alone**, and everything else follows it. These two used to run in
   * one `Promise.all`, which was wrong twice over.
   *
   * The gateway mints an anonymous session for any API request arriving without a cookie and stamps
   * `Set-Cookie` on the answer, so two cookieless requests mint two sessions and the browser keeps
   * whichever reply lands last. The CSRF token this client keeps is the one `/auth/me` returned, and
   * it belongs to that session — the same session only by luck. Every read then works, because a
   * fresh anonymous session can read what an anonymous session can read, and every *write* is
   * refused with "X-Csrf-Token does not match the session's token".
   *
   * The client now holds every request other than `/auth/me` behind the token gate for exactly this
   * reason (see `isSessionCall` in `@kui/api`), which makes the second problem with `Promise.all`
   * fatal rather than merely subtle: `/auth/settings` would wait for the gate, the gate opens on
   * `session.accept` below, and `session.accept` was waiting for `/auth/settings`. A deadlock, ended
   * only by the gate's ten-second deadline — long enough that the first person to see it reads it as
   * the gateway being slow.
   */
  const startUp = async (): Promise<void> => {
    const me = await api.get("/api/v1/auth/me", {});

    report("shell", me.ok ? undefined : me.error);
    if (me.ok) session.accept(me.value);
    else {
      // Nothing answered, or it answered with a failure. The token gate is still released, so a
      // mutation issued now fails fast with a legible 403 instead of hanging for ever.
      session.accept({
        authType: "unknown",
        csrfToken: "",
        principal: { kind: "anonymous", name: "anonymous" },
      });
    }

    // Only now, with the session established and the gate open, does anything else go out.
    const settings = await api.get("/api/v1/auth/settings", {});
    session.acceptSettings(settings.ok ? settings.value : undefined);
  };

  /*
   * The capability stream is opened during construction, not in `onSettled`.
   *
   * `capabilities.start()` builds a reactive root to watch the connection, and Solid 2 forbids
   * creating reactive primitives inside an effect or an owner-backed `onSettled`: it raises
   * `PRIMITIVE_IN_FORBIDDEN_SCOPE`. The development build enforces that and the production build
   * compiles the check away, so the violation ran unnoticed in the browser and failed the moment
   * anything mounted `App` under the development renderer — the worst way round, because the shape
   * that is wrong is the shape that ships.
   *
   * Opening it here is also what the start-up order already asked for: nothing waits for the
   * picture, so there is nothing to gain by waiting for the first paint before asking for it.
   */
  capabilities.start();
  onCleanup(() => capabilities.stop());

  onSettled(() => {
    void startUp();
  });

  /**
   * What the shell currently knows about one feature.
   *
   * Declared *before* the memo that calls it, and that order is load-bearing rather than tidiness.
   * A `const` arrow function is in its temporal dead zone until the line that assigns it runs, and
   * `createMemo` in Solid 2 computes eagerly when it is created — so a memo written above this
   * binding calls it while it is still uninitialised. That throws `ReferenceError: Cannot access
   * 'stateOf' before initialization` inside the reactive graph, which Solid reports as
   * `REACTIVITY_HALTED`: the whole tree stops and the application renders a blank page with no
   * failed request and no visibly broken component to point at.
   */
  const stateOf = (registration: FeatureRegistration): FeatureState =>
    capabilities.featureState(
      registration.serviceId,
      registration.requiresCluster ? cluster.selected() : undefined,
      // A control the caller may not use is disabled and explains itself rather than failing at the
      // server. Until the session has answered, permission is assumed: refusing everything while
      // `/auth/me` is in flight would flash a forbidden navigation on every load.
      session.identity() === undefined ||
        session.permits(
          registration.viewAction.resource,
          registration.viewAction.action,
          cluster.selected(),
        ),
    );

  /** Every feature's registration paired with what the shell currently knows about it. */
  const statuses = createMemo<readonly FeatureStatus[]>(() =>
    featureRegistry.map((registration) => ({
      registration,
      state: stateOf(registration),
    })),
  );

  const statusOf = (id: FeatureId): FeatureStatus | undefined =>
    statuses().find((status) => status.registration.id === id);

  const Router: ShellRouter = createShellRouter(bootstrap.basePath, {
    home: () => <Overview model={toOverviewModel(overview.data)} />,
    settings: () => (
      <SettingsPage
        /* The kernel's singletons, handed in rather than reached for. The page takes them as props
           so a test can drive it without sharing `localStorage` with the next suite. */
        theme={asPreference(themePreference)}
        accent={asPreference(accentPreference)}
        density={asPreference(densityPreference)}
        version={bootstrap.buildVersion}
        apiBase={bootstrap.apiBase}
      />
    ),
    forbidden: () => <ForbiddenPage subject="this page" homeHref={Router.paths()} />,
    notFound: () => <NotFoundPage attempted={window.location.pathname} homeHref={Router.paths()} />,
    feature: (id) => () => {
      const status = statusOf(id);
      if (status === undefined)
        return <NotFoundPage attempted={window.location.pathname} homeHref={Router.paths()} />;
      return (
        <FeatureGate
          registration={status.registration}
          state={() => statusOf(id)?.state ?? { kind: "ready" }}
          onProbe={() => probe(status.registration.serviceId)}
          probing={() => probing().has(status.registration.serviceId)}
          probeError={() => probeErrors().get(status.registration.serviceId)}
          stillWorking={() => stillWorking(statuses(), id)}
        />
      );
    },
  });

  /** The clusters the capability registry knows, folded to one row each. */
  const clusters = createMemo<readonly ClusterSummary[]>(() =>
    clusterSummaries(capabilities.states()),
  );

  // A deployment with one cluster is not asking the user to choose. The selection is filled in and
  // the user is *not* navigated anywhere: that would move somebody who had deliberately opened
  // another page, and the only thing missing was the cluster-scoped navigation entries, which appear
  // the moment the selection exists.
  createEffect(
    () => soleClusterChoice(clusters(), cluster.selected()),
    (only) => {
      if (only !== undefined) cluster.select(only);
    },
  );

  const groups = createMemo(() =>
    navigationGroups({
      features: statuses(),
      landingFor: (registration, chosen) => landingFor(Router, registration.id, chosen),
      cluster: cluster.selected(),
      shellDestinations: shellDestinations(Router),
    }),
  );

  /**
   * What every feature is handed.
   *
   * Built once and shared, but every field that can change is a *function* — `cluster()` and
   * `permits()` are called at the moment a feature needs them, so switching cluster from the rail
   * or having the session settle reaches a mounted screen. Handing over values would freeze both at
   * first render, and the symptom is a screen still showing the previous cluster's topics.
   *
   * `report` narrows the shell's three-way health signal to the one bit a feature can honestly
   * supply: the call failed, or it did not. Deciding whether a failure means "the gateway is down"
   * or "this upstream is down" needs `ApiError.kind`, which is the shell's job — a feature that
   * guessed would put the whole product behind the gateway-unreachable screen because one topic
   * list timed out.
   */
  const featureContext: KuiContextValue = {
    api,
    cluster: () => cluster.selected(),
    permits: (action, name) =>
      session.identity() === undefined ||
      session.permits(action.resource, action.action, cluster.selected(), name),
    paths: shellPaths(Router),
    report: (scope, failed) => health.report(scope, failed ? "answered" : "ok"),
  };

  const banner = createMemo<string | undefined>(() =>
    capabilities.stale() ? StaleBanner : degradedBanner(degradedLabels(statuses())),
  );

  return (
    <Router>
      {(route: RouteSectionProps) => (
        <KuiProvider value={featureContext}>
          <AppFrame
            rail={
              <EnvRail
                environments={clusters()}
                currentId={cluster.selected()}
                onSelect={(id) => cluster.select(id)}
                destinations={railDestinations(Router)}
                homeHref={Router.paths()}
                accountName={session.signedIn() ? session.identity()?.principal.name : undefined}
              />
            }
            drawer={
              <NavDrawer
                groups={groups()}
                currentId={currentFeatureId(route.location.pathname, uiPrefix)}
                cluster={clusters().find((entry) => entry.id === cluster.selected())}
                /* Per-broker disk is not on the overview's model yet, so the meter is told nothing and
                 draws its "not known" rendering — a neutral track and a sentence, never a zero.
                 Wiring it to real figures is the metrics work, not the chrome's. */
              />
            }
            topbar={
              <TopBar
                crumbs={topCrumbs(
                  clusters(),
                  cluster.selected(),
                  route.location.pathname,
                  uiPrefix,
                  Router,
                )}
                /* What the field *searches* is still the features' to supply, so it stays idle. What it
                 no longer does is advertise a shortcut nobody implements: the `⌘K` hint in its corner
                 is bound below, and `inputRef` is how the binding reaches the element. A hint for a
                 key that does nothing teaches the reader that shortcuts here do not work. */
                search={{
                  value: "",
                  onInput: () => undefined,
                  status: "idle",
                  inputRef: (el) => {
                    searchInput = el;
                  },
                }}
                theme={themeMode()}
                notificationsOpen={noticesOpen()}
                onToggleNotifications={() => setNoticesOpen(!noticesOpen())}
                /* There is no notification service yet. The panel therefore opens and says there is
                 nothing, which is the honest answer — and is deliberately not the same rendering as a
                 request that failed. */
                notifications={{ kind: "ready", notices: [] }}
              />
            }
          >
            <Show when={banner()}>
              {(message) => (
                <Banner
                  tone="warning"
                  message={message()}
                  testId="capability-banner"
                  /* A cluster that is not answering must not be dismissible: dismissing it makes every
                   stale number on the page look current. */
                />
              )}
            </Show>

            {route.children}
          </AppFrame>

          <ToastRegion />

          {/* The full-screen states, in the one order that is correct. */}
          <Show when={health.connectivity().kind === "lost"}>
            <GatewayUnreachablePage state={health.connectivity()} onRetry={health.retryNow} />
          </Show>
          <Show when={health.connectivity().kind === "connected" && session.mustSignIn()}>
            <SignIn
              authType={session.settings()?.authType ?? "form"}
              providerLabel={session.settings()?.providerLabel}
              api={api}
              onSignedIn={() => {
                /*
                 * A reload, deliberately, and not a re-fetch.
                 *
                 * Every store in this shell — the permissions, the capability fold, the cluster
                 * list, each feature's own data — was populated as the *anonymous* principal while
                 * the sign-in screen was on top of it. Re-fetching a few of them by hand is a list
                 * somebody will one day fail to keep up to date, and the failure mode is the worst
                 * kind: a signed-in operator looking at what anonymous was allowed to see, with no
                 * indication that anything is missing. A reload cannot get that list wrong.
                 */
                window.location.reload();
              }}
            />
          </Show>
        </KuiProvider>
      )}
    </Router>
  );
}

/**
 * The rail's shortcut glyphs.
 *
 * Only destinations the shell itself owns, because a shortcut whose service is not configured must
 * not be drawn at all — a rail is a set of shortcuts, and a dead shortcut costs the operator the
 * attention it takes to discover it does nothing. The ecosystem glyphs the design shows there join
 * this list when their features exist and their capabilities say so.
 */
function railDestinations(Router: ShellRouter): readonly RailDestination[] {
  return [
    {
      id: "settings",
      label: "Settings",
      icon: "settings",
      href: Router.paths.settings(),
      atFoot: true,
    },
  ];
}

/**
 * The top band's trail: the cluster, then the section.
 *
 * It always begins with the cluster, because this is the *installation* trail — its job is to say
 * which deployment and which cluster you are looking at, which is the question the environment rail
 * answers by colour and this answers in words. An object page adds its own, shorter breadcrumb in
 * the content column; the two are not redundant.
 */
export function topCrumbs(
  clusters: readonly ClusterSummary[],
  selected: string | undefined,
  pathname: string,
  uiPrefix: string,
  Router: ShellRouter,
): readonly Crumb[] {
  if (selected === undefined) return [];
  const name = clusters.find((entry) => entry.id === selected)?.name ?? selected;
  const trail: Crumb[] = [{ label: name, href: Router.paths() }];

  const section = currentFeatureId(pathname, uiPrefix);
  const LABELS: Record<string, string> = {
    clusters: "Brokers",
    topics: "Topics",
    consumers: "Consumers",
    settings: "Settings",
  };
  // "overview" adds nothing: the cluster crumb already links there, and a trail that repeats itself
  // is a trail nobody reads.
  const label = section === undefined ? undefined : LABELS[section];
  if (label !== undefined) trail.push({ label });
  return trail;
}

/**
 * The shell's own destinations.
 *
 * They have no service behind them — they are the frame, not a feature — so they are always
 * reachable, and their hrefs come from the same typed proxy every other link uses.
 */
function shellDestinations(Router: ShellRouter): readonly NavDestination[] {
  return [
    { id: "overview", label: "Overview", icon: "dashboard", href: Router.paths(), state: "ready" },
    {
      id: "settings",
      label: "Settings",
      icon: "settings",
      href: Router.paths.settings(),
      state: "ready",
    },
  ];
}

/**
 * One row per cluster, folded to the *worst* of its services.
 *
 * A cluster whose topic service is fine and whose cluster service is unreachable is not a healthy
 * cluster, and a dot reporting the best of its services would be reassuring and wrong. The name is
 * the one the gateway reported; the id is the fallback, which degrades rather than showing a blank
 * row.
 */
export function clusterSummaries(
  entries: ReadonlyMap<
    string,
    {
      readonly key: { readonly cluster?: string | undefined };
      readonly state: { readonly status: string };
      readonly name?: string | undefined;
    }
  >,
): readonly ClusterSummary[] {
  const worst = new Map<string, { health: ClusterSummary["health"]; name: string }>();

  for (const entry of entries.values()) {
    const id = entry.key.cluster;
    if (id === undefined) continue;
    const health = healthOf(entry.state.status);
    const existing = worst.get(id);
    worst.set(id, {
      health: existing === undefined ? health : worseOf(existing.health, health),
      name: entry.name ?? existing?.name ?? id,
    });
  }

  return [...worst]
    .map(([id, value]) => ({ id, name: value.name, health: value.health }))
    .sort((a, b) => a.name.localeCompare(b.name) || a.id.localeCompare(b.id));
}

const SEVERITY: Record<ClusterSummary["health"], number> = {
  unreachable: 0,
  degraded: 1,
  unknown: 2,
  healthy: 3,
};

function worseOf(
  a: ClusterSummary["health"],
  b: ClusterSummary["health"],
): ClusterSummary["health"] {
  return SEVERITY[a] <= SEVERITY[b] ? a : b;
}

function healthOf(status: string): ClusterSummary["health"] {
  switch (status) {
    case "available":
      return "healthy";
    case "degraded":
      return "degraded";
    case "unavailable":
      return "unreachable";
    default:
      // "not configured" is not a health claim, and neither is a status this build does not know.
      return "unknown";
  }
}

/** Which navigation entry to mark as current, from the address the browser is on. */
export function currentFeatureId(pathname: string, uiPrefix: string): string | undefined {
  const relative = pathname.startsWith(uiPrefix) ? pathname.slice(uiPrefix.length) : pathname;
  const segments = relative.split("/").filter((segment) => segment.length > 0);
  if (segments.length === 0) return "overview";
  if (segments[0] === "settings") return "settings";
  if (segments[0] !== "clusters") return undefined;
  if (segments.includes("topics")) return "topics";
  if (segments.includes("consumer-groups")) return "consumers";
  return "clusters";
}

/**
 * `localStorage`, when there is one.
 *
 * Reaching for it can itself throw — a browser configured to block site data raises on the accessor,
 * not on the call — so even asking the question is wrapped.
 */
function safeLocalStorage(): Storage | undefined {
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

function themeMode(): ThemeMode {
  const attribute = document.documentElement.getAttribute("data-theme");
  return attribute === "light" || attribute === "dark" ? attribute : "auto";
}

/* A placeholder for the one shell-owned screen that is still to come. It renders something honest
 * rather than nothing, because a route that renders nothing is a blank content area and users read a
 * blank page as a broken page. (The overview is no longer among these: it is the real screen now.) */

/**
 * Opens a stream once the session has been established, and stays closable in the meantime.
 *
 * The handle is returned immediately because the caller needs one — `createCapabilities` stores it
 * and calls `close()` on teardown, and a component that unmounts during start-up must not leave a
 * stream to open behind it. So this stands in for the real handle: `close()` before the session
 * settles cancels the opening rather than closing something that does not exist yet.
 *
 * `connection` reports `connecting` while waiting, which is what it is. Reporting `open` would put a
 * green indicator on a stream with no socket, and reporting an error would send somebody to look at
 * a network that is fine.
 */
function deferUntilSession(open: () => SseHandle, csrf: CsrfTokens): SseHandle {
  /*
   * A *signal*, not a plain variable.
   *
   * `connection()` is read inside an effect — that is how the frame's connectivity banner follows
   * the stream — and an accessor that closes over an ordinary variable gives Solid nothing to
   * subscribe to. The effect therefore ran once, read `connecting`, and never ran again: the stream
   * opened, the server sent frames, and the application went on saying "KUI has lost its live
   * connection to the gateway" for the rest of the session. A banner that is wrong in the reassuring
   * direction would be bad; this one was wrong in the alarming direction, which teaches operators to
   * ignore it.
   */
  const [opened, setOpened] = createSignal<SseHandle | undefined>(undefined, { ownedWrite: true });
  let cancelled = false;

  void csrf.waitForToken().then(() => {
    if (!cancelled) setOpened(() => open());
  });

  return {
    connection: () => opened()?.connection() ?? { phase: "connecting" },
    close: () => {
      cancelled = true;
      opened()?.close();
    },
    endMarker: () => opened()?.endMarker(),
  };
}
