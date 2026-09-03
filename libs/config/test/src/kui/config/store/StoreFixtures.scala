package kui.config.store

import scala.io.Source
import scala.util.Using

import io.circe.Json
import io.circe.parser.parse

/** Reads a committed golden file out of `libs/config/test/resources/store` and parses it.
  *
  * Golden files are compared as parsed JSON rather than as text. Comparing text would make the suite fail
  * on a trailing newline or a reordered field, which is noise; comparing parsed JSON fails on exactly the
  * thing that matters, which is a changed format.
  */
object StoreFixtures {

  def golden(name: String): Json = {
    val text = Using.resource(
      Option(getClass.getResourceAsStream(s"/store/$name"))
        .getOrElse(sys.error(s"/store/$name is missing from the test resources"))
    )(stream => Source.fromInputStream(stream, "UTF-8").mkString)
    parse(text).fold(failure => sys.error(s"/store/$name is not valid JSON: ${failure.message}"), identity)
  }
}
