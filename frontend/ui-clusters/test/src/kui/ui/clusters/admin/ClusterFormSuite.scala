package kui.ui.clusters.admin

import munit.FunSuite

import kui.contracts.Section
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto}
import kui.kernel.ClusterId

/** Turning what an operator typed into a write request, and refusing to when it is not one.
  *
  * The form checks a deliberate subset of what the server checks, and this suite is where that subset is
  * written down. The server remains the authority: nothing here may declare something valid that the server
  * then rejects, because a form that says "saved" and then shows a 400 is worse than one that never claimed
  * to know.
  */
final class ClusterFormSuite extends FunSuite {

  private val filled: ClusterForm =
    ClusterForm.Empty.copy(name = "production eu", bootstrapServers = "broker-1:9092")

  test("aPlaintextClusterNeedsOnlyANameAndAnAddress") {
    val request = filled.toRequest.fold(problems => fail(problems.mkString("; ")), identity)

    assertEquals(request.name, "production eu")
    assertEquals(request.bootstrapServers, "broker-1:9092")
    assertEquals(request.security.protocol, ClusterForm.Plaintext)
    // No mechanism, no username, no password: on a plaintext connection those are not settings the
    // cluster has and is ignoring, they are not settings at all.
    assertEquals(request.security.mechanism, None)
    assertEquals(request.security.username, None)
    assertEquals(request.security.password.map(_.value), None)
  }

  test("everyProblemIsReportedAtOnce") {
    // ADR-013's rule, applied to a form for the same reason the server applies it to a body: someone who
    // got three fields wrong should be told about all three rather than discovering them one save at a
    // time.
    val broken = ClusterForm.Empty.copy(timeoutMs = "soon")
    val problems = broken.toRequest.swap.getOrElse(fail("an empty form is not a request"))

    assert(problems.size >= 3, problems.mkString("; "))
    assert(problems.exists(_.contains("name")), problems.mkString("; "))
    assert(problems.exists(_.contains("address")), problems.mkString("; "))
  }

  test("aSaslClusterNeedsAMechanismAUsernameAndAPassword") {
    val partial = filled.copy(protocol = ClusterForm.SaslSsl, username = "", password = "")
    val problems = partial.toRequest.swap.getOrElse(fail("SASL with no credentials is not a request"))

    assert(problems.exists(_.contains("username")), problems.mkString("; "))
    assert(problems.exists(_.contains("password")), problems.mkString("; "))
  }

  test("aCompleteSaslFormCarriesTheCredentialsAsSecrets") {
    val complete =
      filled.copy(protocol = ClusterForm.SaslSsl, username = "kui", password = "hunter2")

    val request = complete.toRequest.fold(problems => fail(problems.mkString("; ")), identity)

    assertEquals(request.security.mechanism, Some("SCRAM-SHA-512"))
    assertEquals(request.security.username, Some("kui"))
    // The `Secret` wrapper is what keeps it out of a log line: its `toString` redacts. The value is still
    // the one that was typed.
    assertEquals(request.security.password.map(_.value), Some("hunter2"))
    assertEquals(request.security.password.map(_.toString), Some("Secret(***)"))
  }

  test("theAdminNumbersAreParsedOnceAndOnlyWhenTheFormIsSubmitted") {
    // Held as strings while the operator types, because an `Int` field would have to decide what an empty
    // box is, and every answer to that is wrong.
    val halfTyped = filled.copy(batchSize = "20x")
    assert(halfTyped.toRequest.isLeft)

    val typed = filled.copy(batchSize = "500")
    assertEquals(typed.toRequest.map(_.admin.batchSize), Right(500))
  }

  test("anEditFormCarriesEverythingTheReadModelHasAndNothingItDoesNot") {
    val row = ClusterRowDto(
      id = ClusterId.unsafe("prod-eu"),
      name = "production eu",
      readOnly = true,
      bootstrapServers = "broker-1:9093",
      security = ClusterSecurityDto("SASL_SSL", Some("SCRAM-SHA-256"), true, false),
      summary = Section.NotConfigured
    )

    val form = ClusterForm.of(row)

    assertEquals(form.name, "production eu")
    assertEquals(form.bootstrapServers, "broker-1:9093")
    assertEquals(form.readOnly, true)
    assertEquals(form.protocol, "SASL_SSL")
    assertEquals(form.mechanism, "SCRAM-SHA-256")
    // The password is not on the read model and must not be invented. An empty box plus the sentence that
    // explains it is the honest rendering; dots would make an operator who did not touch the field wipe
    // the credential by saving.
    assertEquals(form.password, "")
    assertEquals(form.username, "")
  }

  test("theSlugMatchesTheOneTheServerDerives") {
    // The browser has to compute it, because it decides which path the create is sent to. A drift between
    // the two is a 400 an operator can read, not a cluster written under the wrong key -- but it is still
    // a drift, so the shape is asserted here.
    assertEquals(ClusterAdminPage.slugOf("production eu"), "production-eu")
    assertEquals(ClusterAdminPage.slugOf("  Staging (EU) "), "staging-eu")
    assertEquals(ClusterAdminPage.slugOf("a---b"), "a-b")
    assertEquals(ClusterAdminPage.slugOf("!!!"), "")
  }

  test("aClusterFromTheConfigurationFileOffersNoButtons") {
    // The screen reads this before it draws a row, so that an operator is told "this one is in your file"
    // instead of being allowed to fill in a form the server will refuse.
    def row(origin: String): ClusterRowDto =
      ClusterRowDto(
        id = ClusterId.unsafe("prod-eu"),
        name = "production eu",
        readOnly = false,
        bootstrapServers = "broker-1:9092",
        security = ClusterSecurityDto("PLAINTEXT", None, false, false),
        summary = Section.NotConfigured,
        version = Some(3L),
        origin = origin
      )

    assert(!ClusterRowDto.isEditable(row(ClusterRowDto.OriginStatic)))
    assert(!ClusterRowDto.isRemovable(row(ClusterRowDto.OriginStatic)))

    assert(ClusterRowDto.isEditable(row(ClusterRowDto.OriginStored)))
    assert(ClusterRowDto.isRemovable(row(ClusterRowDto.OriginStored)))

    // Editable, because a store write still wins; not removable, because the file would put it back.
    assert(ClusterRowDto.isEditable(row(ClusterRowDto.OriginStaticThenStored)))
    assert(!ClusterRowDto.isRemovable(row(ClusterRowDto.OriginStaticThenStored)))
  }

  test("aRowFromAnOlderGatewayDecodesAsUneditableRatherThanFailing") {
    // The safe default is the one that refuses to edit, not the one that tries: a document written before
    // these fields existed carries no version, and a write with no version is a lost update.
    val document =
      """{"id":"prod-eu","name":"production eu","readOnly":false,
        |"bootstrapServers":"broker-1:9092",
        |"security":{"protocol":"PLAINTEXT","truststoreConfigured":false,"keystoreConfigured":false},
        |"summary":{"status":"not_configured"}}""".stripMargin

    val decoded = io.circe.parser
      .decode[ClusterRowDto](document)
      .fold(error => fail(s"a row from an older gateway must still decode: $error"), identity)

    assertEquals(decoded.version, None)
    assert(!ClusterRowDto.isEditable(decoded))
  }
}
