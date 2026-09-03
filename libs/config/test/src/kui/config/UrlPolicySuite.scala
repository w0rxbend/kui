package kui.config

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import kui.testkit.KuiSuite

/** That an operator cannot point KUI at an address it must not call.
  *
  * `ARCHITECTURE.md` §14 is the rule; the reason is server-side request forgery. KUI fetches
  * addresses supplied in configuration, so a URL such as `http://169.254.169.254/` — the address a
  * cloud instance uses to hand out its own credentials — would turn a configuration field into a
  * way of reading a private network through KUI's own network position.
  */
final class UrlPolicySuite extends KuiSuite {

  private def accepted(raw: String, policy: UrlPolicy): Boolean =
    SafeUrl.from(raw, policy).isRight

  private val privateAddresses = List(
    "http://169.254.169.254/latest/meta-data/",
    "http://[::1]/",
    "http://10.0.0.1/",
    "http://192.168.1.1/",
    "http://172.16.0.1/",
    "http://127.0.0.1:8080/",
    "http://localhost:8080/"
  )

  privateAddresses.foreach { url =>
    test(s"Strict refuses $url and Dev accepts it") {
      assert(!accepted(url, UrlPolicy.Strict), s"$url was accepted under the strict policy")
      assert(accepted(url, UrlPolicy.Dev), s"$url was refused under the dev policy")
    }
  }

  test("a public host is accepted under both policies") {
    assert(accepted("https://registry.example.com:8081", UrlPolicy.Strict))
    assert(accepted("https://registry.example.com:8081", UrlPolicy.Dev))
  }

  test("a host name that merely starts with a private prefix is not mistaken for one") {
    // `fdn.example.com` starts with the letters of the IPv6 unique-local prefix, and `100.example`
    // with the carrier-grade one. Both are ordinary public names.
    assert(accepted("https://fdn.example.com", UrlPolicy.Strict))
    assert(accepted("https://100.example.com", UrlPolicy.Strict))
  }

  test("a scheme other than http or https is refused under every policy") {
    // Dev relaxes which *addresses* may be called, never which schemes. `ARCHITECTURE.md` §14
    // allows http and https and nothing else, and a policy that relaxed it for developers would
    // leave the production path as the one nobody ever exercises.
    List("ftp://x/", "file:///etc/passwd", "jar:file:///x", "gopher://x/").foreach { url =>
      assert(!accepted(url, UrlPolicy.Strict), s"$url was accepted under the strict policy")
      assert(!accepted(url, UrlPolicy.Dev), s"$url was accepted under the dev policy")
    }
  }

  test("credentials in the URL are refused under every policy") {
    assert(!accepted("http://user:pass@example.com/", UrlPolicy.Strict))
    assert(!accepted("http://user:pass@example.com/", UrlPolicy.Dev))
    assert(!accepted("http://user@example.com/", UrlPolicy.Dev))
  }

  test("a URL with no host is refused") {
    assert(!accepted("http:///path", UrlPolicy.Dev))
    assert(!accepted("not-a-url", UrlPolicy.Dev))
  }

  test("the failure message names the field and does not echo an empty expectation") {
    SafeUrl.from("ftp://x/", UrlPolicy.Strict) match {
      case Right(_) => fail("ftp was accepted")
      case Left(error) =>
        assertEquals(error.fieldName, "url")
        assert(error.message.contains("http or https"), error.message)
    }
  }

  private val hosts: Gen[String] =
    Gen.oneOf("example.com", "registry.internal", "10.0.0.1", "localhost", "169.254.169.254")

  private val userInfos: Gen[String] = Gen.oneOf("", "u@", "u:p@")

  private val schemes: Gen[String] = Gen.oneOf("http", "https", "ftp", "file")

  property("no accepted URL carries credentials, under either policy") {
    forAll(schemes, userInfos, hosts, Gen.oneOf(UrlPolicy.Strict, UrlPolicy.Dev)) {
      (scheme, userInfo, host, policy) =>
        val raw = s"$scheme://$userInfo$host/path"
        SafeUrl.from(raw, policy).isLeft || userInfo.isEmpty
    }
  }

  property("no accepted URL under the strict policy is a loopback or private address") {
    forAll(schemes, hosts) { (scheme, host) =>
      val raw = s"$scheme://$host/path"
      val isPrivate = host == "10.0.0.1" || host == "localhost" || host == "169.254.169.254"
      SafeUrl.from(raw, UrlPolicy.Strict).isLeft || !isPrivate
    }
  }
}
