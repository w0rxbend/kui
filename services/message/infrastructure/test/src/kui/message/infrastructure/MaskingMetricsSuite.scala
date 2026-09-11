package kui.message.infrastructure

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import io.opentelemetry.sdk.metrics.data.MetricData
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit

import kui.kernel.serde.Target
import kui.kernel.{ClusterId, TopicName}
import kui.observability.MetricNames
import kui.testkit.KuiIOSuite

/** `kui.masking.applied` as a real meter records it (DM-001, ADR-023).
  *
  * ==Why this suite exists, and why a fake could not be it==
  *
  * `ConfiguredRecordMaskingSuite` asserts the series through a counting `MaskingMetrics` fake, which is the
  * right shape for the question that suite asks — *was masking counted once per read per target?* — and is
  * blind to the question this one asks. The fake is handed a `ClusterId` and a `TopicName` and records both;
  * the adapter is the only thing in this repository that decides which **attribute** each of them becomes,
  * and a fake agrees with whatever it is given. Swapping `topic.value` for `cluster.value` in
  * `MaskingMetrics.scala` left every suite in the repository green (W9-06/F7).
  *
  * An attribute is an interface in the same way a metric name is: a dashboard filtering
  * `kui.masking.applied{topic="payments.transactions"}` goes empty rather than failing when the label starts
  * carrying a cluster id, and the panel that goes empty is the one an operator checks to answer "is this
  * topic actually being masked in production". So the assertion has to read the point back off a meter.
  *
  * ==What is deliberately not asserted==
  *
  * Nothing about how many fields or records were masked. `MetricNames.MaskingApplied` argues that at length
  * and `ConfiguredRecordMasking` keeps it: the number of fields a rule touched is a function of the payload,
  * so a per-field series would publish the shape of protected data onto a dashboard that is routinely less
  * protected than the data itself.
  */
final class MaskingMetricsSuite extends KuiIOSuite {

  private val prod: ClusterId = ClusterId.unsafe("prod")
  private val payments: TopicName = TopicName.unsafe("payments.transactions")

  /** Every recorded point of `kui.masking.applied`, as `(attributes, value)`. */
  private def applications(metrics: List[MetricData]): List[(Map[String, String], Long)] =
    metrics
      .filter(_.getName == MetricNames.MaskingApplied)
      .flatMap(_.getLongSumData.getPoints.asScala.toList)
      .map { point =>
        val attributes =
          point.getAttributes.asMap.asScala.map((key, value) => key.getKey -> value.toString).toMap
        (attributes, point.getValue)
      }
      .sortBy(_._1.toList.sorted.mkString)

  private def recorded(
      write: MaskingMetrics[IO] => IO[Unit]
  ): IO[List[(Map[String, String], Long)]] =
    OtelJavaTestkit
      .inMemory[IO]()
      .use { testkit =>
        for {
          meter <- testkit.meterProvider.get("kui.message")
          metrics <- MaskingMetrics.otel4s[IO](meter)
          _ <- write(metrics)
          collected <- testkit.collectMetrics
        } yield applications(collected.toList)
      }

  test("one masked read writes one point, and the cluster and the topic land on their own attributes") {
    // THE ASSERTION THE FAKE CANNOT MAKE. Both values are present in both orders of the mistake, so a
    // swapped pair is a different map rather than a missing key: `{cluster: payments.transactions}` fails
    // here and satisfies any assertion written as "the point carries the topic somewhere".
    recorded(_.applied(prod, payments, Target.Key)).map { points =>
      assertEquals(
        points,
        List(
          (
            Map(
              MetricNames.Attr.Cluster -> "prod",
              MetricNames.Attr.Topic -> "payments.transactions",
              MetricNames.Attr.Target -> "key"
            ),
            1L
          )
        )
      )
    }
  }

  test("the two halves of a record are two series, because a key rule and a value rule are separate") {
    // `ConfiguredRecordMasking` asks `MaskingEngine.applies` once per target and writes one application per
    // answer. If `target` were not on the point those two reads would add up into one number, and the
    // question an operator asks — "is the value of this topic masked, or only its key?" — would have no
    // answer at all.
    recorded(metrics =>
      metrics.applied(prod, payments, Target.Key) *> metrics.applied(prod, payments, Target.Value)
    ).map { points =>
      assertEquals(points.map(_._1.get(MetricNames.Attr.Target)), List(Some("key"), Some("value")))
      assertEquals(points.map(_._2), List(1L, 1L))
    }
  }

  test("two reads of the same topic add up on one series rather than making a second one") {
    // It is a counter, and the thing being counted is reads. A series per read would be a cardinality
    // explosion with a browse behind it.
    recorded(metrics =>
      metrics.applied(prod, payments, Target.Value) *> metrics.applied(prod, payments, Target.Value)
    ).map(points => assertEquals(points.map(_._2), List(2L)))
  }

  test("the point carries those three attributes and no fourth") {
    // A label is an interface: one added here splits every existing series in two on the day it ships, and
    // a label carrying anything derived from a payload would put protected data on a dashboard. Asserted as
    // an exact key set rather than as three `contains` calls, which is the assertion that notices an
    // addition.
    recorded(_.applied(prod, payments, Target.Value)).map { points =>
      assertEquals(
        points.map(_._1.keySet),
        List(Set(MetricNames.Attr.Cluster, MetricNames.Attr.Topic, MetricNames.Attr.Target))
      )
    }
  }

  test("nothing is written for a read no rule reached, so the series counts masked reads and not reads") {
    // The fast path in `ConfiguredRecordMasking` returns before it touches the metrics, and that is what
    // makes this number readable: a counter that ticked on every browse would say nothing about masking.
    recorded(_ => IO.unit).map(points => assertEquals(points, Nil))
  }

  test("the series carries the sentence a metric catalogue is read by") {
    // W10-04/F4, closed by W10-A2. Every case above reads the name, the attributes and the value off the
    // SDK and none of them reads the description, so any rewrite of `.withDescription` — or deleting the
    // call outright — is green. A description is a catalogue entry: it is what a dashboard author sees
    // beside the series in an autocomplete, and it is the only place the *unit* of this counter is written
    // down where they will see it. "Reads" and not "fields" is the whole discipline of this metric, argued
    // at length in `MetricNames.MaskingApplied` and in this file's own header, and a description that
    // drifted to "fields masked" would send someone to build the panel the design refuses to emit.
    OtelJavaTestkit
      .inMemory[IO]()
      .use { testkit =>
        for {
          meter <- testkit.meterProvider.get("kui.message")
          metrics <- MaskingMetrics.otel4s[IO](meter)
          _ <- metrics.applied(prod, payments, Target.Value)
          collected <- testkit.collectMetrics
        } yield assertEquals(
          collected.toList.filter(_.getName == MetricNames.MaskingApplied).map(_.getDescription),
          List("Reads on which a masking rule was in force, by cluster, topic and target")
        )
      }
  }

  test("the shipped noop writes nothing, which is what makes it safe to hand to a suite") {
    // `MaskingMetrics.noop` had no caller at all until wave 9's closer used it in
    // `ConfiguredRecordMaskingSuite` (W9-06/F8). A `noop` that quietly recorded would make every case that
    // takes it a case about metrics, so the claim in its name is worth one assertion.
    OtelJavaTestkit
      .inMemory[IO]()
      .use { testkit =>
        for {
          _ <- MaskingMetrics.noop[IO].applied(prod, payments, Target.Value)
          collected <- testkit.collectMetrics
        } yield assertEquals(applications(collected.toList), Nil)
      }
  }
}
