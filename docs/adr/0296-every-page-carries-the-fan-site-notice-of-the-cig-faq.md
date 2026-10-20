# ADR-0296 — Every page carries the fan site notice of the CIG FAQ

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** spec [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-018)

## Context

REQ-UI-018 followed two CIG documents, the Fan Kit Guidelines and the Fankit Agreement. A legal
review of all current CIG texts (October 2026) found a third one that binds the Basetool: the
*Star Citizen Fankit and Fandom FAQ* (support article 360006895793, last changed 2026-09-02).
Its "Fan Sites" section requires

- a fixed notice, "open, obvious" and not in a smaller font than other notices,
- a link to the official site,
- no CIG brand or in-game entity names in the domain.

The Basetool had a paraphrase of the notice (`landing.notice`) and linked robertsspaceindustries.com
only on the members-only links page. The long Agreement notice stood on four pages, while
item, ship and location names of CIG appear on many more. Twenty manufacturer logos as SVG
redrawings and an `sc.png` shipped in both jars although nothing used them; the Fan Kit
provides these logos only as PNG, so the SVGs were not Fan Kit material.

## Decision

1. **The FAQ notice is quoted verbatim** as `fankit.fansite`, English in every locale bundle, like
   the two existing notices. It replaces `landing.notice` on the public home page.
2. **Every page carries it.** The global footer gets a full-width row with the notice and a link to
   `https://robertsspaceindustries.com/`; on the phone, where the tab bar hides the footer, the
   drawer's legal group carries the same pair. The footer height is measured at runtime, so the
   fixed footer reserves the extra line itself.
3. **The Fan Kit band links the official site** as its fourth element. The band stays on the home
   page, the dashboard and the two legal pages; the logo and both Fan Kit notices do not move into
   the footer, because the band's height in a fixed footer was the reason it left there.
4. **No CIG artwork outside the Fan Kit ships.** The manufacturer SVGs and `sc.png` are deleted, and
   a test fails when an SVG other than the Basetool's own patterns appears under
   `META-INF/resources/images/`.

## Consequences

- The fixed footer is one to two lines taller on desktop and tablet.
- The fan site notice appears twice on the home page (greeting and footer); that is accepted, the
  FAQ asks for visibility, not uniqueness.
- The domain `das-kartell.org` already meets the domain rule.
- The FAQ changes without announcement; REQ-UI-018 records the checked revision so a later review
  can compare against it.
