package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite

/** A drawer that is already open must survive being told, again, that it is open.
  *
  * Found in Chrome against the demonstration environment. Typing into the Publish form's value box produced
  * exactly one character: the box emptied, and the caret jumped to the drawer's close button. Nothing errored
  * and no test caught it, because the failure is not in the form at all.
  *
  * `ProduceDrawer` keeps the drawer's `open` flag in step with the draft it is editing by writing
  * `open.set(draft.isDefined)` whenever the draft changes, which is on every keystroke. An Airstream `Var`
  * emits on every `set`, including a `set` to the value it already holds, so `open.signal` emitted `true`
  * again for each character. The drawer's `child.maybe <-- open.signal.map(...)` built a fresh panel for each
  * of those emissions and swapped the live one out, taking the focused textarea -- and everything the user had
  * typed into it -- with it.
  *
  * These two tests pin the property that makes that impossible: the panel is built once per *transition*, not
  * once per emission. They are written against the component rather than against the produce form because any
  * caller that keeps a flag in step with something else will do the same thing.
  */
final class DrawerRebuildSuite extends FunSuite with Mounted {

  test("a drawer told it is open while it is already open does not rebuild its body") {
    var built = 0
    val open = Var(false)
    val drawer = Drawer(open, Val("Publish"), () => { built += 1; div("body") })

    mounted(drawer) { _ =>
      open.set(true)
      assertEquals(built, 1)

      // What a caller keeping this flag in step with a draft does on every keystroke.
      open.set(true)
      open.set(true)
      open.set(true)

      assertEquals(
        built,
        1,
        "the drawer rebuilt its body while it was already open, which destroys whatever the user was typing into"
      )
    }
  }

  test("the element the user is typing into survives a redundant open") {
    val open = Var(false)
    val drawer = Drawer(open, Val("Publish"), () => div(textArea(dataAttr("testid") := "box")))

    mounted(drawer) { root =>
      open.set(true)
      val before = root.querySelector("[data-testid='box']")
      assert(before != null)

      open.set(true)

      val after = root.querySelector("[data-testid='box']")
      assert(before eq after, "the drawer replaced the text box, so its content and the caret are gone")
    }
  }

  test("a dialog behaves the same way, for the same reason") {
    var built = 0
    val open = Var(false)
    val dialog = Dialog(open, Val("Delete topic"), () => { built += 1; div("body") }, () => Nil)

    mounted(dialog) { _ =>
      open.set(true)
      open.set(true)
      assertEquals(built, 1)
    }
  }
}
