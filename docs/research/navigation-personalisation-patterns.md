# Navigation personalisation patterns in comparable apps

> **Doc type:** Research note — a snapshot of findings, not a spec and not a decision. Collected
> 2026-10-20 for issue #2801 (map #2800). Nothing here is binding; the navigation concept that
> builds on it gets its own `REQ-UI-…` amendments and ADR.
>
> **Question:** how do GitHub, Linear, Jira, Notion, Slack, Discord, Grafana and VS Code let users
> tailor dense navigation — favourites/pinning, reorder and hide, recents vs. frequency ranking
> (and decay), collapsible icon rails, role-based defaults, command palettes over entities and
> actions — where does each pattern break down, and what does it imply for a lean default?

Sources are the vendors' own help centres, docs and changelogs, plus peer-reviewed HCI work and
NN/g for the UX evidence. Where a primary source was silent or could not be read, the note says
so instead of filling the gap.

## 1. Pattern catalogue

### 1.1 Favourites / pinning (explicit, user-chosen shortcuts)

| App | How it works | Source |
|---|---|---|
| Linear | Star in the page header; favourites appear as personal shortcuts in the sidebar, can be grouped into user-named folders, removed with an X on hover. | [Linear docs: Favorites][linear-fav] |
| Notion | Star at the top of a page; the *Favorites* section only appears once the first page is starred. Database views can be pinned and page shortcuts added without favouriting. | [Notion help: sidebar][notion-sidebar] |
| Jira / Confluence | *Starred* section in the sidebar; Atlassian advises starring only what is used daily or weekly and reviewing the list monthly. | [Atlassian: navigation best practices][atl-best] |
| Slack | Starring a conversation moves it into a *Starred* section at the top of the sidebar; paid plans add user-made custom sections, visible only to the user. | [Slack: custom sections][slack-sections] |
| Grafana | *Bookmarks* section at the top of the main menu for frequently used pages (Grafana 11.2+, `pinNavItems` toggle on self-managed). Stored as `navbar.bookmarkUrls` in the **user** preferences (the same field exists at org level). | [Grafana: Navigation Bookmarks][grafana-bm], [Grafana HTTP API: preferences][grafana-prefs] |
| Discord | No native per-user channel favourites inside a server was found in official docs; it is an open user request on Discord's own feedback forum. Server *folders* group servers, not channels. | [Discord feedback: channel folders/favorites][discord-fav] (feedback post, low trust) |

**Where it breaks down.** Favourites only help users who curate; the empty state is the default
for everyone else (Notion hides the section until the first star). Lists grow stale — Atlassian
has to tell users to prune monthly. Linear's remove-from-sidebar is described as not reversible
except by re-adding.

**Implication for a lean default.** Favourites are an *additive accelerator*, never the thing
that makes the default lean. They need (a) a one-gesture add on the page itself (star in the
header) and (b) a home that is invisible until used.

### 1.2 Reorder and hide (user-edited structure)

| App | How it works | Source |
|---|---|---|
| Linear | *Customize sidebar*: drag to reorder, hide rarely used items or move them into a **More** menu, choose count vs. dot for unread badges; also via right-click on an item. | [Linear changelog 2024-12-18][linear-sidebar] |
| Jira (new navigation) | *More → Customize sidebar*: checkbox to show/hide, drag (or arrow keys) to reorder. Changes are personal — "no one else's sidebar is affected" — and persist across sessions. | [Atlassian: what is the new navigation in Jira][atl-new-nav], [best practices][atl-best] |
| Notion | Show, hide, reorder tabs, buttons and sections; set how many items a section shows; **reset to the original setup**. Workspaces can supply tab presets; teamspace owners can lock sidebar editing. | [Notion help: sidebar][notion-sidebar] |
| Slack | *Preferences → Navigation*: tick/untick tabs; unticked ones move under **More**; drag or arrows to reorder. Sidebar filter defaults to *Active only* (hides conversations idle 30 days). | [Slack: sidebar preferences][slack-prefs] |
| VS Code | Activity Bar views shown/hidden and reordered via right-click and drag; views can move between primary and secondary side bars; layout persists across restarts. | [VS Code: user interface][vsc-ui], [custom layout][vsc-layout] |

