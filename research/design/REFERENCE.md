# The "Kafka UI v2" design, as read from the source

Imported from the Claude Design project `a6db560c-c2e2-41f6-b144-bbe0dd850aa4`, artboard
`Kafka UI v2.dc.html`, on 2026-09-03. The project also contains the superseded `Kafka UI.dc.html`,
a `support.js` runtime and two pasted images.

This document records what the design *is*. It is a reading of the source, not an interpretation:
every value below was extracted from the artboard rather than judged by eye. Nothing from the design
is copied into the product; it is reimplemented in Scala.js and Laminar with the project's own CSS.

## What the design decides, and what it does not

The design is the authority on appearance: colour, type, spacing, radius, density, layout and the
affordances a control offers. It is not the authority on behaviour. What a screen does, which data
it shows, what an action performs and what happens when something fails all come from the researched
behaviour of Kafbat Kafka UI and Provectus Kafka UI in `research/`, and from `docs/FEATURE_MATRIX.md`.

The artboard is populated with invented sample data: topic names, consumer groups, broker hosts,
offsets and JSON payloads. None of it is a specification. It shows the *shape* a row takes, not the
fields the product has.

## Colour

Two themes, dark by default, and four interchangeable seed palettes. The neutral ramp is independent
of the seed, so changing the accent never changes the surfaces.

### Neutral and status tokens

| Token | Meaning | Light | Dark |
| --- | --- | --- | --- |
| `--sf` | page background, the lowest surface | `#F7F9FC` | `#0E1013` |
| `--sfl` | raised surface: the navigation drawer | `#F0F3F8` | `#15181C` |
| `--sfc` | card surface, one step above the drawer | `#E9EEF5` | `#1B1F25` |
| `--sfh` | hovered or higher surface, and neutral chip background | `#E0E6EF` | `#242930` |
| `--sfx` | highest surface: menus, popovers, pressed states | `#D6DEE9` | `#2E343C` |
| `--on` | primary text on any surface | `#171C22` | `#E3E6EB` |
| `--onv` | secondary text: labels, muted values | `#4A525E` | `#A6ACB8` |
| `--ol` | outline: borders and dividers at full strength | `#727B88` | `#666D79` |
| `--olv` | outline variant: subtle separators, table rules | `#CBD2DC` | `#2A3038` |
| `--sec` | secondary container: the active navigation item | `#DCE5F5` | `#3A4657` |
| `--onsec` | text on the secondary container | `#121C2B` | `#DCE5F5` |
| `--ter` | tertiary accent | `#00695C` | `#7FD8C7` |
| `--terc` | tertiary container, used for the compact-policy chip | `#9FF2E1` | `#00504A` |
| `--onterc` | text on the tertiary container | `#00201C` | `#B2F5E8` |
| `--ok` | success foreground | `#2E7D32` | `#8FD36A` |
| `--okc` | success container background | `#C8EAC1` | `#1E4416` |
| `--warn` | warning foreground | `#7A4F00` | `#FFD180` |
| `--warnc` | warning container background | `#FFE1A8` | `#4A3200` |
| `--err` | error foreground | `#B3261E` | `#FFB4AB` |
| `--errc` | error container background | `#F9DEDC` | `#5C1B16` |
| `--st` | state layer: hover and pressed overlays | `rgba(23,28,34,0.06)` | `rgba(227,230,235,0.08)` |
| `--sh` | shadow colour | `rgba(23,28,34,0.16)` | `rgba(0,0,0,0.45)` |

### Seed palettes

The design ships four. Blue is the default.

| Seed | Token | Meaning | Light | Dark |
| --- | --- | --- | --- | --- |
| blue | `--pr` | primary: brand accent, active icon, focus ring | `#0B57D0` | `#A8C7FA` |
| blue | `--onpr` | text on the primary colour | `#FFFFFF` | `#062E6F` |
| blue | `--prc` | primary container: filled buttons and the logo gradient | `#D3E3FD` | `#0B57D0` |
| blue | `--onprc` | text on the primary container | `#041E49` | `#D3E3FD` |
| teal | `--pr` | primary: brand accent, active icon, focus ring | `#00796B` | `#7FD8C7` |
| teal | `--onpr` | text on the primary colour | `#FFFFFF` | `#003731` |
| teal | `--prc` | primary container: filled buttons and the logo gradient | `#B2F5E8` | `#00857A` |
| teal | `--onprc` | text on the primary container | `#00201C` | `#B2F5E8` |
| green | `--pr` | primary: brand accent, active icon, focus ring | `#2E6B14` | `#A6D98A` |
| green | `--onpr` | text on the primary colour | `#FFFFFF` | `#0F3A00` |
| green | `--prc` | primary container: filled buttons and the logo gradient | `#C7F0AE` | `#3E7A22` |
| green | `--onprc` | text on the primary container | `#0A2A00` | `#D9F5C6` |
| amber | `--pr` | primary: brand accent, active icon, focus ring | `#8A5A00` | `#FFD180` |
| amber | `--onpr` | text on the primary colour | `#FFFFFF` | `#4A2E00` |
| amber | `--prc` | primary container: filled buttons and the logo gradient | `#FFE0A3` | `#8A5A00` |
| amber | `--onprc` | text on the primary container | `#2A1A00` | `#FFE7BF` |

The structure is worth noting because it is what makes the palette swappable: every colour is
either a *surface* or an *on-surface* pair, and a container colour always has a matching text
colour declared beside it. A component never picks a text colour; it uses the one paired with the
surface it sits on.

## Type

Three families, loaded from Google Fonts in the artboard:

| Family | Weights used | Role |
| --- | --- | --- |
| Space Grotesk | 500, 600, 700 | display and headings, the product wordmark, large figures |
| Manrope | 400, 500, 600, 700, 800 | body text, labels, table content |
| Material Symbols Rounded | variable, optical size 20–48 | icons |

Sizes observed in the artboard, in pixels: 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 21, 22, 24, 32,
40, 42. The base body size is 14.

## Shape and density

Radii observed: 3, 5, 8, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30 and 99 pixels, the last being a
pill. The design is markedly rounder than either reference product.

Density is a switch, not a theme: a `compact` flag changes table row padding from 15px to 9px.
Everything else is unchanged. This matters for an operator scanning thousands of topics.

## Layout

A fixed navigation drawer 272 pixels wide holding the wordmark and the primary destinations, and a
main region that fills the rest. The active destination is marked by the secondary container colour
rather than by a border or a bar.

## Screens in the artboard

Dashboard, topics, topic detail with a message browser, consumer groups, schema registry, and Kafka
Connect. A brokers destination exists in the drawer and currently points at the dashboard.

These correspond to milestones across the roadmap, not to work available now. The design informs how
each one looks when its milestone arrives; it does not bring that milestone forward.

Notable interaction patterns to carry over:

- A message row expands in place to show pretty-printed JSON and its headers, rather than opening a
  drawer or a dialog.
- Quantities are shown as horizontal bars next to the figure, so relative magnitude is readable
  without reading numbers.
- Status is a filled chip using a container colour and its paired text colour, never a bare dot.
- Out-of-sync replica counts and consumer lag switch to the warning colour on a threshold rather
  than being coloured constantly.

## Where this leaves the implemented product

The implemented token set in `frontend/ui-kernel/resources/css/10-tokens.css` was derived from
studying Kafbat, before this design existed. Its *names* are semantic and stay; the *values* are
superseded by the table above. The mapping and the reconciliation method are task UI-013.
