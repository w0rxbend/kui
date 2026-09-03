# ADR-027 — Cluster state split into per-context snapshots

- Status: Accepted
- Date: 2026-09-03

## Context

Kafbat keeps one `Statistics` object per cluster that snapshots nodes, all topics with
configs and offsets, all consumer groups with committed offsets, Connect states and metrics,
refreshed every 30 s by one scheduler, and serves most list screens from it. In KUI those
resources belong to different services and failure domains.

## Decision

- Each Kafka-facing service owns a per-cluster snapshot of *its* context only, held in a
  `SnapshotCell` (ADR-016) with `status: Initializing | Online | Offline(lastError)`,
  `scrapedAt`, and an incremental update path after its own mutations:
  - cluster: description, controller/quorum, brokers, log dirs, capability set;
  - topic: descriptions, configs, begin/end offsets, name index;
  - consumer: listings, descriptions, committed offsets, end offsets for committed partitions
    (its own bounded `listOffsets`; no cross-service call in the refresh path);
  - connect: connector states per Connect cluster;
  - metrics: scraped broker metrics plus inferred metrics computed from the topic and
    consumer snapshot endpoints (the only cross-service read, 30 s cadence, tolerant of
    `Stale`/`Unavailable`).
- Refresh cadence is per service (`kui.<service>.refreshInterval`, default 30 s) with
  Kafbat's batching knobs forwarded from `ClusterProfile.admin`. A manual refresh endpoint
  exists per resource family; `POST /clusters/{id}/refresh` fans out through the gateway.
- Reads are served from the snapshot with `Section.Stale` when the last refresh failed;
  detail pages re-describe the single resource live.
- The overlap (topic-service and consumer-service both describe topics; cluster-service and
  metrics-service both touch brokers) is accepted; the admin calls are bounded and chunked.
- A shared internal events topic to replace polling is `RESEARCH` for M6+.

## Evidence

- `research/kafbat/architecture.md` F4, F9, F10, D3, D12, open question 6.
- `research/kafka/admin-capabilities.md` DC-D4, DC-D7 ("end offsets from the scrape cache").
- `research/kafbat/api-analysis.md` Finding 3.3, 3.5 (list semantics served from the cache).

## Consequences

- 3–4 partially overlapping scrapes per cluster in distributed mode; one per context in
  all-in-one as well (same code).
- Every list screen has a documented staleness (≤ refresh interval).

## Alternatives rejected

- One "statistics service" all others read from: a utility service with no domain, a single
  point of failure for every list screen.
- No snapshots (live admin calls per request): O(cluster size) per page and Kafka load
  proportional to UI users.

## Reversibility

Medium. Snapshot shapes are internal to each service.
