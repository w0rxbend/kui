package kui.build

/** Keeps `frontend/packages/kernel/styles/index.css` listing every stylesheet in the workspace, exactly once.
  *
  * ## What was lost, and why this exists
  *
  * Until the frontend moved to Vite (ADR-048), `CssPipeline` *discovered* the stylesheets: it walked every
  * frontend module's `resources/css` directory and concatenated what it found. A new file was in the shipped
  * CSS the moment it was written, and there was no list anywhere to forget to update.
  *
  * `index.css` is a list, and a list can be forgotten. The failure that replaces it is quiet and expensive: a
  * developer adds `27-something.css`, styles a screen against it, sees it working in their editor's preview
  * or in a hot-reloaded dev server that happened to pick it up, and ships a screen that paints unstyled —
  * with no error anywhere, because nothing is wrong except that a file is not mentioned.
  *
  * So the discovery property is kept as a check rather than as a mechanism. This is the whole of it: the set
  * of files on disk must equal the set of files named by `index.css`, and no file may be named twice. Naming
  * a file twice is worth reporting on its own — a duplicated `@import` in CSS means the second copy of the
  * rules wins, which silently moves a screen later in the cascade.
  *
  * ## Why it takes plain data
  *
  * The Mill task does the filesystem work and hands the results here, so the rules themselves can be
  * unit-tested against synthetic inputs by `build-tests` instead of only being observed by running the build.
  * `CssPipeline` was arranged the same way, for the same reason.
  */
object CssReferences {

  /** The file whose `@import` list is the cascade. Only `index.css` is a list; every other stylesheet is
    * content, and is expected to be *named* by this one.
    */
  val IndexFile = "frontend/packages/kernel/styles/index.css"

  /** Every `@import "..."` target in a stylesheet, in the order written, quotes stripped.
    *
    * Deliberately a narrow reader rather than a CSS parser: `index.css` is a file this project owns and
    * formats itself, and the one shape it uses is `@import "path";`. A `url(...)` form or a media-qualified
    * import would not be matched, which is why the two suites below assert the count as well as the contents
    * — a reader that silently matched nothing would make everything pass.
    */
  def imports(indexContents: String): List[String] =
    """@import\s+"([^"]+)"""".r.findAllMatchIn(indexContents).map(_.group(1)).toList

  /** Everything wrong with the pairing of the list and the disk, as sentences a developer can act on. Empty
    * means the two agree.
    *
    * The two directions are checked against deliberately different sets, because they are different promises.
    *
    *   - *Every shipped stylesheet is imported.* Judged against what git is **tracking**: a file nobody has
    *     committed is not part of the product yet, and failing a shared build over somebody's half-written
    *     file punishes the wrong person at the wrong moment. The obligation begins the moment the file is
    *     committed.
    *   - *Every import resolves.* Judged against what is **on disk**, because that is what the bundler will
    *     try to open. It is legitimate to write the `@import` in the same change as the stylesheet, before
    *     either is committed, and it must be an error to import something that is not there at all.
    *
    * @param tracked
    *   the stylesheets git is tracking under the packages, as repository-relative paths, `index.css` excluded
    *   — it is the list, not an entry in it.
    * @param onDisk
    *   the stylesheets actually present, on the same terms.
    * @param referenced
    *   what `index.css` names, also as repository-relative paths, in the order written.
    */
  def violations(tracked: Set[String], onDisk: Set[String], referenced: List[String]): List[String] = {
    val missing = (tracked -- referenced.toSet).toList.sorted.map { file =>
      s"$file is not imported by $IndexFile, so none of its rules reach the browser"
    }

    val unknown = (referenced.toSet -- onDisk).toList.sorted.map { file =>
      s"$IndexFile imports $file, which does not exist"
    }

    val duplicated = referenced
      .groupBy(identity)
      .collect { case (file, occurrences) if occurrences.sizeIs > 1 => file }
      .toList
      .sorted
      .map { file =>
        s"$IndexFile imports $file ${referenced.count(_ == file)} times; the later copy wins the cascade"
      }

    missing ++ unknown ++ duplicated
  }
}
