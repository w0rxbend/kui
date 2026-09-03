package kui.gateway.api

import java.util.Locale

import org.scalacheck.{Gen, Prop}
import sttp.model.Header

import kui.testkit.KuiSuite

/** That the gateway's edge policy holds (ADR-040).
  *
  * These are unit and property tests over pure functions, which is the whole reason `EdgeHeaders` was written
  * as pure functions. A security rule that can only be checked by starting a server is a rule that gets
  * checked for the two cases someone thought of; a rule that is a function over a list of headers can be
  * checked against every casing a generator can produce. `GatewayApiSuite` then proves the same rule end to
  * end through a real server, so both the decision and its wiring are covered.
  */
final class EdgeHeadersSuite extends KuiSuite {

  /** `X-Kui-Principal`, `x-kui-principal`, `X-KUI-PRINCIPAL` and every mixture in between. */
  private val anyCasing: Gen[String] =
    for {
      suffix <- Gen.oneOf("principal", "correlation-id", "cluster-id", "csrf", "something-new-in-m7")
      name = s"x-kui-$suffix"
      casing <- Gen.listOfN(name.length, Gen.oneOf(true, false))
    } yield name
      .zip(casing)
      .map((character, upper) => if upper then character.toUpper else character)
      .mkString

  /** Header names a browser is entitled to send, which must survive untouched. */
  private val innocent: Gen[String] =
    Gen.oneOf(
      "Accept",
      "Content-Type",
      "Authorization",
      "Cookie",
      "traceparent",
      "tracestate",
      "Sec-Fetch-Site",
      "X-Forwarded-For",
      // The near miss that a naive `contains("kui")` test would wrongly strip.
      "X-Kuiper-Belt"
    )

  property("stripsEveryXKuiHeaderRegardlessOfCase") {
    Prop.forAll(anyCasing) { name =>
      EdgeHeaders.isForbidden(name) &&
      EdgeHeaders.remove(Seq(Header(name, "forged"))).isEmpty
    }
  }

  property("otherHeadersArePreserved") {
    Prop.forAll(innocent, anyCasing) { (kept, stripped) =>
      val headers = Seq(Header(kept, "value"), Header(stripped, "forged"))
      EdgeHeaders.remove(headers) == Seq(Header(kept, "value"))
    }
  }

  test("everyForbiddenNameTheListNamesIsAlsoCaughtByTheRule") {
    // The list is documentation, not the rule. This is what stops the two drifting: a name added to
    // `Forbidden` that the prefix test does not catch would be a name someone believed was protected.
    EdgeHeaders.Forbidden.foreach { name =>
      assert(EdgeHeaders.isForbidden(name), s"$name is listed as forbidden but the prefix rule misses it")
      assertEquals(name, name.toLowerCase(Locale.ROOT), "the list is lowercase so it can be compared")
    }
  }

  property("theRuleIsAPrefixTestAndNotASubstringTest") {
    // `Fetch-X-Kui-Principal` is not one of ours. Stripping headers merely because the family name appears
    // somewhere inside them would remove headers a proxy or a browser legitimately added.
    Prop.forAll(anyCasing) { name =>
      !EdgeHeaders.isForbidden(s"fetch-$name")
    }
  }
}
