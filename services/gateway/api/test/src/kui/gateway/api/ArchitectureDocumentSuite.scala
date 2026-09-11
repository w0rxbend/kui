package kui.gateway.api

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import munit.FunSuite

/** That `ARCHITECTURE.md` §3 describes this repository rather than the one somebody meant to build.
  *
  * §3 is the document a newcomer is pointed at to learn where a port lives, which layer a service has and why
  * the gateway may name another service's endpoint value. Every claim in it was prose, read by nothing, for
  * six milestones — and two of them were repaired in wave 6 only because a wave plan happened to notice them
  * by eye. This suite is the thing that reads them.
  *
  * It lives in the gateway's test module rather than in `build-tests` because the claims it checks are the
  * gateway's own subject matter: which services it may depend on, where a relayed endpoint value has to live,
  * and which layers the gateway itself has. Nothing here imports another service — the assertions are made
  * against directory names and file text, so no module edge is created by making them.
  */
final class ArchitectureDocumentSuite extends FunSuite {

  private val document: String = read("ARCHITECTURE.md")

  /** The one file `checkArchitecture` derives its rule set from. */
  private val ruleSource: String = read("build-tests/src/kui/build/ArchitectureRules.scala")

  private val gatewayModuleDeps: List[String] = moduleDepsOfGatewayApi()

  /** Every `services/<name>` directory, which is the roster every claim below is measured against. */
  private val services: List[String] =
    Files
      .list(repositoryRoot.resolve("services"))
      .iterator
      .asScala
      .filter(Files.isDirectory(_))
      .map(_.getFileName.toString)
      .toList
      .sorted

  private val otherServices: List[String] = services.filterNot(_ == "gateway")

  test("everyPortNamedInTheServiceTableIsATraitInThatServicesDomain") {
    // The column header says `domain`, and for six of the eight built services it named something else:
    // a port that really lives in `application`, an identifier the tree declares nowhere, or a trait
    // belonging to a library. None of it was checkable, so all of it drifted. Reading the table here is
    // what makes the header a rule instead of a label.
    serviceRows.foreach { case (service, ports) =>
      val domain = repositoryRoot.resolve(s"services/$service/domain/src")

      if Files.isDirectory(domain) then {
        val declared = traitsDeclaredIn(domain)
        identifiersIn(ports).foreach(name =>
          assert(
            declared.contains(name),
            s"ARCHITECTURE.md §3 lists `$name[F]` as a port of the $service service's `domain`, and " +
              s"services/$service/domain/src declares no such trait. Ports declared there: " +
              declared.toList.sorted.mkString(", ")
          )
        )
      } else
        assert(
          ports.contains("not built"),
          s"ARCHITECTURE.md §3 has a $service row and there is no services/$service/domain; a row for a " +
            "service that does not exist has to say so in words"
        )
    }
  }

  test("everyPortTraitInAServicesDomainIsNamedInItsRow") {
    // The other direction, and the one the check above structurally cannot make. It reads the row and asks
    // the tree; nothing asked the tree and read the row, so *omission* was invisible: deleting
    // `ClusterFactsPort[F]` from the alerts row left this suite 6/6 green and the emptied cell then read as
    // a service with no outbound ports at all. That is the identical failure mode `ServiceContracts`' own
    // comments call out for endpoint lists — a list that is missing an entry looks exactly like a list that
    // never had one — and a table of ports is a worse place for it than a list of endpoints, because a
    // missing route 404s and a missing row just misleads a newcomer.
    val rows = serviceRows.toMap

    otherServices.foreach { service =>
      val domain = repositoryRoot.resolve(s"services/$service/domain/src")

      if Files.isDirectory(domain) then {
        val named = rows.get(service).map(identifiersIn).getOrElse(Nil).toSet

        traitsDeclaredIn(domain).toList.sorted.foreach(port =>
          assert(
            named.contains(port),
            s"services/$service/domain/src declares `$port[F]` and ARCHITECTURE.md §3's $service row " +
              s"does not name it. Named there: ${named.toList.sorted.mkString(", ")}"
          )
        )
      }
    }
  }

