package kui.metrics.infrastructure.prometheus

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Outcome
import cats.effect.std.Supervisor
import cats.effect.syntax.all.*
import cats.effect.{Deferred, Ref, Resource, Temporal}
import cats.syntax.all.*

import kui.kernel.error.{ErrorCode, InfrastructureError, KuiError}

enum QueryCacheAccess {
  case Loaded, Hit, Coalesced
}

enum QueryCacheFreshness {
  case Fresh, Stale
}

final case class CachedQuery[+A](
    value: A,
    access: QueryCacheAccess,
    freshness: QueryCacheFreshness
)

/** One logical lookup, including the cache path even when the physical load failed.
  *
  * Keeping access outside the successful value lets telemetry count an owner miss and its coalesced waiters
  * exactly once without turning cache instrumentation into an effect inside the cache state machine.
  */
final case class QueryCacheLookup[+A](
    result: Either[KuiError, CachedQuery[A]],
    access: QueryCacheAccess,
    freshness: QueryCacheFreshness
)

final case class PrometheusQueryCacheStats(
    entries: Int,
    weightBytes: Long,
    inFlight: Int,
    coalescedWaiters: Int,
    closed: Boolean
)

trait PrometheusQueryCache[F[_], K, V] {
  def getOrLoad(key: K)(load: F[Either[KuiError, V]])(
      isTransient: KuiError => Boolean
  ): F[Either[KuiError, CachedQuery[V]]]

  private[prometheus] def lookup(key: K)(load: F[Either[KuiError, V]])(
      isTransient: KuiError => Boolean
  ): F[QueryCacheLookup[V]]

  def stats: F[PrometheusQueryCacheStats]
}

object PrometheusQueryCache {
  val MaxEntries: Int = 1024

  def resource[F[_]: Temporal, K, V](
      freshTtl: FiniteDuration,
      staleTtl: FiniteDuration,
      maxBytes: Long,
      weigh: V => Long,
      maxEntries: Int = MaxEntries
  ): Resource[F, PrometheusQueryCache[F, K, V]] =
    for {
      state <- Resource.eval(Ref.of[F, State[F, K, V]](State.empty))
      supervisor <- Supervisor[F](await = false)
      cache = new Impl[F, K, V](
        freshTtl,
        staleTtl,
        maxBytes.max(1L),
        maxEntries.max(1),
        weigh,
        state,
        supervisor
      )
      _ <- Resource.make(Temporal[F].unit)(_ => cache.close)
    } yield cache

  final private case class Entry[V](
      value: V,
      weight: Long,
      writtenAt: FiniteDuration,
      lastAccess: Long
  )

  final private case class State[F[_], K, V](
      entries: Map[K, Entry[V]],
      inFlight: Map[K, Flight[F, V]],
      weightBytes: Long,
      sequence: Long,
      closed: Boolean
  )

  private object State {
    def empty[F[_], K, V]: State[F, K, V] =
      State(Map.empty, Map.empty, 0L, 0L, closed = false)
  }

  final private case class Flight[F[_], V](
      gate: Deferred[F, Either[KuiError, CachedQuery[V]]],
      waiters: Int
  )

  sealed private trait Decision[F[_], +V]

  private object Decision {
    final case class Hit[F[_], V](value: V) extends Decision[F, V]
    final case class Join[F[_], V](gate: Deferred[F, Either[KuiError, CachedQuery[V]]]) extends Decision[F, V]
    final case class Load[F[_], V]() extends Decision[F, V]
    final case class Closed[F[_], V]() extends Decision[F, V]
  }

