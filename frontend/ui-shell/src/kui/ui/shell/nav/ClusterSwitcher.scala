package kui.ui.shell.nav

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import org.scalajs.dom

import kui.kernel.ClusterId
import kui.ui.kernel.component.{Components, Icon}
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.state.{ClusterColor, ClusterColors, FeatureState}
import kui.ui.shell.{Messages, ShellCss}

/** Which cluster you are looking at, permanently visible, at the top of the drawer.
  *
  * ## The problem it solves
  *
  * An operator running four clusters — production in two regions, staging, and a local one — has to be able
  * to tell at a glance which one is in front of them, and must not be able to act on production while
  * believing they are on staging. Real cluster names differ by one character (`prod-eu-1`, `prod-eu-2`),
  * which is exactly the kind of string a person reads as the one they expected.
  *
  * So there are three marks per row and they say different things:
  *
  *   - the **colour tag**, chosen by this person in this browser, for the cluster *they* find dangerous;
  *   - the **name**;
  *   - the **status dot**, from the capability registry, saying whether the cluster is answering.
  *
  * The tag is a rounded rectangle and the dot is a circle, deliberately: one shape doing both jobs would make
  * a red "production" marker indistinguishable from a failing cluster.
  *
  * ## Why it sits above the destinations
  *
  * From the next milestone onward every destination below it is scoped to the chosen cluster, and a control
  * that scopes the things under it has to sit above them.
  *
  * ## Why a dead cluster is still choosable
  *
  * The same reason a dimmed sidebar entry is still a link: the cluster's own page is the only place the
  * reason, the time it went away and a retry exist. A switcher that refused to open a broken cluster would
  * take away the one route to the explanation.
  */
object ClusterSwitcher {

  def apply(
      entries: Signal[List[ClusterEntry]],
      current: Var[Option[ClusterId]],
      open: ClusterId => Unit,
      testId: Option[String] = Some("cluster-switcher")
  ): HtmlElement = {
    val expanded = Var(false)
    val listId = Components.nextId("kui-cluster-list")
    val triggerId = Components.nextId("kui-cluster-trigger")

    val selected: Signal[Option[ClusterEntry]] =
      entries.combineWith(current.signal).map { (all, chosen) =>
        chosen.flatMap(id => all.find(_.clusterId == id)).orElse(all.headOption)
      }

    def choose(entry: ClusterEntry): Unit = {
      current.set(Some(entry.clusterId))
      expanded.set(false)
      dom.document.getElementById(triggerId) match {
        case element: dom.html.Element => element.focus()
        case _ => ()
      }
      open(entry.clusterId)
    }

    div(
      cls := ShellCss.ClusterSwitcher,
      Components.testIdAttr(testId),
      button(
        idAttr := triggerId,
        tpe := "button",
        cls := ShellCss.ClusterSwitcherTrigger,
        Components.testIdAttr(testId.map(id => s"$id-trigger")),
        // Laminar types `aria-haspopup` as a boolean; the listbox value is written through the raw
        // attribute, because "true" and "listbox" mean different things to a screen reader.
        htmlAttr("aria-haspopup", com.raquo.laminar.codecs.StringAsIsCodec) := "listbox",
        aria.expanded <-- expanded.signal,
        aria.controls := listId,
        child <-- selected.map {
          case Some(entry) => row(entry, showColourPicker = false)
          case None => L.span(cls := ShellCss.ClusterSwitcherEmpty, Messages.NoClusters)
        },
        L.span(cls := ShellCss.ClusterSwitcherCaret, aria.hidden := true, Icon.dot),
        onClick.mapTo(()) --> Observer[Unit](_ => expanded.update(!_)),
        onKeyDown --> Observer[dom.KeyboardEvent] { event =>
          // Down from the trigger opens the list, which is what the combobox pattern promises and what a
          // keyboard user tries first.
          if event.key == "ArrowDown" then {
            event.preventDefault()
            expanded.set(true)
          }
        }
      ),
      ul(
        idAttr := listId,
        cls := ShellCss.ClusterSwitcherList,
        role := "listbox",
        aria.label := Messages.ClusterSwitcherLabel,
        L.hidden <-- expanded.signal.map(!_),
        // Escape closes without changing anything and puts focus back where it came from. A menu a keyboard
        // user cannot get out of is worse than no menu.
        onKeyDown --> Observer[dom.KeyboardEvent] { event =>
          if event.key == "Escape" then {
            expanded.set(false)
            dom.document.getElementById(triggerId) match {
              case element: dom.html.Element => element.focus()
              case _ => ()
            }
          }
        },
        children <-- entries
          .combineWith(current.signal)
          .map((all, chosen) =>
            if all.isEmpty then List(emptyRow)
            else all.map(entry => option(entry, chosen.contains(entry.clusterId), choose))
          )
      )
    )
  }

  /** A cluster nobody has configured is a sentence, not an empty menu: an empty popup looks broken. */
  private def emptyRow: HtmlElement =
    li(cls := ShellCss.ClusterSwitcherEmpty, role := "presentation", Messages.NoClusters)

