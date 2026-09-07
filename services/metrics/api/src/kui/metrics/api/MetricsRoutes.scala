package kui.metrics.api

import cats.effect.kernel.Async
import cats.syntax.all.*
import sttp.tapir.server.ServerEndpoint

import kui.metrics.application.MetricsUseCases
import kui.metrics.contract.MetricsEndpoints
import kui.metrics.contract.dto.*

/** The read endpoints, bound to use cases.
  *
  * One rule shapes every route here, and it is the opposite of the schema service's:
  *
  *   - a request naming a cluster KUI has never heard of **fails** with `404 KUI-CLUSTER-NOT-FOUND`, because
  *     the caller followed a link to something that does not exist;
  *   - everything else **succeeds**, carrying a `Section` that says what happened.
  *
  * The difference is deliberate and is about what the two features are for. Schemas are a feature that is
  * either present or hidden, so an unconfigured cluster is a route the browser should never have called and
  * `KUI-UNSUPPORTED` is the honest answer. Metrics cards are on a dashboard beside cards that do work: the
  * page renders either way, and every one of these cards has a written sentence for "not measured". A 4xx
  * would make a card that is behaving exactly as designed indistinguishable from a broken one.
  *
  * ==Nothing here decides anything==
  *
  * The cluster/source decision is [[MetricsUseCases]]'s, and the reading-to-section decision is
  * [[MetricsMapping]]'s. This module renames fields.
  */
object MetricsRoutes {

  def apply[F[_]: Async](
      metrics: MetricsUseCases[F],
      secured: MetricsApi.Securing[F]
  ): List[ServerEndpoint[Any, F]] =
    List(
      throughputRoute(metrics, secured),
      latencyRoute(metrics, secured),
      requestHandlersRoute(metrics, secured),
      producersRoute(metrics, secured),
      recordSizeRoute(metrics, secured)
    )

  private def throughputRoute[F[_]: Async](
      metrics: MetricsUseCases[F],
      secured: MetricsApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(MetricsEndpoints.throughput) { _ => (cluster, range) =>
      metrics
        .throughput(cluster, MetricsMapping.range(range))
        .map(_.map(reading => ThroughputResponse(MetricsMapping.sectionOf(reading)(MetricsMapping.series))))
    }

  private def latencyRoute[F[_]: Async](
      metrics: MetricsUseCases[F],
      secured: MetricsApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(MetricsEndpoints.latency) { _ => (cluster, window) =>
      metrics
        .latency(cluster, MetricsMapping.range(window))
        .map(
          _.map(reading => LatencyResponse(MetricsMapping.sectionOf(reading)(MetricsMapping.latencySeries)))
        )
    }

  private def requestHandlersRoute[F[_]: Async](
      metrics: MetricsUseCases[F],
      secured: MetricsApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(MetricsEndpoints.requestHandlers) { _ => cluster =>
      metrics
        .requestHandlers(cluster)
        .map(
          _.map(reading =>
            RequestHandlersResponse(MetricsMapping.sectionOf(reading)(MetricsMapping.requestHandlers))
          )
        )
    }

  private def producersRoute[F[_]: Async](
      metrics: MetricsUseCases[F],
      secured: MetricsApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(MetricsEndpoints.producers) { _ => (cluster, top) =>
      metrics
        .producers(cluster, top)
        .map(
          _.map(reading =>
            TopProducersResponse(MetricsMapping.sectionOf(reading)(MetricsMapping.topProducers))
          )
        )
    }

  private def recordSizeRoute[F[_]: Async](
      metrics: MetricsUseCases[F],
      secured: MetricsApi.Securing[F]
  ): ServerEndpoint[Any, F] =
    secured(MetricsEndpoints.recordSize) { _ => cluster =>
      metrics
        .recordSize(cluster)
        .map(
          _.map(reading => RecordSizeResponse(MetricsMapping.sectionOf(reading)(MetricsMapping.recordSize)))
        )
    }
}
