package kui.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.{Gen, Prop}

import kui.kernel.PageSize
import kui.kernel.search.SearchMode
import kui.testkit.KuiSuite

/** That the topic service's dials are the documented ones, that an impossible combination of them stops the
  * process at startup rather than producing overlapping scrapes at three in the morning, and that every
  * problem in the file is reported in one message.
  *
  * The defaults are asserted against the table in `docs/operations/configuration.md` rather than against
  * whatever the code happens to say, so the source and the operator documentation cannot drift apart without
  * a test going red.
  */
final class TopicsConfigSuite extends KuiSuite {

  private def load(
      yaml: String,
      env: Map[String, String] = Map.empty
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List(ConfigFixtures.yaml(yaml)), env, UrlPolicy.Dev)
      .unsafeRunSync()

  private def topics(result: Either[ConfigErrors, KuiConfig]): TopicsConfig =
    result.fold(errors => fail(errors.render), _.topics)

  private def problems(result: Either[ConfigErrors, KuiConfig]): List[ConfigProblem] =
    result match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  private def renderOf(result: Either[ConfigErrors, KuiConfig]): String =
    result match {
      case Left(errors) => errors.render
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  // -----------------------------------------------------------------------------------------------
  // The documented defaults
  // -----------------------------------------------------------------------------------------------

  test("defaultsAreTheDocumentedOnes") {
    val loaded = topics(load("kui:\n  server:\n    port: 8080\n"))
    assertEquals(loaded.refreshInterval, 60.seconds)
    assertEquals(loaded.scrapeTimeout, 45.seconds)
    assertEquals(loaded.internalPrefix, "__")
    assertEquals(loaded.defaultSearchMode, SearchMode.Plain)
    assertEquals(loaded.defaultPageSize.value, 25)
    assertEquals(loaded.maxPageSize.value, 500)
    assertEquals(loaded.clusterProfiles, None)
    assertEquals(loaded, TopicsConfig.Default)
  }

  test("everyKeyIsRead") {
    val loaded = topics(
      load(
        """|kui:
           |  topics:
           |    refreshInterval: 120s
           |    scrapeTimeout: 90s
           |    internalPrefix: _sys_
           |    defaultSearchMode: fts
           |    defaultPageSize: 50
           |    maxPageSize: 200
           |  clusterProfiles:
           |    url: http://kui-cluster:8081
           |    pollInterval: 30s
           |    requestTimeout: 2s
           |    reconnectBackoff: 500ms
           |    maxReconnectBackoff: 20s
           |    startupTimeout: 5s
           |""".stripMargin
      )
    )
    assertEquals(loaded.refreshInterval, 120.seconds)
    assertEquals(loaded.scrapeTimeout, 90.seconds)
    assertEquals(loaded.internalPrefix, "_sys_")
    assertEquals(loaded.defaultSearchMode, SearchMode.Fts)
    assertEquals(loaded.defaultPageSize.value, 50)
    assertEquals(loaded.maxPageSize.value, 200)

    val profiles = loaded.clusterProfiles.getOrElse(fail("the profile client should have been configured"))
    assertEquals(profiles.url.value, "http://kui-cluster:8081")
    assertEquals(profiles.pollInterval, 30.seconds)
    assertEquals(profiles.requestTimeout, 2.seconds)
    assertEquals(profiles.reconnectBackoff, 500.millis)
    assertEquals(profiles.maxReconnectBackoff, 20.seconds)
    assertEquals(profiles.startupTimeout, 5.seconds)
  }

  test("theEnvironmentSuppliesTheSameKeys") {
    val loaded = topics(
      load(
        "kui:\n  server:\n    port: 8080\n",
        Map(
          "KUI_TOPICS_REFRESHINTERVAL" -> "300s",
          "KUI_TOPICS_DEFAULTSEARCHMODE" -> "fts",
          "KUI_CLUSTERPROFILES_URL" -> "http://cluster:8081"
        )
      )
    )
    assertEquals(loaded.refreshInterval, 300.seconds)
    assertEquals(loaded.defaultSearchMode, SearchMode.Fts)
    assertEquals(loaded.clusterProfiles.map(_.url.value), Some("http://cluster:8081"))
  }

  // -----------------------------------------------------------------------------------------------
  // Reporting everything at once
  // -----------------------------------------------------------------------------------------------

  test("everyProblemIsReportedTogether") {
    val message = renderOf(
      load(
        """|kui:
           |  topics:
           |    refreshInterval: 1s
           |    defaultSearchMode: fuzzy
           |    maxPageSize: 100000
           |""".stripMargin
      )
    )
    assert(message.contains("kui.topics.refreshInterval"), message)
    assert(message.contains("kui.topics.defaultSearchMode"), message)
    assert(message.contains("kui.topics.maxPageSize"), message)
  }

  test("anUnknownKeyUnderKuiTopicsIsRejected") {
    // `refreshIntervall` is the typo this rule exists for: without it the operator's setting is silently
    // ignored and the service keeps the default forever.
    val reported = problems(load("kui:\n  topics:\n    refreshIntervall: 90s\n"))
    assertEquals(reported.map(_.key), List("kui.topics.refreshIntervall"))
    assert(reported.head.problem.contains("not a KUI configuration key"), reported.head.problem)
  }

  // -----------------------------------------------------------------------------------------------
  // The cross-field rules
  // -----------------------------------------------------------------------------------------------

  test("scrapeTimeoutMustBeShorterThanTheInterval") {
    val message = renderOf(load("kui:\n  topics:\n    refreshInterval: 10s\n    scrapeTimeout: 30s\n"))
    assert(message.contains("kui.topics.scrapeTimeout"), message)
    assert(message.contains("kui.topics.refreshInterval"), message)
    assert(message.contains("30 seconds"), message)
    assert(message.contains("10 seconds"), message)

    // The other direction is a legal configuration, and so is either dial moved on its own.
    val loaded = topics(load("kui:\n  topics:\n    refreshInterval: 30s\n    scrapeTimeout: 10s\n"))
    assertEquals(loaded.refreshInterval, 30.seconds)
    assertEquals(loaded.scrapeTimeout, 10.seconds)
  }

  test("aScrapeTimeoutEqualToTheIntervalIsRejected") {
    // Equal is not "shorter": two scrapes would meet exactly at the boundary, which is the overlap the
    // rule exists to prevent.
    val message = renderOf(load("kui:\n  topics:\n    refreshInterval: 30s\n    scrapeTimeout: 30s\n"))
    assert(message.contains("must be shorter than"), message)
  }

  test("raisingOnlyTheTimeoutIsCheckedAgainstTheDefaultInterval") {
    // The rule compares against the default when the other key is absent, because a file that sets one
    // dial and not the other is the common case and it can still be nonsense.
    val message = renderOf(load("kui:\n  topics:\n    scrapeTimeout: 90s\n"))
    assert(message.contains("kui.topics.scrapeTimeout"), message)
  }

  test("defaultPageSizeAboveTheMaximumIsRejected") {
    val message = renderOf(load("kui:\n  topics:\n    defaultPageSize: 100\n    maxPageSize: 50\n"))
    assert(message.contains("kui.topics.defaultPageSize"), message)
    assert(message.contains("kui.topics.maxPageSize"), message)
  }

  test("maxPageSizeAboveFiveHundredIsRejected") {
    // ADR-026's cap. An operator raising this to 100 000 would be configuring an outage: these lists are
    // built in memory.
    val reported = problems(load("kui:\n  topics:\n    maxPageSize: 501\n"))
    assertEquals(reported.map(_.key), List("kui.topics.maxPageSize"))
    assertEquals(topics(load("kui:\n  topics:\n    maxPageSize: 500\n")).maxPageSize, PageSize.Max)
  }

  test("anEmptyInternalPrefixIsRejected") {
    // An empty prefix makes `startsWith` true for every name, so every topic on the cluster would be
    // internal and the list would be empty by default.
    val reported = problems(load("kui:\n  topics:\n    internalPrefix: \"\"\n"))
    assertEquals(reported.map(_.key), List("kui.topics.internalPrefix"))
    assert(reported.head.problem.contains("every topic"), reported.head.problem)
  }

  test("anOverlongInternalPrefixIsRejected") {
    val reported = problems(load(s"kui:\n  topics:\n    internalPrefix: ${"x" * 17}\n"))
    assertEquals(reported.map(_.key), List("kui.topics.internalPrefix"))
  }

  test("theInternalPrefixRuleIsTheOneTheDomainUses") {
    val loaded = topics(load("kui:\n  topics:\n    internalPrefix: __\n"))
    assert(loaded.isInternalByPrefix("__kui_config"))
    assert(loaded.isInternalByPrefix("__consumer_offsets"))
    assert(!loaded.isInternalByPrefix("orders"))
  }

  // -----------------------------------------------------------------------------------------------
  // The profile client
  // -----------------------------------------------------------------------------------------------

  test("theProfileUrlMustBeAValidUri") {
    val reported = problems(load("kui:\n  clusterProfiles:\n    url: \"not a url\"\n"))
    assertEquals(reported.map(_.key), List("kui.clusterProfiles.url"))
  }

  test("aProcessWithNoProfileUrlHasNoProfileClient") {
    // The gateway, the cluster service and the store all load this same root and none of them has a
    // profile client. Making the URL globally required would stop every one of them from starting; it is
    // the topic service's own composition root that refuses to run without it.
    assertEquals(topics(load("kui:\n  server:\n    port: 8080\n")).clusterProfiles, None)
  }

  test("theProfileDefaultsAreTheDocumentedOnes") {
    val profiles = topics(load("kui:\n  clusterProfiles:\n    url: http://cluster:8081\n")).clusterProfiles
      .getOrElse(fail("the profile client should have been configured"))
    assertEquals(profiles.pollInterval, 60.seconds)
    assertEquals(profiles.requestTimeout, 5.seconds)
    assertEquals(profiles.reconnectBackoff, 1.second)
    assertEquals(profiles.maxReconnectBackoff, 30.seconds)
    assertEquals(profiles.startupTimeout, 10.seconds)
  }

  test("aBackoffCapBelowTheFirstDelayIsRejected") {
    // A cap under the first delay would make the backoff shrink instead of grow, which is a hot retry loop
    // against a service that is already struggling.
    val message = renderOf(
      load(
        """|kui:
           |  clusterProfiles:
           |    url: http://cluster:8081
           |    reconnectBackoff: 30s
           |    maxReconnectBackoff: 5s
           |""".stripMargin
      )
    )
    assert(message.contains("kui.clusterProfiles.maxReconnectBackoff"), message)
    assert(message.contains("kui.clusterProfiles.reconnectBackoff"), message)
  }

  test("theProfileTimingsAreBounded") {
    val reported = problems(
      load(
        """|kui:
           |  clusterProfiles:
           |    url: http://cluster:8081
           |    pollInterval: 1s
           |    startupTimeout: 5m
           |""".stripMargin
      )
    )
    assertEquals(
      reported.map(_.key).sorted,
      List("kui.clusterProfiles.pollInterval", "kui.clusterProfiles.startupTimeout")
    )
  }

  // -----------------------------------------------------------------------------------------------
  // The documented edges, as a property
  // -----------------------------------------------------------------------------------------------

  private def seconds(duration: FiniteDuration): String = s"${duration.toSeconds}s"

  property("boundsAreInclusiveAtTheDocumentedEdges") {
    val intervals = Gen.oneOf(TopicsConfig.MinRefreshInterval, TopicsConfig.MaxRefreshInterval)
    val prefixes = Gen.oneOf(
      "x",
      "x" * TopicsConfig.MaxInternalPrefixLength
    )
    val pageSizes = Gen.oneOf(1, PageSize.Max.value)

    Prop.forAllNoShrink(intervals, prefixes, pageSizes) { (interval, prefix, maxPageSize) =>
        val loaded = topics(
          load(
            s"""|kui:
                |  topics:
                |    refreshInterval: ${seconds(interval)}
                |    scrapeTimeout: ${seconds(TopicsConfig.MinScrapeTimeout)}
                |    internalPrefix: "$prefix"
                |    defaultPageSize: 1
                |    maxPageSize: $maxPageSize
                |""".stripMargin
          )
        )
        loaded.refreshInterval == interval &&
        loaded.internalPrefix == prefix &&
      loaded.maxPageSize.value == maxPageSize
    }
  }
}
