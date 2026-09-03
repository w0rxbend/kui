package kui.kafka

import cats.effect.IO
import cats.syntax.all.*

import kui.kafka.auth.ClientPurpose
import kui.kernel.ClusterId
import kui.testkit.KuiIOSuite

/** That two KUI clients never share a `client.id`.
  *
  * A broker's request log and its quota accounting are both keyed by `client.id`. Two clients
  * sharing one makes the log unattributable and makes a quota meant for one of them apply to both.
  */
final class ClientIdSuite extends KuiIOSuite {

  private val prod: ClusterId = ClusterId.unsafe("prod")
  private val staging: ClusterId = ClusterId.unsafe("staging")

  test("formatIsPurposeClusterSeq") {
    ClientId.next[IO](ClientPurpose.Admin, prod).map { id =>
      assert(id.value.startsWith("kui-admin-prod-"), id.value)
      assert(id.value.drop("kui-admin-prod-".length).forall(_.isDigit), id.value)
    }
  }

  test("everyPurposeHasItsOwnPrefix") {
    ClientPurpose.values.toList
      .traverse(purpose => ClientId.next[IO](purpose, prod).map(_.value -> purpose.prefix))
      .map(_.foreach((value, prefix) => assert(value.startsWith(s"$prefix-prod-"), value)))
  }

  test("sequenceIncreasesAcrossCalls") {
    for {
      first <- ClientId.next[IO](ClientPurpose.Admin, prod)
      second <- ClientId.next[IO](ClientPurpose.Admin, prod)
    } yield assertNotEquals(first.value, second.value)
  }

  test("twoClustersGetDistinctIds") {
    for {
      one <- ClientId.next[IO](ClientPurpose.Admin, prod)
      other <- ClientId.next[IO](ClientPurpose.Admin, staging)
    } yield {
      assertNotEquals(one.value, other.value)
      assert(one.value.contains("prod"))
      assert(other.value.contains("staging"))
    }
  }

  test("aHundredConcurrentIdsAreAllDistinct") {
    // The counter is process-wide and shared; a client rebuilt by invalidation has to be
    // distinguishable in the broker log from the one it replaced.
    List
      .fill(100)(ClientId.next[IO](ClientPurpose.Admin, prod))
      .parSequence
      .map(ids => assertEquals(ids.map(_.value).distinct.size, 100))
  }
}
