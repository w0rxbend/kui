package kui.ui.kernel.file

import munit.FunSuite

/** The quoting rules, which are the whole reason this file exists.
  *
  * Every case here is a real Kafka payload shape. A JSON value contains commas and quotation marks; a
  * multi-line stack trace in a header contains newlines; and a value that begins with `=` is a formula to
  * every spreadsheet that opens the file. A naive comma-join gets all four wrong, and gets them wrong
  * silently — the file opens, the columns are shifted, and the user acts on data that is not what they
  * exported.
  */
final class CsvSuite extends FunSuite {

  test("anOrdinaryFieldIsNotQuoted") {
    // Quoting only when necessary, because a file where every cell is quoted is harder to read in a text
    // editor — and a text editor is where somebody looks when the spreadsheet has mangled it.
    assertEquals(Csv.field("orders.v1"), "orders.v1")
  }

  test("aFieldWithACommaIsQuoted") {
    assertEquals(Csv.field("a,b"), "\"a,b\"")
  }

  test("aQuotationMarkInsideAQuotedFieldIsDoubled") {
    // RFC 4180's escape. A JSON payload is nothing but quotation marks, so this is the ordinary case for a
    // Kafka value rather than an edge one.
    assertEquals(Csv.field("""{"status":"PAID"}"""), """"{""status"":""PAID""}"""")
  }

  test("aFieldWithANewlineIsQuotedRatherThanEndingTheRow") {
    assertEquals(Csv.field("line one\nline two"), "\"line one\nline two\"")
    assertEquals(Csv.field("carriage\rreturn"), "\"carriage\rreturn\"")
  }

  test("aFieldThatWouldBeAFormulaIsPrefixed") {
    // Not cosmetic. A spreadsheet treats a cell beginning with =, +, - or @ as a formula, so a header
    // value from somebody else's producer becomes executable content in whoever opens the file.
    assertEquals(Csv.field("=1+1"), "'=1+1")
    assertEquals(Csv.field("+49 30 1234"), "'+49 30 1234")
    assertEquals(Csv.field("-3"), "'-3")
    assertEquals(Csv.field("@user"), "'@user")
  }

  test("aFormulaThatAlsoNeedsQuotingGetsBoth") {
    assertEquals(Csv.field("=a,b"), "\"'=a,b\"")
  }

  test("anEmptyFieldIsAnEmptyCellAndNotAQuotedNothing") {
    assertEquals(Csv.field(""), "")
  }

  test("aDocumentIsAHeaderRowAndThenTheRowsSeparatedByCrLf") {
    // CRLF because that is what the RFC says, and because a file written with bare newlines is read as one
    // long line by some of the readers a person will open it in.
    val rendered = Csv.render(List("a", "b"), List(List("1", "2"), List("3", "4")))

    assertEquals(rendered, "a,b\r\n1,2\r\n3,4\r\n")
  }

  test("aDocumentWithNoRowsIsStillItsHeaderRow") {
    // An empty export is a file that says which columns were empty, which is a usable answer; a
    // zero-byte file is not.
    assertEquals(Csv.render(List("a", "b"), Nil), "a,b\r\n")
  }
}
