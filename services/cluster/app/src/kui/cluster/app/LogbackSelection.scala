package kui.cluster.app

import cats.effect.kernel.Sync

import kui.config.LogFormat

/** Choosing which Logback configuration this process uses.
  *
  * `libs/observability` ships two: `logback.xml` writes one JSON object per line, which is what a log system
  * parses, and `logback-text.xml` writes a short human line, which is what a developer reading a terminal
  * wants. `kui.telemetry.logFormat` says which, and this is where that setting turns into an effect.
  *
  * It works by setting the system property Logback reads when it initialises, which means **it has to run
  * before the first logger is created**. Logback configures itself once, on first use, and nothing can move
  * it afterwards. `Main` therefore calls this immediately after the configuration is loaded and before
  * `KuiLogger.make`, and no code path between those two lines may log.
  *
  * The `json` case sets nothing: it is Logback's own default lookup, so leaving the property unset is both
  * correct and one less thing that can be set to something surprising.
  */
object LogbackSelection {

  /** The property Logback reads at initialisation. */
  val Property: String = "logback.configurationFile"

  val TextConfiguration: String = "logback-text.xml"

  /** The configuration file name for a format, or `None` when the default is right. */
  def resourceFor(format: LogFormat): Option[String] =
    format match {
      case LogFormat.Text => Some(TextConfiguration)
      case LogFormat.Json => None
    }

  /** Applies the choice. Nothing already set by the operator is overwritten: someone who passed
    * `-Dlogback.configurationFile=...` on the command line has said something more specific than a
    * configuration file did, and KUI should not argue with them.
    */
  def apply[F[_]: Sync](format: LogFormat): F[Unit] =
    Sync[F].delay {
      resourceFor(format).foreach { resource =>
        if Option(System.getProperty(Property)).isEmpty then {
          val _ = System.setProperty(Property, resource)
        }
      }
    }
}
