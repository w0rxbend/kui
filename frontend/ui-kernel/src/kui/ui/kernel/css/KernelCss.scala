package kui.ui.kernel.css

/** Every CSS class name the kernel's stylesheets define, as Scala constants.
  *
  * ## The rule this object exists to enforce
  *
  * No Scala file anywhere in the frontend may write a class-name string literal. Class names are declared
  * here (and in one `*Css` object per feature module) and referenced by name. Plain CSS has no compiler, so a
  * typo in `cls := "kui-buton"` produces an unstyled element and no error at all; routing every reference
  * through a constant turns that silent bug into "not a member of KernelCss", which the build catches.
  *
  * ## The naming scheme
  *
  * BEM with a `kui` prefix: `kui-<block>__<element>--<modifier>`.
  *
  *   - the **block** is the component (`kui-button`),
  *   - the **element** is a part of it that has no meaning on its own (`kui-dialog__title`),
  *   - the **modifier** is a variant of the block or element (`kui-button--primary`).
  *
  * The point of BEM here is not orthodoxy, it is that every selector is a single class with the same
  * specificity, so the cascade is decided by the file order `CssPipeline` fixes rather than by who nested
  * their selectors more deeply.
  */
object KernelCss {

  /** The class put on the application's outermost element. Everything else is scoped inside it. */
  val Root = "kui"

  /** Present but invisible: still read out by a screen reader, still focusable. Defined in the reset. Not
    * `display: none`, which would remove the element from the accessibility tree too.
    */
  val VisuallyHidden = "kui-visually-hidden"

  val Button = "kui-button"
  val ButtonPrimary = "kui-button--primary"
  val ButtonSecondary = "kui-button--secondary"
  val ButtonDanger = "kui-button--danger"
  val ButtonGhost = "kui-button--ghost"
  val ButtonSm = "kui-button--sm"
  val ButtonMd = "kui-button--md"
  val ButtonLg = "kui-button--lg"
  val ButtonLoading = "kui-button--loading"
  val ButtonIcon = "kui-button__icon"
  val ButtonLabel = "kui-button__label"

  val Field = "kui-field"
  val FieldLabel = "kui-field__label"
  val FieldControl = "kui-field__control"
  val FieldHint = "kui-field__hint"
  val FieldError = "kui-field__error"
  val FieldInvalid = "kui-field--invalid"

  val Tag = "kui-tag"
  val TagNeutral = "kui-tag--neutral"
  val TagInfo = "kui-tag--info"
  val TagSuccess = "kui-tag--success"
  val TagWarning = "kui-tag--warning"
  val TagDanger = "kui-tag--danger"
  val TagDot = "kui-tag__dot"
  val TagRemove = "kui-tag__remove"

  /** The searchable combobox, for a list too long for a native `<select>`. */
  val Combobox = "kui-combobox"
  val ComboboxList = "kui-combobox__list"
  val ComboboxOption = "kui-combobox__option"
  val ComboboxOptionActive = "kui-combobox__option--active"
  val ComboboxEmpty = "kui-combobox__empty"

  val Card = "kui-card"
  val CardElevated = "kui-card--elevated"
  val CardHeader = "kui-card__header"
  val CardBody = "kui-card__body"
  val CardFooter = "kui-card__footer"

  val Tabs = "kui-tabs"
  val TabsList = "kui-tabs__list"
  val TabsTab = "kui-tabs__tab"
  val TabsSelected = "kui-tabs__tab--selected"
  val TabsPanel = "kui-tabs__panel"

  val Icon = "kui-icon"

  val Spinner = "kui-spinner"

  val DialogHost = "kui-dialog-host"
  val DialogBackdrop = "kui-dialog-backdrop"
  val Dialog = "kui-dialog"
  val DialogSm = "kui-dialog--sm"
  val DialogMd = "kui-dialog--md"
  val DialogLg = "kui-dialog--lg"
  val DialogHeader = "kui-dialog__header"
  val DialogTitle = "kui-dialog__title"
  val DialogClose = "kui-dialog__close"
  val DialogBody = "kui-dialog__body"
  val DialogActions = "kui-dialog__actions"

  val DrawerHost = "kui-drawer-host"
  val DrawerBackdrop = "kui-drawer-backdrop"
  val Drawer = "kui-drawer"
  val DrawerRight = "kui-drawer--right"
  val DrawerLeft = "kui-drawer--left"
  val DrawerHeader = "kui-drawer__header"
  val DrawerTitle = "kui-drawer__title"
  val DrawerClose = "kui-drawer__close"
  val DrawerBody = "kui-drawer__body"

  val ToastStack = "kui-toast-stack"
  val ToastQueued = "kui-toast-stack__queued"
  val Toast = "kui-toast"
  val ToastNeutral = "kui-toast--neutral"
  val ToastInfo = "kui-toast--info"
  val ToastSuccess = "kui-toast--success"
  val ToastWarning = "kui-toast--warning"
  val ToastDanger = "kui-toast--danger"
  val ToastContent = "kui-toast__content"
  val ToastTitle = "kui-toast__title"
  val ToastMessage = "kui-toast__message"
  val ToastDismiss = "kui-toast__dismiss"

  val ActionGate = "kui-action-gate"

