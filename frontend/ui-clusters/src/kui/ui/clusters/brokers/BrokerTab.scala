package kui.ui.clusters.brokers

/** Which tab of the broker detail page is open.
  *
  * ## Why this is in the URL and not in feature state
  *
  * Feature state would keep the tab across navigation just as well, and it would be less work. The URL wins
  * because of what these two tabs *are*: a configuration listing is the thing an operator pastes into a
  * ticket or a chat message, and a link that always opens on log directories makes the recipient hunt for
  * what they were sent. The cost is one route pattern.
  *
  * The default tab has no segment of its own, so a broker's canonical URL is its short form and a link to a
  * broker needs to know nothing about tabs.
  */
enum BrokerTab(val segment: Option[String], val label: String) {
  case LogDirs extends BrokerTab(None, "Log directories")
  case Configs extends BrokerTab(Some("configs"), "Configs")
}

object BrokerTab {

  given CanEqual[BrokerTab, BrokerTab] = CanEqual.derived

  /** The tab's id in the DOM and in `history.state`. Spelled out rather than derived from the case name, so
    * that renaming a case cannot silently change a stored state or a test hook.
    */
  def idOf(tab: BrokerTab): String =
    tab match {
      case LogDirs => "logdirs"
      case Configs => "configs"
    }

  def fromId(raw: String): BrokerTab = values.find(tab => idOf(tab) == raw).getOrElse(LogDirs)

  /** Parses the trailing path segment.
    *
    * Anything unrecognised is the default tab, so a hand-edited or truncated URL lands on the page rather
    * than on "not found" — and so does a `history.state` written before this tab existed, which is what keeps
    * the Back button working across a deployment upgrade.
    */
  def fromSegment(segment: Option[String]): BrokerTab =
    segment.flatMap(raw => values.find(_.segment.contains(raw))).getOrElse(LogDirs)
}
