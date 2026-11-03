# ADR-0332 — The fan site notice lives on the home page, not on every page

- **Status:** Accepted
- **Date:** 2026-11-03
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-018); supersedes
  decision 2 of [ADR-0296](0296-every-page-carries-the-fan-site-notice-of-the-cig-faq.md)

## Context

ADR-0296 put the fan site notice of the CIG *Fankit and Fandom FAQ* (`fankit.fansite`) with a link
to `https://robertsspaceindustries.com/` into a full-width row of the global footer and into the
phone drawer's legal group, so every page carried it. The row wraps to two or three lines on a
desktop screen and makes the `position: fixed` footer, which overlays the content of every page,
noticeably taller — the same cost that moved the Fan Kit band out of the footer earlier.

The FAQ asks for the notice to be "open, obvious", not smaller than other notices, and for a link to
the official site. It does not name a placement. Fan Kit Guidelines §2b, the stricter of the CIG
documents on placement, accepts the home page as a sanctioned surface.

## Decision

1. **The fan site notice and its official site link leave the global footer and the phone drawer.**
   The footer carries the legal links, the meta links and the version only.
2. **The public home page carries the notice** in its greeting, with the official site link, and
   the Fan Kit band keeps its own official site link on the home page, the dashboard and the two
   legal pages. The drawer's official site entry stays as a navigation link.
3. Decisions 1, 3 and 4 of ADR-0296 stand: the notice stays verbatim English in every bundle, the
   band links the official site, and no CIG artwork outside the Fan Kit ships.

## Consequences

- The fixed footer is one row again and costs every page less viewport height, most visibly on
  tablet and narrow desktop widths.
- The notice no longer appears on pages other than the home page. Should CIG read the FAQ as
  requiring it on every page, the footer row returns and REQ-UI-018 is amended first.
