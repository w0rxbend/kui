package kui.gateway.application.session

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

import cats.effect.kernel.{Async, Resource, Sync}
import cats.effect.std.AtomicCell
import cats.syntax.all.*

import kui.kernel.Secret
import kui.security.Principal

/** The M0–M5 [[SessionStore]] adapter: everything lives in one process's heap, guarded by one [[AtomicCell]]
  * (ADR-019).
  *
  * ==Why this is correct only for a single gateway replica==
  *
  * A session created on one replica is invisible to another, so horizontal scaling of the gateway waits for
  * the Kafka-backed adapter TD-003 tracks — a compacted topic every replica consumes gives every replica the
  * same view. Nothing about [[SessionStore]]'s interface changes when that adapter arrives; only which
  * implementation `GatewayWiring` constructs.
  *
  * ==The bound, and why it evicts rather than refuses==
  *
  * Anonymous mode issues a session to every browser that visits (ADR-019 §4), with no login step to make
  * flooding the store expensive. [[SessionConfig.maxSessions]] is the backstop: the store never grows past
  * it, and the entry it drops when it is full is the one nobody has touched in longest — the same choice an
  * LRU cache makes, because a session a flood created and never used again is exactly the one that should
  * disappear first, while a session someone is actively using stays.
  */
object InMemorySessionStore {

  private val IdBytes: Int = 32

  final private case class State(
      sessions: Map[SessionId, Session],
      // Most recently touched first. A separate ordering rather than sorting `sessions` by `lastSeenAt` on
      // every eviction, because eviction happens on every `create` once the store is full, and re-sorting a
      // map of ten thousand entries on every request a flood sends is the cost this exists to avoid.
      order: Vector[SessionId]
  )

  private val EmptyState: State = State(Map.empty, Vector.empty)

  /** Builds the store. A `Resource` rather than a bare constructor because M6's Kafka-backed adapter will
    * need one (a consumer to start, a producer to close), and a port's constructor signature should not
    * change shape the day a second adapter arrives.
    */
  def resource[F[_]: Async](config: SessionConfig): Resource[F, SessionStore[F]] =
    Resource.eval(build[F](config))

  private def build[F[_]: Async](config: SessionConfig): F[SessionStore[F]] =
    for {
      cell <- AtomicCell[F].of(EmptyState)
      random <- Sync[F].delay(new SecureRandom())
    } yield new Impl[F](cell, config, random)

  final private class Impl[F[_]: Async](
      cell: AtomicCell[F, State],
      config: SessionConfig,
      random: SecureRandom
  ) extends SessionStore[F] {

    private val idleTimeout: java.time.Duration =
      java.time.Duration.ofNanos(config.idleTimeout.toNanos)

    def create(principal: Principal, now: Instant): F[Session] =
      for {
        id <- randomToken
        secret <- randomToken
        session = newSession(SessionId.unsafe(id), principal, Secret(secret), now)
        _ <- cell.evalUpdate(state => Sync[F].pure(insert(state, session)))
      } yield session

    def get(id: SessionId, now: Instant): F[Option[Session]] =
      cell.evalModify { state =>
        state.sessions.get(id) match {
          case Some(session) if !session.isExpired(now, idleTimeout) =>
            val touched = session.copy(lastSeenAt = now)
            Sync[F].pure((touch(state, touched), Some(touched)))
          case Some(_) =>
            // Expired, but not yet swept. Reported as absent — the caller must not be able to tell "expired"
            // from "never existed", which is the same information a forged id would otherwise leak.
            Sync[F].pure((state, None))
          case None =>
            Sync[F].pure((state, None))
        }
      }

    def rotate(id: SessionId, now: Instant): F[Option[Session]] =
      get(id, now).flatMap {
        case None => none[Session].pure[F]
        case Some(existing) =>
          for {
            newId <- randomToken
            rotated = existing.copy(id = SessionId.unsafe(newId), lastSeenAt = now)
            _ <- cell.evalUpdate { state =>
              Sync[F].pure(insert(remove(state, id), rotated))
            }
          } yield Some(rotated)
      }

    def delete(id: SessionId): F[Unit] =
      cell.evalUpdate(state => Sync[F].pure(remove(state, id)))

    def sweep(now: Instant): F[Int] =
      cell.evalModify { state =>
        val (expired, alive) = state.sessions.partition((_, session) => session.isExpired(now, idleTimeout))
        val next = State(alive, state.order.filter(alive.contains))
        Sync[F].pure((next, expired.size))
      }

    // -----------------------------------------------------------------------------------------------------

    private def newSession(
        id: SessionId,
        principal: Principal,
        secret: Secret[String],
        now: Instant
    ): Session =
      Session(
        id = id,
        principal = principal,
        csrfSecret = secret,
        createdAt = now,
        lastSeenAt = now,
        absoluteExpiry = now.plusMillis(config.absoluteTimeout.toMillis)
      )

    /** Adds a session, evicting the least-recently-touched entry first if the store is already at capacity.
      */
    private def insert(state: State, session: Session): State = {
      val withoutOld = remove(state, session.id)
      val bounded =
        if withoutOld.sessions.sizeIs >= config.maxSessions then evictOldest(withoutOld) else withoutOld
      State(bounded.sessions + (session.id -> session), session.id +: bounded.order)
    }

    private def touch(state: State, session: Session): State =
      insert(remove(state, session.id), session)

    private def remove(state: State, id: SessionId): State =
      State(state.sessions - id, state.order.filterNot(_ == id))

    private def evictOldest(state: State): State =
      state.order.lastOption match {
        case Some(oldest) => State(state.sessions - oldest, state.order.dropRight(1))
        case None => state
      }

    /** 32 random bytes, base64url-encoded without padding — the shape ADR-019 specifies for both the session
      * id and the CSRF secret. `SecureRandom` rather than a PRNG: this value is a bearer credential, and
      * anything predictable about it is a session another browser can guess.
      */
    private def randomToken: F[String] =
      Sync[F].delay {
        val bytes = new Array[Byte](IdBytes)
        random.nextBytes(bytes)
        Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
      }
  }
}
