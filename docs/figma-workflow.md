# Working from Figma

The design rules themselves — MMD before Material3, and the `KompaktTypography` mapping — are in
[`../CLAUDE.md`](../CLAUDE.md). This page is the procedure.

Read designs through the Figma MCP tools (`get_design_context`, `get_metadata`, `get_screenshot`,
`download_assets`) against a URL carrying a `node-id`.

## Audit after every change

**Re-read the node and audit it** — don't trust the implementation:

| What to check | How |
|---|---|
| Typography | every `Text` uses a `KompaktTypography` style matching the Figma style name |
| Icon identity | drawable name/size vs. the Figma component name (`Style=default` vs `Style=tile`) |
| Icon placement | `position: absolute` vs. in-flow; note `locationRelativeToParent` x/y |
| Borders | `strokeDashes` — solid vs. dotted (`dashPathEffect`) vs. dashed |
| Border radius | the `borderRadius` value in dp |
| Padding & spacing | `padding` and `gap` on each frame |
| `Show X: true/false` props | map each visible component property to the rendered node |
| AppBar action icons | the `Buttons & Icons` children feeding `KompaktTopAppBar`'s `actionView` slot |
| Dividers | which items have `Show bottom-separator: true` |
| Default state | initial toggle/selection values |

Go deeper on every layer. When a node is `IMAGE-SVG` it is opaque to the API — its children are
hidden — so download it and read the path data. Never assume two icons are identical because they
share a component-set name.

## Strings from Figma

Layer names may end with a **Frontitude key** in brackets — `"Link a Google account
[calendar.empty_state.title]"`. Use that key verbatim as the resource name (`.` → `_`) in the
`frontitude` module. With no key present, fall back to descriptive `snake_case` prefixed `kompakt_`.

Copy is owned by Frontitude, so a new string is pulled, not hand-written — the pull recipe is in
[`local-config.md`](local-config.md#frontitude-strings).