**Where it breaks down.** NN/g: users often hit severe usability issues when customising, and
busy professionals treat tweaking UI preferences as a hassle ([NN/g: customization features
report][nng-cust-report], [NN/g: customization vs personalization][nng-cust]). Hidden items get
lost; that is why every app above parks them under a **More** entry rather than removing them,
and Notion adds an explicit *reset*.

**Implication.** Hide must mean "move to More", never "gone"; reset-to-default is mandatory; and
since many users never customise, editing is a power feature layered on top of a default that is
already lean.

### 1.3 Recents vs. frequency ranking (system-chosen shortcuts)

| App | How it works | Source |
|---|---|---|
| Jira | *Recent* covers spaces/projects, issues, boards, filters, dashboards visited, edited or created; *For you* is a hub of recent, assigned and starred work. | [Atlassian: new navigation in Jira][atl-new-nav] |
| Notion | *Recents* is a section of the Home tab; the help page does not document ordering. | [Notion help: sidebar][notion-sidebar] |
| GitHub | The command palette's empty-state suggestions are based on current context and recently used resources. | [GitHub Docs: command palette][gh-palette] |
| VS Code | Repeating Quick Open cycles recently opened files; Ctrl+R opens recent folders/workspaces. | [VS Code: tips and tricks][vsc-tips] |
| Firefox (reference algorithm) | *Frecency*: each sampled visit scores `weight × e^(−λ·age)` with a **30-day half-life**; visit weight is bucketed (typed/bookmarked high, link click medium, reload/redirect low); total = mean sampled score × visit count; recomputed on visit and in idle batches. | [Firefox source docs: urlbar ranking][ff-frecency] |

No app in the set documents a frequency-ranked *sidebar*; frequency/recency is used for
**lists inside a search or palette** and for a separate *Recent* section, never to reorder the
main navigation.

**Where it breaks down.** Spatially adaptive menus are rarely faster than static ones because the
user must keep re-learning positions. Findlater & McGrenere (CHI 2004, 27 participants, split
menus with four top items) found a **static** split menu significantly faster than an
**adaptive** (recency/frequency) one, and most participants preferred the **adaptable**
(user-controlled) version ([Findlater & McGrenere 2004][findlater]). Sears & Shneiderman's
static split menu — fixed frequent items on top — was at least as fast as a traditional menu and
mostly faster (summarised in the same paper).

**Implication.** Use recency/frecency only where position is not memorised: the palette's result
list and an optional "Recent" strip. Never let usage reorder the sidebar or the phone tab bar.

### 1.4 Collapsible rails and collapsed groups

