package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

/** One table with a row per primitive, naming the accessibility contract that primitive promises.
  *
  * The shape is the point. Adding a component to the kernel without adding its row here leaves the
  * table visibly incomplete, and the alternative — an accessibility assertion buried in each
  * component's own suite — is the arrangement in which a new component quietly ships with none.
  *
  * What this can and cannot prove: it checks that the documented roles and attributes are present in
  * the rendered DOM. It is not an audit. It cannot tell whether a label reads well, whether a colour
  * pair is legible (`ContrastSuite` does that) or whether the reading order makes sense.
  */
final class A11ySuite extends FunSuite with Mounted {

  /** One expectation: on the element matching `selector`, the attribute `name` must be present, and
    * must equal `value` when a value is given.
    */
  private final case class Expected(selector: String, name: String, value: Option[String])

  private def present(selector: String, name: String): Expected     = Expected(selector, name, None)
  private def equalTo(selector: String, name: String, value: String) = Expected(selector, name, Some(value))

  private val contract: List[(String, HtmlElement, List[Expected])] = List(
    (
      "Button",
      Button(Val("Save"), Observer.empty, loading = Val(true)),
      List(equalTo("button", "type", "button"), equalTo("button", "aria-busy", "true"), present("button", "disabled"))
    ),
    (
      "TextInput",
      TextInput(Var(""), "Topic name", hint = Some("lower case"), error = Val(Some("required"))),
      List(
        present("label", "for"),
        present("input", "id"),
        equalTo("input", "aria-invalid", "true"),
        present("input", "aria-describedby"),
        equalTo("[role='alert']", "role", "alert")
      )
    ),
    (
      "Select",
      Select(Val(List(1 -> "one")), Var(Option.empty[Int]), "Partition"),
      List(present("label", "for"), present("select", "id"))
    ),
    (
      "Tag",
      Tag(Val("Rebalancing"), tone = Tone.Warning, live = true, onRemove = Some(Observer.empty)),
      List(equalTo("[role='status']", "role", "status"), present("button", "aria-label"))
    ),
    (
      "Tabs",
      Tabs(Val(List(Tab("a", "A", () => div("body")))), Var("a")),
      List(
        equalTo("[role='tablist']", "role", "tablist"),
        equalTo("[role='tab']", "aria-selected", "true"),
        equalTo("[role='tab']", "tabindex", "0"),
        present("[role='tab']", "aria-controls"),
        present("[role='tabpanel']", "aria-labelledby")
      )
    ),
    (
      "Dialog",
      Dialog(Var(true), Val("Delete topic"), () => div("body"), () => Nil),
      List(
        equalTo("[role='dialog']", "aria-modal", "true"),
        present("[role='dialog']", "aria-labelledby"),
        equalTo("[role='dialog']", "tabindex", "-1"),
        present(".kui-dialog__close", "aria-label")
      )
    ),
    (
      "Drawer",
      Drawer(Var(true), Val("Message"), () => div("payload")),
      List(equalTo("[role='dialog']", "aria-modal", "true"), present("[role='dialog']", "aria-labelledby"))
    ),
    (
      "Tooltip",
      Tooltip(button(tpe := "button", "Retry"), Val("Ask the gateway to probe again")),
      List(present("button", "aria-describedby"), equalTo("[role='tooltip']", "role", "tooltip"))
    ),
    (
      "Breadcrumbs",
      Breadcrumbs(Val(List(Crumb("Cluster", Some("/ui")), Crumb("orders", None)))),
      List(equalTo("nav", "aria-label", "Breadcrumb"), equalTo("[aria-current]", "aria-current", "page"))
    ),
    (
      "DataTable",
      DataTable[String](List(Column("id", "ID", value => value, sortable = true)), Val(List("a")), identity),
      List(equalTo("th", "scope", "col"), equalTo("th", "aria-sort", "none"))
    )
  )

  contract.foreach { (name, element, expectations) =>
    test(s"$name meets its documented accessibility contract") {
      mounted(element) { root =>
        val failures = expectations.flatMap { expected =>
          matching(root, expected.selector) match {
            case None => Some(s"no element matching '${expected.selector}'")
            case Some(target) =>
              (attributeOf(target, expected.name), expected.value) match {
                case (None, _)                                  => Some(s"'${expected.selector}' has no ${expected.name}")
                case (Some(actual), Some(wanted)) if actual != wanted =>
                  Some(s"'${expected.selector}' ${expected.name} is '$actual', expected '$wanted'")
                case _ => None
              }
          }
        }

        assertEquals(failures, Nil, s"$name: ${failures.mkString("; ")}\n${root.outerHTML}")
      }
    }
  }

  test("every icon is hidden from assistive technology") {
    // An icon carries no meaning of its own; the control around it does. An icon that is announced
    // makes a screen reader read a button twice.
    val offenders = Icon.all.filter { (_, build) =>
      mounted(div(build()))(root => attributeOf(root.querySelector("svg"), "aria-hidden") != Some("true"))
    }

    assertEquals(offenders.map(_._1), Nil)
  }

  /** The element itself if it matches, otherwise the first descendant that does. */
  private def matching(root: dom.Element, selector: String): Option[dom.Element] =
    if root.matches(selector) then Some(root) else Option(root.querySelector(selector))
}
