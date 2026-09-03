package kui.config

import kui.kernel.Port

/** How log lines are rendered.
  *
  * `Json` is the default because containers ship their standard output to a log system that parses it, and a
  * parsed field is searchable where a formatted sentence is not. `Text` exists for a developer reading a
  * terminal, where JSON is unreadable.
  */
enum LogFormat {
  case Json, Text

  def wire: String = this match {
    case Json => "json"
    case Text => "text"
  }
}

object LogFormat {
  def fromWire(raw: String): Option[LogFormat] = values.find(_.wire == raw.toLowerCase)

  given CanEqual[LogFormat, LogFormat] = CanEqual.derived
}

/** Where this process sends its traces, metrics and logs.
  *
  * @param otlpEndpoint
  *   the OpenTelemetry collector to export traces and metrics to. `None` means export nothing: telemetry is
  *   never a startup dependency, and a process with no collector configured still starts and still serves
  *   (OBS-001).
  * @param prometheusPort
  *   an optional extra port on which this process's *own* telemetry is exposed in Prometheus format. This is
  *   not the product's `/metrics` endpoint for Kafka cluster metrics; ADR-009 keeps those two deliberately
  *   separate.
  * @param logFormat
  *   see [[LogFormat]]
  * @param hashUserIds
  *   whether the `user.id` log and span attribute is a salted hash rather than the login name. On by default,
  *   because an operator debugging a request rarely needs to know *who* it was, only that two entries concern
  *   the same person.
  */
final case class TelemetryConfig(
    otlpEndpoint: Option[SafeUrl],
    prometheusPort: Option[Port],
    logFormat: LogFormat,
    hashUserIds: Boolean
)

object TelemetryConfig {

  /** No exporters, JSON logs, hashed user ids. Safe in every environment, including a laptop. */
  val Default: TelemetryConfig = TelemetryConfig(None, None, LogFormat.Json, hashUserIds = true)

  given CanEqual[TelemetryConfig, TelemetryConfig] = CanEqual.derived
}
