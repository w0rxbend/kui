package kui.message.infrastructure

import java.time.Instant

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}

import kui.config.{ClusterConfig, MaskingConfig}
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.serde.{PayloadKind, SerdeName}
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import kui.message.domain.{Decoded, DecodedRecord, RenderedHeader, TimestampType}
import kui.security.masking.{KeepEnds, MaskingKind, MaskingRule}
import kui.testkit.KuiIOSuite

/** DM-001 wired: `kui.clusters[].masking[]` applied to a record on its way out of the message service.
  *
  * ==The rule this file owns==
  *
  * **A masking rule reaches only the half of a record its own scope names, and headers follow the value scope
  * and never the key scope.** `ConfiguredRecordMasking.maskWith` is where the product applies it: it asks
  * `MaskingEngine.applies` once per `Target`, keeps both answers, and gates the header branch on the *value*
  * answer because `MaskingEngine.maskHeaders` filters its own rules under `Target.Value` — a header belongs
  * to the record, not to its key or its value.
  *
  * It is a rule worth a case here and not only in `libs/security-core` because the engine's scope check and
  * this adapter's are two separate decisions about the same question, and the adapter's is the one a browsing
  * user meets. An adapter that asked `applies(..., Target.Value)` and then masked the key with it would put
  * an operator's value rule through every key on the cluster, and every case in `MaskingEngineSuite` would
  * stay green, because none of them goes through this file.
  */
final class ConfiguredRecordMaskingSuite extends KuiIOSuite {

  private val prod: ClusterId = ClusterId.unsafe("prod")
  private val payments: TopicName = TopicName.unsafe("payments.v1")
  private val other: TopicName = TopicName.unsafe("audit.log.raw")

  private val stars: MaskingKind = MaskingKind.Mask("*", KeepEnds.none)

  private def rule(
      kind: MaskingKind = stars,
      fields: Option[NonEmptyList[String]] = None,
      keysPattern: Option[String] = None,
      valuesPattern: Option[String] = None
  ): MaskingRule =
    MaskingRule(kind, fields, None, keysPattern.map(_.r), valuesPattern.map(_.r))

