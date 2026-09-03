package kui.ui.shell

/** The shell's class names, as Scala constants.
  *
  * The same arrangement `KernelCss` uses and for the same reason: a class name typed as a string literal at
  * the point of use is a name that can be misspelled, renamed in the stylesheet only, or deleted from the
  * stylesheet while the code still writes it. Here the compiler catches all three.
  */
object ShellCss {

  val Shell = "kui-shell"
  val SkipLink = "kui-shell__skip"
  val Header = "kui-shell__header"
  val HeaderBrand = "kui-shell__brand"
  val HeaderSpacer = "kui-shell__header-spacer"
  val HeaderActions = "kui-shell__header-actions"
  val HeaderVersion = "kui-shell__version"
  val ClusterSlot = "kui-shell__cluster-slot"
  val Sidebar = "kui-shell__sidebar"
  val SidebarList = "kui-shell__sidebar-list"
  val SidebarLink = "kui-shell__sidebar-link"
  val SidebarLinkCurrent = "kui-shell__sidebar-link--current"
  val Content = "kui-shell__content"
  val Page = "kui-shell__page"
  val PageError = "kui-shell__page-error"
  val PageErrorDetail = "kui-shell__page-error-detail"
  val Gallery = "kui-shell__gallery"
  val GallerySection = "kui-shell__gallery-section"
  val GalleryRow = "kui-shell__gallery-row"
  val GallerySwatch = "kui-shell__gallery-swatch"
  val GalleryIconGrid = "kui-shell__gallery-icons"
  val GalleryIcon = "kui-shell__gallery-icon"
  val SettingsGroup = "kui-shell__settings-group"

  val ErrorPage = "kui-shell__error-page"
  val ErrorPageDetail = "kui-shell__error-page-detail"

  val Unreachable = "kui-shell__unreachable"
  val UnreachableCard = "kui-shell__unreachable-card"
  val UnreachableIcon = "kui-shell__unreachable-icon"
  val UnreachableCountdown = "kui-shell__unreachable-countdown"
  val UnreachableLastContact = "kui-shell__unreachable-last-contact"
}