  val TooltipHost = "kui-tooltip-host"
  val Tooltip = "kui-tooltip"
  val TooltipTop = "kui-tooltip--top"
  val TooltipBottom = "kui-tooltip--bottom"
  val TooltipLeft = "kui-tooltip--left"
  val TooltipRight = "kui-tooltip--right"

  val Breadcrumbs = "kui-breadcrumbs"
  val BreadcrumbsList = "kui-breadcrumbs__list"
  val BreadcrumbsItem = "kui-breadcrumbs__item"
  val BreadcrumbsSeparator = "kui-breadcrumbs__separator"

  val EmptyState = "kui-empty-state"
  val EmptyStateIcon = "kui-empty-state__icon"
  val EmptyStateTitle = "kui-empty-state__title"
  val EmptyStateDescription = "kui-empty-state__description"
  val EmptyStateAction = "kui-empty-state__action"

  val Table = "kui-table"
  val TableLoading = "kui-table--loading"
  val TableBody = "kui-table__body"
  val TableRow = "kui-table__row"
  val TableCell = "kui-table__cell"
  val TableHeaderCell = "kui-table__header-cell"
  val TableSortButton = "kui-table__sort"
  val TableSortPlaceholder = "kui-table__sort-placeholder"
  val TableEmpty = "kui-table__empty"
  val TableCellNumeric = "kui-table__cell--numeric"
  val TableHeaderCellNumeric = "kui-table__header-cell--numeric"

  /** The windowed table (`VirtualizedTable`). It borrows every `kui-table__*` class for the parts that are
    * identical to the plain table — header cells, sort buttons, body cells — and adds only the classes that
    * exist because the rows are windowed: the scroller that owns the scroll position, the two spacers that
    * stand in for the rows outside the window, and the fixed-height row.
    */
  val VirtualTable = "kui-vtable"

  /** Per-table compact density. The global `:root[data-density="compact"]` switch is a preference; this is
    * one table asking for the same nine-pixel row on a screen that is a list of ten thousand things. Both set
    * the same custom property and nothing else, exactly as the design says density works.
    */
  val VirtualTableCompact = "kui-vtable--compact"
  val VirtualTableScroller = "kui-vtable__scroller"
  val VirtualTableRow = "kui-vtable__row"
  val VirtualTableSpacer = "kui-vtable__spacer"
  val VirtualTableEmpty = "kui-vtable__empty"

  val Magnitude = "kui-magnitude"
  val MagnitudeAccent = "kui-magnitude--accent"
  val MagnitudeInline = "kui-magnitude--inline"
  val MagnitudeRow = "kui-magnitude__row"
  val MagnitudeLabel = "kui-magnitude__label"
  val MagnitudeValue = "kui-magnitude__value"
  val MagnitudeTrack = "kui-magnitude__track"
  val MagnitudeFill = "kui-magnitude__fill"

  /** The per-cluster colour marker (`ClusterColors`). A filled chip, deliberately a rounded rectangle rather
    * than a circle, so it cannot be mistaken for the round status dot beside it.
    */
  val ClusterTag = "kui-cluster-tag"
  val ClusterTagNone = "kui-cluster-tag--none"
  val ClusterTagPrimary = "kui-cluster-tag--primary"
  val ClusterTagSuccess = "kui-cluster-tag--success"
  val ClusterTagWarning = "kui-cluster-tag--warning"
  val ClusterTagDanger = "kui-cluster-tag--danger"
  val ClusterTagAccent = "kui-cluster-tag--accent"

  /** The stale-data overlay (ADR-032 DC-H3). `StaleActive` is what dims the content; nothing in Scala
    * computes an opacity.
    */
  val Stale = "kui-stale"
  val StaleActive = "kui-stale--stale"
  val StaleContent = "kui-stale__content"
  val StaleBadge = "kui-stale__badge"
  val StaleBadgeTime = "kui-stale__time"
  val StaleBadgeReason = "kui-stale__reason"

  val Threshold = "kui-threshold"
  val ThresholdOver = "kui-threshold--over"
  val ThresholdCritical = "kui-threshold--critical"
  val ThresholdMark = "kui-threshold__mark"

  /** The search field (`SearchBox`) and its optional plain/full-text toggle. */
  val Search = "kui-search"
  val SearchField = "kui-search__field"
  val SearchIcon = "kui-search__icon"
  val SearchInput = "kui-search__input"
  val SearchClear = "kui-search__clear"
  val SearchModes = "kui-search__modes"
  val SearchMode = "kui-search__mode"
  val SearchModeSelected = "kui-search__mode--selected"

  /** The pagination bar. It renders nothing at all for a single page, so none of these classes appears in the
    * document then — including the container, which is why the empty state needs no class of its own.
    */
  val Pagination = "kui-pagination"
  val PaginationInner = "kui-pagination__inner"
  val PaginationSteps = "kui-pagination__steps"
  val PaginationButton = "kui-pagination__button"
  val PaginationLabel = "kui-pagination__label"
  val PaginationJump = "kui-pagination__jump"
  val PaginationJumpInput = "kui-pagination__jump-input"
  val PaginationGo = "kui-pagination__go"
  val PaginationSize = "kui-pagination__size"
  val PaginationSizeLabel = "kui-pagination__size-label"
  val PaginationSizeSelect = "kui-pagination__size-select"
}
