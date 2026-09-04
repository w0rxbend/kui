package kui.kernel.search

/** A searchable set of names, built once and queried many times.
  *
  * It is built inside a per-context snapshot (ADR-027) and thrown away with it, so construction cost is paid
  * once per scrape rather than once per request. [[SearchMode.Plain]] needs no index at all and is answered
  * by a scan; the trigram tables exist for [[SearchMode.Fts]] and are built lazily, so a deployment that
  * never sends `mode=fts` never pays for them.
  *
  * It indexes strings. It does not know that they are topic names, or group ids, or anything else — which is
  * what lets the browser use the same matcher over a locally held list as the service uses over a snapshot
  * (ADR-038: one definition of "matches", compiled for both platforms).
  */
final class NameIndex private (names: Vector[String]) {

  /** The names, case-folded once. Every match compares against these rather than re-folding per query. */
  private val folded: Vector[String] = names.map(NameIndex.fold)

  /** One trigram set per name, built on first use of [[SearchMode.Fts]] and kept for the index's lifetime.
    *
    * `lazy` is the whole point: an index over ten thousand names costs a scan and no allocation until
    * somebody actually asks for a fuzzy match.
    */
  private lazy val trigrams: Vector[Set[String]] = folded.map(NameIndex.trigramsOf)

  /** The names matching `query` under `mode`, best first.
    *
    * Ties keep the order the index was built in, which is the caller's order — so a caller that built the
    * index from an already sorted list gets a deterministic result, and a later `sort` is stable over it.
    *
    * A blank query matches every name in build order. That is not a special case bolted on: an empty search
    * box means "no filter", and returning nothing would make the list vanish the moment the user deletes
    * their query. It also falls out of the substring rule for free, because every string contains the empty
    * string.
    */
  def search(query: String, mode: SearchMode): List[String] = {
    val needle = NameIndex.fold(query)
    mode match {
      case SearchMode.Plain => substringMatches(needle)
      case SearchMode.Fts =>
        // A trigram index cannot answer a query it cannot cut into trigrams, and the honest choices for a
        // one- or two-character query are "no results" or "substring". A search box that returns nothing
        // after two keystrokes reads as broken, so the toggle falls back rather than refusing.
        if needle.length < NameIndex.NGram then substringMatches(needle)
        else rankedMatches(needle)
    }
  }

  def size: Int = names.size

  private def substringMatches(needle: String): List[String] =
    folded.indices.iterator.filter(i => folded(i).contains(needle)).map(names).toList

  private def rankedMatches(needle: String): List[String] = {
    val wanted = NameIndex.trigramsOf(needle)
    val scored = folded.indices.iterator
      .map(i => (i, NameIndex.overlap(trigrams(i), wanted)))
      .filter(_._2 > 0.0)
      .toList
    // `sortBy` is stable, so equal scores come out in build order rather than in whatever order the
    // underlying sort happened to produce. That stability is a contract, not an implementation detail:
    // without it a list would reshuffle between two identical requests.
    scored.sortBy(-_._2).map(pair => names(pair._1))
  }
}

object NameIndex {

  /** The trigram width.
    *
    * Three, not two or four. Two is noise on Kafka names, where `__` and `-` are everywhere and a bigram
    * matches almost anything; four misses every three-character query outright. The reference product's own
    * n-gram range is 1..4, and the reason it needs the short end is prefix queries, which KUI answers with
    * the substring test in [[SearchMode.Plain]] instead.
    */
  val NGram: Int = 3

  /** Builds an index. The input order is the tie-break order of every later query, so pass the list in the
    * order the caller wants ties resolved — usually already sorted.
    */
  def of(names: List[String]): NameIndex = new NameIndex(names.toVector)

  /** The scoring function, exposed so a suite can assert ranking without building an index.
    *
    * The score is the fraction of the query's distinct trigrams that appear in the name, in `[0.0, 1.0]`. A
    * query shorter than [[NGram]] cannot be cut into trigrams at all and falls back to the substring test,
    * scoring 1.0 or 0.0.
    */
  def score(name: String, query: String): Double = {
    val foldedName = fold(name)
    val foldedQuery = fold(query)
    if foldedQuery.length < NGram then if foldedName.contains(foldedQuery) then 1.0 else 0.0
    else overlap(trigramsOf(foldedName), trigramsOf(foldedQuery))
  }

  /** Case folding that does not depend on the machine's locale.
    *
    * `String.toLowerCase` with no argument uses the default locale, and in a Turkish one `"I"` folds to a
    * dotless `ı`, so a cluster in Istanbul would match differently from the same cluster in London. That is
    * the class of bug found once a year, by someone who cannot reproduce it. `Character.toLowerCase` is
    * defined per code point with no locale at all, on the JVM and on Scala.js alike.
    */
  private[search] def fold(raw: String): String = {
    val builder = new StringBuilder(raw.length)
    raw.foreach(ch => builder.append(Character.toLowerCase(ch)): Unit)
    builder.result()
  }

  /** The distinct trigrams of an already folded string. Shorter than [[NGram]] gives the empty set. */
  private def trigramsOf(folded: String): Set[String] =
    if folded.length < NGram then Set.empty
    else folded.sliding(NGram).toSet

  /** The fraction of `wanted` present in `have`. Zero when `wanted` is empty, so an unanswerable query ranks
    * nothing rather than ranking everything equally.
    */
  private def overlap(have: Set[String], wanted: Set[String]): Double =
    if wanted.isEmpty then 0.0 else wanted.count(have.contains).toDouble / wanted.size.toDouble
}