  final private class Impl[F[_]: Temporal, K, V](
      freshTtl: FiniteDuration,
      staleTtl: FiniteDuration,
      maxBytes: Long,
      maxEntries: Int,
      weigh: V => Long,
      state: Ref[F, State[F, K, V]],
      supervisor: Supervisor[F]
  ) extends PrometheusQueryCache[F, K, V] {

    def getOrLoad(key: K)(load: F[Either[KuiError, V]])(
        isTransient: KuiError => Boolean
    ): F[Either[KuiError, CachedQuery[V]]] = lookup(key)(load)(isTransient).map(_.result)

    def lookup(key: K)(load: F[Either[KuiError, V]])(
        isTransient: KuiError => Boolean
    ): F[QueryCacheLookup[V]] =
      Temporal[F].uncancelable { poll =>
        for {
          now <- Temporal[F].monotonic
          candidate <- Deferred[F, Either[KuiError, CachedQuery[V]]]
          decision <- state.modify(register(key, candidate, now))
          result <- decision match {
            case Decision.Hit(value) =>
              lookupOf(
                CachedQuery(value, QueryCacheAccess.Hit, QueryCacheFreshness.Fresh).asRight[KuiError],
                QueryCacheAccess.Hit
              ).pure[F]
            case Decision.Join(running) =>
              poll(running.get).map(result => lookupOf(result, QueryCacheAccess.Coalesced))
            case Decision.Load() =>
              supervisor.supervise(runLoad(key, candidate, load, isTransient)) *>
                poll(candidate.get).map(result => lookupOf(result, QueryCacheAccess.Loaded))
            case Decision.Closed() =>
              lookupOf(cacheClosed.asLeft[CachedQuery[V]], QueryCacheAccess.Loaded).pure[F]
          }
        } yield result
      }

    private def lookupOf(
        result: Either[KuiError, CachedQuery[V]],
        access: QueryCacheAccess
    ): QueryCacheLookup[V] = {
      val accessed = result.map(_.copy(access = access))
      QueryCacheLookup(
        accessed,
        access,
        accessed.toOption.fold(QueryCacheFreshness.Fresh)(_.freshness)
      )
    }

    def stats: F[PrometheusQueryCacheStats] =
      state.get.map(current =>
        PrometheusQueryCacheStats(
          current.entries.size,
          current.weightBytes,
          current.inFlight.size,
          current.inFlight.valuesIterator.map(_.waiters).sum,
          current.closed
        )
      )

    private[prometheus] def close: F[Unit] =
      state.update(_.copy(entries = Map.empty, inFlight = Map.empty, weightBytes = 0L, closed = true))

    private def register(
        key: K,
        candidate: Deferred[F, Either[KuiError, CachedQuery[V]]],
        now: FiniteDuration
    )(current: State[F, K, V]): (State[F, K, V], Decision[F, V]) = {
      val live = prune(current, now)
      if live.closed then (live, Decision.Closed())
      else
        live.entries.get(key) match {
          case Some(entry) if now - entry.writtenAt <= freshTtl =>
            val nextSequence = live.sequence + 1L
            val touched = entry.copy(lastAccess = nextSequence)
            (
              live.copy(entries = live.entries.updated(key, touched), sequence = nextSequence),
              Decision.Hit(entry.value)
            )
          case _ =>
            live.inFlight.get(key) match {
              case Some(running) =>
                val joined = running.copy(waiters = running.waiters + 1)
                (
                  live.copy(inFlight = live.inFlight.updated(key, joined)),
                  Decision.Join(running.gate)
                )
              case None =>
                (
                  live.copy(inFlight = live.inFlight.updated(key, Flight(candidate, waiters = 0))),
                  Decision.Load()
                )
            }
        }
    }

    private def runLoad(
        key: K,
        gate: Deferred[F, Either[KuiError, CachedQuery[V]]],
        load: F[Either[KuiError, V]],
        isTransient: KuiError => Boolean
    ): F[Unit] =
      load.attempt
        .flatMap {
          case Right(result) => finish(key, result, isTransient)
          case Left(_) => finish(key, Left(loadFailed), isTransient)
        }
        .flatMap(result => gate.complete(result).void)
        .guaranteeCase {
          case Outcome.Succeeded(_) => Temporal[F].unit
          case Outcome.Errored(_) => clearAndComplete(key, gate, loadFailed)
          case Outcome.Canceled() => clearAndComplete(key, gate, loadCancelled)
        }

    private def finish(
        key: K,
        result: Either[KuiError, V],
        isTransient: KuiError => Boolean
    ): F[Either[KuiError, CachedQuery[V]]] =
      Temporal[F].monotonic.flatMap { now =>
        state.modify { current =>
          val withoutFlight = prune(current.copy(inFlight = current.inFlight - key), now)
          result match {
            case Right(value) if !withoutFlight.closed =>
              val inserted = insert(withoutFlight, key, value, now)
              (inserted, Right(CachedQuery(value, QueryCacheAccess.Loaded, QueryCacheFreshness.Fresh)))
            case Right(value) =>
              (withoutFlight, Right(CachedQuery(value, QueryCacheAccess.Loaded, QueryCacheFreshness.Fresh)))
            case Left(error) if isTransient(error) =>
              withoutFlight.entries.get(key) match {
                case Some(entry) if now - entry.writtenAt <= staleTtl =>
                  val stale = CachedQuery(entry.value, QueryCacheAccess.Loaded, QueryCacheFreshness.Stale)
                  (withoutFlight, Right(stale))
                case _ => (withoutFlight, Left(error))
              }
            case Left(error) => (withoutFlight, Left(error))
          }
        }
      }

    private def insert(current: State[F, K, V], key: K, value: V, now: FiniteDuration): State[F, K, V] = {
      val weight = weigh(value).max(1L)
      if weight > maxBytes then current
      else {
        val nextSequence = current.sequence + 1L
        val entries = current.entries.updated(key, Entry(value, weight, now, nextSequence))
        evict(
          current.copy(
            entries = entries,
            weightBytes = totalWeight(entries),
            sequence = nextSequence
          )
        )
      }
    }

    private def evict(current: State[F, K, V]): State[F, K, V] =
      if current.entries.size <= maxEntries && current.weightBytes <= maxBytes then current
      else
        current.entries.minByOption(_._2.lastAccess) match {
          case Some((key, _)) =>
            val entries = current.entries - key
            evict(
              current.copy(
                entries = entries,
                weightBytes = totalWeight(entries)
              )
            )
          case None => current.copy(weightBytes = 0L)
        }

    private def prune(current: State[F, K, V], now: FiniteDuration): State[F, K, V] = {
      val retained = current.entries.filter { case (_, entry) => now - entry.writtenAt <= staleTtl }
      if retained.size == current.entries.size then current
      else current.copy(entries = retained, weightBytes = totalWeight(retained))
    }

    private def totalWeight(entries: Map[K, Entry[V]]): Long =
      entries.valuesIterator.foldLeft(0L)((total, entry) => saturatingAdd(total, entry.weight))

    private def saturatingAdd(left: Long, right: Long): Long =
      if left > Long.MaxValue - right then Long.MaxValue else left + right

    private def clearAndComplete(
        key: K,
        gate: Deferred[F, Either[KuiError, CachedQuery[V]]],
        error: KuiError
    ): F[Unit] =
      state.update(current => current.copy(inFlight = current.inFlight - key)) *>
        gate.complete(Left(error)).void
  }

  private val cacheClosed: KuiError =
    InfrastructureError.Remote(ErrorCode.UpstreamUnavailable, "Prometheus query cache is closed", Nil)

  private val loadFailed: KuiError =
    InfrastructureError.Remote(ErrorCode.UpstreamUnavailable, "Prometheus query cache load failed", Nil)

  private val loadCancelled: KuiError =
    InfrastructureError.Remote(
      ErrorCode.UpstreamUnavailable,
      "Prometheus query cache load was cancelled",
      Nil
    )
}
