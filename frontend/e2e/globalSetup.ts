/**
 * Refuses to run against a stack that is not there, and says how to start one.
 *
 * The failure this prevents is a suite that reports twenty failing tests when the real problem is
 * that nothing is listening — twenty screenshots of a connection error, and a reader who has to
 * open one to find out. One message at the top is worth more than all of them.
 */
import { API, UI } from "../playwright.config";

async function reachable(url: string): Promise<boolean> {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(3_000) });
    return response.ok;
  } catch {
    return false;
  }
}

/** The build the process at each address reports. Two different answers mean two processes. */
async function sameGateway(): Promise<boolean> {
  const commit = async (base: string): Promise<string | undefined> => {
    try {
      const response = await fetch(`${base}/api/v1/info`, { signal: AbortSignal.timeout(3_000) });
      const body = (await response.json()) as { build?: { gitCommit?: string } };
      return body.build?.gitCommit;
    } catch {
      return undefined;
    }
  };
  const [direct, throughProxy] = await Promise.all([commit(API), commit(UI)]);
  return direct !== undefined && direct === throughProxy;
}

export default async function globalSetup(): Promise<void> {
  /*
   * Three checks, and the third is the one that matters.
   *
   * The interface answers `/healthz` from nginx without touching anything behind it, and the API
   * answers on its own published port. Both can be true while the *browser's* path is broken: nginx
   * resolves the gateway by service name on the compose network, so a gateway that is down — or a
   * stray development server holding the published port while the container is not running — gives
   * a healthy interface, a healthy-looking API, and a 502 on every request the browser makes.
   *
   * That happened, and it cost twenty minutes of reading screenshots. So the last check goes through
   * the proxy, which is the only path the tests use.
   */
  const [ui, api, proxied] = await Promise.all([
    reachable(`${UI}/healthz`),
    reachable(`${API}/api/v1/health/ready`),
    reachable(`${UI}/api/v1/clusters`),
  ]);

  /*
   * And that the two are the *same* gateway.
   *
   * This check exists because the alternative wasted an afternoon. A development server left running
   * on the API's published port answered every health check cheerfully while the container behind
   * nginx was a different process entirely — with a different configuration and no clusters in it.
   * The tests then seeded through one gateway and asserted against another, and failed with
   * "cluster 'quickstart' does not exist" on a stack where it plainly did.
   *
   * The build's commit is the cheapest thing that distinguishes two processes, and `/api/v1/info`
   * carries it on both paths.
   */
  if (ui && api && proxied && !(await sameGateway())) {
    throw new Error(
      [
        `${API} and ${UI}/api are not the same gateway.`,
        "",
        "Something else is listening on the API's port — most often a `./mill dev` left running",
        "while the container was restarted. The tests would seed through one and assert against the",
        "other. Stop it, and start the stack again:",
        "",
        "  deployment/quickstart/quickstart.sh",
      ].join("\n"),
    );
  }

  if (ui && api && proxied) return;

  const missing = [
    !ui ? `the interface at ${UI}` : "",
    !api ? `the API at ${API}` : "",
    ui && !proxied ? `the API *through* the interface at ${UI}/api/v1 (nginx is up; what it proxies to is not)` : "",
  ]
    .filter((one) => one !== "")
    .join(", and ");

  throw new Error(
    [
      `Cannot reach ${missing}.`,
      "",
      "This suite drives a running stack rather than starting one — starting a Kafka cluster,",
      "seeding it and building two images is what the quickstart script is for, and it is the same",
      "command a person runs:",
      "",
      "  deployment/quickstart/quickstart.sh",
      "",
      "Then: pnpm e2e",
    ].join("\n"),
  );
}
