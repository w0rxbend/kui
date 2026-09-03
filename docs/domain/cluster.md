# Cluster Registry and Topology

The bounded context served by `kui-cluster-service` (`docs/domain/context-map.md`). It owns which
clusters exist and how to reach them, the topology snapshot (`describeCluster`, the KRaft quorum,
brokers, broker configuration, log directories), cluster capability probing, and the cluster
configuration wizard and store. It is upstream of every other Kafka-facing context, which reads a
`ClusterProfile` from it rather than from its own configuration.

## Status in M0

**Scaffolded, not modelled.** M0 builds the module structure and nothing else, so that M1 adds
Kafka behaviour into a shape that is already correct instead of inventing one under time pressure.

What exists today:

- `services/cluster/domain` — one value object, `Ping` (a message of 1 to 128 characters and the
  instant the service saw it), and one port, `ClockPort[F]`. `Ping.from` returns
  `Either[DomainError, Ping]`; there is no way to build a `Ping` that breaks its own rule.
- `services/cluster/application` — `PingUseCase[F]`, which returns `F[Either[KuiError, Ping]]`, and
  `CapabilityReportUseCase[F]`, which in M0 answers with a constant: every configured cluster is
  configured, has no cluster-scoped features, and is available.
- `services/cluster/contract` — the cross-compiled Tapir description of
  `GET /internal/v1/ping`, with its DTO and codecs.

`Ping` is not a domain concept and will not survive M1. It exists so that the layering has something
to carry: a rule stated in `domain`, a use case in `application` that returns a type it owns, and a
`contract` module that describes the wire without ever seeing either.

## What arrives in M1

The real model: `ClusterProfile` (configuration plus resolved endpoints and security settings),
`ClusterDescription`, `Broker`, `LogDir` and the `ClusterFeature` set, with the ports
`ClusterAdmin[F]`, `ClusterConfigStore[F]` and `ConnectivityProbe[F]`, and the
`infrastructure` module that adapts them to `libs/kafka` and to the `__kui_config` topic
(ADR-042). Until then this context reads nothing but its static Ciris configuration, and there is
deliberately no `infrastructure` module at all — an empty one is an invitation to fill it.
