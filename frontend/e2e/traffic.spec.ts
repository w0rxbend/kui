/**
 * The dashboard's Traffic tab, driven against the deployed product.
 *
 * ## Why almost every assertion here is a comparison with the wire
 *
 * M7's exit criterion was satisfiable for two waves by a service that measured nothing, because
 * every clause of it was a *refusal*: with no exporter anywhere in the repository the endpoint
 * answered `not_configured`, which is exactly what the criterion asked to see. A browser suite can
 * fall into the same hole — "the card says it cannot measure throughput" passes on a deployment
 * that measures it perfectly and on one where the code was never written.
 *
 * So these cases ask the gateway what it says and then assert that the screen says the same thing.
 * On a stack with an exporter that means a chart with real bytes in it; on one without, the
 * sentence. Neither branch is skipped and neither can pass in the other's situation, which is the
 * only shape of this test that can tell a working refusal from a missing feature.
 *
 * Everything is selected by role or by visible text, per `shell.spec.ts`'s rule: a test that selects
 * on a `data-testid` asserts that a developer wrote an attribute, and this suite exists to assert
 * that a person can find the thing.
 */
import { test, expect, CLUSTER, type KuiApi } from "./fixtures";

/** One bucket as the metrics service writes it. Every rate is nullable and null means unsampled. */
interface WireBucket {
  readonly startingAt?: string;
  readonly bytesInPerSecond?: number | null;
  readonly bytesOutPerSecond?: number | null;
}

interface WireThroughput {
  readonly throughput: {
    readonly status: string;
    readonly data?: { readonly buckets?: readonly WireBucket[] };
  };
}

/** One step of the latency series. Both percentiles are nullable and null means unsampled. */
interface WireLatencyBucket {
  readonly startingAt?: string;
  readonly produceP99Millis?: number | null;
  readonly produceP99?: number | null;
  readonly fetchP99Millis?: number | null;
  readonly fetchP99?: number | null;
}

/**
 * `latency` is optional because a gateway that does not route this path answers an ADR-034 error
 * envelope rather than a section — which is exactly what it does until W5-01's endpoints land, and
 * what it would do again if the route were ever removed. Read as `undefined`, that falls into each
 * case's failure branch and asserts the card says so, instead of throwing inside the spec and
 * reporting a `TypeError` where a red card is the finding.
 */
interface WireLatency {
  readonly latency?: {
    readonly status: string;
    readonly data?: { readonly buckets?: readonly WireLatencyBucket[] };
  };
}

/**
 * The request-handlers document, as `RequestHandlerDtos.scala` writes it.
 *
 * It used to be declared here as `data.readings[]` — a shape no service has ever sent — so the case
 * below iterated an empty array, asserted nothing, and reported green while the card drew a
 * confident false sentence about the source. The fields are the server's own now, and the cases
 * assert the arrays are **non-empty** before they iterate.
 */
interface WireHandlers {
  readonly requestHandlers?: {
    readonly status: string;
    readonly data?: {
      readonly requestHandlerIdleRatio?: number | null;
      readonly networkProcessorIdleRatio?: number | null;
      readonly purgatory?: readonly { readonly operation?: string; readonly delayedRequests?: number }[];
    };
  };
  readonly "request-handlers"?: WireHandlers["requestHandlers"];
}

/** The top-producers document, as `ProducerDtos.scala` writes it. The same repair as above. */
interface WireProducers {
  readonly producers?: {
    readonly status: string;
    readonly data?: {
      readonly measuredBy?: string;
      readonly topics?: readonly { readonly topic?: string; readonly bytesInPerSecond?: number | null }[];
      readonly internalTopicsExcluded?: number;
    };
  };
}

interface WireRecordSize {
  readonly recordSize?: { readonly status: string; readonly data?: { readonly meanBytes?: number | null } };
  readonly "record-size"?: WireRecordSize["recordSize"];
}

/** How many buckets each window holds, from `ThroughputRange.bucketCount`. */
const BUCKETS = { "24h": 288, "7d": 168, "30d": 120 } as const;

