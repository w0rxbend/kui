package kui.ui.topics.admin

import java.time.Instant

import scala.collection.mutable

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.kernel.{Offset, PartitionId, TopicName}
import kui.message.contract.{PurgePartitionPlanDto, PurgePlanDto, PurgeReceiptDto, PurgeResultDto, PurgeWarningDto}
import kui.topic.contract.dto.{DeletionPlanDto, PartitionPlanDto, PlanWarningDto}
import kui.ui.kernel.api.ApiError

/** The two changes to a topic that cannot be undone, driven the way an operator drives them.
  *
  * What these assert is the property ADR-045 exists for and that a screen is the only place to check: **the
  * confirm button cannot exist before a plan does**, and what is sent when it is pressed is the plan's token
  * and nothing else. A test of the contract can show that the endpoint takes only a token; only a test of the
  * screen can show that the screen never invents one.
  */
final class TopicAdminPanelSuite extends FunSuite {

  private val topic = TopicName.unsafe("orders.v1")
  private val at = Instant.parse("2026-09-04T09:00:00Z")

  private def partitionPlan(current: Int, target: Int, token: Option[String]): PartitionPlanDto =
    PartitionPlanDto(
      topic = topic,
      current = current,
      target = target,
      warnings = List(PlanWarningDto("KEY_ROUTING_CHANGES", "routing changes for every future record")),
      token = token,
      expiresAt = token.map(_ => at.plusSeconds(300)),
      computedAt = at
    )

  private def deletionPlan(records: Option[Long], token: Option[String]): DeletionPlanDto =
    DeletionPlanDto(
      topic = topic,
      partitions = 3,
      records = records,
      autoCreateEnabled = Some(true),
      warnings = List(PlanWarningDto("AUTO_CREATE_ENABLED", "the topic can come straight back")),
      token = token,
      expiresAt = token.map(_ => at.plusSeconds(300)),
      computedAt = at
    )

  private def purgePlan(records: Long, token: Option[String]): PurgePlanDto =
    PurgePlanDto(
      topic = topic,
      partitions = List(
        PurgePartitionPlanDto(PartitionId.unsafe(0), Offset.unsafe(0L), Offset.unsafe(records))
      ),
      warnings = List(PurgeWarningDto("RECORDS_LOST", "the records are gone")),
      token = token,
      expiresAt = token.map(_ => at.plusSeconds(300)),
      computedAt = at
    )

  /** Records every call the panel makes, so "it sent the token it was given" is an assertion about what went
    * out rather than about what came back.
    */
  final private class Calls {
    val partitionPlans: mutable.ListBuffer[Int] = mutable.ListBuffer.empty
    val partitionTokens: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val deletionPlans: mutable.ListBuffer[Unit] = mutable.ListBuffer.empty
    val deletionTokens: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val purgePlans: mutable.ListBuffer[Unit] = mutable.ListBuffer.empty
    val purgeTokens: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    var deleted: Int = 0
  }

  private def panelWith(
      calls: Calls,
      plannedPartitions: Either[ApiError, PartitionPlanDto] = Right(partitionPlan(3, 6, Some("p-token"))),
      appliedPartitions: Either[ApiError, PartitionPlanDto] = Right(partitionPlan(3, 6, None)),
      plannedDeletion: Either[ApiError, DeletionPlanDto] = Right(deletionPlan(Some(16L), Some("d-token"))),
      appliedDeletion: Either[ApiError, DeletionPlanDto] = Right(deletionPlan(Some(16L), None)),
      plannedPurge: Either[ApiError, PurgePlanDto] = Right(purgePlan(16L, Some("g-token"))),
      appliedPurge: Either[ApiError, PurgeReceiptDto] =
        Right(PurgeReceiptDto(purgePlan(16L, None), PurgeResultDto(Nil, Nil))),
      deletePermitted: Boolean = true
  ): HtmlElement =
    TopicAdminPanel(
      topic = topic,
      partitionCount = Val(Some(3)),
      planPartitions = count => {
        calls.partitionPlans.append(count)
        EventStream.fromValue(plannedPartitions)
      },
      applyPartitions = token => {
        calls.partitionTokens.append(token)
        EventStream.fromValue(appliedPartitions)
      },
      planDeletion = () => {
        calls.deletionPlans.append(())
        EventStream.fromValue(plannedDeletion)
      },
      applyDeletion = token => {
        calls.deletionTokens.append(token)
        EventStream.fromValue(appliedDeletion)
      },
      planPurge = () => {
        calls.purgePlans.append(())
        EventStream.fromValue(plannedPurge)
      },
      applyPurge = token => {
        calls.purgeTokens.append(token)
        EventStream.fromValue(appliedPurge)
      },
      onDeleted = () => calls.deleted += 1,
      deletePermitted = Val(deletePermitted)
    )

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

