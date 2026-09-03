package kui.testkit

import org.scalacheck.Prop.forAll

import kui.kernel.*

/** That the generators generate what they claim.
  *
  * A generator nobody checks is a silent source of false confidence: if `validTopicName` produced
  * something Kafka would reject, every property built on it would be testing the wrong thing, and
  * they would all still be green.
  */
final class GeneratorsSuite extends KuiSuite {

  import Generators.given

  property("every generated cluster id is one the kernel accepts") {
    forAll(Generators.validSlug)(raw => assert(ClusterId.from(raw).isRight, raw))
  }

  property("every generated topic name is one the kernel accepts") {
    forAll(Generators.validTopicName)(raw => assert(TopicName.from(raw).isRight, raw))
  }

  property("every generated correlation id is one the kernel accepts") {
    forAll(Generators.validCorrelationId)(raw => assert(CorrelationId.from(raw).isRight, raw))
  }

  property("every generated host is one the kernel accepts") {
    forAll(Generators.validHost)(raw => assert(Host.from(raw).isRight, raw))
  }

  property("every generated bounded name is one the kernel accepts") {
    forAll(Generators.validBoundedName)(raw => assert(GroupId.from(raw).isRight, raw))
  }

  property("every invalid cluster id is one the kernel refuses") {
    forAll(Generators.invalidClusterId)(raw => assert(ClusterId.from(raw).isLeft, raw))
  }

  property("every invalid topic name is one the kernel refuses") {
    forAll(Generators.invalidTopicName)(raw => assert(TopicName.from(raw).isLeft, raw))
  }

  property("every invalid page size is one the kernel refuses") {
    forAll(Generators.invalidPageSize)(raw => assert(PageSize.from(raw).isLeft, raw.toString))
  }

  property("every invalid port is one the kernel refuses") {
    forAll(Generators.invalidPort)(raw => assert(Port.from(raw).isLeft, raw.toString))
  }

  property("every invalid host is one the kernel refuses") {
    forAll(Generators.invalidHost)(raw => assert(Host.from(raw).isLeft, raw))
  }

  property("arbitrary identifiers are always valid values of their type") {
    forAll { (topic: TopicName, partition: PartitionId, offset: Offset) =>
      assert(TopicName.from(topic.value).isRight)
      assert(PartitionId.from(partition.value).isRight)
      assert(Offset.from(offset.value).isRight)
    }
  }

  property("an arbitrary offset range is never inverted") {
    forAll { (range: OffsetRange) =>
      assert(range.size >= 0L)
      assert(OffsetRange.from(range.from, range.until).isRight)
    }
  }

  property("an arbitrary page's metadata agrees with its contents") {
    forAll(Generators.pageOf[Int]) { page =>
      assertEquals(page.totalItems, Some(page.items.size.toLong))
      assert(page.items.sizeIs <= page.pageSize)
      assert(page.page >= 1)
    }
  }

  property("an arbitrary page request is inside the bounds ADR-026 fixes") {
    forAll { (request: PageRequest) =>
      assert(PageSize.from(request.pageSize.value).isRight)
      assert(request.page.value >= 1)
    }
  }

  property("arbitrary value objects are valid") {
    forAll { (port: Port, size: ByteSize, positive: PositiveInt) =>
      assert(Port.from(port.value).isRight)
      assert(ByteSize.from(size.value).isRight)
      assert(PositiveInt.from(positive.value).isRight)
    }
  }
}
