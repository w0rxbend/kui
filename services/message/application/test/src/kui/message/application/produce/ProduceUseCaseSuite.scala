package kui.message.application.produce

import java.nio.charset.StandardCharsets

import cats.effect.IO
import munit.CatsEffectSuite

import kui.kernel.PartitionId
import kui.kernel.error.{ApplicationError, ErrorCode}
import kui.message.domain.ProduceRequest
import kui.security.audit.{MutationKind, MutationOutcome}

/** Publishing a record: what lands, what is refused, and what is written down about both.
  *
  * The cases that matter here are not "it produces a record" — that is the easy half and a broker test
  * covers it. They are the three that quietly go wrong:
  *
  *   - a tombstone must stay a tombstone all the way down, because turning one into an empty value breaks
  *     compaction for whoever relies on it and nothing on any screen would say so;
  *   - a read-only cluster must be refused **before a producer exists**, which is only assertable because
  *     the fake counts how many times one was asked for;
  *   - every attempt must leave exactly one audit record, refusals included, because a trail that only holds
  *     successes cannot answer the question it exists for.
  */
final class ProduceUseCaseSuite extends CatsEffectSuite {

  import ProduceRig.*

  private def requestOf(
      key: Option[String] = Some("k1"),
      value: Option[String] = Some("""{"id":1}"""),
      partition: Option[PartitionId] = None,
      count: Option[Int] = None
  ): ProduceRequest =
    ProduceRequest
      .of(
        cluster = Cluster,
        topic = Topic,
        partition = partition,
        key = key,
        value = value,
        headers = List("trace" -> "abc"),
        keySerde = None,
        valueSerde = None,
        keySerdeProperties = Map.empty,
        valueSerdeProperties = Map.empty,
        count = count
      )
      .getOrElse(fail("the fixture request is not valid"))

  private def rig(
      readOnly: Boolean = false,
      partitions: Either[kui.kernel.error.KuiError, Int] = Right(4),
      serdeRefusal: Option[kui.kernel.error.KuiError] = None,
      failFrom: Option[Int] = None
  ): IO[(ProduceUseCase[IO], FakeProducers, RecordingAudit, FakeSerdes)] =
    for {
      producers <- FakeProducers.make(partitions, failFrom)
      serdes <- FakeSerdes.make(serdeRefusal)
      audit <- RecordingAudit.make
      guard <- guardFor(new Profiles(readOnly), audit)
    } yield (ProduceUseCase.make[IO](producers, serdes, guard), producers, audit, serdes)

  // -----------------------------------------------------------------------------------------------

  test("aRecordIsPublishedAndTheBrokersOffsetIsReported") {
    for {
      (produce, producers, _, _) <- rig()
      answer <- produce.produce(ProduceRig.Caller, requestOf())
      written <- producers.sent.get
    } yield {
      // The offset is the point. "Published successfully" leaves an operator searching their own topic
      // for the record they just wrote.
      assertEquals(answer.map(_.map(_.offset.value)), Right(List(100L)))
      assertEquals(written.length, 1)
      assertEquals(written.head.value.map(new String(_, StandardCharsets.UTF_8)), Some("""{"id":1}"""))
      assertEquals(written.head.headers.map(_.key), List("trace"))
    }
  }

  test("aTombstoneIsProducedAsANullValueAndNotAsAnEmptyOne") {
    // The single most consequential line in this feature. `value = None` must reach the producer as an
    // absent payload; an empty array here is an ordinary record and stops a compacted topic forgetting
    // the key, which is what the operator asked for.
    for {
      (produce, producers, _, _) <- rig()
      _ <- produce.produce(ProduceRig.Caller, requestOf(value = None))
      written <- producers.sent.get
    } yield {
      assertEquals(written.head.value, None)
      assert(written.head.key.isDefined, "a tombstone still has a key; that is what it deletes")
    }
  }

  test("aRecordWithNoKeyIsNotARecordWithAnEmptyKey") {
    for {
      (produce, producers, _, _) <- rig()
      _ <- produce.produce(ProduceRig.Caller, requestOf(key = None))
      written <- producers.sent.get
    } yield assertEquals(written.head.key, None)
  }

  test("countProducesThatManyRecordsThroughOneProducer") {
    // One producer, five records. Producing five records through five producers is five connections, and
    // the port exists in the shape it does to make the wrong version awkward to write.
    for {
      (produce, producers, _, _) <- rig()
      answer <- produce.produce(ProduceRig.Caller, requestOf(count = Some(5)))
      written <- producers.sent.get
      opened <- producers.opened.get
    } yield {
      assertEquals(answer.map(_.length), Right(5))
      assertEquals(written.length, 5)
      assertEquals(opened, 1)
    }
  }

