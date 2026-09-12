package kui.config

import java.nio.file.{Files, Path}
import java.util.stream.Collectors

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import kui.testkit.KuiSuite

/** Where the `|| true` is, and whether it is still there after one level of indirection.
  *
  * ==Why this suite exists beside `ShippedScriptsSuite` rather than inside it==
  *
  * `ShippedScriptsSuite` closed the rule *a shipped shell script must not be able to die between a failed
  * step and the line written to explain it*, and it closed it for the three instances that were in the tree.
  * Its reader asks whether the folded text of an assignment's command substitution contains `|| true`
  * ANYWHERE. Two mutations of `deployment/quickstart/seed/connect-seed.sh:150`, each measured on 2026-09-12
  * against the bytes that suite shipped, left `./mill libs.config.test` at 395/395 SUCCESS while reproducing
  * the original defect exactly — the script printing `registered quickstart-file-source …` and then exiting 1
  * with nothing else:
  *
  *   1. the guard moved one stage to the left, onto the `printf` that cannot fail:
  *      {{{running="$({ printf '%s' "${status}" || true; } | grep -o '"state":"RUNNING"' | wc -l)"}}}
  *   2. the same pipeline moved into a helper defined two lines above it:
  *      {{{count_running() { printf '%s' "$1" | grep -o '"state":"RUNNING"' | wc -l; }}}}
  *      {{{running="$(count_running "${status}")"}}}
  *
  * Presence is not position, and a reader that folds an assignment to one line cannot see through a function
  * call. Both are the same class the original defect belongs to, so both are closed here.
  *
  * ==The rule this suite reads, stated precisely, because the shell's precedence is the whole of it==
  *
  * Under `set -o pipefail` a pipeline's status is the rightmost non-zero one, so a `grep` that matches
  * nothing takes the pipeline down whatever the stages after it answer. There are therefore exactly two
  * places a fallback rescues it:
  *
  *   - at the END of the whole pipeline — `a | grep x | c || true` — because `||` binds looser than `|`, so
  *     the fallback applies to the pipeline and not to `c`;
  *   - INSIDE the grep's own stage — `a | { grep x || true; } | c` — which is the shape to reach for when the
  *     pipeline's own exit status is still wanted for something else.
  *
  * A fallback anywhere else is decoration. `{ printf … || true; } | grep x | wc -l` reads as guarded to any
  * check that greps the line for `|| true` and dies on the first poll that matches nothing.
  *
  * ==Why the analyser is driven over written-out text before it is pointed at the tree==
  *
  * Every expression below is hand-written, and the tree it reads is honest — which is the state in which a
  * reader that has stopped reading and a reader that finds nothing wrong print the same empty list. So the
  * three shapes above are written out as fixtures and the analyser has to answer *escapes*, *escapes* and
  * *does not escape* over them before the walk over `deployment/` is believed. A guard whose failing case
  * never arrives is a guard nothing distinguishes from `true`.
  */
final class ShippedScriptGuardPositionSuite extends KuiSuite {

  /** One `name="$( … )"` assignment, folded across continuations, with the script and line it began on. */
  final private case class Substitution(script: String, line: Int, text: String)

  /** A mutable builder's return value, thrown away on purpose. */
  private def discard(value: Any): Unit = { val _ = value }

  private val assignsFromSubstitution =
    """^[ \t]*(?:local[ \t]+|export[ \t]+|declare[ \t]+)?[A-Za-z_][A-Za-z0-9_]*=["']?\$\(""".r

  /** A filter whose "I matched nothing" answer is a non-zero exit. `sed`, `awk`, `wc`, `cut` and `sort` all
    * exit 0 over input they do not match, so a rule about them could never fail.
    */
  private val matchOrFail = """(?<![\w-])(?:grep|egrep|fgrep)(?![\w-])""".r

  private val errexit = """(?m)^[ \t]*set[ \t]+(?:-[a-z]*e[a-z]*\b|-o[ \t]+errexit)""".r

  /** `name() {` / `function name {`, which is where a pipeline hides from a reader that folds one line. */
  private val functionHeader = """^[ \t]*(?:function[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)[ \t]*\(\)[ \t]*\{""".r

  /** A fallback that turns "matched nothing" into success, and nothing else. `|| die …` is not one. */
  private val fallback = """^(?:true|:)(?:[ \t;)}].*)?$""".r

