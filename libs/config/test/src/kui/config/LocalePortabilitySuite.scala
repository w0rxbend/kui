package kui.config

import java.util.Locale

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.testkit.KuiSuite

final class LocalePortabilitySuite extends KuiSuite {

  private def underTurkishLocale[A](body: => A): A = {
    val previous = Locale.getDefault
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      body
    } finally Locale.setDefault(previous)
  }

  test("wire token parsing is independent of the host locale") {
    val parsed = underTurkishLocale {
      (
        AuthType.fromWire("OIDC"),
        RegistryAuthConfig.fromWire("BASIC"),
        UpstreamAuthConfig.fromWire("BASIC"),
        MetricsSourceKind.fromWire("PROMETHEUS-API")
      )
    }

    assertEquals(
      parsed,
      (
        Some(AuthType.Oidc),
        Some("basic"),
        Some(UpstreamAuthConfig.Mechanism.Basic),
        Some(MetricsSourceKind.PrometheusApi)
      )
    )
  }

  test("environment key and map-member normalization is independent of the host locale") {
    val config = underTurkishLocale {
      KuiConfigSource
        .loadFrom[IO](
          Nil,
          Nil,
          Map(
            "KUI_SERVER_PORT" -> "9191",
            "KUI_GATEWAY_SERVICES_INTERNAL_URL" -> "http://localhost:8081"
          ),
          UrlPolicy.Dev
        )
        .unsafeRunSync()
        .fold(errors => fail(errors.render), identity)
    }

    assertEquals(config.server.port.value, 9191)
    assertEquals(config.gateway.services.keySet.map(_.value), Set("internal"))
  }
}
