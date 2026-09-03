package kui.gateway.api.openapi

import cats.effect.IO
import munit.FunSuite
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import kui.cluster.contract.ClusterEndpoints
import kui.contracts.KuiEndpoint
import kui.kernel.ServiceId

/** One case per house rule, and a clean document that breaks none of them.
  *
  * Each rule is a promise an integrator relies on and cannot verify for themselves, so each needs a test
  * that would fail if the rule stopped being enforced -- not only a test that the current document passes.
  */
final class OpenApiStyleCheckSuite extends FunSuite {

  private val cluster = ServiceId.unsafe("cluster")

  private def documentOf(endpoints: List[AnyEndpoint]) =
    OpenApiMerge
      .merge("KUI", "1.0", List("/"), List(ServiceDoc(cluster, endpoints)))
      .fold(problems => fail(problems.toList.mkString("; ")), identity)

  private def violationsOf(endpoints: List[AnyEndpoint]): List[StyleViolation] =
    OpenApiStyleCheck.check(documentOf(endpoints))

  test("aCleanDocumentProducesNoViolations") {
    assertEquals(violationsOf(ClusterEndpoints.all), Nil)
  }

  test("theWholeMergedProductDocumentIsClean") {
    // The assertion CI actually cares about: the document KUI publishes today breaks no rule.
    val document = DocsRoutes
      .document[IO](OpenApiDocument.documentedServices, List("/"))
      .fold(problem => fail(problem), identity)

    assertEquals(OpenApiStyleCheck.check(document), Nil)
  }

  test("missingOperationId") {
    val nameless: AnyEndpoint = KuiEndpoint.internal.get.in("internal" / "v1" / "nameless").summary("s")

    assert(
      violationsOf(List(nameless)).exists {
        case StyleViolation.MissingOperationId(_, "GET") => true
        case _ => false
      },
      violationsOf(List(nameless)).toString
    )
  }

  test("missingSummary") {
    val terse: AnyEndpoint = KuiEndpoint.internal.get.in("internal" / "v1" / "terse").name("cluster.terse")

    assert(violationsOf(List(terse)).contains(StyleViolation.MissingSummary("cluster.terse")))
  }

  test("missingTag") {
    // Tags are added by the merge, so this rule is checked against a hand-built document: an endpoint that
    // reached the document untagged would be ungrouped in the UI.
    val untagged =
      OpenAPIDocsInterpreter().toOpenAPI(
        List(KuiEndpoint.internal.get.in("api" / "v1" / "loose").name("cluster.loose").summary("s")),
        "KUI",
        "1.0"
      )

    assert(OpenApiStyleCheck.check(untagged).contains(StyleViolation.MissingTag("cluster.loose")))
  }

  test("duplicateOperationId") {
    // The merge rejects duplicates before a document exists, so the check is exercised on a document built
    // without it -- the rule still has to hold for any document reaching the checker.
    val twice = List(
      KuiEndpoint.internal.get.in("api" / "v1" / "a").name("cluster.same").summary("s").tag("cluster"),
      KuiEndpoint.internal.get.in("api" / "v1" / "b").name("cluster.same").summary("s").tag("cluster")
    )
    val document = OpenAPIDocsInterpreter().toOpenAPI(twice, "KUI", "1.0")

    assert(OpenApiStyleCheck.check(document).contains(StyleViolation.DuplicateOperationId("cluster.same")))
  }

  test("nonKebabPath") {
    val shouty: AnyEndpoint =
      KuiEndpoint.internal.get.in("internal" / "v1" / "TopicNames").name("cluster.shouty").summary("s")

    assert(
      violationsOf(List(shouty)).contains(StyleViolation.NonKebabPath("/api/v1/TopicNames")),
      violationsOf(List(shouty)).toString
    )
  }

  test("pathParametersAreExemptFromTheKebabRule") {
    // `{service}` is a Scala identifier, camelCase by convention throughout Tapir, and renaming those to
    // kebab-case would make the generated client's parameter names unidiomatic for no reader's benefit.
    val parameterised: AnyEndpoint = KuiEndpoint.internal.get
      .in("internal" / "v1" / "topics" / path[String]("topicName"))
      .name("cluster.topic")
      .summary("s")

    assertEquals(violationsOf(List(parameterised)), Nil)
  }

  test("errorResponseIsNotTheEnvelope") {
    // An endpoint that does not answer with KUI's one error shape leaves every client to write bespoke
    // error handling for it (ADR-034).
    val bespoke: AnyEndpoint =
      endpoint.get.in("api" / "v1" / "odd").name("cluster.odd").summary("s").tag("cluster")
    val document = OpenAPIDocsInterpreter().toOpenAPI(List(bespoke), "KUI", "1.0")

    assert(
      OpenApiStyleCheck.check(document).exists {
        case StyleViolation.ErrorResponseIsNotTheEnvelope("cluster.odd", _) => true
        case _ => false
      },
      OpenApiStyleCheck.check(document).toString
    )
  }

  test("everyViolationExplainsItselfToWhoeverTrippedIt") {
    // The person who trips this is looking at a build failure in a service they may not own.
    val all = List(
      StyleViolation.MissingOperationId("/api/v1/x", "GET"),
      StyleViolation.MissingSummary("a.b"),
      StyleViolation.MissingTag("a.b"),
      StyleViolation.DuplicateOperationId("a.b"),
      StyleViolation.NonKebabPath("/api/v1/X"),
      StyleViolation.ErrorResponseIsNotTheEnvelope("a.b", 500)
    )

    all.foreach(violation => assert(violation.message.nonEmpty, s"$violation has no message"))
  }
}