  private def cluster(rules: List[MaskingRule]): ClusterConfig =
    ClusterConfig(
      id = prod,
      name = "Production",
      bootstrapServers = BootstrapServers.unsafe("prod-broker:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default,
      masking = MaskingConfig(rules)
    )

  /** A counting `MaskingMetrics`, so a case can say which `kui.masking.applied` series were written. */
  private def counting: IO[(Ref[IO, List[(String, String, String)]], MaskingMetrics[IO])] =
    Ref.of[IO, List[(String, String, String)]](Nil).map { recorded =>
      val metrics: MaskingMetrics[IO] =
        (cluster, topic, target) => recorded.update(_ :+ ((cluster.value, topic.value, target.label)))

      (recorded, metrics)
    }

  /** The cases that are not about the series take the SHIPPED `MaskingMetrics.noop`, and not a fake.
    *
    * Two reasons, and the second is the one that made this an edit rather than a preference. A case asserting
    * what a mask does to a record has no business building a counter it never reads. And `noop` had no caller
    * at all -- not in production, not in a suite -- while every other `noop` in this repository
    * (`CacheMetrics`, `FilterMetrics`, `AdminMetrics`, `SerdeMetrics`) has several: a declaration nothing
    * constructs is a declaration nothing has ever compiled against a real use. Filed as W9-06/F8.
    */
  private def masking(rules: List[MaskingRule]): IO[ConfiguredRecordMasking[IO]] =
    IO.pure(ConfiguredRecordMasking.of[IO](List(cluster(rules)), MaskingMetrics.noop[IO]))

  private def record(
      key: Decoded = Decoded("k-4111", PayloadKind.Text, SerdeName.String, Map.empty),
      value: Decoded = Decoded("""{"card":"4111"}""", PayloadKind.Json, SerdeName.Json, Map.empty),
      headers: List[RenderedHeader] = List(RenderedHeader("authorization", "Bearer token"))
  ): DecodedRecord =
    DecodedRecord(
      partition = PartitionId.unsafe(0),
      offset = Offset.unsafe(1L),
      timestamp = Instant.EPOCH,
      timestampType = TimestampType.CreateTime,
      key = key,
      value = value,
      headers = headers,
      keySize = 6,
      valueSize = 15,
      headersSize = 20,
      decodeErrors = Nil
    )

  // -------------------------------------------------------------------- the scope rule this file owns

  test("a value-scoped rule masks the value and leaves the key exactly as the serde produced it") {
    // THE ASSERTION IS ON THE PRODUCT'S OUTPUT, NOT ON THE ENGINE'S. `maskWith` decides which half each
    // `applies` answer is allowed to touch, and this is the only case in the repository that reads that
    // decision.
    for {
      mask <- masking(
        List(rule(valuesPattern = Some("payments\\..*"), fields = Some(NonEmptyList.of("card"))))
      )
      applied <- mask.forTopic(prod, payments)
      masked = applied(record())
    } yield {
      assertEquals(masked.value.text, """{"card":"****"}""")
      assertEquals(masked.key.text, "k-4111")
    }
  }

  test("a key-scoped rule masks the key and leaves the value alone") {
    for {
      mask <- masking(List(rule(keysPattern = Some("payments\\..*"))))
      applied <- mask.forTopic(prod, payments)
      masked = applied(record())
    } yield {
      assertEquals(masked.key.text, "******")
      assertEquals(masked.value.text, """{"card":"4111"}""")
    }
  }

  test("a key-scoped rule never reaches a header, however well its name matches") {
    // The half of the scope rule that is easiest to get wrong here, because a header rule is written
    // against a *field name* and `authorization` matches whatever the topic scope says. The engine argues
    // it — "a header belongs to the record, not to its key or its value" — and this adapter has to ask the
    // question the same way round, which is why the header branch reads `values` and not `keys`.
    for {
      mask <- masking(
        List(rule(keysPattern = Some(".*"), fields = Some(NonEmptyList.of("authorization"))))
      )
      applied <- mask.forTopic(prod, payments)
      masked = applied(record())
    } yield assertEquals(masked.headers, List(RenderedHeader("authorization", "Bearer token")))
  }

  test("a value-scoped rule does reach a header of the same name") {
    for {
      mask <- masking(
        List(rule(valuesPattern = Some(".*"), fields = Some(NonEmptyList.of("authorization"))))
      )
      applied <- mask.forTopic(prod, payments)
      masked = applied(record())
    } yield assertEquals(masked.headers, List(RenderedHeader("authorization", "*" * "Bearer token".length)))
  }

  test("a remove rule drops the header, the way it drops an object key") {
    for {
      mask <- masking(
        List(
          rule(
            kind = MaskingKind.Remove,
            valuesPattern = Some(".*"),
            fields = Some(NonEmptyList.of("authorization"))
          )
        )
      )
      applied <- mask.forTopic(prod, payments)
      masked = applied(
        record(headers = List(RenderedHeader("authorization", "x"), RenderedHeader("id", "7")))
      )
    } yield assertEquals(masked.headers, List(RenderedHeader("id", "7")))
  }

  test("two headers sharing a name are both masked, and neither is lost on the way to the engine") {
    // The engine takes a `Map` and this service keeps an ordered list; duplicate names are legal in Kafka.
    // Converting the whole list to a `Map` in one go would silently drop one of them, which is a record
    // arriving on a screen with a header its producer wrote missing.
    for {
      mask <- masking(
        List(rule(valuesPattern = Some(".*"), fields = Some(NonEmptyList.of("trace"))))
      )
      applied <- mask.forTopic(prod, payments)
      masked = applied(
        record(headers = List(RenderedHeader("trace", "abc"), RenderedHeader("trace", "defg")))
      )
    } yield assertEquals(
      masked.headers,
      List(RenderedHeader("trace", "***"), RenderedHeader("trace", "****"))
    )
  }

  // -------------------------------------------------------------------- topic scope, and the fast path

  test("a topic no rule names gets the identity mask and its record back unchanged") {
    for {
      mask <- masking(List(rule(valuesPattern = Some("payments\\..*"))))
      applied <- mask.forTopic(prod, other)
      original = record()
    } yield assertEquals(applied(original), original)
  }

  test("a cluster this deployment does not configure gets the empty mask rather than another's rules") {
    for {
      mask <- masking(List(rule(valuesPattern = Some(".*"))))
      applied <- mask.forTopic(ClusterId.unsafe("typo"), payments)
      original = record()
    } yield assertEquals(applied(original), original)
  }

  test("a cluster with no masking section leaves every record it reads alone") {
    for {
      mask <- masking(Nil)
      applied <- mask.forTopic(prod, payments)
      original = record()
    } yield assertEquals(applied(original), original)
  }

  // -------------------------------------------------------------------- the absent payload

  test("an absent key is not given a value by a whole-value replace rule") {
    // A record with no key, and a tombstone's absent value, are `Decoded.absent`: empty text. A `replace`
    // rule applied to one would write the replacement *into* it, and a deleted key would come back to the
    // screen carrying data it does not have.
    for {
      mask <- masking(List(rule(kind = MaskingKind.Replace("<redacted>"), valuesPattern = Some(".*"))))
      applied <- mask.forTopic(prod, payments)
      masked = applied(record(value = Decoded.absent(SerdeName.String)))
    } yield assertEquals(masked.value, Decoded.absent(SerdeName.String))
  }

  // -------------------------------------------------------------------- kui.masking.applied

  test("kui.masking.applied is written once per read per target in force, and not once per record") {
    // `MetricNames.MaskingApplied` documents itself as browses on which a rule applied, deliberately not a
    // count of fields masked: that number is a function of the payload, so a per-field series would
    // publish the shape of protected data onto a dashboard less protected than the data. Resolving the
    // mask once per read is what makes that promise keepable, so the count is asserted across three
    // records rather than one.
    for {
      pair <- counting
      (recorded, metrics) = pair
      mask = ConfiguredRecordMasking.of[IO](
        List(cluster(List(rule(valuesPattern = Some("payments\\..*"))))),
        metrics
      )
      applied <- mask.forTopic(prod, payments)
      _ = List(record(), record(), record()).map(applied.apply)
      written <- recorded.get
    } yield assertEquals(written, List(("prod", "payments.v1", "value")))
  }

  test("a rule reaching both halves writes both series, and a topic out of scope writes none") {
    for {
      pair <- counting
      (recorded, metrics) = pair
      mask = ConfiguredRecordMasking.of[IO](List(cluster(List(rule()))), metrics)
      _ <- mask.forTopic(prod, payments)
      afterInScope <- recorded.get
      unconfigured = ConfiguredRecordMasking.of[IO](List(cluster(Nil)), metrics)
      _ <- unconfigured.forTopic(prod, payments)
      afterOutOfScope <- recorded.get
    } yield {
      assertEquals(
        afterInScope,
        List(("prod", "payments.v1", "key"), ("prod", "payments.v1", "value"))
      )
      assertEquals(afterOutOfScope, afterInScope)
    }
  }
}
