package kui.e2e

/** A KUI that is up and can be talked to.
  *
  * The same shape whether it is one jar in a child process (`AllInOneFixture`) or two containers
  * (`ComposeFixture`), so a page object and an assertion never have to know which deployment shape they are
  * running against — which is the point of ADR-005 and therefore the thing the tests should be able to
  * demonstrate rather than assume.
  *
  * @param logs
  *   everything the process has written so far. A function and not a string because it is read at the moment
  *   a test fails, not at the moment the fixture was built.
  * @param stop
  *   shuts it down. Idempotent: a fixture's teardown can run after a test has already stopped something
  *   itself, and a teardown that throws would hide the failure that mattered.
  */
final case class RunningKui(baseUrl: String, logs: () => String, stop: () => Unit)