| App | How it works | Source |
|---|---|---|
| Slack | Right-click the sidebar to switch between *icons only* and *icons and text*; drag the edge to resize. | [Slack: sidebar preferences][slack-prefs] |
| Jira / Confluence | `Ctrl+[` collapses/expands the sidebar; recommended for small screens or focus work. | [Atlassian: best practices][atl-best] |
| Notion | Sections collapse by clicking their name; whole sidebar collapses with `Ctrl+\`. | [Notion help: sidebar][notion-sidebar] |
| VS Code | Activity Bar can sit left, top, bottom or be hidden; compact size only on the side. | [VS Code: custom layout][vsc-layout] |
| Grafana 10.3 | Menu reworked to show all levels in a more compact layout with an improved dock. | [Grafana: what's new in 10.3][grafana-103] |

**Where it breaks down.** An icon-only rail needs recognisable icons for every entry and falls
apart with dozens of entries (VS Code works because it has roughly ten views). Collapsing hides
items but does not reduce the count a member sees when expanded.

**Implication.** A rail is a viewport tactic, not a simplification. ADR-0240's rejection of an
icon rail still holds for the reduction goal; collapse remains a per-user convenience.

### 1.5 Role-based and admin-set defaults

| App | How it works | Source |
|---|---|---|
| Notion | Tab setups come from Notion or from **workspace presets**; every member sees the default teamspace; teamspace owners can disable sidebar editing. | [Notion help: sidebar][notion-sidebar] |
| Jira | Project admins can reorder/remove the horizontal project tabs once "and everyone on the team benefits"; the personal sidebar stays personal. | [Atlassian: best practices][atl-best] |
| Linear | Settings split into Account, Features, **Administration (admins only)**, teams — admin surfaces separated from member surfaces. | [Linear changelog 2024-12-18][linear-sidebar] |
| Discord | Server admins define onboarding questions that assign roles and channels; members change them later in *Channels & Roles* and add others via *Browse Channels*. | [Discord: Community Onboarding FAQ][discord-onb] |

**Where it breaks down.** Discord's opt-in model hides newly created channels from existing
members unless they enable *Show All Channels* — a recurring complaint on Discord's own feedback
forum ([example][discord-showall], low trust). A default that is *chosen once* drifts as the
product grows.

**Implication.** NN/g recommends role-based personalisation where roles are well defined in
advance ([NN/g][nng-cust]) — the Basetool's roles are. The lean default should be **derived from
role** (and recomputed when roles or features change), with admin surfaces in a separate area
rather than interleaved, exactly as Linear does.

### 1.6 Command palettes over entities and actions

| App | How it works | Source |
|---|---|---|
| GitHub | `Ctrl+K`: navigation/search mode by default; prefixes `#` issues/PRs/discussions/projects, `!` projects, `@` users/orgs/repos, `/` files or repos; `>` command mode (global, org, repo, file and page-scoped actions); scope chip narrowed with Tab, widened with Backspace; `?` for help. Public preview, off by default. | [GitHub Docs: command palette][gh-palette] |
| Linear | `Cmd/Ctrl+K` runs any action or jumps to any page by name and searches second-level actions; type-prefixes such as `i` + space focus issues; `/` opens workspace search over issues and documents. | [Linear: navigating Linear][linear-nav], [Linear docs: search][linear-search], [changelog 2020-08-13][linear-2020] |
| VS Code | `Ctrl+Shift+P` commands for the current context; `Ctrl+P` Quick Open for files, `?` lists prefixes; keybindings shown next to commands. | [VS Code: tips and tricks][vsc-tips] |

