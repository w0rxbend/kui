/**
 * The design system's control primitives — the things every screen is assembled from.
 *
 * Everything here is presentational. None of it knows what a topic is, none of it fetches
 * anything, and none of it reads a store. That is what makes each one testable in one story and
 * reusable in a feature nobody has written yet.
 *
 * The icon set is not re-exported from here: it lives at `../icon.jsx` and is shared with the
 * list, card and chart components, because one keyed record of glyphs is the only way a name can
 * be a type error rather than a blank square.
 */
export { Spinner, type SpinnerProps } from "./Spinner.jsx";
export { Button, type ButtonProps, type ButtonVariant, type ButtonSize } from "./Button.jsx";
export { TextField, type TextFieldProps } from "./TextField.jsx";
export { Select, type SelectProps, type SelectOption } from "./Select.jsx";
export { Checkbox, type CheckboxProps } from "./Checkbox.jsx";
export { StatusPill, type StatusPillProps, type PillTone } from "./StatusPill.jsx";
export { IconTile, type IconTileProps, type TileTone } from "./IconTile.jsx";
export { Avatar, initialsOf, type AvatarProps } from "./Avatar.jsx";
export { Tooltip, type TooltipProps } from "./Tooltip.jsx";

/**
 * The controls the v3 screens added (`research/design/SCREENS.md` §2).
 *
 * `Switch` and `Checkbox` are two shapes for what looks like one idea, and the rule dividing them
 * is about when the change happens rather than about how they look — a switch takes effect at
 * once, a checkbox contributes to something that will be submitted. `SegmentedControl` and
 * `Select` divide the same way: a segmented control when the alternatives are worth advertising, a
 * select when there are too many to show. Both rules are argued in the components themselves.
 */
export { Switch, type SwitchProps } from "./Switch.jsx";
export { SegmentedControl, type SegmentedControlProps, type Segment } from "./SegmentedControl.jsx";
export {
  FilterChip,
  FilterChipBar,
  SingleSelectChips,
  type FilterChipProps,
  type FilterChipBarProps,
  type SingleSelectChipsProps,
} from "./FilterChips.jsx";
export { ConfigChip, ConfigChips, type ConfigChipProps, type ConfigChipsProps } from "./ConfigChip.jsx";
export { Pagination, pageWindow, type PaginationProps } from "./Pagination.jsx";

/**
 * Lists: the surfaces an operator spends the day scanning.
 *
 * Two treatments, deliberately not merged (design spec §3.5). `DataTable` and `VirtualizedTable`
 * are a grid of comparable values — ruled rows, aligned columns, the eye travelling across — and
 * differ from each other only in how the rows reach the document. `RecordRow` is a stack of
 * independent cards, each of which opens in place.
 */
export {
  DataTable,
  nextSort,
  type Column,
  type ColumnAlign,
  type DataTableProps,
  type Sort,
  type SortOrder,
  type TableSelection,
} from "./DataTable.jsx";
export {
  VirtualizedTable,
  DEFAULT_ROW_HEIGHT,
  ROW_HEIGHT_PROPERTY,
  type VirtualizedTableProps,
} from "./VirtualizedTable.jsx";
export { RecordRow, RecordList, type RecordRowProps } from "./RecordRow.jsx";
export { HeaderChip, type HeaderChipProps } from "./HeaderChip.jsx";
export {
  formatBytes,
  formatOffset,
  prettyValue,
  previewValue,
  recordKey,
  relativeTime,
  type KafkaRecord,
  type RecordHeader,
  type RecordValue,
} from "./record.js";
export { EmptyState, Missing, Skeleton, type EmptyKind, type EmptyStateProps, type SkeletonProps } from "./EmptyState.jsx";

/**
 * The window arithmetic behind `VirtualizedTable`, exported because it is the part that is worth
 * testing on its own: every off-by-one a virtualizer can have lives in `slice`, and none of them
 * throws.
 */
export { slice, endIndex, trailingHeightPx, MAX_SCROLL_HEIGHT_PX, type Slice } from "./window.js";
export { createIsCompact, COMPACT_ROW_SAVING_PX } from "./density.js";

/**
 * Surfaces, and every rendering that is not the happy one.
 *
 * The card is where a panel's *state* lives — loading, empty, filtered out, stale, unavailable,
 * forbidden — because SPEC §7.1's rule is that the frame never disappears: a page has to be able
 * to show three healthy panels and one that failed, at the same time, without either lying.
 *
 * The dialog and the drawer are the same object in two shapes. They share `overlay.ts`, which is
 * where the focus trap, `Escape` and the focus restoration live, because those are the parts that
 * break silently when they are written twice.
 */
export { Card, type CardProps, type CardState } from "./Card.jsx";
export { StatCard, type StatCardProps, type StatFigure, type StatPill } from "./StatCard.jsx";
export { StatTile, type StatTileProps, type TileFigure, type TileChip, type TileChipTone } from "./StatTile.jsx";
export { StaleBadge, relativeAge, type StaleBadgeProps } from "./StaleBadge.jsx";
export { Banner, type BannerProps, type BannerTone } from "./Banner.jsx";
export { Dialog, ConfirmDialog, type DialogProps, type DialogSize, type ConfirmDialogProps } from "./Dialog.jsx";
export { Drawer, type DrawerProps } from "./Drawer.jsx";
export {
  ToastRegion,
  notify,
  dismissToast,
  clearToasts,
  toasts,
  MAX_VISIBLE_TOASTS,
  type Toast,
  type ToastTone,
  type NotifyOptions,
} from "./Toast.jsx";
export { modalBehaviour, scrimClickHandler, focusableWithin, type ModalBehaviourOptions } from "./overlay.js";

/**
 * The chart family — the components that draw a quantity rather than printing one: the two plots,
 * the donut, the progress bar, the horizontal bar list, the legend and the range selector.
 *
 * They are re-exported from here so that a feature imports one thing from `@kui/kernel`, but they
 * live in their own directory because they share arithmetic (a guarded denominator, a formatter, a
 * tone-to-token map) that nothing else needs.
 */
export * from "./charts/index.js";

/**
 * Navigation, categorisation and the two numeric treatments.
 *
 * `Tabs` and `Breadcrumbs` are the two ways a screen says where you are; `Tag` labels a row;
 * `MagnitudeBar` and `ThresholdValue` are the two ways a number is drawn — one relative to its
 * neighbours, one relative to a limit. All five were ported from the Laminar kernel, comments and
 * all, because the reasoning in them was paid for by defects rather than deduced.
 */
export { Tabs, tabTarget, type Tab, type TabsProps } from "./Tabs.jsx";
export { Breadcrumbs, type Crumb, type BreadcrumbsProps } from "./Breadcrumbs.jsx";
export { Tag, type TagProps, type TagTone } from "./Tag.jsx";
export { MagnitudeBar, percentage, type MagnitudeBarProps } from "./MagnitudeBar.jsx";
export {
  ThresholdValue,
  thresholdLevel,
  defaultAnnouncement,
  type ThresholdLevel,
  type ThresholdValueProps,
} from "./ThresholdValue.jsx";

/**
 * The block above every content region: breadcrumb, title, status chip, actions, and the voice line
 * that a list carries and an object page does not (SPEC §4.12, §5.2).
 */
export { PageHeader, type PageHeaderProps } from "./PageHeader.jsx";
