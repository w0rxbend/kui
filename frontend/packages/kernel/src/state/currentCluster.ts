/**
 * Which cluster the user is looking at.
 *
 * Almost every request in KUI is about one cluster, and threading it through every component would
 * put a parameter on every signature for a value that changes once a session.
 *
 * ## Why it is remembered, and why the URL still wins
 *
 * The choice is stored in this browser, so a reload comes back to the cluster somebody was working
 * on rather than to whichever one sorts first. But a URL naming a cluster overrides it on load: a
 * link is usually pasted by a colleague, and it has to show the recipient what the sender saw. The
 * shell applies the URL's cluster before anything reads the stored one.
 *
 * ## Storage that throws still yields a working selector
 *
 * `localStorage` is not always there: a private window, cleared site data, a browser configured to
 * block it, and — the one that surprises people — a `SecurityError` thrown by the *accessor itself*
 * under some embedding contexts. Every read and write is wrapped, and a failure degrades to an
 * in-memory selection that works for the life of the tab. Losing the preference is a small cost;
 * failing to start is not.
 */
import { createSignal, type Accessor } from "solid-js";

/** The `localStorage` key. Namespaced, because a KUI deployment may share an origin. */
export const CurrentClusterStorageKey = "kui.cluster.current";

export type CurrentCluster = {
  readonly selected: Accessor<string | undefined>;
  readonly select: (cluster: string | undefined) => void;
};

export type CurrentClusterOptions = {
  /** Where the choice is kept. `undefined` for a selection that lives only in this tab. */
  readonly storage?: Storage | undefined;
};

export function createCurrentCluster(options: CurrentClusterOptions = {}): CurrentCluster {
  const storage = options.storage;
  const [selected, setSelected] = createSignal<string | undefined>(read(storage));

  return {
    selected,
    select: (cluster) => {
      setSelected(cluster);
      write(storage, cluster);
    },
  };
}

function read(storage: Storage | undefined): string | undefined {
  if (storage === undefined) return undefined;
  try {
    const stored = storage.getItem(CurrentClusterStorageKey);
    // An empty or absent value is "no cluster chosen", which the whole application already handles,
    // rather than a value that would have to be rejected somewhere.
    return stored !== null && stored.length > 0 ? stored : undefined;
  } catch {
    return undefined;
  }
}

function write(storage: Storage | undefined, cluster: string | undefined): void {
  if (storage === undefined) return;
  try {
    if (cluster === undefined) storage.removeItem(CurrentClusterStorageKey);
    else storage.setItem(CurrentClusterStorageKey, cluster);
  } catch {
    // The selection still works for this tab; only its persistence is lost.
  }
}

/**
 * Picks the cluster for a user who has no choice to make.
 *
 * Every cluster-scoped destination has a cluster id in its URL, so the navigation leaves those
 * entries out until a cluster is chosen. That rule is right — an entry whose link would be
 * `/ui/clusters//topics` is a dead link, and an empty path segment collapses so the address matches
 * no route — but it is unfriendly in the deployment most people start with: somebody running the
 * quickstart has exactly one cluster, has never been asked to choose anything, and sees a navigation
 * with no Topics in it and nothing on screen saying that picking the only entry in the switcher is
 * what makes the rest of the application appear.
 *
 * So: exactly one cluster and nothing chosen means choose it. With two or more it stays unchosen,
 * because then it really is a choice and guessing it would put an operator on a cluster they did not
 * pick. An existing choice is never overridden — this only ever fills in a blank.
 */
export function soleClusterChoice(
  clusters: readonly { readonly id: string }[],
  chosen: string | undefined,
): string | undefined {
  if (chosen !== undefined) return undefined;
  return clusters.length === 1 ? clusters[0]!.id : undefined;
}
