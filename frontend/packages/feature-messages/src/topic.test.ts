import { describe, expect, it } from "vitest";

import { topicHealthOf } from "./topic.js";

describe("topic health beside the message browser", () => {
  it("does not invent a healthy state when replication figures are absent", () => {
    expect(topicHealthOf({})).toBe("unknown");
    expect(topicHealthOf({ offlinePartitions: null, outOfSyncReplicas: null })).toBe("unknown");
  });

  it("gives offline partitions precedence over under-replicated replicas", () => {
    expect(topicHealthOf({ offlinePartitions: 1, outOfSyncReplicas: 2 })).toBe("offline");
  });

  it("distinguishes under-replicated and fully synchronized topics", () => {
    expect(topicHealthOf({ offlinePartitions: 0, outOfSyncReplicas: 2 })).toBe(
      "under-replicated",
    );
    expect(topicHealthOf({ offlinePartitions: 0, outOfSyncReplicas: 0 })).toBe("in-sync");
  });
});
