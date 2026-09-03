package kui.kafka.auth

import java.util.Collections

import scala.jdk.CollectionConverters.*

import org.apache.kafka.common.config.types.Password
import org.apache.kafka.common.security.JaasContext
import org.scalacheck.Prop.forAll
import org.scalacheck.{Gen, Prop}

import kui.kafka.auth.Jaas.JaasValue
import kui.kernel.Secret
import kui.testkit.{ClusterGenerators, KuiSuite}

/** The test that closes the injection bug.
  *
  * `renderedJaasParsesBackToTheInput` is not checked against a reimplementation of the JAAS
  * grammar, which could share a bug with the renderer. It is checked against
  * `JaasContext.loadClientContext`, which is the code Kafka itself runs on the string KUI produces.
  * If it parses back to exactly the options that went in, and to no others, then no password can
  * end a quoted value early and smuggle a login-module option past the operator.
  */
final class JaasSuite extends KuiSuite {

  /** A thousand samples rather than the project default of a hundred.
    *
    * This is the one property in KUI whose failure is a security defect rather than a bug, and its
    * input space — the awkward characters of a password — is small enough that a thousand samples
    * cover it densely and cost a fraction of a second.
    */
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  /** Parses a rendered `sasl.jaas.config` the way a Kafka client does. */
  private def parseOptions(jaas: String): Map[String, String] = {
    val context =
      JaasContext.loadClientContext(Collections.singletonMap("sasl.jaas.config", new Password(jaas)))

    context.configurationEntries.get(0).getOptions.asScala.map { (key, value) =>
      key -> String.valueOf(value)
    }.toMap
  }

  private val Backslash: String = "\\"
  private val Quote: String = "\""

  test("quoteEscapesBackslashThenQuote") {
    assertEquals(Jaas.quote(""), Quote + Quote)
    assertEquals(Jaas.quote("plain"), Quote + "plain" + Quote)
    assertEquals(Jaas.quote(Backslash), Quote + Backslash + Backslash + Quote)
    assertEquals(Jaas.quote(Quote), Quote + Backslash + Quote + Quote)
    assertEquals(
      Jaas.quote(Backslash + Quote),
      Quote + Backslash + Backslash + Backslash + Quote + Quote
    )
    assertEquals(
      Jaas.quote(Quote + Quote),
      Quote + Backslash + Quote + Backslash + Quote + Quote
    )
  }

  test("moduleTerminatesWithASemicolon") {
    val rendered = Jaas
      .module(LoginModules.Plain, "required", List("username" -> JaasValue.Plain("u")))
      .map(_.value)

    assertEquals(rendered, Right(s"${LoginModules.Plain} required username=${Quote}u${Quote};"))
  }

  test("aModuleWithNoOptionsIsStillWellFormed") {
    assertEquals(
      Jaas.module(LoginModules.OAuthBearer, "required", Nil).map(_.value),
      Right(s"${LoginModules.OAuthBearer} required;")
    )
  }

  test("rejectsControlCharacters") {
    val forbidden: List[Char] = List('\n', '\r', '\t', '\u0000', '\u007f')

    forbidden.foreach { bad =>
      val result = Jaas.module(
        LoginModules.Plain,
        "required",
        List("password" -> JaasValue.Hidden(Secret(s"prefix${bad}suffix")))
      )

      assert(result.isLeft, s"expected a rejection for character ${bad.toInt}")

      val message = result.left.map(_.message).left.getOrElse("")
      assert(message.contains("control character"), message)
      assert(!message.contains("prefix"), "the error echoed the value it refused")
    }
  }

  test("theRejectionNamesTheOptionAndNotTheValue") {
    val result = Jaas.module(
      LoginModules.Plain,
      "required",
      List("password" -> JaasValue.Hidden(Secret("line\nbreak")))
    )

    assertEquals(result.left.map(_.fieldName), Left("sasl.jaas.config.password"))
  }

  property("renderedJaasParsesBackToTheInput") {
    forAll(ClusterGenerators.genUsername, ClusterGenerators.genAwkwardSecretString) {
      (user, password) =>
        val rendered = Jaas
          .module(
            LoginModules.Scram,
            "required",
            List(
              "username" -> JaasValue.Plain(user),
              "password" -> JaasValue.Hidden(Secret(password))
            )
          )
          .map(_.value)

        rendered match {
          case Left(error) => Prop.falsified :| s"refused a legal password: ${error.fieldName}"
          case Right(jaas) =>
            val parsed = parseOptions(jaas)

            assertEquals(parsed.get("username"), Some(user))
            assertEquals(parsed.get("password"), Some(password))
            // Nothing was injected: exactly the two options that were rendered came back.
            assertEquals(parsed.keySet, Set("username", "password"))
            Prop.passed
        }
    }
  }

  property("aPasswordThatWouldCloseTheQuotedValueEarlyDoesNot") {
    val hostile: Gen[String] = Gen.oneOf(
      Quote + " password=" + Quote + "injected",
      Quote + "; org.example.Evil required x=" + Quote + "1" + Quote + ";",
      Backslash,
      Backslash + Backslash + Quote + " serviceName=" + Quote + "kafka"
    )

    forAll(hostile) { password =>
      val jaas = Jaas
        .module(
          LoginModules.Plain,
          "required",
          List(
            "username" -> JaasValue.Plain("u"),
            "password" -> JaasValue.Hidden(Secret(password))
          )
        )
        .map(_.value)
        .getOrElse("")

      val parsed = parseOptions(jaas)

      assertEquals(parsed.keySet, Set("username", "password"))
      assertEquals(parsed.get("password"), Some(password))
    }
  }
}
