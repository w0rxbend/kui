package kui.ui.topics.detail

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.contracts.topic.PartitionDto
import kui.ui.topics.Messages

/** What an empty partition table says, which depends on why it is empty.
  *
  * Found by stopping the quickstart's broker and opening a topic. The detail page fell back to the topic-list
  * snapshot — which is the right thing to do, and the stale badge said so — and the partition table underneath
  * announced "The broker reported no partitions for this topic, which is unusual — a topic always has at
  * least one." No broker had reported anything. The sentence was a confident false statement about a
  * six-partition topic, shown to an operator at the exact moment they were investigating an outage.
  *
  * The two sentences are two different facts and the table has to be able to tell them apart.
  */
final class PartitionTableEmptySuite extends FunSuite {

  private def textOf(stale: Boolean, loading: Boolean = false): String = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(
      container,
      PartitionTable(Val(List.empty[PartitionDto]), Var(600), Val(stale), Val(loading))
    )

    try container.textContent
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  test("a live read that returned no partitions says the broker reported none") {
    val text = textOf(stale = false)

    assert(text.contains(Messages.NoPartitionsTitle), text)
    assert(text.contains(Messages.NoPartitions), text)
  }

  test("a table filled from the stale snapshot never claims the broker reported no partitions") {
    val text = textOf(stale = true)

    assert(text.contains(Messages.NoPartitionsStaleTitle), text)
    assert(text.contains(Messages.NoPartitionsStale), text)
    assert(
      !text.contains(Messages.NoPartitions),
      s"the stale table repeated the live read's sentence, which is false here: $text"
    )
  }

  /** Found in a browser against the demonstration environment: opening a topic with twenty-four partitions
    * showed "No partitions - the broker reported no partitions for this topic, which is unusual" for the
    * second the first request was in flight, and then replaced it with the twenty-four rows. The screen was
    * not slow; it was wrong, and then it was right.
    */
  test("a table whose first read has not come back yet does not claim there are no partitions") {
    val text = textOf(stale = false, loading = true)

    assert(text.contains(Messages.PartitionsLoadingTitle), text)
    assert(
      !text.contains(Messages.NoPartitions),
      s"a table that is still reading announced that the broker reported no partitions: $text"
    )
  }

  test("loading wins over staleness, because nothing has been read yet to be stale") {
    val text = textOf(stale = true, loading = true)

    assert(text.contains(Messages.PartitionsLoadingTitle), text)
    assert(!text.contains(Messages.NoPartitionsStale), text)
  }
}
