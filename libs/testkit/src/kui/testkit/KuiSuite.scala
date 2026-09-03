package kui.testkit

import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.Test.Parameters

/** The base class for a KUI unit or property suite.
  *
  * It exists so that every suite in the project runs the same number of samples and reports a failure the
  * same way. The two settings are the ones that make a property failure actionable: a hundred samples is
  * enough to catch a rule that is wrong without making the suite slow, and MUnit prints the seed of a failing
  * run, which is what turns "it failed on CI once" into a reproducible bug (paste the seed into
  * `scalaCheckInitialSeed`).
  */
abstract class KuiSuite extends ScalaCheckSuite {

  override def scalaCheckTestParameters: Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(100).withWorkers(1)
}

/** The same, for suites whose subject is an effect.
  *
  * `CatsEffectSuite` lets a test body return `IO[A]`, which is run for the test rather than being built and
  * dropped — a mistake that otherwise makes a test pass by not testing anything.
  */
abstract class KuiIOSuite extends CatsEffectSuite
