package kui.topic.application

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

import kui.kernel.TopicName
import kui.testkit.KuiSuite

/** The union rule, as a truth table. */
final class InternalTopicsSuite extends KuiSuite {

  private def internal(name: String, flag: Boolean, prefix: String = InternalTopics.DefaultPrefix): Boolean =
    InternalTopics.isInternal(TopicName.unsafe(name), flag, prefix)

  test("theKafkaFlagAloneIsEnough") {
    assert(internal("consumer_offsets", flag = true))
  }

  test("thePrefixAloneIsEnough") {
    assert(internal("__consumer_offsets", flag = false))
  }

  test("neitherMeansNotInternal") {
    assert(!internal("orders", flag = false))
  }

  test("bothIsStillInternal") {
    assert(internal("__consumer_offsets", flag = true))
  }

  test("kuiOwnMetadataTopicsAreInternal") {
    // The case that decided the rule. `__kui_config` and `__kui_files` are ordinary topics as far as Kafka is
    // concerned — the flag is false for both — and noise as far as an operator is concerned. Kafka's flag
    // alone would list them among the operator's own topics.
    assert(internal("__kui_config", flag = false))
    assert(internal("__kui_files", flag = false))
  }

  property("thePrefixIsConfigurable") {
    // The operator's dial, `kui.topics.internalPrefix`. A deployment whose own conventions mark internal
    // topics some other way sets it, and the same name is then internal under one prefix and not under
    // another.
    forAll(Gen.identifier) { suffix =>
      assert(internal("sys." + suffix, flag = false, prefix = "sys."))
      assert(!internal("sys." + suffix, flag = false, prefix = "__"))
    }
  }

  property("theRuleIsTheUnionOfItsTwoHalves") {
    forAll(Gen.identifier, Gen.oneOf(true, false)) { (name, flag) =>
      val byPrefix = name.startsWith("__")

      Prop(internal(name, flag) == (flag || byPrefix))
    }
  }

  test("anEmptyPrefixDisablesTheNameHalfRatherThanMatchingEverything") {
    // Every string starts with the empty string, so a naive `startsWith` would make every topic internal the
    // moment an operator blanked the setting — an empty topic list with no error anywhere.
    assert(!internal("orders", flag = false, prefix = ""))
    assert(internal("orders", flag = true, prefix = ""))
  }
}
