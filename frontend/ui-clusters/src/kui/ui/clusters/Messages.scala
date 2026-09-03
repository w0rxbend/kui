package kui.ui.clusters

/** Every sentence this feature shows, in one place (ADR-024).
  *
  * One object per module rather than a message catalogue with lookups: KUI ships in English in M0 and has no
  * i18n runtime. The gain is consistency and review — the wording can be read as prose in one file instead of
  * being hunted for across screens.
  */
object Messages {

  val Title: String = "Clusters"

  val EmptyTitle: String = "No clusters yet"

  val EmptyDescription: String =
    "Clusters configured in this deployment appear here."

  /** What this feature says on the shell's fallback panel when its service is unavailable.
    *
    * The one sentence the shell cannot write, because it is about what this feature can still do.
    */
  val UnavailableView: String =
    "Cluster metadata is unavailable, so no cluster can be inspected or switched to. Pages that do not need " +
      "the cluster service — settings, and the component gallery — still work."
}
