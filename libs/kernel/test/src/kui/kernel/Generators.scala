package kui.kernel

import org.scalacheck.{Arbitrary, Gen}

/** ScalaCheck generators for the kernel vocabulary.
  *
  * Two families live here on purpose. The `valid*` generators produce values that the matching smart
  * constructor accepts, which is what a property about behaviour needs; the `invalid*` ones produce
  * values it must reject, which is what a property about validation needs. Writing the second family
  * by hand — rather than "any string that is not valid" — keeps the rejected cases readable in a
  * failure report.
  *
  * KERN-007 moves this file to `libs/testkit` so that every service's suites share it. It lives in
  * the kernel's own test sources until that module exists.
  */
object Generators {

  private val lowerAlphaNum: Gen[Char] = Gen.oneOf(('a' to 'z') ++ ('0' to '9'))

  /** A slug: lowercase letters, digits and dashes, 1 to 64 characters, never starting or ending with
    * a dash (ADR-031).
    */
  val validSlug: Gen[String] = for {
    first  <- lowerAlphaNum
    middle <- Gen.listOfN(30, Gen.oneOf(lowerAlphaNum, Gen.const('-'))).flatMap(Gen.someOf(_))
    last   <- lowerAlphaNum
  } yield (first +: middle.toList :+ last).mkString

  val invalidClusterId: Gen[String] = Gen.oneOf(
    Gen.const(""),
    Gen.const("-leading-dash"),
    Gen.const("trailing-dash-"),
    Gen.const("Upper"),
    Gen.const("has space"),
    Gen.const("has.dot"),
    Gen.const("has_underscore"),
    Gen.const("a" * 65),
    Gen.const("üñí"),
    Gen.const("a/b")
  )

  private val topicChar: Gen[Char] =
    Gen.oneOf(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ Seq('.', '_', '-'))

  val validTopicName: Gen[String] =
    Gen.chooseNum(1, 249).flatMap(n => Gen.listOfN(n, topicChar).map(_.mkString)).suchThat { raw =>
      raw != "." && raw != ".."
    }

  val invalidTopicName: Gen[String] = Gen.oneOf(
    Gen.const(""),
    Gen.const("."),
    Gen.const(".."),
    Gen.const("a" * 250),
    Gen.const("has space"),
    Gen.const("has/slash"),
    Gen.const("has:colon")
  )

  /** Any non-empty, at-most-255-character string of printable ASCII: the shape of the identifiers
    * whose only rule is a length bound.
    */
  val validBoundedName: Gen[String] =
    Gen.chooseNum(1, 255).flatMap(n => Gen.listOfN(n, Gen.asciiPrintableChar).map(_.mkString))

  val invalidBoundedName: Gen[String] = Gen.oneOf(Gen.const(""), Gen.const("x" * 256))

  val validCorrelationId: Gen[String] =
    Gen
      .chooseNum(1, 64)
      .flatMap(n => Gen.listOfN(n, Gen.oneOf(Gen.alphaNumChar, Gen.const('-'))).map(_.mkString))

  val invalidCorrelationId: Gen[String] =
    Gen.oneOf(Gen.const(""), Gen.const("x" * 65), Gen.const("has space"), Gen.const("has_underscore"))

  val validHost: Gen[String] = Gen.oneOf(
    Gen.const("localhost"),
    Gen.const("broker-1.kafka.svc.cluster.local"),
    Gen.const("10.0.0.7"),
    Gen.const("[2001:db8::1]")
  )

  private def unsafely[A](gen: Gen[String])(wrap: String => A): Gen[A] = gen.map(wrap)

  given Arbitrary[ClusterId]      = Arbitrary(unsafely(validSlug)(ClusterId.unsafe))
  given Arbitrary[ServiceId]      = Arbitrary(unsafely(validSlug)(ServiceId.unsafe))
  given Arbitrary[KafkaClusterId] = Arbitrary(unsafely(validBoundedName)(KafkaClusterId.unsafe))
  given Arbitrary[TopicName]      = Arbitrary(unsafely(validTopicName)(TopicName.unsafe))
  given Arbitrary[GroupId]        = Arbitrary(unsafely(validBoundedName)(GroupId.unsafe))
  given Arbitrary[Subject]        = Arbitrary(unsafely(validBoundedName)(Subject.unsafe))
  given Arbitrary[ConnectName]    = Arbitrary(unsafely(validBoundedName)(ConnectName.unsafe))
  given Arbitrary[ConnectorName]  = Arbitrary(unsafely(validBoundedName)(ConnectorName.unsafe))
  given Arbitrary[UserName]       = Arbitrary(unsafely(validBoundedName)(UserName.unsafe))
  given Arbitrary[RoleName]       = Arbitrary(unsafely(validBoundedName)(RoleName.unsafe))
  given Arbitrary[CorrelationId]  = Arbitrary(unsafely(validCorrelationId)(CorrelationId.unsafe))
  given Arbitrary[Host]           = Arbitrary(unsafely(validHost)(Host.unsafe))

  given Arbitrary[PartitionId] = Arbitrary(Gen.chooseNum(0, 10000).map(PartitionId.unsafe))
  given Arbitrary[BrokerId]    = Arbitrary(Gen.chooseNum(0, 10000).map(BrokerId.unsafe))
  given Arbitrary[SchemaId]    = Arbitrary(Gen.chooseNum(0, 100000).map(SchemaId.unsafe))
  given Arbitrary[TaskId]      = Arbitrary(Gen.chooseNum(0, 1000).map(TaskId.unsafe))
  given Arbitrary[Offset]      = Arbitrary(Gen.chooseNum(0L, Long.MaxValue).map(Offset.unsafe))
  given Arbitrary[Port]        = Arbitrary(Gen.chooseNum(1, 65535).map(Port.unsafe))
  given Arbitrary[PositiveInt] = Arbitrary(Gen.chooseNum(1, Int.MaxValue).map(PositiveInt.unsafe))
  given Arbitrary[ByteSize]    = Arbitrary(Gen.chooseNum(0L, Long.MaxValue).map(ByteSize.unsafe))

  given Arbitrary[TopicPartition] = Arbitrary(
    for {
      topic     <- Arbitrary.arbitrary[TopicName]
      partition <- Arbitrary.arbitrary[PartitionId]
    } yield TopicPartition(topic, partition)
  )

  given Arbitrary[TopicPartitionReplica] = Arbitrary(
    for {
      tp     <- Arbitrary.arbitrary[TopicPartition]
      broker <- Arbitrary.arbitrary[BrokerId]
    } yield TopicPartitionReplica(tp, broker)
  )

  /** A half-open range that is never inverted, built by generating a start and a length. */
  given Arbitrary[OffsetRange] = Arbitrary(
    for {
      begin <- Gen.chooseNum(0L, 1000000L)
      size  <- Gen.chooseNum(0L, 10000L)
    } yield OffsetRange
      .from(Offset.unsafe(begin), Offset.unsafe(begin + size))
      .fold(_ => OffsetRange.emptyAt(Offset.unsafe(begin)), identity)
  )
}
