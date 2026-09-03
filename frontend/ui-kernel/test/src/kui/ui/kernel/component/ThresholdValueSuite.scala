package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

/** The whole value of this component is the rule "quiet until the limit is crossed", so that is what the
  * tests are about: the healthy case must stay uncoloured and unmarked, and the crossed case must carry three
  * cues rather than one.
  */
final class ThresholdValueSuite extends FunSuite with Mounted {

  private def classesOf(root: dom.Element): String = attributeOf(root, "class").getOrElse("")

  private def marks(root: dom.Element): Int = root.querySelectorAll(".kui-threshold__mark").length

  test("a value under the limit is drawn like any other number") {
    mounted(ThresholdValue(Val("0"), Val(ThresholdLevel.Normal))) { root =>
      assert(!classesOf(root).contains("kui-threshold--"), classesOf(root))
      assertEquals(marks(root), 0)
      assertEquals(root.textContent, "0")
    }
  }

  test("a crossed limit adds a colour, a mark and a word, not just a colour") {
    mounted(ThresholdValue(Val("18"), Val(ThresholdLevel.Warning))) { root =>
      assert(classesOf(root).contains("kui-threshold--over"), classesOf(root))
      assertEquals(marks(root), 1)
      assert(root.textContent.contains("above the warning threshold"), root.textContent)
    }
  }

  test("the critical level is distinct from the warning level") {
    mounted(ThresholdValue(Val("9000"), Val(ThresholdLevel.Critical))) { root =>
      assert(classesOf(root).contains("kui-threshold--critical"), classesOf(root))
      assert(!classesOf(root).contains("kui-threshold--over"), classesOf(root))
    }
  }

  test("the level follows its signal, and the mark comes and goes with it") {
    val level = Var(ThresholdLevel.Normal)

    mounted(ThresholdValue(Val("3"), level.signal)) { root =>
      assertEquals(marks(root), 0)
      level.set(ThresholdLevel.Warning)
      assertEquals(marks(root), 1)
      level.set(ThresholdLevel.Normal)
      assertEquals(marks(root), 0)
    }
  }

  test("a caller can say what the crossing means in its own words") {
    val announcement: ThresholdLevel => String = _ => "2 replicas out of sync"

    mounted(ThresholdValue(Val("2"), Val(ThresholdLevel.Warning), announcement = announcement)) { root =>
      assert(root.textContent.contains("2 replicas out of sync"), root.textContent)
    }
  }

  test("both bounds are exclusive, so a value sitting exactly on the limit is still normal") {
    // "Warn above zero" has to mean that zero out-of-sync replicas is the healthy case. Making the
    // bound inclusive would colour every healthy row on the cluster.
    assertEquals(ThresholdLevel.above(0, warnAbove = 0), ThresholdLevel.Normal)
    assertEquals(ThresholdLevel.above(1, warnAbove = 0), ThresholdLevel.Warning)
    assertEquals(ThresholdLevel.above(100, warnAbove = 0, criticalAbove = Some(100)), ThresholdLevel.Warning)
    assertEquals(ThresholdLevel.above(101, warnAbove = 0, criticalAbove = Some(100)), ThresholdLevel.Critical)
  }

  test("critical wins even when the caller's two bounds disagree about which is higher") {
    // A misconfigured pair of limits must still produce the louder of the two levels rather than
    // silently downgrading an alarm.
    assertEquals(ThresholdLevel.above(50, warnAbove = 80, criticalAbove = Some(10)), ThresholdLevel.Critical)
  }
}