**Where it breaks down.** Palettes are invisible to users who do not know the shortcut (GitHub
ships it off by default); prefix grammars are expert-only. NN/g's flexibility-and-efficiency
heuristic frames accelerators as hidden from novices and speeding up experts — they complement
visible navigation, they do not replace it ([NN/g heuristic #7][nng-flex]).

**Implication.** The palette is the right place for the long tail and for entities (a mission,
an order, a ship), which is what takes the "2 keystrokes" criterion off the sidebar's shoulders.
It must show a visible trigger and, when empty, recents + favourites.

### 1.7 Phone tab bars

Apple's HIG: avoid overflow tabs because a *More* tab makes hidden content hard to reach and
notice; in apps with many sections it can be useful to let people add frequently used items to
the tab bar or remove rarely used ones; if users choose their own tabs, aim for a **default of
five or fewer**; do not disable or hide tab buttons, because a bar that changes makes the
interface look unstable ([Apple HIG: tab bars][hig-tabs]). Material's guidance pages could not be
read in this review.

**Implication.** A user-selected tab bar is endorsed, with a ≤ 5 default and stable slots; a
favourite that becomes unpermitted needs a deterministic fallback rather than a vanishing tab.

## 2. Cross-cutting observations

1. **Default first, customisation second.** NN/g: personalisation and customisation "should not be
   used as a fix for a broken site" ([NN/g][nng-cust]). Every app above ships a curated default
   and treats editing as optional.
2. **Stable positions beat clever ones.** Usage-driven reordering of persistent navigation is
   absent from every product reviewed and slower in the lab ([Findlater & McGrenere][findlater]).
3. **"More" is the universal overflow.** Linear, Slack and Jira all move hidden items to *More*
   rather than delete them; Apple warns that items behind *More* are rarely found — so only
   genuinely rare items belong there.
4. **Preferences are per user and server-side where it matters.** Jira says layouts persist
   across sessions; Grafana stores bookmarks in user preferences (`/api/user/preferences`);
   Slack sections are private to the user. None of the docs reviewed relies on browser storage
   for favourites.
5. **Reset and admin presets are part of the feature.** Notion offers reset and workspace
   presets; without them customisation becomes a support problem.

## 3. Implications for the Basetool

Mapped to map #2800's success criteria and constraints.

**Criterion: frequent targets reachable in 1 click or 2 keystrokes.**

- *1 click:* a **Favourites** section at the top of the sidebar (star on the page header, Linear /
  Notion style), hidden while empty, plus the **phone tab bar** filled from the user's top
  favourites with a ≤ 5-slot default (Apple HIG). Positions stay where the user put them — no
  automatic reordering.
- *2 keystrokes:* Ctrl+K opening on **recents + favourites** in its empty state (GitHub), ranked by
  frecency inside the palette only (Firefox's 30-day half-life is a documented, simple
  reference); typing a short prefix reaches entities. Extending the palette to entity search
  (missions, orders, ships) follows GitHub's and Linear's prefix model and needs a deliberate
  revisit of ADR-0240's "no backend-served palette index".
- Optional jump-to-favourite-n shortcuts fit this model and are listed as not yet specified on
  the map.

**Criterion: a plain member's default view shows at most ~10 entries without expanding.**

- Reach this by **restructuring the default per role**, not by personalisation (NN/g; many users
  never customise). Role-derived defaults are well supported because Basetool roles are defined
  in advance.
- Put admin-mode navigation in its own area (Linear's Administration split) so it never inflates
  the member or officer view.
- Rarely used member pages go under a single **More** group — but only rare ones, because items
  behind *More* are hard to discover (Apple HIG). Hide/reorder by the user moves items to *More*
  and comes with *reset to default*.

**What the evidence argues against.**

- Frequency-ranked or self-reordering sidebars and tab bars (Findlater & McGrenere 2004; no
  product precedent).
- An icon rail as the reduction lever (it hides labels, not entries; ADR-0240 stays right on
  that).
- Favourites in localStorage only: every product reviewed keeps them per user, and the map's
  "desktop and phone share one model" requires a server-side preference store.
- Discord-style opt-in channel lists: new features must appear in the role default
  automatically, or members never find them.

[linear-fav]: https://linear.app/docs/favorites
[linear-sidebar]: https://linear.app/changelog/2024-12-18-personalized-sidebar
[linear-nav]: https://linear.app/enablement/guide/navigating-linear
[linear-search]: https://linear.app/docs/search
[linear-2020]: https://linear.app/changelog/2020-08-13-linear-preview-view-options
[notion-sidebar]: https://www.notion.com/help/sidebar
[atl-best]: https://support.atlassian.com/navigation/docs/navigation-best-practices/
[atl-new-nav]: https://support.atlassian.com/navigation/docs/what-is-the-new-navigation-in-jira/
[slack-sections]: https://slack.com/intl/en-gb/help/articles/360043207674-Organise-your-sidebar-with-customised-sections
[slack-prefs]: https://slack.com/intl/en-gb/help/articles/212596808-Adjust-your-sidebar-preferences
[grafana-bm]: https://grafana.com/docs/grafana-cloud/whats-new/2024-08-23-navigation-bookmarks
[grafana-prefs]: https://grafana.com/docs/grafana/latest/developers/http_api/preferences/
[grafana-103]: https://grafana.com/docs/grafana/latest/whatsnew/whats-new-in-v10-3/
[discord-fav]: https://support.discord.com/hc/en-us/community/posts/4403096835223-User-Channel-Folders-Favorites
[discord-onb]: https://support.discord.com/hc/en-us/articles/11074987197975-Community-Onboarding-FAQ
[discord-showall]: https://support.discord.com/hc/en-us/community/posts/19283634454551-Enable-Show-All-Channels-on-server-by-default
[vsc-ui]: https://code.visualstudio.com/docs/getstarted/userinterface
[vsc-layout]: https://code.visualstudio.com/docs/configure/custom-layout
[vsc-tips]: https://code.visualstudio.com/docs/getstarted/tips-and-tricks
[gh-palette]: https://docs.github.com/en/get-started/accessibility/github-command-palette
[ff-frecency]: https://firefox-source-docs.mozilla.org/browser/urlbar/ranking.html
[findlater]: https://www.cs.ubc.ca/labs/edapt/papers/findlater2004.pdf
[nng-cust]: https://www.nngroup.com/articles/customization-personalization/
[nng-cust-report]: https://www.nngroup.com/reports/customization-features/
[nng-flex]: https://www.nngroup.com/articles/flexibility-efficiency-heuristic/
[hig-tabs]: https://developer.apple.com/design/human-interface-guidelines/tab-bars
