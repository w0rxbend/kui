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
}
