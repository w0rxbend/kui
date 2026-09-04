package kui.ui.topics

/** Every sentence this feature shows, in one place (ADR-024).
  *
  * One object per module rather than a message catalogue with lookups: KUI ships in English and has no i18n
  * runtime. The gain is consistency and review — the wording can be read as prose in one file instead of
  * being hunted for across screens.
  */
object Messages {

  val Title: String = "Topics"

  /** The feature's own half of the shell's fallback panel.
    *
    * Only the feature can write this sentence: the reason, the "since", the retry and the list of other
    * working features are the shell's, and it draws them around this. What belongs here is what a user can
    * still do while the topic service is down (ADR-032).
    */
  val UnavailableView: String =
    "Topic browsing is unavailable while the topic service is down. The dashboard, the brokers page and " +
      "your settings all still work, and nothing on your Kafka clusters is affected."
}
