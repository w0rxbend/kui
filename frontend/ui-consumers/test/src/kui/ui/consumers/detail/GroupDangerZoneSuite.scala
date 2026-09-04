package kui.ui.consumers.detail

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.consumer.contract.dto.DeletedOffsetsDto
import kui.contracts.consumer.TopicSubscriptionDto
import kui.kernel.{GroupId, TopicName}
import kui.ui.consumers.Messages
import kui.ui.kernel.api.ApiError
import kui.kernel.error.ErrorCode

/** The two controls that throw a consumer group's state away.
  *
  * The endpoints behind them have answered since M4; what did not exist was any way to reach them. So the
  * assertions here are mostly about the screen keeping its promises rather than about the wire: that nothing
  * is sent until a confirmation is given, that a refusal the operator can act on is shown as a sentence, and
  * that "the group had no offsets there" is distinguishable from "the offsets are gone".
  */
final class GroupDangerZoneSuite extends FunSuite {

  private val group: GroupId = GroupId.unsafe("orders-indexer")

  private val subscriptions: List[TopicSubscriptionDto] =
    List(
      TopicSubscriptionDto(TopicName.unsafe("orders"), Some(12L), 0, Nil),
      TopicSubscriptionDto(TopicName.unsafe("shipments"), Some(0L), 0, Nil)
    )

  final private class Fixture(
      groupOutcome: Either[ApiError, Unit] = Right(()),
      offsetsOutcome: Either[ApiError, DeletedOffsetsDto] =
        Right(DeletedOffsetsDto(GroupId.unsafe("orders-indexer"), TopicName.unsafe("orders"), List(0, 1, 2)))
  ) {
    var groupCalls: Int = 0
    var offsetCalls: List[String] = Nil
    var navigatedAway: Boolean = false

    val element: HtmlElement = GroupDangerZone(
      group = group,
      topics = Val(subscriptions),
      deleteGroup = () => {
        groupCalls += 1
        EventStream.fromValue(groupOutcome)
      },
      deleteOffsets = topic => {
        offsetCalls = offsetCalls :+ topic.value
        EventStream.fromValue(offsetsOutcome)
      },
      onDeleted = () => navigatedAway = true
    )
  }

  private def mounted[A](fixture: Fixture)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, fixture.element)
    try check(fixture.element.ref)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def optionalTestId(root: dom.Element, testId: String): Option[dom.Element] =
    Option(root.querySelector(s"[data-testid='$testId']"))

  private def byTestId(root: dom.Element, testId: String): dom.Element =
    optionalTestId(root, testId)
      .getOrElse(fail(s"no element with data-testid='$testId' in ${root.outerHTML}"))

  private def click(element: dom.Element): Unit =
    element.asInstanceOf[dom.HTMLElement].click()

  /** The confirm dialogue is rendered into the document rather than inside this element, so it is looked
    * for from the document root.
    */
  private def inDocument(testId: String): dom.Element =
    Option(dom.document.querySelector(s"[data-testid='$testId']"))
      .getOrElse(fail(s"no element with data-testid='$testId' anywhere in the document"))

  test("pressingDeleteSendsNothingUntilTheConfirmationIsGiven") {
    // The whole safety property of this panel. There is no plan token here, so the dialogue is the only
    // thing between a mis-click and a group that is gone.
    val fixture = new Fixture
    mounted(fixture) { root =>
      click(byTestId(root, "group-delete-button"))
      assertEquals(fixture.groupCalls, 0, "the click must open a dialogue, not send a request")

      click(inDocument("group-delete-confirm-confirm"))
      assertEquals(fixture.groupCalls, 1)
      assert(fixture.navigatedAway, "a page about a deleted group describes nothing")
    }
  }

  test("cancellingTheConfirmationSendsNothing") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      click(byTestId(root, "group-delete-button"))
      click(inDocument("group-delete-confirm-cancel"))
      assertEquals(fixture.groupCalls, 0)
      assert(!fixture.navigatedAway)
    }
  }

  test("aGroupThatStillHasMembersIsRefusedInWordsAndTheScreenStays") {
    // Kafka's refusal, and the one an operator can act on directly — by stopping their consumers. The
    // panel deliberately does not pre-empt it: a consumer can join between the click and the write.
    val refusal = ApiError.Envelope(
      ErrorCode.GroupNotEmpty.wire,
      "the group still has members",
      Nil,
      "correlation-1",
      false
    )

    val fixture = new Fixture(groupOutcome = Left(refusal))
    mounted(fixture) { root =>
      click(byTestId(root, "group-delete-button"))
      click(inDocument("group-delete-confirm-confirm"))

      assert(!fixture.navigatedAway, "a refused delete must leave the operator on the group")
      val message = byTestId(root, "group-danger-error").textContent
      assert(message.nonEmpty, "a refusal with no sentence is a button that did nothing")
    }
  }

  test("offsetsAreForgottenOnOneNamedTopicAndOnlyAfterConfirmation") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      click(byTestId(root, "group-forget-offsets-shipments"))
      assertEquals(fixture.offsetCalls, Nil)

      click(inDocument("group-forget-offsets-confirm-confirm"))
      assertEquals(
        fixture.offsetCalls,
        List("shipments"),
        "the request must be about the topic whose button was pressed"
      )
    }
  }

  test("theReceiptSaysHowManyPartitionsWereForgotten") {
    val fixture = new Fixture
    mounted(fixture) { root =>
      click(byTestId(root, "group-forget-offsets-orders"))
      click(inDocument("group-forget-offsets-confirm-confirm"))

      val receipt = byTestId(root, "group-forget-offsets-receipt").textContent
      assert(receipt.contains("3"), s"the partition count is the point of the receipt, got $receipt")
      assert(receipt.contains("orders"), receipt)
    }
  }

  test("aGroupThatHeldNoOffsetsThereSaysSoRatherThanClaimingADeletion") {
    // A bare "done" cannot tell the two apart, and the difference decides whether the operator has
    // finished or is looking in the wrong place.
    val fixture = new Fixture(
      offsetsOutcome = Right(DeletedOffsetsDto(group, TopicName.unsafe("orders"), Nil))
    )

    mounted(fixture) { root =>
      click(byTestId(root, "group-forget-offsets-orders"))
      click(inDocument("group-forget-offsets-confirm-confirm"))

      val receipt = byTestId(root, "group-forget-offsets-receipt").textContent
      assertEquals(receipt, Messages.forgotOffsets("orders", 0))
    }
  }
}
