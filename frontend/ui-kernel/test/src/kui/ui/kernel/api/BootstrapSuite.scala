package kui.ui.kernel.api

import munit.FunSuite
import org.scalajs.dom

/** Reading the block the gateway injects, and every way it can be absent or wrong.
  *
  * The rule under test is that none of those ways stops the frontend from starting. A missing or malformed
  * bootstrap block means one of two things — a development build opened without a gateway, or a deployment
  * mistake — and in both cases an application that comes up pointed at the conventional path is far easier to
  * diagnose than a blank page with a stack trace in the console.
  */
class BootstrapSuite extends FunSuite {

  private def withScript[A](contents: String)(check: () => A): A = {
    val script = dom.document.createElement("script")
    script.id = Bootstrap.ElementId
    script.setAttribute("type", "application/json")
    script.textContent = contents
    dom.document.body.appendChild(script): Unit
    try check()
    finally dom.document.body.removeChild(script): Unit
  }

  test("readsEveryFieldTheGatewayInjects") {
    val json = """{"basePath":"/kafka","apiBase":"/kafka/api/v1","buildVersion":"1.2.3"}"""
    withScript(json) { () =>
      assertEquals(Bootstrap.read(), Bootstrap("/kafka", "/kafka/api/v1", "1.2.3"))
    }
  }

  test("aMissingBlockYieldsTheRootDeploymentDefaultsRatherThanAStartUpFailure") {
    assertEquals(Bootstrap.read(), Bootstrap.Fallback)
  }

  test("malformedJsonYieldsTheDefaultsAndDoesNotThrow") {
    withScript("{ this is not json") { () =>
      assertEquals(Bootstrap.read(), Bootstrap.Fallback)
    }
  }

  test("aBlockMissingAFieldFallsBackFieldByField") {
    // A gateway that stops sending a field, or one built before a field existed, must not blank the
    // other two: each field falls back on its own.
    withScript("""{"buildVersion":"9.9.9"}""") { () =>
      assertEquals(Bootstrap.read(), Bootstrap("", "/api/v1", "9.9.9"))
    }
  }

  test("trailingSlashesAreRemovedSoJoiningNeverDoublesOne") {
    withScript("""{"basePath":"/kafka/","apiBase":"/kafka/api/v1/","buildVersion":"1.0.0"}""") { () =>
      assertEquals(Bootstrap.read(), Bootstrap("/kafka", "/kafka/api/v1", "1.0.0"))
    }
  }

  test("absoluteApiBaseJoinsTheBrowsersOriginToTheConfiguredPath") {
    val bootstrap = Bootstrap("", "/api/v1", "dev")
    assertEquals(Bootstrap.absoluteApiBase(bootstrap, "http://localhost:8080"), "http://localhost:8080/api/v1")
    assertEquals(Bootstrap.absoluteApiBase(bootstrap, "http://localhost:8080/"), "http://localhost:8080/api/v1")
  }

  test("anApiBaseThatIsAlreadyAbsoluteIsNotPrefixed") {
    val bootstrap = Bootstrap("", "https://api.example.com/v1", "dev")
    assertEquals(Bootstrap.absoluteApiBase(bootstrap, "http://localhost:8080"), "https://api.example.com/v1")
  }
}
