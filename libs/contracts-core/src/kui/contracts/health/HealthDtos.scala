package kui.contracts.health

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given

/** What one readiness check found.
  *
  * @param name
  *   what was checked, in words an operator can act on — `store-cluster`, `schema-registry`, and not
  *   `check3`. This is the string that appears in a 503 body at three in the morning.
  * @param healthy
  *   whether it passed
  * @param detail
  *   why not, when it did not. `timeout` is the reserved value for a check that exceeded its own budget,
  *   because "it did not answer" and "it answered no" have different causes.
  */
final case class CheckResult(name: String, healthy: Boolean, detail: Option[String])

object CheckResult {

  /** The detail of a check that ran out of time rather than failing. */
  val TimeoutDetail: String = "timeout"

  def healthy(name: String): CheckResult = CheckResult(name, healthy = true, None)
  def failed(name: String, detail: String): CheckResult = CheckResult(name, healthy = false, Some(detail))
  def timedOut(name: String): CheckResult = failed(name, TimeoutDetail)

  given Codec[CheckResult] = Codec.from(
    (cursor: HCursor) =>
      for {
        name <- cursor.get[String]("name")
        healthy <- cursor.get[Boolean]("healthy")
        detail <- cursor.get[Option[String]]("detail")
      } yield CheckResult(name, healthy, detail),
    (result: CheckResult) =>
      Json.obj(
        "name" -> result.name.asJson,
        "healthy" -> result.healthy.asJson,
        "detail" -> result.detail.asJson
      )
  )

  given Schema[CheckResult] = Schema.derived[CheckResult].description("One readiness check and its result")

  given CanEqual[CheckResult, CheckResult] = CanEqual.derived
}

/** Whether the service can serve requests now, and if not, which checks say otherwise.
  *
  * Every check is listed, not only the failing ones. An operator looking at a 503 needs to know what *was*
  * tried as much as what failed: a report naming one broken upstream out of four is a different situation
  * from a report naming the only check there is.
  */
final case class ReadinessReport(ready: Boolean, checks: List[CheckResult], at: Instant)

object ReadinessReport {

  def of(checks: List[CheckResult], at: Instant): ReadinessReport =
    ReadinessReport(checks.forall(_.healthy), checks, at)

  given Codec[ReadinessReport] = Codec.from(
    (cursor: HCursor) =>
      for {
        ready <- cursor.get[Boolean]("ready")
        checks <- cursor.getOrElse[List[CheckResult]]("checks")(Nil)
        at <- cursor.get[Instant]("at")
      } yield ReadinessReport(ready, checks, at),
    (report: ReadinessReport) =>
      Json.obj(
        "ready" -> report.ready.asJson,
        "checks" -> report.checks.asJson,
        "at" -> report.at.asJson
      )
  )

  given Schema[ReadinessReport] =
    Schema.derived[ReadinessReport].description("Whether the service can serve requests now")

  given CanEqual[ReadinessReport, ReadinessReport] = CanEqual.derived
}

/** Whether the process is running and its runtime is not wedged.
  *
  * It carries nothing but a flag and a time on purpose. Liveness answers exactly one question — "should this
  * process be restarted" — and anything else in the body invites someone to make a restart decision depend on
  * it. It never depends on an upstream: a service restarted in a loop because a registry is down is a second
  * outage on top of the first.
  */
final case class LivenessReport(alive: Boolean, at: Instant)

object LivenessReport {

  def at(now: Instant): LivenessReport = LivenessReport(alive = true, now)

  given Codec[LivenessReport] = Codec.from(
    (cursor: HCursor) =>
      for {
        alive <- cursor.get[Boolean]("alive")
        at <- cursor.get[Instant]("at")
      } yield LivenessReport(alive, at),
    (report: LivenessReport) => Json.obj("alive" -> report.alive.asJson, "at" -> report.at.asJson)
  )

  given Schema[LivenessReport] =
    Schema.derived[LivenessReport].description("Whether the process should be restarted")

  given CanEqual[LivenessReport, LivenessReport] = CanEqual.derived
}
