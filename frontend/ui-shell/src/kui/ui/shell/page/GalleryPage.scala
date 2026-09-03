package kui.ui.shell.page

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.*
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.state.{Notification, NotificationBus}
import kui.ui.shell.ShellCss

/** Every kernel primitive on one page, in whichever theme is switched on.
  *
  * Deferred here from UI-003 and UI-004: until the shell existed there was no router to register a page with.
  *
  * ## What it is for
  *
  * It is a development page, not a product feature. Its job is to make a change to a primitive reviewable:
  * one address shows every component, every tone and every size, so that a change to the button's padding is
  * seen next to the tag and the toast it has to line up with, instead of being discovered three screens later
  * by somebody else. Switching the theme in the header re-renders all of it, which is how a colour that only
  * works in one theme is caught.
  *
  * Every example is built from the same public API a feature would use. Nothing here reaches inside a
  * component, so the gallery cannot drift into showing something a caller could not actually produce.
  */
object GalleryPage {

  def apply(): HtmlElement =
    div(
      cls := ShellCss.Page,
      cls := ShellCss.Gallery,
      dataAttr("testid") := "page-gallery",
      h1("Components"),
      p(
        "Every primitive the kernel provides, in the theme currently selected. Switch the theme in ",
        "the header to check both."
      ),
      buttons,
      tags,
      inputs,
      feedback,
      table,
      overlays,
      icons
    )

  /** One titled block. Named `panel` and not `section` so that it cannot shadow Laminar's own tag. */
  private def panel(title: String, testId: String, content: Modifier[HtmlElement]*): HtmlElement =
    sectionTag(
      cls := ShellCss.GallerySection,
      dataAttr("testid") := testId,
      h2(title),
      content
    )

  private def row(content: Modifier[HtmlElement]*): HtmlElement =
    div(cls := ShellCss.GalleryRow, content)

  private def noop: Observer[Unit] = Observer.empty

  private def buttons: HtmlElement =
    panel(
      "Buttons",
      "gallery-buttons",
      row(
        Button(Val("Primary"), noop, ButtonVariant.Primary),
        Button(Val("Secondary"), noop, ButtonVariant.Secondary),
        Button(Val("Danger"), noop, ButtonVariant.Danger),
        Button(Val("Ghost"), noop, ButtonVariant.Ghost)
      ),
      row(
        Button(Val("Small"), noop, size = Size.Sm),
        Button(Val("Medium"), noop, size = Size.Md),
        Button(Val("Large"), noop, size = Size.Lg)
      ),
      row(
        Button(Val("Disabled"), noop, disabled = Val(true)),
        Button(Val("Loading"), noop, loading = Val(true)),
        Button(Val("With icon"), noop, icon = Some(() => Icon.plus))
      )
    )

  private def tags: HtmlElement =
    panel(
      "Tags",
      "gallery-tags",
      row(
        Tag(Val("Neutral"), Tone.Neutral),
        Tag(Val("Info"), Tone.Info),
        Tag(Val("Success"), Tone.Success),
        Tag(Val("Warning"), Tone.Warning),
        Tag(Val("Danger"), Tone.Danger)
      ),
      row(
        Tag(Val("With a dot"), Tone.Success, dot = true),
        Tag(Val("Removable"), Tone.Info, onRemove = Some(noop))
      )
    )

  private def inputs: HtmlElement = {
    val text = Var("kui-topic-1")
    val invalid = Var("")
    val choice = Var(Option("b"))

    panel(
      "Inputs",
      "gallery-inputs",
      row(
        TextInput(text, "Topic name", hint = Some("Letters, digits, dots, dashes and underscores.")),
        TextInput(invalid, "Partitions", error = Val(Some("Must be a whole number above zero."))),
        Select(
          Val(List("a" -> "Earliest", "b" -> "Latest", "c" -> "Timestamp")),
          choice,
          "Start from"
        )
      )
    )
  }

  private def feedback: HtmlElement =
    panel(
      "Feedback",
      "gallery-feedback",
      row(
        Button(
          Val("Raise a toast"),
          Observer[Unit](_ =>
            NotificationBus.push(
              Notification(Tone.Info, "Nothing happened", Some("This is what a toast looks like."))
            )
          )
        )
      ),
      EmptyState(
        title = "No topics yet",
        description = Some("Create one to get started."),
        icon = Some(() => Icon.info),
        action = Some(Button(Val("Create topic"), noop, ButtonVariant.Primary))
      ),
      Card(header = Some(h3("A card")), body = p("Cards group related things."), elevated = true)
    )

  private def table: HtmlElement = {
    val rows = List(
      GalleryRow("orders", 12, "3 days"),
      GalleryRow("payments", 6, "7 days"),
      GalleryRow("audit", 1, "forever")
    )

    panel(
      "Table",
      "gallery-table",
      DataTable[GalleryRow](
        columns = List(
          Column("name", "Topic", row => span(row.name), sortable = true),
          Column("partitions", "Partitions", row => span(row.partitions.toString), sortable = true),
          Column("retention", "Retention", row => span(row.retention))
        ),
        rows = Val(rows),
        rowKey = _.name
      )
    )
  }

  private def overlays: HtmlElement = {
    val dialogOpen = Var(false)
    val drawerOpen = Var(false)
    val tab = Var("one")

    panel(
      "Overlays and navigation",
      "gallery-overlays",
      row(
        Button(Val("Open a dialog"), Observer[Unit](_ => dialogOpen.set(true))),
        Button(Val("Open a drawer"), Observer[Unit](_ => drawerOpen.set(true))),
        Tooltip(Button(Val("Hover me"), noop), Val("This is a tooltip."))
      ),
      Breadcrumbs(
        Val(List(Crumb("Clusters", Some("#")), Crumb("prod-eu", Some("#")), Crumb("orders", None)))
      ),
      Tabs(
        Val(
          List(
            Tab("one", "First", () => p("The first panel.")),
            Tab("two", "Second", () => p("The second panel.")),
            Tab("three", "Third", () => p("The third panel."))
          )
        ),
        tab
      ),
      Dialog(
        open = dialogOpen,
        title = Val("A dialog"),
        body = () => p("Dialogs trap focus and close on Escape."),
        actions = () => List(Button(Val("Close"), Observer[Unit](_ => dialogOpen.set(false))))
      ),
      Drawer(
        open = drawerOpen,
        title = Val("A drawer"),
        body = () => p("Drawers slide in from the side.")
      )
    )
  }

  private def icons: HtmlElement =
    panel(
      "Icons",
      "gallery-icons",
      div(
        cls := ShellCss.GalleryIconGrid,
        Icon.all.map { (name, build) =>
          div(cls := ShellCss.GalleryIcon, build(), span(cls := KernelCss.VisuallyHidden, name), code(name))
        }
      )
    )
}

/** A row for the table example. Named rather than a tuple so the columns read as English. */
final case class GalleryRow(name: String, partitions: Int, retention: String)
