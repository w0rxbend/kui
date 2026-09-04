package kui.serde

import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

import kui.kernel.TopicName
import kui.kernel.error.ErrorCode
import kui.serde.SerdeResolution.{PatternRule, Rules}
import kui.testkit.KuiSuite

/** The resolution order, one assertion per step. */
final class SerdeResolutionSuite extends KuiSuite {

  private val available: Set[SerdeName] =
    Set(SerdeName.String, SerdeName.Json, SerdeName.Hex, SerdeName.SchemaRegistry)

  private def topic(name: String): TopicName = TopicName.unsafe(name)

  private val rules = Rules(
    patterns = List(PatternRule("orders.*".r, SerdeName.SchemaRegistry, Target.Value)),
    defaultKey = None,
    defaultValue = Some(SerdeName.Json)
  )

  test("the order, in one place") {
    // A pattern that matches wins over the cluster default.
    assertEquals(
      SerdeResolution.resolve(rules, available, topic("orders-v2"), Target.Value, None),
      Right(SerdeName.SchemaRegistry)
    )
    // No pattern matches, so the cluster default answers.
    assertEquals(
      SerdeResolution.resolve(rules, available, topic("payments"), Target.Value, None),
      Right(SerdeName.Json)
    )
    // An explicit choice beats both.
    assertEquals(
      SerdeResolution.resolve(rules, available, topic("orders-v2"), Target.Value, Some(SerdeName.Hex)),
      Right(SerdeName.Hex)
    )
    // Nothing configured at all still resolves: String is the terminal case.
    assertEquals(
      SerdeResolution.resolve(Rules.empty, available, topic("anything"), Target.Value, None),
      Right(SerdeName.String)
    )
  }

  test("a pattern for one target does not apply to the other") {
    // The rule above is a value pattern. A key on the same topic gets the key default, which is unset, so
    // the terminal case answers.
    assertEquals(
      SerdeResolution.resolve(rules, available, topic("orders-v2"), Target.Key, None),
      Right(SerdeName.String)
    )
  }

  test("an explicit name this cluster does not have is an error, not a silent fallback") {
    // The user asked for something specific. Quietly giving them something else is how they conclude their
    // data is wrong rather than their choice.
    val result =
      SerdeResolution.resolve(rules, available, topic("orders-v2"), Target.Value, Some(SerdeName.Base64))
    assertEquals(result.swap.toOption.map(_.code), Some(ErrorCode.Unsupported))
    assert(result.swap.exists(_.message.contains("Base64")))
  }

  test("a configured serde that is not available falls through rather than failing the browse") {
    // The Schema Registry is down: `SchemaRegistry` is configured for `orders.*` and not in `available`.
    // Browsing continues through the fallback, with the marker on each row (ADR-035, exit criterion 8).
    val withoutRegistry = available - SerdeName.SchemaRegistry
    assertEquals(
      SerdeResolution.resolve(rules, withoutRegistry, topic("orders-v2"), Target.Value, None),
      Right(SerdeName.Json)
    )
  }

  test("patterns are evaluated in configuration order, and the first match wins") {
    val ordered = Rules(
      patterns = List(
        PatternRule("orders.*".r, SerdeName.Json, Target.Value),
        PatternRule("orders-v2".r, SerdeName.Hex, Target.Value)
      ),
      defaultKey = None,
      defaultValue = None
    )
    // The more specific pattern is second, so it never runs. That is the documented behaviour and the
    // reason patterns are a `List` and not a `Map`: map iteration order is the classic silent difference
    // between a developer's machine and production.
    assertEquals(
      SerdeResolution.resolve(ordered, available, topic("orders-v2"), Target.Value, None),
      Right(SerdeName.Json)
    )
    assertEquals(
      SerdeResolution.resolve(
        ordered.copy(patterns = ordered.patterns.reverse),
        available,
        topic("orders-v2"),
        Target.Value,
        None
      ),
      Right(SerdeName.Hex)
    )
  }

  private val topics: Gen[TopicName] =
    Gen.oneOf("orders-v2", "payments", "a", "orders", "x.y.z").map(TopicName.unsafe)

  private val targets: Gen[Target] = Gen.oneOf(Target.Key, Target.Value)

  property("with no explicit name, resolution always succeeds") {
    forAll(topics, targets) { (t, target) =>
      SerdeResolution.resolve(rules, available, t, target, None).isRight &&
      SerdeResolution.resolve(Rules.empty, available, t, target, None).isRight
    }
  }

  property("with no explicit name, the answer is always one this cluster has") {
    forAll(topics, targets) { (t, target) =>
      SerdeResolution.resolve(rules, available, t, target, None).exists(available.contains)
    }
  }

  property("resolution is deterministic") {
    forAll(topics, targets) { (t, target) =>
      SerdeResolution.resolve(rules, available, t, target, None) ==
        SerdeResolution.resolve(rules, available, t, target, None)
    }
  }
}
