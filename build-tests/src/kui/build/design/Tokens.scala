package kui.build.design

/** The names of every design token, as Scala constants.
  *
  * The values live in `frontend/packages/kernel/styles/10-tokens.css` and only the browser ever reads them.
  * What this list is for is the *names*: to make `TokensSuite` fail when a token is added to the stylesheet
  * and forgotten here, and to give `ContrastSuite` the vocabulary it checks legibility over.
  *
  * Nothing in KUI may write a colour literal in a component. If a component needs a colour, it needs a token,
  * and if no token fits, the answer is a new token here and in the stylesheet, not a hex code in a component
  * (ADR-024).
  *
  * It is Scala, and it lives in `build-tests`, because the two suites that read it are build tests. The
  * browser code is TypeScript and never names a token in code at all — it writes an attribute on `<html>` and
  * lets the stylesheet do the rest (ADR-048 §5).
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

    /** The two ends of the text ramp beyond body copy.
      *
      * `strong` is the one thing in a region the eye is meant to land on: a page title, a card title, the
      * large figure on the dashboard. `subtle` is deliberately *below* the AA body threshold, which is why it
      * appears in no contrast pair and why it is only ever allowed on text that repeats something available
      * elsewhere — a chart axis tick beside a labelled bar, a relative time that also carries an absolute
      * one. Never a value an operator has to read.
      */
    val TextStrong = "--kui-color-text-strong"
    val TextSubtle = "--kui-color-text-subtle"

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

    /** The scrollbar thumb, and the same thumb under the pointer. Also translucent, and for the same reason
      * as the state layer: a scrollbar sits over every surface in the product, so a thumb that darkens what
      * is behind it needs one value per theme rather than one per surface.
      */
    val ScrollbarThumb = "--kui-color-scrollbar-thumb"
    val ScrollbarThumbHover = "--kui-color-scrollbar-thumb-hover"

    /** The veil behind a dialog or a drawer. One value for both, because they say the same thing. */
    val Scrim = "--kui-color-scrim"

    /** The chart palette, which is not a sixth ramp: each of these is declared as an alias of a colour that
      * already exists, so a chart never invents ink of its own.
      *
      * They have their own names anyway, because "the third line on this chart" and "this thing is healthy"
      * are different ideas that happen to share a colour today. Only in the partition-health donut do 3, 4
      * and 5 carry their status meaning, because there the categories genuinely are healthy, degraded and
      * failed; anywhere else a series colour means nothing but "a different line".
      */
    val Series1 = "--kui-color-series-1"
    val Series2 = "--kui-color-series-2"
    val Series3 = "--kui-color-series-3"
    val Series4 = "--kui-color-series-4"
    val Series5 = "--kui-color-series-5"

    val all: List[String] = List(
      Surface,
      SurfaceRaised,
      SurfaceElevated,
      SurfaceHover,
      SurfaceOverlay,
      Text,
      TextMuted,
      TextStrong,
      TextSubtle,
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
      StateLayer,
      ScrollbarThumb,
      ScrollbarThumbHover,
      Scrim,
      Series1,
      Series2,
      Series3,
      Series4,
      Series5
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

  /** Stacking order. Every `z-index` KUI writes is one of these five, so that a popover opened from a dialog
    * cannot end up behind it.
    */
  object Z {
    val Dropdown = "--kui-z-dropdown"
    val Drawer = "--kui-z-drawer"
    val Dialog = "--kui-z-dialog"
    val Toast = "--kui-z-toast"

    /** Above everything, including a toast. The sign-in cover and the gateway-unreachable screen are not
      * layers over a usable application — nothing behind them works — so nothing may sit on top of them and
      * invite a click that cannot do anything.
      */
    val Cover = "--kui-z-cover"

    val all: List[String] = List(Dropdown, Drawer, Dialog, Toast, Cover)
  }

  /** Dimming. Two values, not five: a control that is off, and a figure that is out of date. */
  object Opacity {
    val Disabled = "--kui-opacity-disabled"
    val Stale = "--kui-opacity-stale"

    val all: List[String] = List(Disabled, Stale)
  }

  object Duration {
    val Fast = "--kui-duration-fast"
    val Normal = "--kui-duration-normal"

    val all: List[String] = List(Fast, Normal)
  }

  /** Whether a card, a table container or a record row draws an edge — which is a property of the theme and
    * not of the card. In dark the fill step is strong enough on its own and the design draws no border; in
    * light the equivalent step is far weaker and a borderless card disappears into the page. One token,
    * redefined per theme, rather than the same decision written out inside every component that is a card.
    */
  object Border {
    val Card = "--kui-card-border"

    val all: List[String] = List(Card)
  }

  /** The one gradient in the product: the rounded tile at the head of the navigation drawer. Written in the
    * accent tokens rather than in two hex values, so a teal deployment does not have a blue mark.
    */
  object Gradient {
    val Brand = "--kui-gradient-brand"

    val all: List[String] = List(Brand)
  }

  /** The three fixed measurements of the application frame.
    *
    * Tokens rather than numbers in the shell's stylesheet because several rules have to agree about each of
    * them — the drawer's own width, the left offset of the content column, the left offset of the top bar,
    * and the offset a sticky table header scrolls to. Written out separately they drift, and the symptom is a
    * one-pixel seam of page ground down the side of the drawer that nobody can find the source of.
    */
  object Frame {
    val DrawerWidth = "--kui-drawer-width"
    val TopbarHeight = "--kui-topbar-height"
    val PageGutter = "--kui-page-gutter"

    /** The environment rail: the narrow strip of cluster tiles to the left of the drawer, and the size of one
      * tile in it. Both are read by the rail itself and by the offsets everything to its right is positioned
      * from, which is the same reason the drawer's width is a token.
      */
    val RailWidth = "--kui-rail-width"
    val RailTileSize = "--kui-rail-tile-size"

    val all: List[String] = List(DrawerWidth, TopbarHeight, PageGutter, RailWidth, RailTileSize)
  }

  /** Every token KUI defines. `TokensSuite` asserts that this list and the stylesheet agree in both
    * directions, so neither can gain a token the other does not know about.
    */
  val all: List[String] =
    Color.all ++ Space.all ++ Density.all ++ Font.all ++ Radius.all ++ Shadow.all ++ Z.all ++
      Opacity.all ++ Duration.all ++ Border.all ++ Gradient.all ++ Frame.all
}
