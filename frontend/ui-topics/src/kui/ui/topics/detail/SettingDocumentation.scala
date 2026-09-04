package kui.ui.topics.detail

/** Turns the description Kafka ships with a topic setting into prose a person can read.
  *
  * ==The problem this exists to solve==
  *
  * Kafka's `ConfigDef` documentation strings are written for the Kafka website, so they are HTML fragments,
  * not sentences. `cleanup.policy` really does contain
  *
  * {{{
  * ... will enable <a href="#compaction">log compaction</a>, which retains ...
  * }}}
  *
  * and `min.insync.replicas` contains a couple of dozen `<code>` and `<br>` tags. KUI put that string on the
  * screen unchanged, and because a browser shows text as text, the operator read the angle brackets:
  *
  * {{{
  * will enable <a href="#compaction">log compaction</a>, which retains the latest value for each key
  * }}}
  *
  * ==Why the tags are removed and not rendered==
  *
  * Rendering them would mean putting a string that came from the *cluster* into the page as markup, which is
  * how a hostile or merely broken broker gets to write HTML into KUI's interface. The anchors also point at
  * fragments of Kafka's own documentation site, so they would be dead links here. Both problems disappear if
  * the tags are simply removed and the words inside them kept — the sentence is what carries the meaning, and
  * `log compaction` reads the same with or without the link around it.
  *
  * ==What it does==
  *
  *   - `<br>`, `<p>` and `<li>` become a space, because they separate words that would otherwise run
  *     together;
  *   - every other tag is dropped and whatever it wrapped is kept;
  *   - the five XML entities Kafka's strings actually use are decoded, so `&lt;` reads as `<`;
  *   - runs of whitespace collapse to one space, and the result is trimmed.
  *
  * Nothing here tries to be a general HTML parser. It is a display cleaner for one specific source of
  * strings, and a string that is not HTML at all passes through it unchanged.
  */
object SettingDocumentation {

  /** The documentation as a single line of readable prose, or `None` when nothing is left of it. */
  def plainText(documentation: String): Option[String] = {
    val spaced = SeparatingTags.replaceAllIn(documentation, " ")
    val stripped = AnyTag.replaceAllIn(spaced, "")
    val decoded = Entities.foldLeft(stripped) { case (text, (entity, character)) =>
      text.replace(entity, character)
    }
    val collapsed = Whitespace.replaceAllIn(decoded, " ").trim
    Option.when(collapsed.nonEmpty)(collapsed)
  }

  /** Tags that stand between two words: dropping them without a space would join the words. */
  private val SeparatingTags = "(?i)</?(br|p|li|ul|ol|div|tr|td|th)\\s*/?>".r

  /** Any remaining tag, opening or closing, with or without attributes. */
  private val AnyTag = "</?[a-zA-Z][^>]*>".r

  private val Whitespace = "\\s+".r

  /** Decoded last, so a `&lt;` in the source text cannot turn into a tag and then be stripped. */
  private val Entities: List[(String, String)] =
    List("&lt;" -> "<", "&gt;" -> ">", "&quot;" -> "\"", "&#39;" -> "'", "&amp;" -> "&")
}