  test("theRosterOfDomainOwningServicesIsTheRosterOnDisk") {
    // §3's prose used to open with a count — "six of the eight built services" — that had been wrong for two
    // waves, because `services/connect` and then `services/ksql` arrived and no arithmetic anywhere was
    // rerun. A count in prose is the cheapest thing in a document to get wrong and the hardest to notice,
    // so the sentence now carries the roster itself and this reads it. The list is the gate, not the
    // number: a name added to the tree and not to the sentence fails here, and so does the reverse.
    val anchor = "**These services own a `domain`**"
    val start = document.indexOf(anchor)

    assert(start >= 0, s"ARCHITECTURE.md §3 no longer opens its roster sentence with $anchor")

    val fromDash = document.indexOf('—', start + anchor.length)
    val toDash = document.indexOf('—', fromDash + 1)

    assert(fromDash > 0 && toDash > fromDash, "§3's roster sentence is no longer delimited by em dashes")

    val named = "`([a-z]+)`".r
      .findAllMatchIn(document.substring(fromDash, toDash))
      .map(_.group(1))
      .toSet
    val onDisk =
      otherServices.filter(service => Files.isDirectory(repositoryRoot.resolve(s"services/$service/domain")))

    assertEquals(named, onDisk.toSet)
  }

  test("everyBuiltServiceHasARowInTheServiceTable") {
    // The failure the check above cannot see: a service that is simply missing from the table reads as a
    // service with no ports at all. The gateway is excluded because §3 describes it in prose below the
    // table, and that prose has its own case here.
    val rows = serviceRows.map(_._1).toSet

    otherServices.foreach(service =>
      assert(rows.contains(service), s"services/$service has no row in ARCHITECTURE.md §3's table")
    )
  }

  test("theLayeringRuleTableNamesExactlyTheRulesCheckArchitectureApplies") {
    // §3 publishes a rule table and `./mill checkArchitecture` applies a rule set; nothing compared them.
    // A rule deleted from the checker would leave a documented rule that is enforced by nothing, which is
    // strictly worse than an undocumented one — a reader would stop looking for the violation.
    val documented = ruleIdsIn(document)
    val applied = "Violation\\(\"(A\\d+)\"".r.findAllMatchIn(ruleSource).map(_.group(1)).toSet

    assertEquals(
      documented,
      applied,
      s"ARCHITECTURE.md §3 documents ${documented.toList.sorted.mkString(", ")} and " +
        s"ArchitectureRules applies ${applied.toList.sorted.mkString(", ")}"
    )
    // A7 is deliberately not in either set: it is the shell holding no static reference to a feature,
    // which cannot be decided from module metadata and is enforced by `frontend/scripts/bundle-shape.mjs`.
    // §3 says so in the paragraph after the table; this asserts the gap is that one and no other.
    assert(!documented.contains("A7"), "A7 is not checkable from module metadata")
    assert(document.contains("bundle-shape.mjs"), "§3 no longer says what does enforce A7")
  }

  test("theGatewayHasTheFourLayersSection3NamesAndNeitherOfTheTwoItDisclaims") {
    // "It has no `domain` and no `infrastructure`" is the sentence that makes rules A4 and A8 the
    // gateway's real constraints instead of A1 and A3. A `services/gateway/domain` appearing one day
    // would not fail any layering rule — the rules are scoped to services that own a domain — so this
    // sentence is the only thing standing between the gateway and a business rule.
    List("contract", "application", "api", "app").foreach(layer =>
      assert(
        Files.isDirectory(repositoryRoot.resolve(s"services/gateway/$layer")),
        s"ARCHITECTURE.md §3 says the gateway has a `$layer` module and services/gateway/$layer is absent"
      )
    )
    List("domain", "infrastructure").foreach(layer =>
      assert(
        !Files.exists(repositoryRoot.resolve(s"services/gateway/$layer")),
        s"ARCHITECTURE.md §3 says the gateway has no `$layer`, and services/gateway/$layer exists"
      )
    )
  }

