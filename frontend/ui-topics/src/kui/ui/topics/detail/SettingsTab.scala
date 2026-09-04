package kui.ui.topics.detail

import com.raquo.laminar.api.L.*

import kui.contracts.Section
import kui.contracts.topic.TopicConfigEntryDto
import kui.topic.contract.dto.TopicConfigViewDto
import kui.ui.kernel.component.*
import kui.ui.kernel.css.KernelCss
import kui.ui.topics.{Messages, TopicsCss}

/** Every configuration key of one topic: its value, its default, and what it means.
  *
  * ## An empty table and "you may not look" are different answers
  *
  * A topic with no overrides has an empty table and that is a fact about the topic. A caller who may see the
  * topic but not its settings has no table at all, and that is a fact about the caller — whose remedy is an
  * ACL change they will never think of if the screen tells them the topic has no settings. The contract
  * carries the two as separate cases (`TopicConfigViewDto`) precisely so this screen cannot conflate them,
  * and it does not.
  *
  * ## Overridden keys are emphasised, not filtered
  *
  * An operator scanning this table is looking for what somebody changed, so a key whose value differs from
  * its default is bold. Hiding the defaults instead would make the table shorter and would also make it
  * impossible to answer "what is this set to", which is the other half of why anybody opens it.
  */
object SettingsTab {

  def apply(config: Signal[Option[Section[TopicConfigViewDto]]]): HtmlElement = {
    val view: Signal[Option[TopicConfigViewDto]] = config.map(_.flatMap(_.toOption))

    val entries: Signal[List[TopicConfigEntryDto]] =
      view.map {
        case Some(TopicConfigViewDto.Entries(values)) => values.sortBy(_.name)
        case _ => Nil
      }

    val notPermitted: Signal[Option[String]] =
      view.map {
        case Some(TopicConfigViewDto.NotPermitted(detail)) => Some(detail)
        case _ => None
      }

    div(
      cls := TopicsCss.Settings,
      dataAttr("testid") := "topic-settings",
      child.maybe <-- notPermitted.map(
        _.map(detail =>
          EmptyState(
            Messages.ConfigNotPermittedTitle,
            // The server's own sentence, unedited. ADR-032: an operator needs the string they can search
            // for or paste into a ticket, not a friendlier paraphrase of it.
            description = Some(Messages.configNotPermitted(detail)),
            testId = Some("topic-settings-not-permitted")
          )
        )
      ),
      child.maybe <-- notPermitted.map(refused =>
        Option.when(refused.isEmpty)(
          DataTable[TopicConfigEntryDto](
            columns = columns,
            rows = entries,
            rowKey = _.name,
            empty = () => EmptyState(Messages.NoOverridesTitle, description = Some(Messages.NoOverrides)),
            testId = Some("topic-settings-table")
          )
        )
      )
    )
  }

  private val columns: List[Column[TopicConfigEntryDto]] = List(
    Column[TopicConfigEntryDto](
      id = "name",
      header = Messages.ColumnSetting,
      render = entry =>
        span(
          cls := TopicsCss.SettingName,
          // Bold when it has been changed away from its default. Weight, not colour, because this is the
          // one distinction the table exists to draw and it has to survive a monochrome print-out.
          cls(TopicsCss.SettingOverridden) := entry.overridden,
          dataAttr("testid") := s"topic-setting-${entry.name}",
          entry.name,
          entry.documentation.map(doc => span(cls := TopicsCss.SettingDoc, doc))
        )
    ),
    Column[TopicConfigEntryDto](
      id = "value",
      header = Messages.ColumnValue,
      render = entry => valueCell(entry)
    ),
    Column[TopicConfigEntryDto](
      id = "default",
      header = Messages.ColumnDefault,
      // Blank when the value *is* the default: repeating it in both columns doubles the reading and says
      // nothing. The blank is the statement "this one has not been changed".
      render = entry =>
        if entry.overridden then entry.defaultValue.getOrElse(DataTable.missing)
        else ""
    )
  )

  /** The value, masked when the broker calls it sensitive, with a unit hint where the key's name gives one.
    */
  private def valueCell(entry: TopicConfigEntryDto): Modifier[HtmlElement] =
    if entry.sensitive then
      span(
        cls := TopicsCss.SettingMasked,
        dataAttr("testid") := s"topic-setting-${entry.name}-value",
        // A fixed-width mask. One bullet per character would tell a reader how long the secret is — and the
        // server did not send the value at all, so its length is not even known here.
        span(aria.hidden := true, ConfigValue.masked),
        span(cls := KernelCss.VisuallyHidden, ConfigValue.maskedLabel)
      )
    else
      span(
        dataAttr("testid") := s"topic-setting-${entry.name}-value",
        entry.value.getOrElse(DataTable.missing),
        entry.value
          .flatMap(value => ConfigValue.hint(entry.name, value))
          // Beside the raw number and never instead of it: an operator comparing this against a setting
          // they are about to type needs the number they will type.
          .map(hint => span(cls := TopicsCss.SettingHint, s"($hint)"))
      )
}
