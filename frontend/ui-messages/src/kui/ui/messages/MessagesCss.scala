package kui.ui.messages

/** This feature's class names, as Scala constants, for the same reason `KernelCss` exists: a name typed as a
  * string literal at the point of use can be misspelled, or deleted from the stylesheet while the code still
  * writes it, and here the compiler catches both.
  */
object MessagesCss {
  val Page = "kui-messages"
  val Fallback = "kui-messages__fallback"
  val Error = "kui-messages__error"

  val Controls = "kui-messages__controls"
  val ControlGroup = "kui-messages__control-group"
  val ControlLabel = "kui-messages__control-label"
  val Status = "kui-messages__status"
  val StatusPhase = "kui-messages__status-phase"

  val Table = "kui-messages__table"

  /** The cell the empty state sits in: one cell spanning every column of the table. */
  val EmptyCell = "kui-messages__empty-cell"
  val Row = "kui-messages__row"
  val RowOpen = "kui-messages__row--open"
  val Toggle = "kui-messages__toggle"
  val Key = "kui-messages__key"
  val Value = "kui-messages__value"
  val Tombstone = "kui-messages__tombstone"

  val Detail = "kui-messages__detail"
  val DetailSection = "kui-messages__detail-section"
  val DetailHeading = "kui-messages__detail-heading"
  val Payload = "kui-messages__payload"
  val Headers = "kui-messages__headers"
  val HeaderName = "kui-messages__header-name"
  val DecodeError = "kui-messages__decode-error"

  val RowActions = "kui-messages__row-actions"

  val Form = "kui-messages__form"
  val FormRow = "kui-messages__form-row"
  val FormText = "kui-messages__form-text"
  val FormHeaders = "kui-messages__form-headers"
  val FormActions = "kui-messages__form-actions"
  val FormResult = "kui-messages__form-result"
  val FormNote = "kui-messages__form-note"
}