  /** The top level of one pipeline: its stages, and the fallback that applies to the pipeline as a whole.
    *
    * Single and double quoted spans are copied through untouched, because
    * `grep -Ev '^kui-(gateway|frontend)$'` is one stage and contains both a pipe and a paren. `${name}` does
    * not open a group; a bare `{` preceded by anything other than `$` does.
    */
  final private case class Pipeline(stages: List[String], tail: String)

  private def topLevel(text: String): Pipeline = {
    val stages = mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    val open = mutable.Stack.empty[Char]
    var tail = ""
    var index = 0
    var single = false
    var double = false
    var stopped = false

    while index < text.length && !stopped do {
      val char = text(index)
      val previous = if index > 0 then text(index - 1) else ' '
      if single then {
        if char == '\'' then single = false
        current.append(char)
      } else if double then {
        if char == '"' && previous != '\\' then double = false
        current.append(char)
      } else
        char match {
          case '\'' => single = true; current.append(char)
          case '"' => double = true; current.append(char)
          case '(' => open.push('('); current.append(char)
          case ')' =>
            if open.headOption.contains('(') then discard(open.pop())
            current.append(char)
          case '{' if previous != '$' => open.push('{'); current.append(char)
          case '}' =>
            if open.headOption.contains('{') then discard(open.pop())
            current.append(char)
          case '|' if open.isEmpty && index + 1 < text.length && text(index + 1) == '|' =>
            tail = text.substring(index + 2).trim
            stopped = true
          case '|' if open.isEmpty => stages += current.toString; current.clear()
          case _ => current.append(char)
        }
      if !stopped then index += 1
    }
    stages += current.toString
    Pipeline(stages.toList.map(_.trim).filter(_.nonEmpty), tail)
  }

  private def isGroup(stage: String): Boolean = {
    val trimmed = stage.trim
    (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
    (trimmed.startsWith("(") && trimmed.endsWith(")"))
  }

  private def inside(stage: String): String = {
    val trimmed = stage.trim
    trimmed.substring(1, trimmed.length - 1).trim.stripSuffix(";").trim
  }

  private def calls(stage: String): String = stage.trim.takeWhile(char => char.isLetterOrDigit || char == '_')

  /** Every `$( … )` in this text, as the pipelines they are, outermost first.
    *
    * A command substitution's exit status belongs to the assignment or the expansion around it, not to the
    * statement it sits in, so it is a pipeline of its own and is asked the question separately. Without this
    * the body of `smoke.sh`'s `routable_contracts` -- three statements, the middle one a correctly guarded
    * substitution -- reads as one unguarded `grep`, which is a refusal of the shape the rule exists to
    * require.
    */
  private def commandSubstitutions(text: String): List[String] = {
    val found = mutable.ListBuffer.empty[String]
    var index = 0
    var single = false
    while index < text.length do {
      val char = text(index)
      if single then { if char == '\'' then single = false; index += 1 }
      else if char == '\'' then { single = true; index += 1 }
      else if char == '$' && index + 1 < text.length && text(index + 1) == '(' then {
        val inner = new StringBuilder
        var depth = 1
        var at = index + 2
        var quote = false
        while at < text.length && depth > 0 do {
          val here = text(at)
          if quote then { if here == '\'' then quote = false }
          else if here == '\'' then quote = true
          else if here == '(' then depth += 1
          else if here == ')' then depth -= 1
          if depth > 0 then discard(inner.append(here))
          at += 1
        }
        found += inner.toString
        index = at
      } else index += 1
    }
    found.toList
  }

  /** The same scan, with every substitution blanked, so the statement around one can be read on its own. */
  private def withoutSubstitutions(text: String): String =
    commandSubstitutions(text).foldLeft(text)((carrying, sub) => carrying.replace("$(" + sub + ")", ""))

  /** The statements of a script fragment, one pipeline each.
    *
    * Split on newlines and joined again wherever the shell would join them -- a line ending in `|`, `||`,
    * `&&` or `\\`, or one whose brackets are still open. Folding a whole function body to a single line,
    * which is what a reader that works line-at-a-time has to do to see a wrapped pipeline, makes the first
    * `||` in the body swallow every statement after it: `gateway_contracts`'s `[[ -f … ]] || fail …` hid the
    * unguarded `grep` four lines below it that way.
    */
  private def statements(text: String): List[String] = {
    val out = mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    text.linesIterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#")).foreach { line =>
      if current.nonEmpty then discard(current.append(' '))
      discard(current.append(line))
      val sofar = current.toString
      val open = stripQuoted(withoutSubstitutions(sofar))
      val continues = sofar.endsWith("|") || sofar.endsWith("&&") || sofar.endsWith("\\") ||
        open.count(_ == '(') > open.count(_ == ')') ||
        open.replace("${", "").count(_ == '{') > open.count(_ == '}')
      if !continues then { out += sofar; current.clear() }
    }
    if current.nonEmpty then out += current.toString
    out.toList
  }

