package kui.contracts

import munit.FunSuite

/** The header names are part of the wire contract, so they are pinned like any other wire value.
  *
  * Renaming one is a breaking change for every browser tab that is already open and for every script anyone
  * has written against the API. This suite is what makes that rename show up in a diff as a deliberate act
  * rather than as a passing build.
  */
class HttpHeadersSuite extends FunSuite {

  test("theCsrfHeaderIsNamedXCsrfToken") {
    assertEquals(HttpHeaders.Csrf, "X-Csrf-Token")
  }

  test("theCsrfHeaderIsOutsideTheStrippedXKuiFamily") {
    // `EdgeHeaders` removes every inbound `X-Kui-*` header (ADR-040). A CSRF header inside that family
    // would be deleted before the check that needs it ever ran, which is precisely the failure this
    // constant was extracted to prevent.
    assert(!HttpHeaders.Csrf.toLowerCase.startsWith("x-kui-"))
  }
}