  private def find(root: dom.Element, testId: String): Option[dom.Element] =
    Option(root.querySelector(s"[data-testid='$testId']"))

  private def click(root: dom.Element, testId: String): Unit =
    find(root, testId)
      .map(_.asInstanceOf[dom.HTMLElement])
      .getOrElse(fail(s"$testId is not on the screen"))
      .click()

  private def typeInto(root: dom.Element, testId: String, value: String): Unit = {
    val input = find(root, testId).getOrElse(fail(s"$testId is not on the screen"))
    input.asInstanceOf[dom.html.Input].value = value
    input.dispatchEvent(new dom.Event("input", new dom.EventInit { bubbles = true })): Unit
  }

  test("thereIsNothingToConfirmUntilAPlanHasBeenRead") {
    // Not "the button is disabled" — the button is not there. The apply endpoint takes a token and there is
    // nothing else the screen could send, which is what makes skipping the preview impossible rather than
    // merely discouraged.
    val calls = new Calls
    mounted(panelWith(calls)) { root =>
      assert(find(root, "topic-partitions-apply").isEmpty)
      assert(find(root, "topic-delete-apply").isEmpty)
      assert(find(root, "topic-purge-apply").isEmpty)
      assertEquals(calls.partitionTokens.toList, Nil)
      assertEquals(calls.deletionTokens.toList, Nil)
      assertEquals(calls.purgeTokens.toList, Nil)
    }
  }

  test("thePartitionPlanIsShownWithItsWarningAndAppliedAgainstItsOwnToken") {
    val calls = new Calls
    mounted(panelWith(calls)) { root =>
      typeInto(root, "topic-partitions-target", "6")
      click(root, "topic-partitions-plan")

      assertEquals(calls.partitionPlans.toList, List(6))
      assert(find(root, "topic-partitions-plan-result").isDefined)
      assert(
        find(root, "topic-plan-warning-key-routing-changes").isDefined,
        "the warning that routing changes must be on the screen the operator confirms"
      )

      click(root, "topic-partitions-apply")

      // The token the plan carried, and never the number that was typed.
      assertEquals(calls.partitionTokens.toList, List("p-token"))
      assertEquals(find(root, "topic-partitions-receipt").map(_.textContent.nonEmpty), Some(true))
    }
  }

  test("aTargetThatIsNotANumberIsRefusedWithoutAskingTheServer") {
    val calls = new Calls
    mounted(panelWith(calls)) { root =>
      typeInto(root, "topic-partitions-target", "lots")
      click(root, "topic-partitions-plan")

      assertEquals(calls.partitionPlans.toList, Nil)
      assert(find(root, "topic-partitions-error").isDefined)
    }
  }

  test("aDeleteTakesAPlanAndThenATypedConfirmation") {
    // Two gates, and they guard different mistakes: the plan answers "what will this do", and the dialog
    // answers "did you mean this row".
    val calls = new Calls
    mounted(panelWith(calls)) { root =>
      click(root, "topic-delete-plan")
      assertEquals(calls.deletionPlans.size, 1)
      assert(find(root, "topic-plan-warning-auto-create-enabled").isDefined)

      click(root, "topic-delete-apply")
      // The dialog is open and nothing has been sent yet.
      assertEquals(calls.deletionTokens.toList, Nil)

      click(root, "topic-delete-confirm-confirm")
      assertEquals(calls.deletionTokens.toList, List("d-token"))
      assertEquals(calls.deleted, 1)
    }
  }