  /** Whether a `grep` inside this text can hand its exit 1 to the assignment that reads it.
    *
    * A stage that names a function defined in the same script is replaced by that function's body and the
    * question is asked again of the body, which is the whole of the answer to mutation 2 above. The name is
    * dropped from the map on the way in, so a recursive helper terminates instead of exhausting the stack.
    */
  private def escapes(text: String, bodies: Map[String, String]): Boolean =
    statements(text).exists { statement =>
      commandSubstitutions(statement).exists(sub => escapes(sub, bodies)) ||
      escapesPipeline(withoutSubstitutions(statement), bodies)
    }

  private def escapesPipeline(text: String, bodies: Map[String, String]): Boolean = {
    val pipeline = topLevel(text)
    if pipeline.tail.nonEmpty && fallback.findFirstIn(pipeline.tail).isDefined then false
    else
      pipeline.stages.exists { stage =>
        bodies.get(calls(stage)) match {
          case Some(body) => escapes(body, bodies - calls(stage))
          case None if matchOrFail.findFirstIn(stage).isEmpty => false
          case None if isGroup(stage) => escapesPipeline(inside(stage), bodies)
          case None => true
        }
      }
  }

  /** Every function defined in a script, as name -> body, by counting brackets outside quoted spans.
    *
    * The body keeps its newlines: they are what `statements` reads, and flattening them is exactly the
    * reading that let mutation 2 through.
    */
  private def functionBodies(contents: String): Map[String, String] = {
    val lines = contents.linesIterator.toVector
    lines.indices.flatMap { start =>
      functionHeader.findFirstMatchIn(lines(start)).map { header =>
        val body = mutable.ListBuffer.empty[String]
        var depth = 0
        var at = start
        var open = true
        while at < lines.size && open do {
          val line = stripQuoted(lines(at)).replace("${", "")
          if at == start then body += lines(start).substring(lines(start).indexOf('{') + 1)
          else body += lines(at)
          depth += line.count(_ == '{') - line.count(_ == '}')
          open = depth > 0
          at += 1
        }
        val text = body.toList.mkString("\n")
        header.group(1) -> text.reverse.replaceFirst("\\}", "").reverse.trim
      }
    }.toMap
  }

  /** Quoted spans blanked, so a brace inside `awk '{print $2}'` does not unbalance a function body. */
  private def stripQuoted(line: String): String =
    line.replaceAll("'[^']*'", "''").replaceAll("\"[^\"]*\"", "\"\"")

  private def deploymentScripts(root: Path): List[String] = {
    val stream = Files.walk(root.resolve("deployment"))
    try
      stream
        .filter(Files.isRegularFile(_))
        .map[String](path => root.relativize(path).toString.replace('\\', '/'))
        .filter(_.endsWith(".sh"))
        .collect(Collectors.toList[String])
        .asScala
        .toList
        .sorted
    finally stream.close()
  }

  private def substitutions(script: String, contents: String): List[Substitution] = {
    val lines = contents.linesIterator.toVector
    lines.indices.toList.flatMap { index =>
      val line = lines(index)
      Option.when(assignsFromSubstitution.findFirstIn(line).isDefined) {
        var text = line.substring(line.indexOf("$(") + 2)
        var last = index
        while (text.count(_ == '(') >= text.count(_ == ')')) && last + 1 < lines.size do {
          last += 1
          text = text + " " + lines(last)
        }
        val balanced = text.lastIndexOf(')')
        Substitution(
          script,
          index + 1,
          (if balanced >= 0 then text.substring(0, balanced) else text).replaceAll("\\s+", " ").trim
        )
      }
    }
  }

  // The three shapes, written out, because the tree is honest and an honest tree cannot tell a working
  // reader from one that has stopped reading. The first is what `connect-seed.sh:150` ships; the second and
  // third are the two mutations that survived `ShippedScriptsSuite` while reproducing the original defect.
  private val shipped =
    """printf '%s' "${status}" | { grep -o '"state":"RUNNING"' || true; } | wc -l | tr -d ' '"""
  private val guardOnTheWrongStage =
    """{ printf '%s' "${status}" || true; } | grep -o '"state":"RUNNING"' | wc -l | tr -d ' '"""
  private val hiddenBehindAFunction = """count_running "${status}""""
  private val countRunningBody = """printf '%s' "$1" | grep -o '"state":"RUNNING"' | wc -l | tr -d ' '"""

