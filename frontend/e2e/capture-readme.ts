/**
 * Captures the real quickstart for the README media gallery.
 *
 * This is deliberately a standalone Playwright program rather than a test. The acceptance suite
 * must stay read-only unless a case explicitly owns a mutation, while this program's output is a
 * set of documentation assets. It still applies the suite's important rules: one real deployed
 * stack, direct/proxied gateway identity checks, accessible locators, browser-error collection and
 * no `networkidle` wait (the shell keeps long-lived streams open).
 *
 * Build and start current-source images first; see `capture:readme` in package.json.
 */
import { spawn } from "node:child_process";
import { mkdir, stat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { chromium, type Page } from "playwright";

const UI = (process.env["KUI_E2E_UI"] ?? "http://127.0.0.1:8090").replace(/\/$/, "");
const API = (process.env["KUI_E2E_API"] ?? "http://127.0.0.1:8080").replace(/\/$/, "");
const CLUSTER = "quickstart";
const STAGING = "staging-eu-01";
const VIEWPORT = { width: 1440, height: 900 } as const;
const HERE = dirname(fileURLToPath(import.meta.url));
const OUTPUT = resolve(HERE, "../../docs/frontend/screenshots/current");
const GIF = resolve(HERE, "../../docs/frontend/screenshots/kui-walkthrough.gif");

type Capture = {
  readonly file: string;
  readonly path: string;
  readonly ready: string;
};

const PAGES: readonly Capture[] = [
  { file: "01-landing.png", path: "/ui/", ready: "[data-testid='overview']" },
  { file: "02-settings.png", path: "/ui/settings", ready: "[data-testid='page-settings']" },
  { file: "03-clusters.png", path: "/ui/clusters", ready: "[data-testid='clusters-head']" },
  { file: "04-manage-clusters.png", path: "/ui/clusters/manage", ready: ".kui-cluster-admin" },
  {
    file: "05-cluster-overview.png",
    path: `/ui/clusters/${CLUSTER}/dashboard/overview`,
    ready: "[data-testid='panel-broker-health']",
  },
  {
    file: "06-cluster-traffic.png",
    path: `/ui/clusters/${CLUSTER}/dashboard/traffic`,
    ready: "[data-testid='panel-throughput']",
  },
  {
    file: "07-cluster-storage.png",
    path: `/ui/clusters/${CLUSTER}/dashboard/storage`,
    ready: "[data-testid='panel-storage']",
  },
  {
    file: "08-brokers.png",
    path: `/ui/clusters/${CLUSTER}/brokers`,
    ready: "[data-testid='brokers']",
  },
  {
    file: "09-broker-detail.png",
    path: `/ui/clusters/${CLUSTER}/brokers/1`,
    ready: "[data-testid='broker-detail']",
  },
  {
    file: "10-topics.png",
    path: `/ui/clusters/${CLUSTER}/topics`,
    // The topic table is paginated and internal/Connect topics can legitimately push any named
    // fixture off its first page. The page landmark plus the generic loading guards in `settle`
    // is the stable readiness contract; a specific row is not.
    ready: ".kui-topic-list",
  },
  {
    file: "11-topic-overview.png",
    path: `/ui/clusters/${CLUSTER}/topics/orders.v1`,
    ready: ".kui-topic-overview",
  },
  {
    file: "12-topic-partitions.png",
    path: `/ui/clusters/${CLUSTER}/topics/orders.v1?tab=partitions`,
    ready: ".kui-partitions",
  },
  {
    file: "13-topic-consumers.png",
    path: `/ui/clusters/${CLUSTER}/topics/orders.v1?tab=consumers`,
    ready: ".kui-topic-consumers",
  },
  {
    file: "14-topic-settings.png",
    path: `/ui/clusters/${CLUSTER}/topics/orders.v1?tab=settings`,
    ready: ".kui-topic-config",
  },
  {
    file: "15-messages.png",
    path: `/ui/clusters/${CLUSTER}/topics/orders.v1/messages?seekTo=beginning`,
    ready: ".kui-browse",
  },
  {
    file: "16-message-tracker.png",
    path: `/ui/clusters/${CLUSTER}/messages/track`,
    ready: ".kui-track",
  },
  {
    file: "17-consumer-groups.png",
    path: `/ui/clusters/${CLUSTER}/consumer-groups`,
    ready: "[data-testid='consumer-groups']",
  },
  {
    file: "18-consumer-group-detail.png",
    path: `/ui/clusters/${CLUSTER}/consumer-groups/analytics-indexer`,
    ready: "[data-testid='consumer-group-detail']",
  },
  {
    file: "19-alerts.png",
    path: `/ui/clusters/${CLUSTER}/alerts`,
    ready: "[data-testid='alerts-feed']",
  },
  {
    file: "20-connect.png",
    path: `/ui/clusters/${CLUSTER}/connect`,
    ready: "[data-testid='connector']",
  },
  {
    file: "21-ksql.png",
    path: `/ui/clusters/${CLUSTER}/ksql`,
    ready: "[data-testid='ksql-workspace']",
  },
  {
    file: "22-schemas.png",
    path: `/ui/clusters/${CLUSTER}/schemas`,
    ready: `a[href$='/schemas/orders.avro-value']`,
  },
  {
    file: "23-schema-subject.png",
    path: `/ui/clusters/${CLUSTER}/schemas/orders.avro-value`,
    ready: ".kui-subject",
  },
  { file: "24-forbidden.png", path: "/ui/forbidden", ready: "[data-testid='page-forbidden']" },
  { file: "25-not-found.png", path: "/ui/not-a-real-page", ready: "[data-testid='page-not-found']" },
] as const;

const GIF_FRAMES = [
  "01-landing.png",
  "05-cluster-overview.png",
  "06-cluster-traffic.png",
  "10-topics.png",
  "15-messages.png",
  "26-filter-json.png",
  "28-filter-avro.png",
  "31-copy-controls.png",
  "32-pagination.png",
  "20-connect.png",
] as const;

function fail(message: string): never {
  throw new Error(message);
}

async function json(url: string): Promise<Record<string, unknown>> {
  const response = await fetch(url, { signal: AbortSignal.timeout(5_000) });
  if (!response.ok) fail(`${response.status} from ${url}: ${(await response.text()).slice(0, 300)}`);
  return (await response.json()) as Record<string, unknown>;
}

async function sectionRows(cluster: string, resource: "brokers" | "log-dirs", key: string): Promise<number> {
  try {
    const body = await json(`${API}/api/v1/clusters/${cluster}/${resource}`);
    const section = body[key];
    if (typeof section !== "object" || section === null) return 0;
    const record = section as Record<string, unknown>;
    return record["status"] === "ok" && Array.isArray(record["data"])
      ? record["data"].length
      : 0;
  } catch {
    return 0;
  }
}

async function awaitFirstScrape(clusters: readonly string[]): Promise<void> {
  const deadline = Date.now() + 120_000;
  for (;;) {
    const rows = await Promise.all(
      clusters.flatMap((cluster) => [
        sectionRows(cluster, "brokers", "brokers"),
        sectionRows(cluster, "log-dirs", "logDirs"),
      ]),
    );
    if (rows.every((count) => count > 0)) return;
    if (Date.now() >= deadline) {
      fail(`The quickstart did not finish its first Kafka scrape for ${clusters.join(", ")}.`);
    }
    await new Promise<void>((wake) => setTimeout(wake, 2_000));
  }
}

async function verifyStack(): Promise<void> {
  const [health, direct, proxied] = await Promise.all([
    fetch(`${UI}/healthz`, { signal: AbortSignal.timeout(5_000) }),
    json(`${API}/api/v1/info`),
    json(`${UI}/api/v1/info`),
  ]).catch((cause: unknown) =>
    fail(
      `The current quickstart is not ready at ${UI} and ${API}. Build both images and start the ` +
        `quickstart before capturing. (${String(cause)})`,
    ),
  );
  if (!health.ok) fail(`${UI}/healthz answered ${health.status}.`);

  const commit = (body: Record<string, unknown>): string | undefined => {
    const build = body["build"];
    if (typeof build !== "object" || build === null) return undefined;
    const value = (build as Record<string, unknown>)["gitCommit"];
    return typeof value === "string" ? value : undefined;
  };
  if (commit(direct) === undefined || commit(direct) !== commit(proxied)) {
    fail(`${API} and ${UI}/api are not the same gateway; refusing to capture a mixed stack.`);
  }

  const clusters = await json(`${API}/api/v1/clusters`);
  const section = clusters["clusters"] as
    | { readonly data?: readonly { readonly cluster?: { readonly id?: string } }[] }
    | undefined;
  const ids = (section?.data ?? []).map((entry) => entry.cluster?.id);
  for (const required of [CLUSTER, STAGING]) {
    if (!ids.includes(required)) fail(`The quickstart does not register required fixture ${required}.`);
  }
  await awaitFirstScrape([CLUSTER, STAGING]);

  const topics = await json(`${API}/api/v1/clusters/${CLUSTER}/topics?pageSize=100`);
  const body = JSON.stringify(topics);
  for (const required of [
    "orders.v1",
    "payments.transactions",
    "inventory.stock-levels",
    "audit.log.raw",
    "orders.avro",
    "orders.jsonschema",
    "orders.protobuf",
  ]) {
    if (!body.includes(required)) fail(`The seeded quickstart is missing topic ${required}.`);
  }

  console.log(`Capturing gateway commit ${commit(direct)} from ${UI}.`);
}

async function settle(page: Page, ready: string): Promise<void> {
  await page.locator("[data-testid='nav-drawer']").waitFor({ state: "visible", timeout: 30_000 });
  await page.locator(ready).first().waitFor({ state: "visible", timeout: 30_000 });
  await page.locator("[data-testid='feature-loading']").waitFor({ state: "hidden", timeout: 30_000 });
  await page.locator(".kui-skeleton").first().waitFor({ state: "hidden", timeout: 30_000 });
  await page.evaluate(
    () =>
      new Promise<void>((done) => {
        requestAnimationFrame(() => requestAnimationFrame(() => done()));
      }),
  );
}

async function go(page: Page, path: string, ready: string): Promise<void> {
  const response = await page.goto(`${UI}${path}`, { waitUntil: "domcontentloaded" });
  if (response === null || !response.ok()) {
    fail(`Navigation to ${path} answered ${response?.status() ?? "without a response"}.`);
  }
  await settle(page, ready);
}

async function shot(page: Page, file: string): Promise<void> {
  await page.evaluate(() => window.scrollTo({ top: 0, left: 0, behavior: "instant" }));
  await page.screenshot({ path: resolve(OUTPUT, file), fullPage: false });
  console.log(`captured ${file}`);
}

async function capturePages(page: Page): Promise<void> {
  for (const capture of PAGES) {
    await go(page, capture.path, capture.ready);
    await shot(page, capture.file);
  }
}

async function readMessages(page: Page): Promise<void> {
  const response = page.waitForResponse(
    (candidate) => candidate.url().includes("/messages/stream") && candidate.request().method() === "GET",
    { timeout: 30_000 },
  );
  await page.getByRole("button", { name: /^read$/i }).first().click();
  if (!(await response).ok()) fail(`The message stream failed at ${page.url()}.`);
  await page.locator(".kui-browse__phase").filter({ hasText: "Finished" }).waitFor({
    state: "visible",
    timeout: 30_000,
  });
}

async function applyFieldFilter(
  page: Page,
  topic: string,
  path: string,
  value: string,
  serdes = "",
): Promise<void> {
  await go(
    page,
    `/ui/clusters/${CLUSTER}/topics/${encodeURIComponent(topic)}/messages?seekTo=beginning${serdes}`,
    ".kui-browse",
  );
  await page.getByRole("button", { name: "Filter with an expression" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.waitFor({ state: "visible" });
  await dialog.getByLabel("Field path").fill(path);
  await dialog.getByLabel("Compare with").fill(value);
  const registered = page.waitForResponse(
    (response) => response.url().includes("/messages/filters") && response.request().method() === "POST",
  );
  await dialog.getByRole("button", { name: "Use this filter" }).click();
  if (!(await registered).ok()) fail(`The filter registration for ${topic} failed.`);
  await dialog.waitFor({ state: "hidden" });
  await readMessages(page);
  await page.locator(".kui-record").first().waitFor({ state: "visible" });
}

async function expandFirstRecord(page: Page): Promise<void> {
  const row = page.locator(".kui-record").first();
  await row.locator(".kui-record__summary").click();
  await row.getByRole("button", { name: "Copy value", exact: true }).waitFor({ state: "visible" });
}

async function captureMessageStates(page: Page): Promise<void> {
  await applyFieldFilter(page, "payments.transactions", "$.method.type", "card");
  await expandFirstRecord(page);
  await shot(page, "26-filter-json.png");

  await go(
    page,
    `/ui/clusters/${CLUSTER}/topics/audit.log.raw/messages?seekTo=beginning&value=result%3Dsuccess`,
    ".kui-browse",
  );
  await readMessages(page);
  await page.locator(".kui-record").first().waitFor({ state: "visible" });
  await shot(page, "27-filter-string.png");

  await applyFieldFilter(
    page,
    "orders.avro",
    "$.address.city",
    "Krakow",
    "&keySerde=String&valueSerde=SchemaRegistry",
  );
  await expandFirstRecord(page);
  await shot(page, "28-filter-avro.png");

  await applyFieldFilter(
    page,
    "orders.jsonschema",
    "$.shipping.city",
    "Krakow",
    "&keySerde=String&valueSerde=SchemaRegistry",
  );
  await expandFirstRecord(page);
  await shot(page, "29-filter-json-schema.png");

  await applyFieldFilter(
    page,
    "orders.protobuf",
    "$.shipping.city",
    "Krakow",
    "&keySerde=String&valueSerde=SchemaRegistry",
  );
  await expandFirstRecord(page);
  await shot(page, "30-filter-protobuf.png");

  await go(
    page,
    `/ui/clusters/${CLUSTER}/topics/inventory.stock-levels/messages?seekTo=beginning`,
    ".kui-browse",
  );
  await readMessages(page);
  await expandFirstRecord(page);
  for (const label of ["Copy value", "Copy key", "Copy headers"]) {
    await page.getByRole("button", { name: label, exact: true }).first().waitFor({ state: "visible" });
  }
  await shot(page, "31-copy-controls.png");

  await captureLoadingModes(page);
}

async function captureLoadingModes(page: Page): Promise<void> {
  await go(
    page,
    `/ui/clusters/${CLUSTER}/topics/orders.v1/messages?seekTo=beginning&limit=2`,
    ".kui-browse",
  );
  await readMessages(page);
  await page.locator(".kui-record").nth(1).waitFor({ state: "visible" });
  const pager = page.getByRole("navigation", { name: "Message offset pages" });
  await pager.waitFor({ state: "visible" });
  await shot(page, "32-pagination.png");

  const second = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname.endsWith("/messages/stream") && url.searchParams.has("cursor");
  });
  await pager.getByRole("button", { name: "Next offset page" }).click();
  if (!(await second).ok()) fail("The second message page failed.");
  await pager.locator(".kui-browse__offset-range").filter({ hasText: "Page 2" }).waitFor();
  await pager.getByRole("button", { name: "Previous offset page" }).click();
  await pager.locator(".kui-browse__offset-range").filter({ hasText: "Page 1" }).waitFor();

  const mode = page.getByRole("radiogroup", { name: "Message loading mode" });
  await mode.getByRole("radio", { name: "Infinite scroll" }).check();
  await page.waitForFunction(
    () => document.querySelectorAll(".kui-record").length > 4,
    undefined,
    { timeout: 30_000 },
  );
  await shot(page, "33-infinite-scroll.png");
}

async function captureNotConfigured(page: Page): Promise<void> {
  const states: readonly (Capture & { readonly expected?: RegExp })[] = [
    {
      // Staging deliberately shares the quickstart broker and its Kafka metrics source. This is
      // the contrasting configured state; the optional dependencies below are the not-configured
      // examples.
      file: "34-staging-traffic.png",
      path: `/ui/clusters/${STAGING}/dashboard/traffic`,
      ready: "[data-testid='panel-throughput']",
    },
    {
      file: "35-staging-schemas-not-configured.png",
      path: `/ui/clusters/${STAGING}/schemas`,
      ready: "[data-testid='feature-notice']",
      expected: /not configured/i,
    },
    {
      file: "36-staging-connect-not-configured.png",
      path: `/ui/clusters/${STAGING}/connect`,
      ready: "[data-testid='feature-notice']",
      expected: /not configured/i,
    },
    {
      file: "37-staging-ksql-not-configured.png",
      path: `/ui/clusters/${STAGING}/ksql`,
      ready: "[data-testid='feature-notice']",
      expected: /not configured/i,
    },
  ];

  for (const capture of states) {
    await go(page, capture.path, capture.ready);
    if (capture.expected !== undefined) {
      await page.getByText(capture.expected).first().waitFor({ state: "visible", timeout: 30_000 });
    }
    await shot(page, capture.file);
  }
}

async function run(command: string, args: readonly string[]): Promise<void> {
  await new Promise<void>((done, reject) => {
    const child = spawn(command, [...args], { stdio: "inherit" });
    child.once("error", reject);
    child.once("exit", (code) => {
      if (code === 0) done();
      else reject(new Error(`${command} exited with ${String(code)}.`));
    });
  });
}

async function buildGif(): Promise<void> {
  const frames = GIF_FRAMES.map((file) => resolve(OUTPUT, file));
  await run("magick", [
    "-delay",
    "140",
    "-loop",
    "0",
    ...frames,
    "-resize",
    "1120x700!",
    "-dither",
    "FloydSteinberg",
    "-colors",
    "128",
    "-layers",
    "OptimizePlus",
    "-strip",
    GIF,
  ]);

  const maximumBytes = 8 * 1024 * 1024;
  if ((await stat(GIF)).size <= maximumBytes) return;
  await run("magick", [
    "-delay",
    "140",
    "-loop",
    "0",
    ...frames,
    "-resize",
    "960x600!",
    "-dither",
    "FloydSteinberg",
    "-colors",
    "96",
    "-layers",
    "OptimizePlus",
    "-strip",
    GIF,
  ]);
}

async function main(): Promise<void> {
  await verifyStack();
  await mkdir(OUTPUT, { recursive: true });

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: VIEWPORT,
    colorScheme: "dark",
    reducedMotion: "reduce",
    locale: "en-US",
    timezoneId: "UTC",
    permissions: ["clipboard-read", "clipboard-write"],
  });
  await context.addInitScript((cluster: string) => {
    localStorage.setItem("kui.theme", "dark");
    localStorage.setItem("kui.accent", "blue");
    localStorage.setItem("kui.density", "comfortable");
    localStorage.setItem("kui.cluster.current", cluster);
  }, CLUSTER);

  const page = await context.newPage();
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(`pageerror: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() !== "error") return;
    const text = message.text();
    if (text.startsWith("Failed to load resource: the server responded with a status of")) return;
    errors.push(`console.error: ${text}`);
  });

  try {
    await capturePages(page);
    await captureMessageStates(page);
    await captureNotConfigured(page);
    if (errors.length > 0) fail(`The browser reported errors:\n${errors.join("\n")}`);
  } finally {
    await context.close();
    await browser.close();
  }

  await buildGif();
  console.log(`Captured ${PAGES.length + 12} screenshots in ${OUTPUT}.`);
  console.log(`Built ${GIF}.`);
}

await main();
