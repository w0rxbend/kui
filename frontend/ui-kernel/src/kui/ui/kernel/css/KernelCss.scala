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
}