const throughput = async (api: KuiApi, range: string): Promise<WireThroughput> =>
  (await api.get(`/api/v1/clusters/${CLUSTER}/metrics/throughput?range=${range}`)) as WireThroughput;

const latency = async (api: KuiApi, window: string): Promise<WireLatency> =>
  (await api.get(`/api/v1/clusters/${CLUSTER}/metrics/latency?window=${window}`)) as WireLatency;

const measured = (bucket: WireBucket): boolean =>
  typeof bucket.bytesInPerSecond === "number" || typeof bucket.bytesOutPerSecond === "number";

const latencyMeasured = (bucket: WireLatencyBucket): boolean =>
  typeof (bucket.produceP99Millis ?? bucket.produceP99) === "number" ||
  typeof (bucket.fetchP99Millis ?? bucket.fetchP99) === "number";

/**
 * The sentence a card draws for a cluster with no metrics source, exactly as `NotMeasured.tsx`
 * builds it.
 *
 * Retyped here rather than imported, and that is a real cost: this suite runs under Playwright's
 * own tsconfig and does not resolve `@kui/shell`. What stops it drifting is that the fragment
 * asserted is the *middle clause*, which the builder writes once for all five cards — so a change
 * to any card's noun leaves this passing and a change to the shared sentence reddens every case
 * that uses it at once.
 */
const NOT_CONFIGURED = "No metrics source is configured for it";