  test("everyRelayedEndpointValueIsWhereSection3SaysItIs") {
    // The claim that decides whether a stream can be relayed at all. Rule A4 lets the gateway see a
    // service's `contract` and nothing else, so an endpoint value a relay names has to live there — and
    // §3 named `services/alerts/api/` for a whole wave, which is a directory the gateway may not read.
    // Both halves are asserted: the file is where the document says, and the value is not in the `api`
    // module the document used to name.
    List(
      "services/alerts/contract/src-jvm/" -> "kui/alerts/contract/AlertsStreamEndpoint.scala",
      "services/message/contract/src-jvm/" -> "kui/message/contract/MessageEndpoints.scala",
      // The third relay, and the eleventh service's. It is here for the same reason as the two above it:
      // a push query never finishes, so it cannot be a derived proxy route, and the endpoint value the
      // relay is written against has to sit where rule A4 lets the gateway see it.
      "services/ksql/contract/src-jvm/" -> "kui/ksql/contract/KsqlStreamEndpoint.scala"
    ).foreach { (documented, file) =>
      assert(document.contains(documented), s"ARCHITECTURE.md §3 no longer names $documented")
      assert(
        Files.isRegularFile(repositoryRoot.resolve(documented + file)),
        s"ARCHITECTURE.md §3 says a relayed endpoint value lives in $documented, and $file is not there"
      )
    }

    // The `api` module the document named for a whole wave, and which rule A4 makes invisible to a relay.
    val inTheApiModule = "services/alerts/api/src/kui/alerts/api/AlertsStreamEndpoint.scala"

    assert(
      !Files.exists(repositoryRoot.resolve(inTheApiModule)),
      "AlertsStreamEndpoint is back in services/alerts/api, where rule A4 makes it invisible to the relay"
    )
  }

  test("theGatewayDeclaresAContractEdgeForEveryServiceThatPublishesOne") {
    // House rule 15, as a gate. `services/alerts` shipped complete and unroutable in wave 6 because
    // `alerts.contract.jvm` in `services.gateway.api`'s `moduleDeps` belonged to no packet: the module was
    // one packet's, the `ServiceContracts` row another's, and the *edge* between them nobody's. §3 states
    // the rule in prose — "It depends on every service's `contract` module" — and this is the only place
    // that reads it. A tenth service's contract module appearing without this edge fails here, in the
    // gateway's own suite, rather than at integration or in a browser.
    otherServices
      .filter(service => Files.isDirectory(repositoryRoot.resolve(s"services/$service/contract")))
      .foreach(service =>
        assert(
          gatewayModuleDeps.contains(s"$service.contract.jvm"),
          s"services/$service publishes a contract module and services.gateway.api does not depend on " +
            s"$service.contract.jvm, so the gateway cannot name a single one of its endpoint values. " +
            s"Declared there: ${gatewayModuleDeps.mkString(", ")}"
        )
      )
  }

  test("theGatewaysModuleDepsBlockWasReadAsABlockOfModuleDependencies") {
    // What the case above stands on, asserted rather than assumed. Until wave 8 the block was cut out of
    // `build.mill` by `build.indexOf("\n      )", start)` — a six-space-indented closing paren — and the
    // membership question was `String.contains`. Two ways for that to be wrong and both are quiet:
    // reformatting the block changes the indentation, so the cut runs on to some later paren and the text
    // examined is most of the build file, in which every `<service>.contract.jvm` is trivially present; or
    // the cut stops early and the check reads a prefix, in which case an edge that is really there is
    // reported missing. A gate whose input can be silently the wrong region can only ever go quiet.
    //
    // So the block is now taken by balancing the parentheses of `Seq(` and split into entries, and this
    // case asserts the result is what a module-dependency list looks like: dotted identifiers, nothing
    // else. A reformat that this reader cannot make sense of fails here, loudly, naming the block.
    assert(gatewayModuleDeps.nonEmpty, "services.gateway.api's moduleDeps block parsed as no entries")

    gatewayModuleDeps.foreach(entry =>
      assert(
        entry.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"),
        s"`$entry` is not a module path, so the region read out of build.mill is not a moduleDeps block; " +
          s"the whole block parsed as: ${gatewayModuleDeps.mkString(", ")}"
      )
    )

    // The three the gateway cannot work without, named so that a block which parsed cleanly but came from
    // the wrong module — there are ten modules called `api` in this build — is caught here rather than by
    // whichever service's edge happens to be missing that wave.
    List("application", "contract.jvm", "libs.http").foreach(entry =>
      assert(
        gatewayModuleDeps.contains(entry),
        s"the block read as services.gateway.api's moduleDeps does not contain `$entry`, so it is " +
          s"probably some other module's: ${gatewayModuleDeps.mkString(", ")}"
      )
    )
  }

  /** The `| service | aggregates | ports | adapters |` rows of §3's per-service table, as (service, ports).
    *
    * Scoped to the one table by its header row rather than by "a line beginning with a pipe": §9's caching
    * table is also keyed by service name, and reading its third column as a port list is how the first draft
    * of this suite failed on a row it had no business looking at.
    */
  private def serviceRows: List[(String, String)] = {
    val header = "| Service | Key aggregates / value objects | Ports in `domain` | Adapters in"

    document.linesIterator
      .dropWhile(!_.startsWith(header))
      .drop(2)
      .takeWhile(_.startsWith("| "))
      .map(_.split('|').map(_.trim).toList)
      .collect { case _ :: service :: _ :: ports :: _ if service.matches("[a-z]+") => (service, ports) }
      .toList
  }

