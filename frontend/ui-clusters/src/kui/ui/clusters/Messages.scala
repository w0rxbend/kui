package kui.ui.clusters

/** Every sentence this feature shows, in one place (ADR-024).
  *
  * One object per module rather than a message catalogue with lookups: KUI ships in English in M0 and has no
  * i18n runtime. The gain is consistency and review — the wording can be read as prose in one file instead of
  * being hunted for across screens.
  */
object Messages {

  val Title: String = "Clusters"

  val Lead: String =
    "M1 fills this page with the real cluster list. Until then it proves the chain end to end: the button " +
      "below calls the cluster service through the gateway, using a client generated from the same contract " +
      "the service is built from."

  val MessageLabel: String = "Message"

  val MessageHint: String = "Anything you like. The service echoes it back with the time it saw it."

  val PingButton: String = "Ping"

  val PingInFlight: String = "Pinging…"

  val TableCaption: String = "Replies from the cluster service, newest first"

  val EmptyTitle: String = "No pings yet"

  val EmptyDescription: String = "Press Ping and the service's reply appears here."

  val ColumnMessage: String = "Message"

  val ColumnAt: String = "Seen at"

  val ColumnService: String = "Answered by"

  /** Shown above the table when the last call failed and older replies are still on screen.
    *
    * ADR-032's stale-data rule: what was fetched successfully stays visible, greyed, with its timestamp, and
    * is labelled as old rather than thrown away. Clearing the table on a failure destroys the only
    * information the user still had.
    */
  val StaleResults: String =
    "These replies are from before the last failure, and may be out of date."

  def failed(detail: String): String = s"The ping did not go through: $detail"

  /** What this feature says on the shell's fallback panel when its service is unavailable.
    *
    * The one sentence the shell cannot write, because it is about what this feature can still do.
    */
  val UnavailableView: String =
    "Cluster metadata is unavailable, so no cluster can be inspected or switched to. Pages that do not need " +
      "the cluster service — settings, and the component gallery — still work."
}
