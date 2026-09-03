package kui.ui.clusters.brokers

import munit.{FunSuite, ScalaCheckSuite}
import org.scalacheck.Prop.forAll

import kui.ui.kernel.component.ThresholdLevel

class SkewSuite extends FunSuite with ScalaCheckSuite {

  test("skewOfAnEvenSpreadIsZeroForEveryBroker") {
    assertEquals(Skew.percentages(List(Some(10), Some(10), Some(10))), List(Some(0.0), Some(0.0), Some(0.0)))
  }

  property("onlyBrokersAboveTheMeanReportASkew") {
    // A broker carrying less than its share is not a problem, so it shows nothing rather than a negative
    // number a reader has to work out is good news.
    forAll { (counts: List[Int]) =>
      val bounded = counts.map(count => Some(math.abs(count % 1000)))
      Skew.percentages(bounded).flatten.forall(_ >= 0.0)
    }
  }

  property("aZeroMeanYieldsNoSkewAndNoNaN") {
    // A cluster with no partitions is an ordinary state on a fresh install and must not divide by zero.
    forAll { (size: Byte) =>
      val zeroes = List.fill(math.abs(size % 20))(Some(0))
      val result = Skew.percentages(zeroes)
      result.forall(_.isEmpty) && result.length == zeroes.length
    }
  }

  test("aSingleBrokerIsZeroPercent") {
    // With one broker the mean is that broker, so the answer is genuinely zero rather than unknown.
    assertEquals(Skew.percentages(List(Some(42))), List(Some(0.0)))
  }

  test("unknownCountsAreExcludedFromTheMeanAndReportNothing") {
    // Counting the unknown broker as zero would drag the mean to 5 and report +100 % on the others: a wrong
    // number that looks like a right one, which is worse than a dash.
    val result = Skew.percentages(List(Some(10), Some(10), None))
    assertEquals(result, List(Some(0.0), Some(0.0), None))
  }

  test("oneBrokerAboveTheMeanIsReportedAndTheOthersAreNot") {
    val result = Skew.percentages(List(Some(6), Some(3), Some(3)))
    assertEquals(result.head.map(value => math.round(value * 10) / 10.0), Some(50.0))
    assertEquals(result.drop(1), List(None, None))
  }

  test("levelWarnsAtTenAndIsCriticalAtTwenty") {
    assertEquals(Skew.level(Some(9.99)), ThresholdLevel.Normal)
    assertEquals(Skew.level(Some(10.0)), ThresholdLevel.Warning)
    assertEquals(Skew.level(Some(19.99)), ThresholdLevel.Warning)
    assertEquals(Skew.level(Some(20.0)), ThresholdLevel.Critical)
    assertEquals(Skew.level(None), ThresholdLevel.Normal)
  }

  test("formatIsOneDecimalAndAMissingMarkerForNothing") {
    assertEquals(Skew.format(Some(12.44)), "12.4 %")
    assertEquals(Skew.format(None), kui.ui.kernel.component.DataTable.missing)
  }
}
