package kui.ui.kernel

/** Facts about the running frontend build.
  *
  * A browser cannot read a file off the server's disk to find out which version of KUI it is running, so the
  * build compiles the answer in: `build.mill` generates `KernelBuildInfo` from `kuiVersion`, and this object
  * is the stable name the rest of the frontend reads it through. Going through a KUI-owned object rather than
  * the generated one means the generator can be changed (a git hash added, the build tool swapped) without
  * touching call sites.
  */
object Kernel {

  /** The product version, e.g. `0.1.0-SNAPSHOT`. Rendered in the shell footer (UI-009) so that a screenshot
    * in a bug report says which build it came from.
    */
  val version: String = KernelBuildInfo.version
}
