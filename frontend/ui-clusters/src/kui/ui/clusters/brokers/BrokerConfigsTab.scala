package kui.ui.clusters.brokers

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.contracts.Section
import kui.contracts.cluster.BrokerConfigEntryDto
import kui.kernel.{BrokerId, ClusterId}
import kui.ui.clusters.{ClustersCss, ClustersQueries, Messages}
import kui.ui.kernel.component.*

/** What this broker is configured with, and — the part that matters — who set it.
  *
  * Entries are ordered by source: what somebody changed at runtime first, what the file said next, the
  * defaults last. That order *is* the feature. An operator opening this tab is comparing one broker against
  * its peers or looking for a change somebody made at three in the morning, and either way the answer is at
  * the top.
  *
  * ## Read-only, with nothing that looks otherwise
  *
  * There is no edit control anywhere on this screen, not even a disabled one. Broker configuration edits
  * arrive in a later milestone behind read-only mode and an audit trail, and a greyed-out Edit button in the
  * meantime would be a promise the product has not made.
  */
object BrokerConfigsTab {

  /** How long typing settles before the table narrows. Long enough not to re-render on every keystroke, short
    * enough that the table feels attached to the keyboard.
    */
  private val SearchDebounce: FiniteDuration = 200.millis

  def apply(
      cluster: ClusterId,
      broker: BrokerId,
      queries: ClustersQueries,
      zone: Signal[String],
      now: () => Instant
  ): HtmlElement = {
    val search = Var("")

    // `lastGood`, not the current outcome: when a refetch fails, the settings the user is reading stay on
    // screen and the overlay says they are old.
    val section: Signal[Option[Section[List[BrokerConfigEntryDto]]]] =
      queries.brokerConfigs.state((cluster, broker, true)).map(_.lastGood.map(_.configs))

    val all: Signal[List[ConfigEntry]] =
      section.map(_.flatMap(_.toOption).map(ConfigEntry.of).getOrElse(Nil))

    val visible: Signal[List[ConfigEntry]] =
      all
        .combineWith(search.signal.composeChanges(_.debounce(SearchDebounce.toMillis.toInt)))
        .map((entries, term) => entries.filter(ConfigEntry.matches(_, term)))

    val table = DataTable[ConfigEntry](
      columns = columns,
      rows = visible,
      rowKey = _.name,
      empty = () =>
        EmptyState(Messages.ConfigsNoMatchTitle, description = Some(Messages.ConfigsNoMatchDescription)),
      testId = Some("broker-configs-table")
    )

    div(
      TextInput(
        value = search,
        label = Messages.ConfigsSearchLabel,
        placeholder = Messages.ConfigsSearchPlaceholder,
        testId = Some("broker-config-search")
      ),
      TabBody(
        section = section,
        unavailableTestId = "broker-configs-unavailable",
        unavailableMessage = Messages.configsUnavailable,
        forbiddenMessage = Messages.ConfigsForbidden,
        emptyTitle = Messages.ConfigsEmptyTitle,
        emptyDescription = Messages.ConfigsEmptyDescription,
        isEmpty = all.map(_.isEmpty),
        body = table,
        overlayTestId = "broker-configs-region",
        zone = zone,
        now = now
      )
    )
  }

  private def columns: List[Column[ConfigEntry]] =
    List(
      Column(
        id = "name",
        header = Messages.ColumnSetting,
        sortable = true,
        render = entry =>
          Seq[Modifier[HtmlElement]](
            dataAttr("testid") := s"broker-config-row-${entry.name}",
            code(cls := ClustersCss.ConfigName, entry.name),
            entry.documentation.map(text => Tooltip(trigger = L.span(Icon.info), content = Val(text)))
          )
      ),
      Column("value", Messages.ColumnValue, render = entry => valueCell(entry.value)),
      Column(
        id = "source",
        header = Messages.ColumnSource,
        sortable = true,
        render = entry =>
          entry.source match {
            // The raw string is kept in the title, so a source this build has no name for can still be
            // recognised by somebody reading the screen.
            case ConfigSource.Unknown(raw) => L.span(title := raw, ConfigSource.Unknown(raw).label)
            case known => L.span(known.label)
          }
      ),
      Column(
        id = "readOnly",
        header = Messages.ColumnReadOnly,
        render = entry =>
          if entry.readOnly then Tag(Val(Messages.ReadOnly), tone = Tone.Neutral) else DataTable.missing
      )
    )

  private def valueCell(value: ConfigValue): Modifier[HtmlElement] =
    value match {
      case ConfigValue.Redacted =>
        // The sentence is a fact about the system and is worth saying on the screen: the value was withheld
        // by the server, not hidden by the browser. Anyone who has used a UI that merely masks a value it
        // holds has reason to want the difference spelled out.
        L.span(
          cls := ClustersCss.ConfigRedacted,
          title := Messages.RedactedExplanation,
          Messages.RedactedMask
        )
      case ConfigValue.Empty =>
        L.span(title := Messages.EmptyValueExplanation, DataTable.missing)
      case ConfigValue.Bytes(raw, formatted) =>
        L.span(title := raw, cls := ClustersCss.ConfigValue, formatted)
      case ConfigValue.Duration(raw, formatted) =>
        L.span(title := raw, cls := ClustersCss.ConfigValue, formatted)
      // Wrapped, never truncated: a `listeners` value is long and the part that differs between two brokers
      // is usually the end of it.
      case ConfigValue.Plain(text) => L.span(cls := ClustersCss.ConfigValue, text)
    }
}