  private def option(entry: ClusterEntry, isCurrent: Boolean, choose: ClusterEntry => Unit): HtmlElement =
    li(
      role := "option",
      cls := ShellCss.ClusterSwitcherOption,
      cls(ShellCss.ClusterSwitcherOptionCurrent) := isCurrent,
      dataAttr("testid") := s"cluster-switcher-option-${entry.clusterId.value}",
      // The state, machine-readable and not a class name, for the same reason the sidebar's entries carry
      // one: a class belongs to the visual design and changes with it; this is a statement about state.
      dataAttr("state") := stateName(entry.state),
      aria.selected := isCurrent,
      // In the tab order, so the list is operable from a keyboard at all.
      tabIndex := 0,
      row(entry, showColourPicker = true),
      onClick.mapTo(()) --> Observer[Unit](_ => choose(entry)),
      onKeyDown --> Observer[dom.KeyboardEvent] { event =>
        event.key match {
          case "Enter" | " " =>
            event.preventDefault()
            choose(entry)
          case "ArrowDown" => move(event, forwards = true)
          case "ArrowUp" => move(event, forwards = false)
          case _ => ()
        }
      }
    )

  private def move(event: dom.KeyboardEvent, forwards: Boolean): Unit = {
    event.preventDefault()
    event.currentTarget match {
      case element: dom.html.Element =>
        val next = if forwards then element.nextElementSibling else element.previousElementSibling
        next match {
          case target: dom.html.Element => target.focus()
          case _ => ()
        }
      case _ => ()
    }
  }

  /** The colour tag, the name and the status dot, in that order. */
  private def row(entry: ClusterEntry, showColourPicker: Boolean): HtmlElement = {
    val colour = ClusterColors.of(entry.clusterId.value)

    L.span(
      cls := ShellCss.ClusterSwitcherRow,
      L.span(
        cls := KernelCss.ClusterTag,
        cls <-- colour.signal.map(ClusterColors.className),
        dataAttr("testid") := s"cluster-tag-${entry.clusterId.value}",
        aria.hidden := true
      ),
      L.span(cls := ShellCss.ClusterSwitcherName, entry.displayName),
      dot(entry),
      Option.when(showColourPicker)(picker(entry, colour))
    )
  }

  /** The status dot, with a text alternative.
    *
    * A `<title>` inside the mark rather than a tooltip on it, so the state reaches a screen reader and is not
    * carried by colour alone.
    */
  private def dot(entry: ClusterEntry): HtmlElement =
    L.span(
      cls := ShellCss.ClusterSwitcherDot,
      cls := dotClass(entry.state),
      dataAttr("testid") := s"cluster-switcher-dot-${entry.clusterId.value}",
      role := "img",
      // The state word, then the reason exactly as the registry sent it.
      aria.label := describe(entry.state),
      title := describe(entry.state),
      Icon.dot
    )

  /** Choosing a colour, on the row of the cluster it belongs to.
    *
    * Not a settings screen: it is a per-cluster property, and the switcher is where clusters are listed.
    */
  private def picker(entry: ClusterEntry, colour: Var[ClusterColor]): HtmlElement =
    L.select(
      cls := ShellCss.ClusterSwitcherColour,
      dataAttr("testid") := s"cluster-colour-${entry.clusterId.value}",
      aria.label := Messages.colourFor(entry.displayName),
      // The row's click handler chooses the cluster; a click on the colour menu must not also navigate.
      onClick.stopPropagation --> Observer.empty,
      onKeyDown.stopPropagation --> Observer.empty,
      ClusterColor.values.toList.map(choice =>
        L.option(L.value := choice.storageValue, choice.label, L.selected := colour.now() == choice)
      ),
      L.value <-- colour.signal.map(_.storageValue),
      onChange.mapToValue --> Observer[String](raw => colour.set(ClusterColor.fromStorage(raw)))
    )

  /** The state word, then the registry's reason verbatim. ADR-032's single rule for reasons. */
  private[nav] def describe(state: FeatureState): String =
    state match {
      case FeatureState.Ready => Messages.ClusterOnline
      case FeatureState.Degraded(reason) => s"${Messages.ClusterDegraded}: ${reason.message}"
      case FeatureState.Unavailable(code, message, _) =>
        val detail = if message.isEmpty then Messages.reason(code) else message
        s"${Messages.ClusterUnavailable}: $detail"
      case FeatureState.Forbidden => Messages.ClusterForbidden
      case FeatureState.NotConfigured => Messages.ClusterNotConfigured
    }

  private def dotClass(state: FeatureState): String =
    state match {
      case FeatureState.Ready => ShellCss.ClusterDotReady
      case FeatureState.Degraded(_) => ShellCss.ClusterDotDegraded
      case FeatureState.Unavailable(_, _, _) => ShellCss.ClusterDotUnavailable
      case FeatureState.Forbidden | FeatureState.NotConfigured => ShellCss.ClusterDotNeutral
    }

  private def stateName(state: FeatureState): String =
    state match {
      case FeatureState.Ready => "ready"
      case FeatureState.Degraded(_) => "degraded"
      case FeatureState.Unavailable(_, _, _) => "unavailable"
      case FeatureState.Forbidden => "forbidden"
      case FeatureState.NotConfigured => "notconfigured"
    }
}
