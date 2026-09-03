package kui.contracts

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.capability.ReasonCode
import kui.kernel.error.*

/** One part of an aggregated response, which may have failed without failing the whole response.
  *
  * A cluster dashboard asks five services for five things. If the schema registry is down, the honest answer
  * is not a 500 — the other four sections are fine and the user can still work. It is also not an empty list,
  * which silently claims there are no schemas. It is "this section is unavailable, for this reason, since
  * this time", rendered as a placeholder in the panel that would have held it (`ARCHITECTURE.md` §6).
  *
  * `Stale` is the case that earns the type: the gateway still has the last snapshot it fetched, so showing it
  * — clearly marked, with the time it was fetched — is more useful than showing nothing. `Forbidden` and
  * `NotConfigured` are not failures at all, and a client must not render them as errors: one means "you may
  * not see this", the other "this deployment has no such thing".
  */
enum Section[+A] {
  case Ok[A](data: A, fetchedAt: Instant) extends Section[A]
  case Stale[A](data: A, fetchedAt: Instant, reason: ReasonCode) extends Section[A]
  case Unavailable(reason: ReasonCode, message: String, since: Option[Instant]) extends Section[Nothing]
  case Forbidden extends Section[Nothing]
  case NotConfigured extends Section[Nothing]

  /** The `status` discriminator. Contract, like `CapabilityState`'s. */
  def status: String = this match {
    case Ok(_, _) => "ok"
    case Stale(_, _, _) => "stale"
    case Unavailable(_, _, _) => "unavailable"
    case Forbidden => "forbidden"
    case NotConfigured => "not_configured"
  }

  /** The data, when there is any. `Stale` counts: it is real data, just old. */
  def toOption: Option[A] = this match {
    case Ok(data, _) => Some(data)
    case Stale(data, _, _) => Some(data)
    case _ => None
  }
}

object Section {

  given [A: {Encoder, Decoder}] => Codec[Section[A]] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[String]("status").flatMap {
        case "forbidden" => Right(Forbidden)
        case "not_configured" => Right(NotConfigured)
        case "ok" =>
          for {
            data <- cursor.get[A]("data")
            fetchedAt <- cursor.get[Instant]("fetchedAt")
          } yield Ok(data, fetchedAt)
        case "stale" =>
          for {
            data <- cursor.get[A]("data")
            fetchedAt <- cursor.get[Instant]("fetchedAt")
            reason <- cursor.get[ReasonCode]("reason")
          } yield Stale(data, fetchedAt, reason)
        case "unavailable" =>
          for {
            reason <- cursor.get[ReasonCode]("reason")
            message <- cursor.get[String]("message")
            since <- cursor.get[Option[Instant]]("since")
          } yield Unavailable(reason, message, since)
        case other =>
          Left(io.circe.DecodingFailure(s"'$other' is not a section status", cursor.history))
      },
    (section: Section[A]) =>
      section match {
        case Forbidden | NotConfigured => Json.obj("status" -> Json.fromString(section.status))
        case Ok(data, fetchedAt) =>
          Json.obj(
            "status" -> Json.fromString(section.status),
            "data" -> summon[Encoder[A]](data),
            "fetchedAt" -> fetchedAt.asJson
          )
        case Stale(data, fetchedAt, reason) =>
          Json.obj(
            "status" -> Json.fromString(section.status),
            "data" -> summon[Encoder[A]](data),
            "fetchedAt" -> fetchedAt.asJson,
            "reason" -> reason.asJson
          )
        case Unavailable(reason, message, since) =>
          Json.obj(
            "status" -> Json.fromString(section.status),
            "reason" -> reason.asJson,
            "message" -> message.asJson,
            "since" -> since.asJson
          )
      }
  )

  /** Tapir cannot see inside a union of five shapes, and a schema that lies is worse than a vague one, so the
    * documented schema is an open object with the discriminator described.
    */
  given [A] => Schema[Section[A]] = Schema
    .any[Section[A]]
    .description(
      "A part of an aggregated response: status is one of ok, stale, unavailable, forbidden, " +
        "not_configured; ok and stale carry data"
    )

  /** Turns a use case's result into a section.
    *
    * The mapping is by failure *case*, not by error code, because two different situations share the code
    * `KUI-UPSTREAM-UNAVAILABLE` — an upstream that cannot be reached and a circuit breaker that is open — and
    * a user interface says different things about them.
    */
  def fromEither[A](result: Either[KuiError, A], at: Instant): Section[A] = result match {
    case Right(value) => Ok(value, at)
    case Left(error) =>
      error match {
        case ApplicationError.Forbidden(_) => Forbidden
        case ApplicationError.Unsupported(_) => NotConfigured
        case InfrastructureError.CircuitOpen(_, since) =>
          Unavailable(ReasonCode.CircuitOpen, error.message, Some(since))
        case InfrastructureError.Timeout(_, _) =>
          Unavailable(ReasonCode.UpstreamTimeout, error.message, Some(at))
        case InfrastructureError.AuthFailed(_) =>
          Unavailable(ReasonCode.UpstreamAuth, error.message, Some(at))
        case InfrastructureError.Unreachable(_, _) | InfrastructureError.Upstream(_, _) =>
          Unavailable(ReasonCode.UpstreamUnavailable, error.message, Some(at))
        case _ => Unavailable(ReasonCode.Unknown, error.message, Some(at))
      }
  }

  given [A] => CanEqual[Section[A], Section[A]] = CanEqual.derived
}
