package kui.build

/** One `*.css` file belonging to one frontend module, read off disk.
  *
  * @param module
  *   the Mill module the file came from, e.g. `uiKernel`. Used to decide whether the file is kernel CSS or
  *   feature CSS, and printed into the banner so a reader of the concatenated output can see where a rule
  *   came from.
  * @param fileName
  *   the file's own name including the extension, e.g. `10-tokens.css`
  * @param content
  *   the file's text, exactly as written
  */
final case class CssSource(module: String, fileName: String, content: String)

/** Assembles every module's CSS into the single `kui.css` the browser downloads (ADR-024).
  *
  * ## Why a fixed order, and why this one
  *
  * Plain CSS has no import graph and no module system: when two rules match the same element with the same
  * specificity, the one written later wins. So the order files are pasted together in *is* the cascade, and
  * it has to be decided once rather than falling out of whatever order the filesystem happened to return.
  *
  * ADR-024 fixes it as **tokens, then reset, then kernel, then features**:
  *
  *   1. `tokens` first, because everything after it reads the custom properties it defines. A custom property
  *      has to be declared before it is used at parse time only in the sense that the declaration must exist
  *      on a matching element; putting it first also means a reader opening `kui.css` sees the palette before
  *      the rules that consume it.
  *   2. `reset` next, because its job is to overwrite browser defaults, and anything KUI writes afterwards
  *      must be able to overwrite the reset in turn.
  *   3. `kernel` next: the shared primitives (button, dialog, table) every screen is built from.
  *   4. `features` last, so a feature can adjust a kernel primitive inside its own page without having to win
  *      a specificity war.
  *
  * Inside a group files are sorted by module and then by file name, so the same inputs always produce
  * byte-identical output. That matters for caching: a build that produced a different `kui.css` on every run
  * would defeat both Mill's own change detection and the browser's.
  *
  * ## Why the numeric filename prefixes do not decide the order
  *
  * The files are named `00-reset.css`, `10-tokens.css`, `20-kernel-controls.css` and so on. Those prefixes
  * group related files visually in a directory listing; they are deliberately *not* what this code sorts by,
  * because a numeric prefix is a fact about one directory and the cascade is a fact about the whole product.
  * Classifying by role means a new module cannot silently insert itself into the middle of the cascade by
  * picking a low number.
  */
object CssPipeline {

  /** The Mill module that owns the design tokens, the reset and the kernel primitives. */
  private val KernelModule = "uiKernel"

  private val TokensGroup = 0
  private val ResetGroup = 1
  private val KernelGroup = 2
  private val FeaturesGroup = 3

  /** Which of the four cascade groups a file belongs to.
    *
    * Role is read from the file name with the numeric prefix ignored, so `10-tokens.css` and `tokens.css` are
    * both token files.
    */
  private def cascadeGroup(source: CssSource): Int = {
    val role = source.fileName.dropWhile(character => character.isDigit || character == '-')
    if role.startsWith("tokens") then TokensGroup
    else if role.startsWith("reset") then ResetGroup
    else if source.module == KernelModule then KernelGroup
    else FeaturesGroup
  }

  /** The inputs in the exact order they are pasted into `kui.css`. Total and deterministic: the same list in
    * any input order comes back in the same output order.
    */
  def order(sources: List[CssSource]): List[CssSource] =
    sources.sortBy(source => (cascadeGroup(source), source.module, source.fileName))

  /** The finished stylesheet: every input in cascade order, each preceded by a banner naming the file it came
    * from.
    *
    * The banner is a plain CSS comment, so it survives into the served file and a developer looking at the
    * network tab can tell which source file a rule lives in without a source map.
    */
  def concatenate(sources: List[CssSource]): String =
    order(sources)
      .map(source => s"/* ${source.module}/${source.fileName} */\n${source.content.stripSuffix("\n")}\n")
      .mkString("\n")
}
