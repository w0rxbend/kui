package kui.build

/** The rules that say whether a Scala.js link produced the bundle shape ADR-012 asks for.
  *
  * ADR-012 wants the shell to load a feature only when the user navigates to it. The Scala.js linker can only
  * honour that if it emits the feature as its **own** JavaScript module: one file per feature package, loaded
  * by a real `import()`, rather than everything flattened into `main.js`. A misconfigured `moduleSplitStyle`,
  * or a stray direct reference from shell code to a feature class, silently undoes the split — the
  * application still works, it just downloads the whole product on first paint. Nobody notices that in a code
  * review, so the build checks it.
  *
  * The rules live here, as a function from plain data to a verdict, so `build-tests` can unit-test them
  * against synthetic linker output. `build.mill` supplies the real linker output and prints the verdict.
  */
object BundleShape {

  /** The name Scala.js emits for a class, as it appears in the linked JavaScript.
    *
    * Checking for the *source* name `kui.ui.clusters.ClustersFeature` would prove nothing: that string never
    * reaches the output, so the assertion would pass even when the class was inlined into `main.js`. The
    * linker mangles a fully qualified name into an internal symbol — `kui.ui.clusters.ClustersFeature`
    * becomes `Lkui_ui_clusters_ClustersFeature` — and that is what actually appears next to the class's code.
    * Asserting on the mangled name is therefore an assertion about the emitted program rather than about a
    * comment or a string literal.
    */
  def emittedSymbol(className: String): String = "L" + className.replace('.', '_')

  /** The symbol that appears exactly where the linker **defines** the class.
    *
    * `$c_` is Scala.js's prefix for a class's constructor and prototype — the class itself. Everything else
    * built on the mangled name (`$p_` for a private method, `$f_` for a default method, a method symbol
    * embedding the class name) can legitimately appear in another module: the optimiser is allowed to inline
    * a small method across a module boundary, and it does. Only `$c_` says "the class lives here".
    *
    * That distinction is what makes rule 2 mean what it claims. Matching the bare mangled name flags a
    * one-line inlined accessor exactly as loudly as a feature that really did ship with the shell, and a
    * check that cries wolf is a check somebody turns off. What the rule is defending is the property the user
    * feels: `main.js` cannot construct the feature, so the browser must fetch its module first.
    */
  def definitionSymbol(className: String): String = "$c_" + emittedSymbol(className)

  /** One feature microfrontend the shell expects to load lazily.
    *
    * @param entryClass
    *   the fully qualified name of the feature's entry point, e.g. `kui.ui.clusters.ClustersFeature`.
    * @param modulePrefix
    *   the start of the file name the linker is expected to give the feature's own module, e.g.
    *   `kui.ui.clusters`. Scala.js appends its own suffix, so this is a prefix match, not equality.
    */
  final case class Feature(entryClass: String, modulePrefix: String)

  /** What a Scala.js link left in its output directory, reduced to the facts the rules need. */
  final case class LinkerOutput(
      fileNames: Seq[String],
      mainJsContent: String,
      mainJsSizeBytes: Long
  )

  /** The verdict. `Skipped` is deliberately distinct from `Passed`: a check with nothing to check must not
    * report success, or the day someone deletes the feature list the build goes green.
    */
  enum Result {
    case Skipped(message: String)
    case Passed(message: String)
    case Failed(problems: Seq[String])
  }

  private val mainJsFileName = "main.js"

  /** Applies all three bundle-shape rules and reports every failure at once.
    *
    * Reporting every failure together, rather than stopping at the first, matters because the failures are
    * usually related: a feature that did not get its own module is also the feature whose symbol now sits in
    * `main.js` and pushed it over budget. Seeing all three lines makes the single underlying cause obvious;
    * seeing them one build at a time does not.
    *
    * @param features
    *   the feature modules that must be split out. Empty until UI-012 creates the first one, and an empty
    *   list skips rather than passes.
    * @param sizeBudgetBytes
    *   the largest `main.js` the shell may ship, uncompressed.
    */
  def check(output: LinkerOutput, features: Seq[Feature], sizeBudgetBytes: Long): Result =
    if features.isEmpty then {
      Result.Skipped(
        "checkBundleShape: no feature packages configured, nothing to assert yet " +
          "(UI-012 adds the first feature module)"
      )
    } else if !output.fileNames.contains(mainJsFileName) then {
      Result.Failed(
        Seq(
          s"the linker output has no $mainJsFileName; found: ${describe(output.fileNames)}"
        )
      )
    } else {
      val problems = missingFeatureModules(output, features) ++
        leakedFeatureSymbols(output, features) ++
        oversizedMainJs(output, sizeBudgetBytes)

      if problems.isEmpty then {
        Result.Passed(
          s"checkBundleShape: ${features.size} feature module(s) split out, " +
            s"$mainJsFileName is ${output.mainJsSizeBytes} B of a ${sizeBudgetBytes} B budget"
        )
      } else {
        Result.Failed(problems)
      }
    }

  /** Rule 1: every feature has a JavaScript module of its own. */
  private def missingFeatureModules(output: LinkerOutput, features: Seq[Feature]): Seq[String] =
    features
      .filterNot(feature => output.fileNames.exists(isModuleFor(feature)))
      .map(feature =>
        s"no module file matching ${feature.modulePrefix}*.js was linked, so ${feature.entryClass} " +
          s"cannot be loaded lazily; found: ${describe(output.fileNames)}"
      )

  private def isModuleFor(feature: Feature)(fileName: String): Boolean =
    fileName != mainJsFileName && fileName.startsWith(feature.modulePrefix) && fileName.endsWith(".js")

  /** Rule 2: no feature's code ended up inside the shell's own module. */
  private def leakedFeatureSymbols(output: LinkerOutput, features: Seq[Feature]): Seq[String] =
    features
      .filter(feature => output.mainJsContent.contains(definitionSymbol(feature.entryClass)))
      .map(feature =>
        s"$mainJsFileName contains ${definitionSymbol(feature.entryClass)}, the linked definition of " +
          s"${feature.entryClass}, so the feature ships with the shell instead of being loaded on " +
          "demand; look for a direct reference to the feature from shell code"
      )

  /** Rule 3: the shell's own module stays within its size budget. */
  private def oversizedMainJs(output: LinkerOutput, sizeBudgetBytes: Long): Seq[String] =
    if output.mainJsSizeBytes <= sizeBudgetBytes then {
      Seq.empty
    } else {
      Seq(
        s"$mainJsFileName is ${output.mainJsSizeBytes} B, over the ${sizeBudgetBytes} B budget " +
          s"by ${output.mainJsSizeBytes - sizeBudgetBytes} B"
      )
    }

  private def describe(fileNames: Seq[String]): String =
    if fileNames.isEmpty then "(no files)" else fileNames.sorted.mkString(", ")
}