  test("aUserWhoMayNotDeleteIsGivenADisabledButtonAndAReason") {
    // E4's worked example, from the screen's end. The decision is `Rbac.decide`'s, made from the permission
    // list `/auth/me` returned; what this asserts is that the screen acts on it — the control is disabled
    // and says why, rather than being offered and then refused by the server.
    val calls = new Calls
    mounted(panelWith(calls, deletePermitted = false)) { root =>
      val button = find(root, "topic-delete-plan").getOrElse(fail("no delete button"))
      assertEquals(button.getAttribute("aria-disabled"), "true")
      assert(button.hasAttribute("disabled"))

      click(root, "topic-delete-plan")
      // Nothing was even planned. A plan the user cannot apply is a page of counts that ends in a refusal.
      assertEquals(calls.deletionPlans.toList, Nil)
    }
  }

  test("theReceiptOfADeleteStaysOnScreenAndThePartitionControlsGo") {
    // The M4 lesson, kept: a receipt is the operator's only record of an irreversible action, and this one
    // has to survive the page reacting to the delete. What must *not* survive is a control offering to grow
    // a topic that no longer exists.
    val calls = new Calls
    mounted(panelWith(calls)) { root =>
      click(root, "topic-delete-plan")
      click(root, "topic-delete-apply")
      click(root, "topic-delete-confirm-confirm")

      val receipt = find(root, "topic-delete-receipt").getOrElse(fail("the delete left no receipt"))
      assert(receipt.textContent.contains(topic.value), receipt.textContent)

      val partitions =
        find(root, "topic-partitions-section").getOrElse(fail("the partitions section vanished entirely"))
      assert(
        partitions.getAttribute("class").contains("kui-topics__hidden"),
        s"the partition controls are still offered for a deleted topic: ${partitions.getAttribute("class")}"
      )
    }
  }

  test("aRefusedPlanShowsTheServersSentenceAndOffersNoConfirmation") {
    val calls = new Calls
    val refusal = ApiError.Envelope("KUI-READ-ONLY", "cluster staging is configured read-only", Nil, "c1", false)

    mounted(panelWith(calls, plannedDeletion = Left(refusal))) { root =>
      click(root, "topic-delete-plan")

      assertEquals(calls.deletionTokens.toList, Nil)
      assert(find(root, "topic-delete-apply").isEmpty)
      val shown = find(root, "topic-delete-error").getOrElse(fail("the refusal was not shown"))
      assert(shown.textContent.nonEmpty, "a refusal with no sentence tells the operator nothing")
    }
  }

  test("aPurgeTakesAPlanAndThenATypedConfirmationAndReportsWhatWentt") {
    val calls = new Calls
    mounted(panelWith(calls)) { root =>
      click(root, "topic-purge-plan")
      assertEquals(calls.purgePlans.size, 1)
      assert(find(root, "topic-plan-warning-records-lost").isDefined)

      click(root, "topic-purge-apply")
      assertEquals(calls.purgeTokens.toList, Nil)

      click(root, "topic-purge-confirm-confirm")
      assertEquals(calls.purgeTokens.toList, List("g-token"))

      val receipt = find(root, "topic-purge-receipt").getOrElse(fail("the purge left no receipt"))
      assert(receipt.textContent.contains("16"), receipt.textContent)
    }
  }

  test("aPurgeOfAnEmptyTopicOffersNothingToConfirm") {
    // A confirmation dialogue for an operation that changes nothing is how operators learn to click
    // through confirmation dialogues.
    val calls = new Calls
    mounted(panelWith(calls, plannedPurge = Right(purgePlan(0L, Some("g-token"))))) { root =>
      click(root, "topic-purge-plan")

      assert(find(root, "topic-purge-plan-result").isDefined)
      assert(find(root, "topic-purge-apply").isEmpty)
      assertEquals(calls.purgeTokens.toList, Nil)
    }
  }
}
