package kui.ui.kernel.component

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import munit.FunSuite

import kui.contracts.capability.{DegradedReason, ReasonCode}
import kui.ui.kernel.state.FeatureState

/** One wrapper, two independent reasons, one tooltip (ADR-032).
  *
  * The test that matters most is the one where both reasons apply at once: two nested wrappers would show
  * one tooltip and hide the other, and a user who fixed the outage would then discover they were never
  * allowed to do this anyway.
  */
class ActionPermissionWrapperSuite extends FunSuite with Mounted {

  private def action = Button(Val("Delete"), Observer.empty[Unit], testId = Some("action"))

  private val unavailable =
    FeatureState.Unavailable(ReasonCode.UpstreamUnavailable, "The cluster service is down.", None)

  test("aReadyCapabilityAndPermissionLeaveTheActionAlone") {
    mounted(ActionPermissionWrapper(action, Val(FeatureState.Ready))) { root =>
      val button = byTestId(root, "action")
      assertEquals(attributeOf(button, "disabled"), None)
      assertEquals(button.getAttribute("aria-disabled"), "false")
      assertEquals(button.getAttribute("aria-describedby"), "")
    }
  }

  test("aDegradedCapabilityDoesNotBlockTheAction") {
    // A slow service is still a working service. Disabling every write during a spell of high latency
    // takes the product away from the user at exactly the moment they are trying to fix something.
    val degraded = FeatureState.Degraded(DegradedReason(ReasonCode.UpstreamTimeout, "Slow.", None, None))

    mounted(ActionPermissionWrapper(action, Val(degraded))) { root =>
      assertEquals(attributeOf(byTestId(root, "action"), "disabled"), None)
    }
  }

  test("anUnavailableCapabilityDisablesTheActionAndExplainsWhy") {
    mounted(ActionPermissionWrapper(action, Val(unavailable))) { root =>
      val button = byTestId(root, "action")
      assert(attributeOf(button, "disabled").isDefined)
      assertEquals(button.getAttribute("aria-disabled"), "true")
      assertEquals(
        root.querySelector("[role='tooltip']").textContent,
        "The cluster service is down."
      )
    }
  }

  test("bothReasonsAppearInOneMergedTooltip") {
    mounted(ActionPermissionWrapper(action, Val(unavailable), permitted = Val(false))) { root =>
      val tooltip = root.querySelector("[role='tooltip']").textContent
      assert(tooltip.contains(ActionPermissionWrapper.NotPermittedMessage), tooltip)
      assert(tooltip.contains("The cluster service is down."), tooltip)
      // One tooltip, not two: the wrapper's whole reason for existing.
      assertEquals(root.querySelectorAll("[role='tooltip']").length, 1)
    }
  }

  test("theTooltipIsHiddenUntilTheBlockedActionIsHoveredOrFocused") {
    mounted(ActionPermissionWrapper(action, Val(unavailable))) { root =>
      val tooltip = root.querySelector("[role='tooltip']")
      assertEquals(attributeOf(tooltip, "hidden"), Some(""))

      dispatch(byTestId(root, "action"), new dom.MouseEvent("mouseenter", new dom.MouseEventInit {}))
      assertEquals(attributeOf(tooltip, "hidden"), None)
    }
  }

  test("aCallerCanSaySomethingMoreSpecificThanTheDefault") {
    val wrapper = ActionPermissionWrapper(
      action,
      Val(unavailable),
      capabilityMessage = _ => Some("You cannot delete a topic while the cluster service is down.")
    )

    mounted(wrapper) { root =>
      assertEquals(
        root.querySelector("[role='tooltip']").textContent,
        "You cannot delete a topic while the cluster service is down."
      )
    }
  }
}
