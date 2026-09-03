package kui.e2e

import kui.e2e.fixtures.{ComposeFixture, RunningStack}
import kui.e2e.pages.ShellPage

/** A suite that runs against KUI in its distributed shape: the gateway and the cluster service as two
  * separate containers.
  *
  * This is the only shape in which the milestone's central claim can be tested at all. In the all-in-one
  * process the "services" are objects in the same JVM, and stopping one means calling a method that pretends
  * to be down. Here, `docker stop kui-cluster` kills a real operating-system process, and the gateway finds
  * out the way it would in production: a connection that is refused.
  *
  * The stack is started once for the suite. Bringing two containers up takes tens of seconds, and the suite's
  * tests are consecutive steps of one story rather than independent checks — which is why [[pagePerTest]] is
  * off here too.
  */
abstract class ComposeE2ESuite extends BaseE2ESuite {

  override protected def pagePerTest: Boolean = false

  private val composeFixture: Option[ComposeFixture] = ComposeFixture.fromEnvironment

  val stack: Fixture[RunningStack] = new Fixture[RunningStack]("compose-stack") {
    private var running: Option[RunningStack] = None

    def apply(): RunningStack =
      running.getOrElse(throw new IllegalStateException("the Compose stack is not running"))

    override def beforeAll(): Unit =
      running = composeFixture.map(_.start())

    override def afterAll(): Unit = {
      composeFixture.foreach(_.stop())
      running = None
    }
  }

  /** The stack fixture starts before the browser, because a browser that opens against a stack which is still
    * coming up wastes its first navigation on a connection refusal.
    */
  override def munitFixtures: Seq[Fixture[?]] = stack +: super.munitFixtures

  override def munitIgnore: Boolean = {
    val noStack = composeFixture.isEmpty || !ComposeFixture.dockerAvailable
    if noStack then println(s"SKIPPING ${getClass.getSimpleName}: ${ComposeFixture.SkipHint}")
    super.munitIgnore || noStack
  }

  /** The four things needed to tell a UI bug from a gateway bug from a service bug without re-running
    * anything: both containers' logs, and the last capability document, alongside the screenshot and console
    * the base class already attaches.
    */
  override protected def diagnostics: List[(String, String)] =
    scala.util
      .Try(
        List(
          "kui-gateway.log" -> stack().logsOf("kui-gateway"),
          "kui-cluster.log" -> stack().logsOf("kui-cluster"),
          "capabilities.json" -> Capabilities.raw(stack().baseUrl)
        )
      )
      .getOrElse(Nil)

  /** The shell, opened on the running stack. */
  def shell: ShellPage = new ShellPage(browser(), stack().baseUrl)
}