  test("the reader can tell a fallback on the grep's own stage from one anywhere else") {
    assert(
      !escapes(shipped, Map.empty),
      s"the shipped `connect-seed.sh` pipeline reads as unguarded: [$shipped]. A rule that refuses the " +
        "line written to satisfy it is a rule the next writer deletes"
    )
    assert(
      escapes(guardOnTheWrongStage, Map.empty),
      s"a fallback on the `printf` stage reads as guarding the `grep` three stages later: " +
        s"[$guardOnTheWrongStage]. Under `pipefail` the pipeline's status is the rightmost non-zero one, " +
        "so this dies on the first poll that matches nothing and prints nothing — which is the defect " +
        "verbatim, past a check that greps the line for `|| true`"
    )
    assert(
      escapes(hiddenBehindAFunction, Map("count_running" -> countRunningBody)),
      s"a helper defined in the same script hides the pipeline: [$hiddenBehindAFunction] calling " +
        s"[$countRunningBody]. One level of indirection is all it takes to put an unguarded `grep` back " +
        "into a script whose every assignment line reads clean"
    )
    assert(
      !escapes(hiddenBehindAFunction, Map("count_running" -> s"{ $shipped ; }")),
      "a helper whose own body guards its `grep` reads as unguarded, so the indirection rule refuses the " +
        "correct fix as well as the defect"
    )
    assert(
      !escapes("""grep -c foo "$file" || true""", Map.empty),
      "a fallback at the end of a one-stage pipeline reads as absent"
    )
    assert(
      escapes("""grep -c foo "$file" || die "no"""", Map.empty),
      "`|| die` reads as a fallback; it is the opposite of one — it is the branch that exits"
    )
  }

  test("no shipped script hands a `grep`'s exit 1 to an assignment from any stage or any helper") {
    val root = repositoryRoot
    val scripts = deploymentScripts(root)

    assert(
      scripts.nonEmpty,
      "the walk over deployment/ found no shell script, so everything below is vacuous"
    )

    val examined = scripts.flatMap { relative =>
      val contents = Files.readString(root.resolve(relative))
      if errexit.findFirstIn(contents).isEmpty then Nil
      else {
        val bodies = functionBodies(contents)
        substitutions(relative, contents)
          .filter(sub => matchOrFail.findFirstIn(sub.text).isDefined || bodies.contains(calls(sub.text)))
          .map(sub => sub -> escapes(sub.text, bodies))
      }
    }

    // The anti-vacuity half. The tree carries correctly guarded `grep` substitutions of exactly this shape,
    // so a reader that still works finds them; one whose folding or whose stage split has stopped working
    // finds none and fails here rather than reporting an empty offender list.
    assert(
      examined.exists { case (_, leaks) => !leaks },
      "this suite found no guarded `grep` substitution anywhere under deployment/, so its own reader has " +
        "stopped matching the shape it reads and the offender list below it is empty for the wrong reason"
    )

    val leaking = examined.collect { case (sub, true) => sub }

    assertEquals(
      leaking.map(sub => s"${sub.script}:${sub.line}"),
      Nil,
      clue = leaking
        .map(sub => s"  ${sub.script}:${sub.line}\n    ${sub.text}")
        .mkString(
          "a `grep` whose exit 1 reaches an assignment in a script that sets `-e`:\n",
          "\n",
          "\nThe fallback has to be on the grep's own stage — `{ grep … || true; }` — or at the end of " +
            "the whole pipeline, and it has to be in the function the assignment calls if the pipeline " +
            "lives there. Under `pipefail` the pipeline's status is the rightmost non-zero one, so a " +
            "`|| true` on an earlier stage rescues nothing and reads to a line-at-a-time check as though " +
            "it did."
        )
    )
  }

  /** The repository root, found by walking up to the directory holding `build.mill`. The fifth copy in the
    * tree and the third in this package; `ShippedScriptsSuite` names the fifth as the point at which it
    * should move into `libs/testkit`, and moving it is an edit to a file this packet does not own.
    */
  private def repositoryRoot: Path = {
    val start = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))
  }
}
