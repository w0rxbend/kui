package kui.ui.kernel.theme

/** The names of every design token, as Scala constants.
  *
  * The values live in `resources/css/10-tokens.css` and only the browser ever reads them. What Scala code
  * needs is the *names*: to read a computed value in a test, to pass a token into an inline style when a
  * value has to be computed at runtime, and — most importantly — to make `ContrastSuite` fail when a token is
  * added to the stylesheet and forgotten here.
  *
  * Nothing in KUI may write a colour literal in Scala. If a component needs a colour, it needs a token, and
  * if no token fits, the answer is a new token here and in the stylesheet, not a hex code in a component
  * (ADR-024).
  */
object Tokens {

  /** Colours. Read `docs/frontend/tokens.md` for what each one means and which pairs are contrast checked;
    * the short version is in `10-tokens.css` next to each value.
    */
  object Color {
    val Surface = "--kui-color-surface"
    val SurfaceRaised = "--kui-color-surface-raised"
    val Border = "--kui-color-border"
    val BorderStrong = "--kui-color-border-strong"
    val Text = "--kui-color-text"
    val TextMuted = "--kui-color-text-muted"
    val Primary = "--kui-color-primary"
    val PrimaryContrast = "--kui-color-primary-contrast"
    val Success = "--kui-color-success"
    val Warning = "--kui-color-warning"
    val Danger = "--kui-color-danger"
    val Info = "--kui-color-info"
    val Focus = "--kui-color-focus"

    val all: List[String] = List(
      Surface,
      SurfaceRaised,
      Border,
      BorderStrong,
      Text,
      TextMuted,
      Primary,
      PrimaryContrast,
      Success,
      Warning,
      Danger,
      Info,
      Focus
    )
  }

  /** The 4 px spacing scale. Every gap, margin and padding in KUI is one of these. */
  object Space {
    val S0 = "--kui-space-0"
    val S1 = "--kui-space-1"
    val S2 = "--kui-space-2"
    val S3 = "--kui-space-3"
    val S4 = "--kui-space-4"
    val S5 = "--kui-space-5"
    val S6 = "--kui-space-6"
    val S7 = "--kui-space-7"
    val S8 = "--kui-space-8"

    val all: List[String] = List(S0, S1, S2, S3, S4, S5, S6, S7, S8)
  }

  object Font {
    val FamilySans = "--kui-font-family-sans"
    val FamilyMono = "--kui-font-family-mono"
    val SizeXs = "--kui-font-size-xs"
    val SizeSm = "--kui-font-size-sm"
    val SizeMd = "--kui-font-size-md"
    val SizeLg = "--kui-font-size-lg"
    val SizeXl = "--kui-font-size-xl"
    val WeightRegular = "--kui-font-weight-regular"
    val WeightMedium = "--kui-font-weight-medium"
    val WeightBold = "--kui-font-weight-bold"
    val LineHeightTight = "--kui-font-line-height-tight"
    val LineHeightNormal = "--kui-font-line-height-normal"

    val all: List[String] = List(
      FamilySans,
      FamilyMono,
      SizeXs,
      SizeSm,
      SizeMd,
      SizeLg,
      SizeXl,
      WeightRegular,
      WeightMedium,
      WeightBold,
      LineHeightTight,
      LineHeightNormal
    )
  }

  object Radius {
    val Sm = "--kui-radius-sm"
    val Md = "--kui-radius-md"
    val Lg = "--kui-radius-lg"
    val Pill = "--kui-radius-pill"

    val all: List[String] = List(Sm, Md, Lg, Pill)
  }

  object Shadow {
    val Sm = "--kui-shadow-sm"
    val Md = "--kui-shadow-md"

    val all: List[String] = List(Sm, Md)
  }

  /** Stacking order. Every `z-index` KUI writes is one of these four, so that a popover opened from a dialog
    * cannot end up behind it.
    */
  object Z {
    val Dropdown = "--kui-z-dropdown"
    val Drawer = "--kui-z-drawer"
    val Dialog = "--kui-z-dialog"
    val Toast = "--kui-z-toast"

    val all: List[String] = List(Dropdown, Drawer, Dialog, Toast)
  }

  object Duration {
    val Fast = "--kui-duration-fast"
    val Normal = "--kui-duration-normal"

    val all: List[String] = List(Fast, Normal)
  }

  /** Every token KUI defines. `TokensSuite` asserts that this list and the stylesheet agree in both
    * directions, so neither can gain a token the other does not know about.
    */
  val all: List[String] =
    Color.all ++ Space.all ++ Font.all ++ Radius.all ++ Shadow.all ++ Z.all ++ Duration.all
}
