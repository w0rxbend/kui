package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalajs.dom

/** The bar is decoration over a figure, so the two things worth testing are that the figure is always there
  * and that the decoration can never paint outside its own track.
  */
final class MagnitudeBarSuite extends ScalaCheckSuite with Mounted {

  private def fillWidth(root: dom.Element): String =
    Option(root.querySelector(".kui-magnitude__fill"))
      .flatMap(element => attributeOf(element, "style"))
      .getOrElse(fail(s"no fill in ${root.outerHTML}"))

  test("the figure is rendered in full beside the bar") {
    mounted(MagnitudeBar(Val("48.2 GB"), Val(0.42))) { root =>
      assertEquals(Option(root.querySelector(".kui-magnitude__value")).map(_.textContent), Some("48.2 GB"))
    }
  }

  test("a fraction becomes a percentage width") {
    mounted(MagnitudeBar(Val("100"), Val(0.425))) { root =>
      assertEquals(fillWidth(root), "width: 42.5%")
    }
  }

  test("the label is shown only when the caller supplies one") {
    mounted(MagnitudeBar(Val("1 GB"), Val(0.1))) { root =>
      assertEquals(Option(root.querySelector(".kui-magnitude__label")), None)
    }

    mounted(MagnitudeBar(Val("1 GB"), Val(0.1), label = Some(Val("orders.created")))) { root =>
      assertEquals(
        Option(root.querySelector(".kui-magnitude__label")).map(_.textContent),
        Some("orders.created")
      )
    }
  }

  test("the width follows its signal") {
    val fraction = Var(0.1)

    mounted(MagnitudeBar(Val("x"), fraction.signal)) { root =>
      assertEquals(fillWidth(root), "width: 10%")
      fraction.set(0.9)
      assertEquals(fillWidth(root), "width: 90%")
    }
  }

  property("no fraction, however wrong, paints outside the track") {
    // A caller whose denominator was zero or stale hands this component a negative, an enormous or a
    // NaN fraction. Every one of those has to come out as a width between nothing and full: a bar
    // that overflows its own track is a rendering bug that looks like a data bug.
    forAll { (fraction: Double) =>
      mounted(MagnitudeBar(Val("x"), Val(fraction))) { root =>
        val percent = fillWidth(root).stripPrefix("width: ").stripSuffix("%").toDouble
        percent >= 0.0 && percent <= 100.0
      }
    }
  }

  test("a NaN fraction renders an empty bar rather than an invalid width") {
    // Worth its own test because NaN survives clamping: every comparison with it is false, so
    // `max` and `min` both leave it alone and it would reach the stylesheet as "width: NaN%".
    mounted(MagnitudeBar(Val("x"), Val(0.0 / 0.0))) { root =>
      assertEquals(fillWidth(root), "width: 0%")
    }
  }
}