  test("readOnlyClusterIsRefusedBeforeAProducerIsEvenAskedFor") {
    for {
      (produce, producers, audit, serdes) <- rig(readOnly = true)
      answer <- produce.produce(ProduceRig.Caller, requestOf())
      opened <- producers.opened.get
      serialised <- serdes.decodes.get
      entries <- audit.entries.get
    } yield {
      assertEquals(answer.left.map(_.code), Left(ErrorCode.ReadOnly))
      // ADR-047's "before any Kafka client is touched", as a number.
      assertEquals(opened, 0)
      assertEquals(serialised, 0)
      // And the attempt is still recorded. Somebody trying to write to a read-only cluster is exactly
      // the kind of thing an audit trail exists to have noticed.
      assertEquals(entries.map(_.outcome), List(MutationOutcome.Refused))
      assertEquals(entries.map(_.kind), List(MutationKind.Produce))
    }
  }

  test("aPartitionTheTopicDoesNotHaveIsRefusedWithTheCountInTheMessage") {
    // "Partition 7 does not exist" sends an operator to look at their cluster. "This topic has 4
    // partitions, numbered 0 to 3" sends them to look at their form, which is where the mistake is.
    for {
      (produce, producers, _, _) <- rig(partitions = Right(4))
      answer <- produce.produce(ProduceRig.Caller, requestOf(partition = Some(PartitionId.unsafe(7))))
      written <- producers.sent.get
    } yield {
      assertEquals(answer.left.map(_.code), Left(ErrorCode.Validation))
      assert(
        answer.swap.exists(_.message.contains("4 partitions")),
        s"the refusal does not name the partition count: $answer"
      )
      assertEquals(written, Nil)
    }
  }

  test("aSerialisationFailureIsTerminalAndNothingIsWritten") {
    // Unlike a *decode* failure, which never fails a browse. Bytes KUI could not encode would put a
    // record in a topic that outlives the mistake, so this one stops the request.
    val refusal = ApplicationError.Invalid("that is not valid JSON", Nil)

    for {
      (produce, producers, audit, _) <- rig(serdeRefusal = Some(refusal))
      answer <- produce.produce(ProduceRig.Caller, requestOf())
      written <- producers.sent.get
      entries <- audit.entries.get
    } yield {
      assertEquals(answer.left.map(_.code), Left(ErrorCode.Validation))
      assertEquals(written, Nil)
      assertEquals(entries.map(_.outcome), List(MutationOutcome.Refused))
    }
  }

  test("aBatchThatFailsHalfwayReportsWhatLanded") {
    // There is no rollback and there cannot be one: a record the broker accepted is written. Reporting
    // three of five is the honest answer; reporting a failure would send the operator to retry and end
    // up with eight.
    for {
      (produce, producers, _, _) <- rig(failFrom = Some(3))
      answer <- produce.produce(ProduceRig.Caller, requestOf(count = Some(5)))
      written <- producers.sent.get
    } yield {
      assertEquals(answer.map(_.length), Right(3))
      assertEquals(written.length, 5)
    }
  }

  test("aBatchWhereNothingLandedIsAFailureAndNotAnEmptyList") {
    for {
      (produce, _, _, _) <- rig(failFrom = Some(0))
      answer <- produce.produce(ProduceRig.Caller, requestOf())
    } yield assert(answer.isLeft, s"a produce that wrote nothing must not look like a success: $answer")
  }

  test("aSuccessWritesExactlyOneAuditRecordNamingTheTopicAndTheCount") {
    for {
      (produce, _, audit, _) <- rig()
      _ <- produce.produce(ProduceRig.Caller, requestOf(count = Some(3)))
      entries <- audit.entries.get
    } yield {
      assertEquals(entries.length, 1)
      assertEquals(entries.head.outcome, MutationOutcome.Succeeded)
      assertEquals(entries.head.resource, Topic.value)
      assertEquals(entries.head.detail.get("count"), Some("3"))
      // The payload is never in the record. An audit log is read by more people than the topic it
      // describes, which is exactly why it must not contain the data (ADR-023).
      assert(
        !entries.head.detail.values.exists(_.contains("""{"id":1}""")),
        s"the audit record carries the payload: ${entries.head.detail}"
      )
    }
  }

  test("aClusterThisDeploymentDoesNotHaveIsAFourOhFourAndIsStillRecorded") {
    for {
      audit <- RecordingAudit.make
      producers <- FakeProducers.make()
      serdes <- FakeSerdes.make()
      guard <- guardFor(new Profiles(readOnly = false, known = false), audit)
      produce = ProduceUseCase.make[IO](producers, serdes, guard)
      answer <- produce.produce(ProduceRig.Caller, requestOf())
      entries <- audit.entries.get
      opened <- producers.opened.get
    } yield {
      assertEquals(answer.left.map(_.code), Left(ErrorCode.ClusterNotFound))
      assertEquals(opened, 0)
      assertEquals(entries.map(_.outcome), List(MutationOutcome.Failed))
    }
  }
}