test.describe("the Traffic tab", () => {
  test("is reachable from the tab strip and marked when it is open", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);

    /*
     * A tab, not a navigation destination (§3.1): the drawer's selected row stays on Dashboard and
     * only the address's last segment moves. The strip was two segments before this wave and the
     * third one is here because the endpoint behind it began answering — the design's own rule is
     * that a tab whose data is not collected does not get drawn.
     */
    await page.getByRole("link", { name: "Traffic" }).click();
    await expect(page).toHaveURL(new RegExp(`/ui/clusters/${CLUSTER}/dashboard/traffic$`));
    await expect(page.getByRole("link", { name: "Traffic" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByTestId("nav-overview")).toHaveAttribute("aria-current", "page");
  });

  test("carries the same stat cards as Overview, and its own last row", async ({ page }) => {
    const cards = ["BROKERS ONLINE", "TOPICS", "PARTITIONS IN SYNC", "PRODUCTION", "CONSUME", "CONSUMER LAG"];

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/overview`);
    for (const label of cards) await expect(page.getByText(label, { exact: true })).toBeVisible();

    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);
    // §4.2: the tab changes the voice line, the last row and the address, and nothing else.
    for (const label of cards) await expect(page.getByText(label, { exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Cluster overview" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Broker health" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Partition health" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Latency · p99" })).toBeVisible();

    /* And the last row is this tab's own. The producers heading is asserted by its stem, because
       what follows the interpunct is the word the *server* used — `topic` on a broker with no
       client quotas configured, `client.id` on one with them — and a suite that pinned either
       would be asserting the deployment rather than the rule. */
    await expect(page.getByRole("heading", { name: /^Top producers/ })).toBeVisible();
    for (const title of ["Message size distribution", "Request handlers"]) {
      await expect(page.getByRole("heading", { name: title })).toBeVisible();
    }
    // `Storage by broker` belongs to the other two tabs.
    await expect(page.getByRole("heading", { name: "Storage by broker" })).toHaveCount(0);
  });

  test("draws what the endpoint actually answered, and never a zero for a gap", async ({ page, api }) => {
    const wire = await throughput(api, "24h");
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    const card = page.locator('[data-testid="panel-throughput"]');
    await expect(page.getByRole("heading", { name: "Throughput" })).toBeVisible();

    if (wire.throughput.status === "ok" || wire.throughput.status === "stale") {
      const buckets = wire.throughput.data?.buckets ?? [];

      /*
       * The axis is the window and not the sample count. A range's bucket count is constant whatever
       * was sampled — `ThroughputRange.bucketCount` — so a quiet hour draws the same axis as a busy
       * one, and this is the browser end of that: the chart's hidden data table has one row per
       * bucket, and there are as many buckets as the range holds.
       */
      expect(buckets.length).toBe(BUCKETS["24h"]);
      await expect(card.locator("table tbody tr")).toHaveCount(buckets.length);

      /*
       * The rule this whole card exists for. A bucket the exporter did not answer for prints the em
       * dash; one that was measured prints its rate, including a measured zero. They must never be
       * the same cell, because as bars they are already the same picture.
       */
      const gap = buckets.findIndex((bucket) => !measured(bucket));
      if (gap >= 0) {
        const cells = card.locator("table tbody tr").nth(gap).locator("td");
        await expect(cells.first()).toHaveText("—");
        await expect(cells.first()).not.toHaveText(/^0 /);

        /* And the same fact in words and in ink, because the bars cannot carry it: the caption
           counts the unsampled steps and the strip under the axis paints them. A reader looking at
           the picture has to be able to see the difference the hidden table spells out. */
        const absent = buckets.filter((bucket) => !measured(bucket)).length;
        await expect(card).toContainText(`${absent} of the ${buckets.length}`);
        await expect(card).toContainText("were never sampled");
        await expect(
          card.locator(".kui-throughput__coverage-run--absent"),
        ).not.toHaveCount(0);
      }

      /* M7's positive half: something on this deployment really is measured. Asserted against the
         wire rather than as a literal, so a quickstart with a different exporter still passes. */
      const anyMeasured = buckets.some(measured);
      if (anyMeasured) {
        const row = buckets.findIndex(measured);
        await expect(card.locator("table tbody tr").nth(row).locator("td").first()).toContainText("/s");
        /* And the legend carries the current rate, which is where §3.1 puts it — the reason the
           plot is allowed no y-axis at all. By list item rather than by text, because "produce" is
           also a column heading in the chart's hidden data table and the two would collide. */
        await expect(card.getByRole("listitem").filter({ hasText: "produce" })).toContainText("/s");
      } else {
        // A source KUI can reach and has sampled nothing from: the axis is drawn and the plot says
        // why it is empty, which is a different picture from the not-configured one below.
        await expect(card).toContainText("Nothing has been sampled in this window yet");
      }
    } else if (wire.throughput.status === "not_configured") {
      /*
       * The refusal, and the reason it is not enough on its own. This branch is the one M7's old
       * criterion could be satisfied by, so what it asserts is not merely that a sentence appears —
       * it is that no axis, no table and no figure appear beside it. An empty plot with a labelled
       * time axis is a claim that the quantity is measured and merely absent right now, and it sends
       * somebody to find an exporter that was never configured.
       */
      await expect(card).toContainText(NOT_CONFIGURED);
      await expect(card.locator("table")).toHaveCount(0);
      await expect(card.locator('[role="img"]')).toHaveCount(0);
      await expect(card).not.toContainText("0 B/s");
      // Never a bare dash where a sentence belongs, either.
      await expect(card).not.toContainText(/^\s*—\s*$/);
    } else {
      // An exporter that is configured and not answering: a failure with the code somebody quotes.
      await expect(card).toContainText(/did not answer|unavailable|KUI-/i);
    }

    /* Whatever the status, the record-size card refuses the distribution the design drew. That
       refusal is a fact about what a broker publishes rather than about this deployment, so it
       holds on a stack where everything else is measured — which is the only way it can be told
       apart from an endpoint nobody wrote. */
    await expect(page.locator('[data-testid="panel-message-sizes"]')).toContainText(
      "publishes no record-size distribution",
    );
    /* By the chart family's own classes rather than by `svg`: `Card` draws its title icon as one,
       so a count of every `svg` would be asserting that the card has no icon. */
    await expect(page.locator('[data-testid="panel-message-sizes"] .kui-histogram')).toHaveCount(0);
    await expect(page.locator('[data-testid="panel-message-sizes"] .kui-plot')).toHaveCount(0);
  });

  test("draws the latency the endpoint answered, and never a zero for a gap", async ({ page, api }) => {
    const wire = await latency(api, "24h");
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    const card = page.locator('[data-testid="panel-latency"]');
    await expect(page.getByRole("heading", { name: "Latency · p99" })).toBeVisible();

    if (wire.latency?.status === "ok" || wire.latency?.status === "stale") {
      const buckets = wire.latency.data?.buckets ?? [];

      /* The axis is the window and not the sample count, exactly as it is for throughput: a range's
         bucket count is constant whatever was sampled, so a quiet hour draws the same axis. */
      expect(buckets.length).toBe(BUCKETS["24h"]);
      await expect(card.locator("table tbody tr")).toHaveCount(buckets.length);

      const gap = buckets.findIndex((bucket) => !latencyMeasured(bucket));
      if (gap >= 0) {
        /* The rule this card exists for. A step nothing sampled prints the em dash; drawn as a
           zero it would say the broker answered instantly, which is the most flattering possible
           rendering of "we were not looking". */
        const cells = card.locator("table tbody tr").nth(gap).locator("td");
        await expect(cells.first()).toHaveText("—");
        await expect(cells.first()).not.toHaveText(/^0/);

        const absent = buckets.filter((bucket) => !latencyMeasured(bucket)).length;
        await expect(card).toContainText(`${absent} of the ${buckets.length}`);
        // And in this card's own words, not the throughput card's.
        await expect(card).toContainText("rather than as zero latency");
      }

      if (buckets.some(latencyMeasured)) {
        const row = buckets.findIndex(latencyMeasured);
        await expect(card.locator("table tbody tr").nth(row).locator("td").first()).toContainText(/ms|s$/);
        /* And the legend carries the current reading, which is where §3.1 puts it — the reason the
           plot is allowed no y-axis labels at all. By list item rather than by text, because
           "produce" is also a column heading in the hidden data table and the two would collide. */
        await expect(card.getByRole("listitem").filter({ hasText: "produce" })).toContainText(/ms|s$/);
      }
    } else if (wire.latency?.status === "not_configured") {
      /* The refusal, and the same reason it is not enough on its own that the throughput case
         gives: what is asserted is not that a sentence appears but that no axis, no table and no
         figure appear beside it. */
      await expect(card).toContainText(NOT_CONFIGURED);
      await expect(card.locator("table")).toHaveCount(0);
      await expect(card.locator('[role="img"]')).toHaveCount(0);
    } else {
      await expect(card).toContainText(/did not answer|unavailable|KUI-/i);
    }
  });

  test("draws a ratio as a ring and a queue length as a count", async ({ page, api }) => {
    const wire = (await api.get(
      `/api/v1/clusters/${CLUSTER}/metrics/request-handlers`,
    )) as WireHandlers;
    const section = wire.requestHandlers ?? wire["request-handlers"];
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    const card = page.locator('[data-testid="panel-request-handlers"]');
    await expect(page.getByRole("heading", { name: "Request handlers" })).toBeVisible();

    if (section?.status === "ok" || section?.status === "stale") {
      const data = section.data;
      const purgatory = data?.purgatory ?? [];

      /*
       * Asserted before anything is iterated, and this is the repair rather than a nicety. The
       * previous version of this case read `data.readings`, a field no service has ever sent, so
       * every loop below ran zero times and the case reported green against a card drawing nothing.
       * A browser case that iterates an empty array has asserted nothing, so the array's emptiness
       * is now the first thing that can fail. The service refuses the whole section when a scrape
       * carried none of the three, so an `ok` document always carries at least one of them.
       */
      const measuredReadings =
        (typeof data?.requestHandlerIdleRatio === "number" ? 1 : 0) +
        (typeof data?.networkProcessorIdleRatio === "number" ? 1 : 0) +
        purgatory.length;
      expect(measuredReadings, `an ok section must carry a reading: ${JSON.stringify(section)}`).toBeGreaterThan(0);

      const ratios: readonly [string, number | null | undefined][] = [
        ["handler-network-idle", data?.networkProcessorIdleRatio],
        ["handler-io-idle", data?.requestHandlerIdleRatio],
      ];
      for (const [id, value] of ratios) {
        const tile = card.locator(`[data-testid="${id}"]`);
        await expect(tile).toBeVisible();
        if (typeof value === "number") {
          /*
           * A ratio in 0..1 arrives from the endpoint and the card multiplies it. A gauge printing
           * `1%` for a broker that is 99.98% idle is what a missing multiply looks like, and that
           * is what this compares — *within five points*, not exactly. These are live readings: the
           * request this spec made and the request the page made are two scrapes apart, and pinning
           * the digit would make the case fail on a broker whose idle ratio moved rather than on a
           * browser that read the wrong field. Five points is far tighter than the factor of a
           * hundred a missing multiply costs.
           */
          await expect(tile.locator(".kui-gauge")).toHaveCount(1);
          const drawn = Number(((await tile.textContent()) ?? "").match(/(\d+)\s*%/)?.[1] ?? NaN);
          expect(drawn, `${id} drew no percentage at all`).not.toBeNaN();
          expect(Math.abs(drawn - value * 100)).toBeLessThanOrEqual(5);
        } else {
          // Served the name and not the value: the plain track and an em dash, never a full ring.
          await expect(tile.locator(".kui-gauge__arc")).toHaveCount(0);
          await expect(tile).toContainText("—");
        }
      }

      /*
       * Every delayed operation the API named has a tile, and no tile names an operation the API did
       * not — which is the drift this case exists for, and is a comparison a live counter cannot
       * make flaky. The depth itself is a queue length that changes several times a second, so what
       * is asserted about the figure is that it *is* a figure, grouped as this product groups
       * thousands, and that it is not a percentage: §3.4 draws "38% PURGATORY" and there is no
       * ceiling to divide a queue length by, so dividing it by an invented one is a fabricated
       * figure.
       */
      const operations = purgatory.map((queue) => (queue.operation ?? "").toLowerCase()).sort();
      const tiles = card.locator('[data-testid^="handler-purgatory-"]');
      await expect(tiles).toHaveCount(operations.length);
      for (const operation of operations) {
        const tile = card.locator(`[data-testid="handler-purgatory-${operation}"]`);
        await expect(tile).toBeVisible();
        await expect(tile.locator(".kui-gauge")).toHaveCount(0);
        await expect(tile).not.toContainText("%");
        const drawn = ((await tile.textContent()) ?? "").replace(/[\s,\u00a0\u202f]/g, "");
        expect(drawn, `${operation} purgatory drew no figure`).toMatch(/\d/);
        await expect(card).toContainText("queue length");
      }
    } else if (section?.status === "not_configured") {
      await expect(card).toContainText(NOT_CONFIGURED);
      await expect(card.locator(".kui-gauge")).toHaveCount(0);
    } else {
      await expect(card).toContainText(/did not answer|unavailable|KUI-/i);
    }
  });

  test("titles the producers card with the word the server used", async ({ page, api }) => {
    const wire = (await api.get(
      `/api/v1/clusters/${CLUSTER}/metrics/producers?top=5`,
    )) as WireProducers;
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    const card = page.locator('[data-testid="panel-top-producers"]');

    if (wire.producers?.status === "ok" || wire.producers?.status === "stale") {
      const data = wire.producers.data;
      const topics = data?.topics ?? [];

      /*
       * §4 draws `Top producers · client.id` and a broker publishes no per-`client.id` byte rate
       * unless quotas are configured — it publishes a per-*topic* one. The heading has to say
       * whichever the server actually measured, because a tile labelled `client.id` over a topic
       * name is the drift wave 5's rule 7 exists to stop. `measuredBy` is the server's own word for
       * it, so the same case passes on a deployment with quotas and on one without.
       */
      const subject = data?.measuredBy === "client.id" ? "client.id" : "topic";
      await expect(page.getByRole("heading", { name: `Top producers · ${subject}` })).toBeVisible();

      if (topics.length === 0) {
        /* An answer and not a failure: the exporter published the family and served no line
           carrying a topic. The card says so in a sentence rather than drawing an empty box, and
           this branch is what stops the one below from being satisfied by a card that drew nothing.
           On the quickstart stack this is the branch a freshly restarted KUI takes for a minute. */
        await expect(card).toContainText("named no producers");
        await expect(card.locator(".kui-progress__fill")).toHaveCount(0);
      } else {
        /*
         * The repair. This used to read `data.entries`, which the service has never sent, so the
         * loop ran zero times, `first` was `undefined` and the heading assertion above was skipped
         * entirely — a case reporting green against a card that had rendered its empty-answer
         * sentence. Every row the API named is now required to be on the screen.
         */
        for (const entry of topics) {
          const name = entry.topic;
          expect(name, `a ranked row must carry a topic: ${JSON.stringify(entry)}`).toBeTruthy();
          await expect(card).toContainText(name ?? "");
          // A named producer whose rate did not arrive keeps its place and says so in words:
          // dropping it shortens a top-five without saying so, and a zero ranks it last on nothing.
          if (entry.bytesInPerSecond === null) await expect(card).toContainText("not measured");
        }
        // As many bars as rows that carried a rate, and no bar for a row that did not.
        const rated = topics.filter((entry) => typeof entry.bytesInPerSecond === "number").length;
        await expect(card.locator(".kui-progress__fill")).toHaveCount(rated);
      }

      /* Kafka's own topics are not ranked, and the card says how many were left out rather than
         showing a silently shortened list. Never a zero: at zero there is no sentence at all. */
      const excluded = data?.internalTopicsExcluded ?? 0;
      if (excluded > 0) await expect(card).toContainText(`${excluded} of Kafka's own internal`);
      else await expect(card.getByTestId("producers-excluded")).toHaveCount(0);
    } else if (wire.producers?.status === "not_configured") {
      await expect(card).toContainText(NOT_CONFIGURED);
      await expect(card.locator(".kui-progress")).toHaveCount(0);
    } else {
      await expect(card).toContainText(/did not answer|unavailable|KUI-/i);
    }
  });

  test("prints a mean record size and refuses the distribution the design drew", async ({ page, api }) => {
    const wire = (await api.get(`/api/v1/clusters/${CLUSTER}/metrics/record-size`)) as WireRecordSize;
    const section = wire.recordSize ?? wire["record-size"];
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    const card = page.locator('[data-testid="panel-message-sizes"]');
    await expect(page.getByRole("heading", { name: "Message size distribution" })).toBeVisible();

    if (section?.status === "ok" || section?.status === "stale") {
      /*
       * The card ADR-052 calls unmeasurable, asserted on the deployment where the throughput card
       * is drawing real bytes — which is the only way this refusal can be told apart from an
       * endpoint nobody wrote. §3.5 draws twelve buckets and three percentile chips; Kafka
       * publishes a mean and nothing else, so the mean is printed and the rest is named as absent
       * rather than spread across twelve columns.
       */
      await expect(card.locator('[data-testid="record-size-mean"]')).toBeVisible();
      if (typeof section.data?.meanBytes !== "number") {
        await expect(card.locator('[data-testid="record-size-mean"]')).toContainText("not measured");
      }
      await expect(card).toContainText("publishes no record-size distribution");
      await expect(card).not.toContainText("p50");
      await expect(card.locator(".kui-histogram, .kui-plot")).toHaveCount(0);
    } else if (section?.status === "not_configured") {
      await expect(card).toContainText(NOT_CONFIGURED);
    } else {
      await expect(card).toContainText(/did not answer|unavailable|KUI-/i);
    }
  });

  test("fills the produce and consume stat cards from the series, or says why not", async ({ page, api }) => {
    const wire = await throughput(api, "24h");
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    const production = page.locator('[data-testid="stat-production"]');
    const consume = page.locator('[data-testid="stat-consume"]');
    const anyMeasured = (wire.throughput.data?.buckets ?? []).some(measured);

    if ((wire.throughput.status === "ok" || wire.throughput.status === "stale") && anyMeasured) {
      /* §3.2's `Produce rate 86.4 MB/s`, from `bytesInPerSecond` — the same broker metric the chart
         below is drawn from, not a rate derived from anything this browser happens to hold. */
      await expect(production).toContainText("/s");
      await expect(consume).toContainText("/s");
    } else {
      /* Words, never `— MB/s`: a dash says the rate is momentarily unreadable, and the truth is
         either that nothing is configured to read it or that nothing has been sampled yet. */
      await expect(production).not.toContainText("—");
      await expect(production).toContainText(/No metrics source|Nothing has been sampled|permission/);
    }
  });

  test("puts the chosen window in the address, so a colleague can be sent one", async ({ page }) => {
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic`);

    /*
     * The selector is a group of real radios, so this is how a person chooses one. The assertion is
     * the address: a range held in component state would draw the same chart and make every link
     * anybody pasted land on the default window, which is the failure the URL is here to prevent.
     */
    /* The label, not the input. `RangeSelector` clips its radios rather than hiding them — clipping
       keeps them in the accessibility tree and in the tab order, which is the whole arrangement —
       so the drawn segment is the label, and the label is what a pointer lands on. */
    await page
      .getByRole("radiogroup", { name: "Throughput range" })
      .getByText("7d", { exact: true })
      .click();
    await expect(page).toHaveURL(/[?&]range=7d/);
    await expect(page.getByRole("radio", { name: "7d" })).toBeChecked();
  });

  test("opens on the window the address names, and asks the server for that one", async ({ page, api }) => {
    const wire = await throughput(api, "30d");
    await page.goto(`/ui/clusters/${CLUSTER}/dashboard/traffic?range=30d`);

    await expect(page.getByRole("radio", { name: "30d" })).toBeChecked();
    if (wire.throughput.status === "ok" || wire.throughput.status === "stale") {
      // Read as well as written: a selector that only wrote to the address would show `30d` over a
      // day of data. 120 rows is the thirty-day window at its own six-hour step.
      const rows = page.locator('[data-testid="panel-throughput"] table tbody tr');
      await expect(rows).toHaveCount(BUCKETS["30d"]);
      expect(wire.throughput.data?.buckets?.length).toBe(BUCKETS["30d"]);
    }
  });

  test("says the same thing about a cluster nobody configured a source for", async ({ page, api }) => {
    /*
     * The other half of M7's criterion, and the half that has to keep working: a second cluster with
     * no `kui.metrics.sources` entry answers `not_configured` on the same deployment, and the screen
     * draws the sentence rather than an empty axis. Skipped rather than failed when the deployment
     * has only one cluster, because the assertion is about a second cluster and not about how many
     * clusters a quickstart happens to run.
     */
    const clusters = (await api.get("/api/v1/clusters")) as {
      readonly clusters?: { readonly data?: readonly { readonly cluster?: { readonly id?: string } }[] };
    };
    const other = (clusters.clusters?.data ?? [])
      .map((entry) => entry.cluster?.id)
      .find((id): id is string => id !== undefined && id !== CLUSTER);
    test.skip(other === undefined, "this deployment runs one cluster; there is no second one to ask");
    if (other === undefined) return;

    const wire = (await api.get(
      `/api/v1/clusters/${other}/metrics/throughput?range=24h`,
    )) as WireThroughput;
    await page.goto(`/ui/clusters/${other}/dashboard/traffic`);

    const card = page.locator('[data-testid="panel-throughput"]');
    if (wire.throughput.status === "not_configured") {
      await expect(card).toContainText(NOT_CONFIGURED);
      await expect(card.locator("table")).toHaveCount(0);
      /* And the same on every other card on the tab, in the same sentence. One unconfigured cluster
         beside one measured cluster on one deployment is what M7's criterion asks for, and it is
         the pair that a refusal-only assertion could never establish. */
      for (const panel of ["panel-latency", "panel-top-producers", "panel-request-handlers"]) {
        await expect(page.locator(`[data-testid="${panel}"]`)).toContainText(NOT_CONFIGURED);
      }
    } else {
      await expect(card.locator("table tbody tr")).toHaveCount(BUCKETS["24h"]);
    }
  });
});
