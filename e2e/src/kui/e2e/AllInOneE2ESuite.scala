package kui.e2e

import kui.e2e.fixtures.AllInOneFixture
import kui.e2e.pages.ShellPage

/** A suite that runs against the all-in-one jar, started once for the whole suite.
  *
  * ## Why the fixture is suite-local and not test-local
  *
  * Starting a JVM and a server costs several seconds. Doing it per test would put the M0 happy path well past
  * its ninety-second budget, and a slow end-to-end suite is one that gets moved to a nightly job and then
  * ignored. The isolation the tests actually need is browser isolation — no shared cookies, no shared
  * `localStorage` — and that is bought per test by the browser fixture, at a cost of milliseconds.
  *
  * ## Why this is separate from [[BaseE2ESuite]]
  *
  * E2E-001's specification puts the all-in-one fixture on the base class. It cannot stay there: the
  * fault-isolation suites of E2E-002 run against the *distributed* Compose stack — that is the whole point of
  * them, since only separate processes can have one of them stopped — and inheriting an all-in-one fixture
  * would start a second, irrelevant KUI beside every one of those tests. So the base class holds what every
  * browser suite needs, and each deployment shape adds its own fixture.
  */
abstract class AllInOneE2ESuite extends BaseE2ESuite {

  val allInOne: Fixture[RunningKui] = new Fixture[RunningKui]("all-in-one") {
    private var running: Option[RunningKui] = None

    def apply(): RunningKui =
      running.getOrElse(throw new IllegalStateException("the all-in-one fixture is not running"))

    override def beforeAll(): Unit = running = Some(AllInOneFixture.start())

    override def afterAll(): Unit = {
      running.foreach(_.stop())
      running = None
    }
  }

  override def munitFixtures: Seq[Fixture[?]] = super.munitFixtures :+ allInOne

  /** Skips when there is no jar to run, as loudly as the missing-browser case. */
  override def munitIgnore: Boolean = {
    val noJar = AllInOneFixture.jar.isEmpty
    if noJar then
      println(
        s"SKIPPING ${getClass.getSimpleName}: no all-in-one jar. Run the suite through `./mill e2e.test`."
      )
    super.munitIgnore || noJar
  }

  /** The server's own log, attached to any failure. Half of every end-to-end failure is explained by one line
    * of it, and going back to fetch it means reproducing the failure first.
    */
  override protected def diagnostics: List[(String, String)] =
    List("server.log" -> scala.util.Try(allInOne().logs()).getOrElse("<the server log is unavailable>"))

  /** The shell, opened on the running all-in-one. */
  def shell: ShellPage = new ShellPage(browser(), allInOne().baseUrl)
}
