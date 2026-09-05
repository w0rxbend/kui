import { describe, expect, it } from "vitest";
import { createShellRouter } from "./routes.jsx";
import { shellPaths } from "./paths.js";

/**
 * Every link a feature can build, asserted against the real router.
 *
 * ## Why these are worth testing when the proxy is already typed
 *
 * The types catch a renamed *segment*. They do not catch two other things, and both have already
 * happened in this file's short history:
 *
 * 1. **A link built from the wrong branch.** `/topics/:topicName` and `/topics/:topicName/messages`
 *    used to be siblings, and the proxy resolves an intersection of call signatures to the *first*
 *    one — so `.messages()` type-checked as absent and the only way to link to the message browser
 *    was to build the string by hand. Nesting fixed it; this suite is what notices if somebody
 *    flattens it again, because the symptom otherwise is a compile error in a different package.
 * 2. **A missing terminating call.** A parameterised node returns another node, and forgetting the
 *    final `()` yields an object where a string was wanted. TypeScript catches that at the
 *    interface boundary — but only because `KuiPaths` says `=> string`, and the day someone widens
 *    that type the compiler goes quiet and every link becomes `[object Object]`.
 *
 * The base path is exercised too. A deployment behind a reverse proxy is the case nobody develops
 * against and everybody eventually runs.
 */
const noop = () => null;
const views = {
  home: noop,
  settings: noop,
  forbidden: noop,
  notFound: noop,
  feature: () => noop,
};

describe("shellPaths", () => {
  const paths = shellPaths(createShellRouter("", views));

  it("builds every link the features need", () => {
    expect(paths.home()).toBe("/ui");
    expect(paths.settings()).toBe("/ui/settings");
    expect(paths.clusters()).toBe("/ui/clusters");
    expect(paths.manageClusters()).toBe("/ui/clusters/manage");

    expect(paths.brokers("prod")).toBe("/ui/clusters/prod/brokers");
    expect(paths.broker("prod", 3)).toBe("/ui/clusters/prod/brokers/3");

    expect(paths.topics("prod")).toBe("/ui/clusters/prod/topics");
    expect(paths.topic("prod", "orders")).toBe("/ui/clusters/prod/topics/orders");
    // The one the flattened route table could not express at all.
    expect(paths.topicMessages("prod", "orders")).toBe("/ui/clusters/prod/topics/orders/messages");
    expect(paths.trackMessages("prod")).toBe("/ui/clusters/prod/messages/track");

    expect(paths.consumerGroups("prod")).toBe("/ui/clusters/prod/consumer-groups");
    expect(paths.consumerGroup("prod", "etl")).toBe("/ui/clusters/prod/consumer-groups/etl");
  });

  it("encodes parameters exactly once", () => {
    // The proxy encodes; a caller must not encode again. `%2F` passed in comes out `%252F`, which
    // resolves to something that does not exist.
    expect(paths.topic("prod", "orders payments v2")).toBe("/ui/clusters/prod/topics/orders%20payments%20v2");
    expect(paths.topic("prod", "at 100%")).not.toContain("%2525");
  });

  it("encodes a slash in a consumer group id, which the proxy does not", () => {
    // The gap this file's header records: the proxy encodes a space and a percent sign but leaves
    // `/` alone, so an unencoded group id would become two path segments and match no route. A
    // Kafka group id is an arbitrary string and job frameworks really do name one after a path.
    const link = paths.consumerGroup("prod", "flink/checkpoint/job-7");
    expect(link).toBe("/ui/clusters/prod/consumer-groups/flink%2Fcheckpoint%2Fjob-7");
    expect(link).not.toContain("%252F");
  });

  it("carries the deployment's base path into every link", () => {
    const behindProxy = shellPaths(createShellRouter("/kui", views));
    expect(behindProxy.home()).toBe("/kui/ui");
    expect(behindProxy.topicMessages("prod", "orders")).toBe("/kui/ui/clusters/prod/topics/orders/messages");
  });

  it("returns strings, not path nodes", () => {
    // The terminating-call mistake, caught as a value rather than as a type: every one of these is
    // a string or the link renders as "[object Object]".
    for (const link of [
      paths.home(),
      paths.broker("prod", 1),
      paths.topic("prod", "t"),
      paths.topicMessages("prod", "t"),
      paths.consumerGroup("prod", "g"),
    ]) {
      expect(typeof link).toBe("string");
    }
  });
});
