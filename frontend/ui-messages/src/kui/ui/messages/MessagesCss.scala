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
  val Heading = "kui-messages__heading"
  val Lead = "kui-messages__lead"
  val Status = "kui-messages__status"
  val StatusPhase = "kui-messages__status-phase"

  /** The smart-filter editor (MS-007). */
  val Filter = "kui-messages__filter"
  val FilterBody = "kui-messages__filter-body"
  val FilterInput = "kui-messages__filter-input"
  val FilterHint = "kui-messages__filter-hint"
  val FilterActions = "kui-messages__filter-actions"
  val FilterError = "kui-messages__filter-error"

  val Table = "kui-messages__table"

  /** The table view (MS-004): the flattened grid, its scroll box, its column picker and its cells. */
  val Grid = "kui-messages__grid"
  val GridScroll = "kui-messages__grid-scroll"
  val GridTable = "kui-messages__grid-table"
  val GridFixed = "kui-messages__grid-fixed"
  val GridPath = "kui-messages__grid-path"
  val GridCell = "kui-messages__grid-cell"
  val GridPicker = "kui-messages__grid-picker"
  val GridPickerList = "kui-messages__grid-picker-list"
  val GridNote = "kui-messages__grid-note"
  val ViewSwitch = "kui-messages__view-switch"

  /** The cell the empty state sits in: one cell spanning every column of the table. */
  val EmptyCell = "kui-messages__empty-cell"
  val Row = "kui-messages__row"
  val RowOpen = "kui-messages__row--open"
  val Toggle = "kui-messages__toggle"

  /** The chevron inside the toggle: the mark that says a row opens.
    *
    * Without it the only thing that looked like a control on the row was the offset, and an offset does not
    * look like a control -- it looks like a number. The row was reported as having no way to open it at all
    * by somebody using it for the first time.
    */
  val ToggleIcon = "kui-messages__toggle-icon"
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
