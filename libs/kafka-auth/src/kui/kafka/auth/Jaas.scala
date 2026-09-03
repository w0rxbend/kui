package kui.kafka.auth

import kui.kernel.{Secret, ValidationError}

/** The JAAS grammar, and only it.
  *
  * `sasl.jaas.config` is parsed by Kafka with the `javax.security.auth.login` grammar, which is read by a
  * `java.io.StreamTokenizer` configured with the double quote as its quote character. That tokenizer
  * understands an escaped backslash and an escaped double quote inside a quoted value, and it does not
  * understand a raw line break inside one — there is no escape sequence that carries one through.
  *
  * Both facts are encoded here rather than discovered by an operator whose password happens to contain a
  * quote. The alternative, which two of the products KUI is measured against actually ship, is
  * `String.format` with the password interpolated raw: a password containing `" ` ends the quoted value early
  * and everything after it becomes further login-module options that the operator did not write.
  */
object Jaas {

  /** One option value, and whether it may be printed. */
  enum JaasValue {
    case Plain(v: String)
    case Hidden(v: Secret[String])

    private[auth] def raw: String = this match {
      case Plain(v) => v
      case Hidden(v) => v.value
    }
  }

  object JaasValue {
    given CanEqual[JaasValue, JaasValue] = CanEqual.derived
  }

  /** The characters the grammar cannot carry, exposed so that an error message can name the class rather than
    * echoing the value: the C0 control characters (line feed, carriage return and tab among them) and DEL.
    */
  val forbiddenCharacters: Set[Char] =
    ((0 to 0x1f).map(_.toChar) :+ 0x7f.toChar).toSet

  /** Backslash becomes two backslashes, a double quote becomes an escaped double quote, and the result is
    * wrapped in double quotes.
    *
    * The order matters and is the whole trick: escaping the quote first and the backslash second would turn
    * `\"` into `\\\"`, which the tokenizer reads as a literal backslash followed by the end of the value.
    */
  def quote(value: String): String = {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    s"\"$escaped\""
  }

  /** Renders `<loginModule> <flag> k="v" k2="v2";` — one module terminated by a semicolon, which is the shape
    * `sasl.jaas.config` takes for a client.
    *
    * The result is a `Secret` because a rendered module almost always contains a credential, and a type that
    * has to be unwrapped on purpose is the only reliable way to keep it out of a log line somebody adds
    * later.
    *
    * Returns `Left` when an option value contains a character the grammar cannot carry. Refusing is the right
    * answer: a renderer that emitted such a value would produce a string Kafka parses into something other
    * than what the operator typed, which is the same class of defect as the injection this object exists to
    * close, only quieter. The error names the option and the character class and never echoes the value.
    */
  def module(
      loginModule: String,
      flag: String,
      options: List[(String, JaasValue)]
  ): Either[ValidationError, Secret[String]] = {
    val offending = options.collect {
      case (name, value) if value.raw.exists(forbiddenCharacters.contains) => name
    }

    if offending.nonEmpty then
      Left(
        ValidationError.Invariant(
          s"sasl.jaas.config.${offending.head}",
          "must not contain a control character (a line break or a tab, most often pasted in by " +
            "accident): the JAAS grammar has no escape for one. Supply the value through the " +
            "cluster's `properties` override layer instead, where the quoting is yours to own"
        )
      )
    else {
      val rendered = options
        .map((name, value) => s"$name=${quote(value.raw)}")
        .mkString(s"$loginModule $flag ", " ", ";")

      Right(Secret(if options.isEmpty then s"$loginModule $flag;" else rendered))
    }
  }
}