  /** Every `` `Name[F]` `` in a table cell. The backticks matter: they are what distinguishes an identifier
    * the tree can be asked about from a sentence explaining that there is nothing to ask.
    */
  private def identifiersIn(cell: String): List[String] =
    "`([A-Za-z][A-Za-z0-9]*)\\[F\\]`".r.findAllMatchIn(cell).map(_.group(1)).toList

  private def traitsDeclaredIn(directory: Path): Set[String] = {
    val sources = Files.walk(directory).iterator.asScala.filter(_.toString.endsWith(".scala")).toList
    val declaration = "trait ([A-Za-z][A-Za-z0-9]*)\\[F\\[_\\]\\]".r

    sources.flatMap(file => declaration.findAllMatchIn(Files.readString(file)).map(_.group(1))).toSet
  }

  /** The rule ids of §3's `| Rule | What it forbids |` table, which is the only table whose first column is a
    * bare `A` followed by digits.
    */
  private def ruleIdsIn(text: String): Set[String] =
    text.linesIterator
      .filter(_.startsWith("| A"))
      .flatMap(line => "^\\| (A\\d+) \\|".r.findFirstMatchIn(line).map(_.group(1)))
      .toSet

  /** `services.gateway.api`'s declared module dependencies, one entry per element of its `Seq(...)`.
    *
    * Anchoring on `buildInfoPackage = "kui.gateway.api"` rather than on `object api` is what makes this
    * exact: ten modules in the build are called `api` and only one of them is the gateway's.
    *
    * The block is closed by **balancing the parentheses** rather than by searching for a closing paren at a
    * particular indentation, which is what the first version did. Indentation is a formatter's business —
    * `./mill __.checkFormat` may move it at any time and nothing here would be told — and a cut that lands in
    * the wrong place produces either a region far too large (in which every service's contract module is
    * trivially "present") or one far too small (in which a real edge is reported missing). Balanced parens
    * are a property of the language rather than of the layout, so the region is right or the read fails.
    */
  private def moduleDepsOfGatewayApi(): List[String] = {
    val build = read("build.mill")
    val anchor = build.indexOf("""buildInfoPackage = "kui.gateway.api"""")

    assert(anchor >= 0, "build.mill no longer identifies the gateway's api module by its build-info package")

    val opening = "def moduleDeps = Seq("
    val start = build.indexOf(opening, anchor)

    assert(start >= 0, "services.gateway.api declares no `def moduleDeps = Seq(` block")

    entriesOf(balancedBody(build, start + opening.length))
  }

  /** The text between an already-opened `(` and the `)` that closes it. */
  private def balancedBody(text: String, from: Int): String = {
    var depth = 1
    var index = from

    while index < text.length && depth > 0 do {
      text.charAt(index) match {
        case '(' => depth += 1
        case ')' => depth -= 1
        case _ => ()
      }
      index += 1
    }

    assert(depth == 0, "services.gateway.api's moduleDeps block has no closing parenthesis")
    text.substring(from, index - 1)
  }

  /** The elements of a `Seq(...)` body: line comments dropped, then split on commas.
    *
    * Splitting on commas rather than on lines so that the reader survives a formatter deciding the list fits
    * on one. A comma inside an element would break this, and a module path cannot contain one.
    */
  private def entriesOf(body: String): List[String] =
    body.linesIterator
      .map(line => line.indexOf("//") match { case -1 => line; case at => line.take(at) })
      .mkString("\n")
      .split(',')
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  private def read(relative: String): String = {
    val file = repositoryRoot.resolve(relative)
    if Files.isRegularFile(file) then Files.readString(file)
    else fail(s"$relative is read by this suite and does not exist")
  }

  /** The repository root, found by walking up to the directory holding `build.mill`.
    *
    * A test runs in a sandbox directory, so a relative path means nothing. The same walk
    * `ShippedConfigurationSuite` uses, and for the same reason: it fails with a sentence rather than a
    * `NoSuchFileException` when it is wrong.
    */
  private lazy val repositoryRoot: Path = {
    val start = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))
  }
}
