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

    /** The five neutral surfaces, ordered from the page outwards. `text` and `text-muted` are legible on
      * every one of them, which is what lets a component inherit its text colour from whatever it is sitting
      * inside instead of choosing one.
      */
    val Surface = "--kui-color-surface"
    val SurfaceRaised = "--kui-color-surface-raised"
    val SurfaceElevated = "--kui-color-surface-elevated"
    val SurfaceHover = "--kui-color-surface-hover"
    val SurfaceOverlay = "--kui-color-surface-overlay"

    val Text = "--kui-color-text"
    val TextMuted = "--kui-color-text-muted"

    val Border = "--kui-color-border"
    val BorderStrong = "--kui-color-border-strong"

    /** The accent seed. These four are the only colours a seed changes; everything else above and below is
      * the same whichever seed is selected.
      */
    val Primary = "--kui-color-primary"
    val PrimaryContrast = "--kui-color-primary-contrast"
    val PrimaryContainer = "--kui-color-primary-container"
    val PrimaryContainerContrast = "--kui-color-primary-container-contrast"

    /** The fill behind a selected thing — the current navigation destination — and its text. */
    val Selected = "--kui-color-selected"
    val SelectedContrast = "--kui-color-selected-contrast"

    /** A second accent, independent of the seed, for a marker that must not read as "primary". */
    val Accent = "--kui-color-accent"
    val AccentContainer = "--kui-color-accent-container"
    val AccentContainerContrast = "--kui-color-accent-container-contrast"

    /** Status. Each has a foreground for text and an icon on a neutral surface, and a container for the
      * filled chip the design uses instead of a bare coloured dot. The foreground doubles as the text colour
      * on its own container, which is the pairing the contrast table checks.
      */
    val Success = "--kui-color-success"
    val SuccessContainer = "--kui-color-success-container"
    val Warning = "--kui-color-warning"
    val WarningContainer = "--kui-color-warning-container"
    val Danger = "--kui-color-danger"
    val DangerContainer = "--kui-color-danger-container"
    val Info = "--kui-color-info"

    val Focus = "--kui-color-focus"

    /** The translucent wash painted over a surface on hover and while pressed. Not a solid colour: it is laid
      * on top of whatever is underneath, so one value works on all five surfaces.
      */
    val StateLayer = "--kui-color-state-layer"

    val all: List[String] = List(
      Surface,
      SurfaceRaised,
      SurfaceElevated,
      SurfaceHover,
      SurfaceOverlay,
      Text,
      TextMuted,
      Border,
      BorderStrong,
      Primary,
      PrimaryContrast,
      PrimaryContainer,
      PrimaryContainerContrast,
      Selected,
      SelectedContrast,
      Accent,
      AccentContainer,
      AccentContainerContrast,
      Success,
      SuccessContainer,
      Warning,
      WarningContainer,
      Danger,
      DangerContainer,
      Info,
      Focus,
      StateLayer
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
    val FamilyDisplay = "--kui-font-family-display"
    val FamilySans = "--kui-font-family-sans"
    val FamilyMono = "--kui-font-family-mono"
    val FamilyIcon = "--kui-font-family-icon"
    val SizeXs = "--kui-font-size-xs"
    val SizeSm = "--kui-font-size-sm"
    val SizeMd = "--kui-font-size-md"
    val SizeLg = "--kui-font-size-lg"
    val SizeXl = "--kui-font-size-xl"
    val Size2xl = "--kui-font-size-2xl"
    val Size3xl = "--kui-font-size-3xl"
    val WeightRegular = "--kui-font-weight-regular"
    val WeightMedium = "--kui-font-weight-medium"
    val WeightBold = "--kui-font-weight-bold"
    val WeightDisplay = "--kui-font-weight-display"
    val LineHeightTight = "--kui-font-line-height-tight"
    val LineHeightNormal = "--kui-font-line-height-normal"

    val all: List[String] = List(
      FamilyDisplay,
      FamilySans,
      FamilyMono,
      FamilyIcon,
      SizeXs,
      SizeSm,
      SizeMd,
      SizeLg,
      SizeXl,
      Size2xl,
      Size3xl,
      WeightRegular,
      WeightMedium,
      WeightBold,
      WeightDisplay,
      LineHeightTight,
      LineHeightNormal
    )
  }

  /** Density. One value, because the design makes density a switch rather than a theme: it changes how much
    * air a table row has and nothing else. Its own group and not a `Space` step, because a `Space` step is a
    * fixed number a component may choose and this one is chosen for the component by the user's density
    * preference.
    */
  object Density {
    val RowPaddingY = "--kui-density-row-padding-y"

    val all: List[String] = List(RowPaddingY)
  }

  object Radius {
    val Xs = "--kui-radius-xs"
    val Sm = "--kui-radius-sm"
    val Md = "--kui-radius-md"
    val Lg = "--kui-radius-lg"
    val Xl = "--kui-radius-xl"
    val Pill = "--kui-radius-pill"

    val all: List[String] = List(Xs, Sm, Md, Lg, Xl, Pill)
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
    Color.all ++ Space.all ++ Density.all ++ Font.all ++ Radius.all ++ Shadow.all ++ Z.all ++
      Duration.all
}
