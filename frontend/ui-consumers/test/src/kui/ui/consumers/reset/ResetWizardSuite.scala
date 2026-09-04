package kui.ui.consumers.reset

import java.time.Instant

import scala.collection.mutable

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.consumer.contract.dto.{PlannedPartitionDto, ResetPlanDto, ResetPlanRequest, ResetWarningDto}
import kui.contracts.consumer.{PartitionDto, TopicSubscriptionDto}
import kui.kernel.group.ResetTarget
import kui.kernel.{GroupId, TopicName}
import kui.ui.kernel.api.ApiError

/** The offset-reset wizard, and the form it builds its first request from.
  *
  * What is actually being protected here is ADR-045's shape: the destructive request is the second one, it
  * carries only the token the first one returned, and there is no path through this screen that writes
  * offsets the operator has not been shown.
  */
class ResetWizardSuite extends FunSuite {

  private def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, element)
    try check(element.ref)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    Option(root.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId'"))

  private def find(root: dom.Element, testId: String): Option[dom.Element] =
    Option(root.querySelector(s"[data-testid='$testId']"))

  private def click(element: dom.Element): Unit =
    element.dispatchEvent(new dom.MouseEvent("click", new dom.MouseEventInit { bubbles = true })): Unit

  private val at: Instant = Instant.parse("2026-09-04T09:05:00Z")

  private def partition(id: Int): PartitionDto =
    PartitionDto(id, committed = Some(40L), begin = Some(0L), end = Some(100L), lag = Some(60L), anomalies = Nil, memberId = None, host = None)

  private val topics: List[TopicSubscriptionDto] =
    List(TopicSubscriptionDto(TopicName.unsafe("orders"), lag = Some(60L), excludedPartitions = 0, partitions = List(partition(0), partition(1))))

  private def planDto(
      partitions: List[PlannedPartitionDto],
      warnings: List[ResetWarningDto] = Nil,
      noOp: Boolean = false,
      token: String = "plan.v1.abc"
  ): ResetPlanDto =
    ResetPlanDto(
      groupId = GroupId.unsafe("orders-indexer"),
      topic = TopicName.unsafe("orders"),
      target = ResetTarget.Earliest,
      partitions = partitions,
      warnings = warnings,
      noOp = noOp,
      token = token,
      expiresAt = at.plusSeconds(300)
    )

  private val proposed: List[PlannedPartitionDto] =
    List(
      PlannedPartitionDto(partition = 0, current = Some(40L), proposed = 0L, delta = Some(-40L)),
      PlannedPartitionDto(partition = 1, current = None, proposed = 0L, delta = None)
    )

  // --- The form ----------------------------------------------------------------------------------

  private val form: ResetForm = ResetForm.Empty.copy(topic = "orders")

  test("aTargetWithNoParameterNeedsNothingElse") {
    val request = ResetForm.requestOf(form.copy(target = ResetTarget.Latest), List(0, 1))
    assertEquals(request.map(_.target), Right(ResetTarget.Latest))
    assertEquals(request.map(_.partitions), Right(List(0, 1)))
  }

  test("theScopeIsNamedExplicitlyRatherThanLeftAsEveryPartition") {
    // An empty partition list means "all of them" to the server. Naming them keeps the plan to exactly what
    // the screen showed, so a partition added to the topic since the page loaded is not swept in.
    assertEquals(ResetForm.requestOf(form, List(3, 1)).map(_.partitions), Right(List(1, 3)))
  }

  test("anOffsetResetPutsTheSameOffsetOnEveryPartitionInScope") {
    val request = ResetForm.requestOf(form.copy(target = ResetTarget.Offset, offset = "500"), List(0, 1))
    assertEquals(request.map(_.offsets), Right(Some(Map("0" -> 500L, "1" -> 500L))))
  }

  test("aShiftMayBeNegativeBecauseRewindingIsThePointOfIt") {
    val request = ResetForm.requestOf(form.copy(target = ResetTarget.ShiftBy, shiftBy = "-200"), List(0))
    assertEquals(request.map(_.shiftBy), Right(Some(-200L)))
  }

  test("eachTargetsMissingParameterIsRefusedHereRatherThanSentAndDefaulted") {
    // The server refuses these too, and deliberately: defaulting a missing timestamp to "now" would reset a
    // consumer group to a point in time nobody asked for. This is the same refusal, one round trip earlier.
    assert(ResetForm.requestOf(form.copy(target = ResetTarget.Offset), List(0)).isLeft)
    assert(ResetForm.requestOf(form.copy(target = ResetTarget.Timestamp), List(0)).isLeft)
    assert(ResetForm.requestOf(form.copy(target = ResetTarget.ShiftBy), List(0)).isLeft)
    assert(ResetForm.requestOf(form.copy(target = ResetTarget.Duration), List(0)).isLeft)
  }

  test("aNegativeOffsetIsNotAnOffset") {
    assertEquals(ResetForm.requestOf(form.copy(target = ResetTarget.Offset, offset = "-1"), List(0)), Left(kui.ui.consumers.Messages.BadOffset))
  }

  test("aTopicWithNoPartitionsInScopeIsRefused") {
    assertEquals(ResetForm.requestOf(form, Nil), Left(kui.ui.consumers.Messages.NoPartitions))
  }

  test("aDurationIsSentAsMilliseconds") {
    val request = ResetForm.requestOf(form.copy(target = ResetTarget.Duration, durationMinutes = "90"), List(0))
    assertEquals(request.map(_.durationMs), Right(Some(5400000L)))
  }

  // --- The wizard --------------------------------------------------------------------------------

  final private class Rig(
      plans: List[Either[ApiError, ResetPlanDto]],
      applies: List[Either[ApiError, ResetPlanDto]] = Nil
  ) {
    val planned: mutable.ListBuffer[ResetPlanRequest] = mutable.ListBuffer.empty
    val applied: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    private var remainingPlans = plans
    private var remainingApplies = applies

    val element: HtmlElement = ResetWizard(
      topics = topics,
      plan = request => {
        planned.append(request)
        remainingPlans match {
          case head :: tail => remainingPlans = tail; EventStream.fromValue(head)
          case Nil => EventStream.empty
        }
      },
      applyPlan = token => {
        applied.append(token)
        remainingApplies match {
          case head :: tail => remainingApplies = tail; EventStream.fromValue(head)
          case Nil => EventStream.empty
        }
      },
      zone = Val("UTC"),
      now = () => at
    )
  }

  test("nothingIsAskedForUntilTheWizardIsOpened") {
    val rig = new Rig(List(Right(planDto(proposed))))
    mounted(rig.element) { root =>
      assertEquals(find(root, "group-reset-form"), None)
      assertEquals(rig.planned.toList, Nil)
    }
  }

  test("thePreviewAsksForAPlanAndChangesNothing") {
    val rig = new Rig(List(Right(planDto(proposed))))
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))

      assertEquals(rig.planned.size, 1)
      assertEquals(rig.planned.head.topic.value, "orders")
      // The point of the whole flow: asking what would happen writes nothing.
      assertEquals(rig.applied.toList, Nil)
      byTestId(root, "group-reset-plan"): Unit
    }
  }

  test("thePlanShowsWhatEachPartitionWouldMoveFromAndTo") {
    val rig = new Rig(List(Right(planDto(proposed))))
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))

      val table = byTestId(root, "group-reset-plan-table").textContent
      assert(table.contains("40"), table)
      assert(table.contains("-40"), table)
    }
  }

  test("aPartitionWithNoCommittedOffsetShowsADashAndNeverAZero") {
    // A zero would say the consumer sits at the beginning of the log, when in fact nobody knows where it is.
    val rig = new Rig(List(Right(planDto(List(PlannedPartitionDto(0, None, 0L, None))))))
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))

      val cells = byTestId(root, "group-reset-plan-table").querySelectorAll("[title]")
      assert(cells.length > 0, "the missing 'from' cell carries no explanation")
    }
  }

  test("theApplyButtonSendsTheTokenAndNothingElse") {
    // ADR-045's whole point. There is no path from this screen that carries a specification to the write.
    val rig = new Rig(
      plans = List(Right(planDto(proposed, token = "plan.v1.signed"))),
      applies = List(Right(planDto(proposed, token = "plan.v1.signed")))
    )
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))
      click(byTestId(root, "group-reset-apply"))

      assertEquals(rig.applied.toList, List("plan.v1.signed"))
      byTestId(root, "group-reset-receipt"): Unit
    }
  }

  test("theReceiptIsWhatTheServerSaysItWroteAndNotWhatWasAskedFor") {
    val wrote = List(PlannedPartitionDto(partition = 0, current = Some(9L), proposed = 16L, delta = Some(7L)))
    val rig = new Rig(plans = List(Right(planDto(proposed))), applies = List(Right(planDto(wrote))))
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))
      click(byTestId(root, "group-reset-apply"))

      val receipt = byTestId(root, "group-reset-receipt-table").textContent
      assert(receipt.contains("16"), receipt)
      assert(receipt.contains("+7"), receipt)
    }
  }

  test("aPlanThatChangesNothingOffersNothingToConfirm") {
    // A confirmation dialogue for an operation that changes nothing teaches operators to click through
    // confirmation dialogues.
    val rig = new Rig(List(Right(planDto(proposed, noOp = true))))
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))

      byTestId(root, "group-reset-noop"): Unit
      assertEquals(find(root, "group-reset-apply"), None)
    }
  }

  test("everyWarningIsOnScreenAboveTheNumbersItQualifies") {
    // Clamping is the case warnings exist for: an operator who asked for offset 9 000 000 on a partition
    // that holds four hundred records has to see what will actually be written.
    val rig = new Rig(
      List(
        Right(
          planDto(
            proposed,
            warnings = List(ResetWarningDto("CLAMPED", Some(0), "9000000 is past the end of partition 0"))
          )
        )
      )
    )
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))
      assert(byTestId(root, "group-reset-warnings").textContent.contains("past the end"), "no warning shown")
    }
  }

  test("aRefusedPlanGoesBackToTheFormWithTheReason") {
    val rig = new Rig(List(Left(ApiError.Unreachable("connection refused"))))
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))

      byTestId(root, "group-reset-form"): Unit
      assert(byTestId(root, "group-reset-problem").textContent.nonEmpty)
    }
  }

  test("anExpiredTokenLeavesThePlanOnScreenRatherThanRePlanningSilently") {
    // Re-planning would compute different offsets from the ones the operator read, which is precisely what
    // the two-phase flow exists to prevent.
    val rig = new Rig(
      plans = List(Right(planDto(proposed))),
      applies = List(Left(ApiError.Unreachable("the plan token is not valid")))
    )
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      click(byTestId(root, "group-reset-preview"))
      click(byTestId(root, "group-reset-apply"))

      byTestId(root, "group-reset-plan"): Unit
      assertEquals(find(root, "group-reset-receipt"), None)
      assertEquals(rig.planned.size, 1, clue = "the wizard re-planned behind the operator's back")
    }
  }

  test("aFormRefusalNeverReachesTheServer") {
    val rig = new Rig(Nil)
    mounted(rig.element) { root =>
      click(byTestId(root, "group-reset-open"))
      // Switching to a target whose parameter is empty and pressing preview.
      // The test id is on the `<select>` itself; the placeholder row is index 0, so a target sits one
      // further along than its position in the list.
      val select = byTestId(root, "group-reset-target").asInstanceOf[dom.html.Select]
      select.selectedIndex = ResetTarget.All.indexOf(ResetTarget.Offset) + 1
      select.dispatchEvent(new dom.Event("change", new dom.EventInit { bubbles = true })): Unit
      click(byTestId(root, "group-reset-preview"))

      assertEquals(rig.planned.toList, Nil)
      assertEquals(byTestId(root, "group-reset-problem").textContent, kui.ui.consumers.Messages.BadOffset)
    }
  }
}
