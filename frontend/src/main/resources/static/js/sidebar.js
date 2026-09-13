/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Sidebar / page-chrome behaviour module, extracted verbatim from the former inline script of
 * fragments/sidebar.html (ADR-0069, follow-up to #924). Loaded on every page via the sidebar
 * fragment; the whole body runs on DOMContentLoaded.
 *
 * Wires the mobile hamburger + overlay open/close, the collapsible sidebar sections (active-page
 * detection plus localStorage-persisted open state), the /admin/* admin-mode body class + header
 * chip, the fixed-footer relocation and --krt-footer-height measurement, UTC-timestamp localisation
 * (also re-run on krt:swapped so fragment-swapped rows are formatted), and the auto-submitting
 * squadron switcher / filter forms.
 */

document.addEventListener('DOMContentLoaded', function () {
    // Direct listener on the hamburger button. Clicks on its inner
    // <span> bars bubble up to the button, so a single listener here
    // covers them without scanning the parent chain on every click.
    const hamburgerEl = document.getElementById('hamburger');
    if (hamburgerEl) {
        hamburgerEl.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            const sidebarEl = document.getElementById('sidebar');
            const overlayEl = document.getElementById('sidebar-overlay');
            if (sidebarEl) sidebarEl.classList.add('open');
            if (overlayEl) overlayEl.classList.add('visible');
        });
    }

    // Sidebar sections: mark the active page's link + its section, then
    // restore each section's open/closed state from localStorage. The drawer
    // is re-rendered on every server round-trip, so a <details> would
    // otherwise reopen collapsed on every navigation; persisting the state
    // (and force-opening the active section) keeps the user's context. All
    // localStorage access is guarded so privacy modes that throw on access
    // degrade to "always collapsed" instead of breaking the drawer.
    (function initSidebarSections() {
        const here = window.location.pathname;
        let activeLink = null;
        let activeLen = -1;
        document.querySelectorAll('.sidebar-links a').forEach(function (link) {
            const href = link.getAttribute('href');
            if (!href || href.charAt(0) !== '/') return;
            const linkPath = href.split('?')[0].split('#')[0];
            const isMatch =
                linkPath === '/'
                    ? here === '/'
                    : here === linkPath || here.indexOf(linkPath + '/') === 0;
            // Most specific (longest) matching path wins, so /materials/overview
            // beats /materials on the overview page.
            if (isMatch && linkPath.length > activeLen) {
                activeLink = link;
                activeLen = linkPath.length;
            }
        });
        let activeGroup = null;
        if (activeLink) {
            activeLink.classList.add('sidebar-link-active');
            activeLink.setAttribute('aria-current', 'page');
            activeGroup = activeLink.closest('.sidebar-group');
            if (activeGroup) activeGroup.classList.add('sidebar-group-active');
        }
        const readState = function (storageKey) {
            try {
                return localStorage.getItem(storageKey);
            } catch (_e) {
                return null;
            }
        };
        const writeState = function (storageKey, value) {
            try {
                localStorage.setItem(storageKey, value);
            } catch (_e) {
                /* storage unavailable */
            }
        };
        document.querySelectorAll('.sidebar-group').forEach(function (group) {
            const groupKey = group.getAttribute('data-group-key');
            if (group === activeGroup) {
                group.open = true;
            } else if (groupKey) {
                const stored = readState('krt.sidebar.' + groupKey);
                if (stored === 'open') group.open = true;
                else if (stored === 'closed') group.open = false;
            }
            if (groupKey) {
                group.addEventListener('toggle', function () {
                    writeState('krt.sidebar.' + groupKey, group.open ? 'open' : 'closed');
                });
            }
        });
    })();

    // Admin-mode visual marker: every page under /admin/* gets a
    // `.admin-mode` body class plus a small "ADMIN" chip injected
    // into the page header. Pure UX cue — does NOT grant any
    // permission, the backend still enforces hasRole('ADMIN').
    if (window.location.pathname.indexOf('/admin/') === 0) {
        document.body.classList.add('admin-mode');
        const navEl = document.querySelector('header > nav');
        if (navEl && !document.querySelector('.admin-mode-chip')) {
            const chip = document.createElement('span');
            chip.className = 'admin-mode-chip';
            chip.setAttribute('aria-label', 'Administration');
            chip.textContent = 'ADMIN';
            navEl.appendChild(chip);
        }
    }

    // Relocate the global footer to the bottom of <body>. The footer
    // fragment is included from this sidebar fragment, which Thymeleaf
    // renders near the top of <body> — before <header> and <main>.
    //
    // This is a TIDY-UP, not the layout mechanism, and it stopped being
    // the latter twice over. Above 768px the footer is
    // `position: fixed; bottom: 0`, so DOM order never mattered there.
    // On the phone class it is `position: static` and in flow, where DOM
    // order WOULD matter — so `styles.css` gives it `order: 999` inside a
    // flex-column body, which places it correctly at first paint. Relying
    // on this handler instead would paint the legal links above the header
    // until it runs, and leave them there if anything earlier in it throws.
    //
    // What the move still buys: reading order for screen readers, which get
    // the footer after the main content as web convention expects.
    const footerEl = document.querySelector('body > .krt-footer');
    if (footerEl && footerEl !== document.body.lastElementChild) {
        document.body.appendChild(footerEl);
    }

    // Publish how much of the viewport bottom the footer COVERS as a
    // CSS custom property (--krt-footer-height), so <main> can reserve
    // exactly that much padding-bottom — see the `main` rule in
    // styles.css — and so the half-dozen max-height / bottom calcs that
    // read it (materialboerse, materials-overview, promotion, org-chart,
    // mission- and operation-detail) stay off the footer too.
    // Footer height varies with viewport width (link wrap, column
    // stacking on <=768px, font load) and a static `padding-bottom`
    // value either over-reserves space on wide screens or under-
    // reserves and clips the last content line behind the fixed
    // footer. Re-measuring on resize + ResizeObserver covers every
    // reflow path without coupling to specific breakpoints.
    //
    // COVERS, not "is tall": on the phone class (<=768px) the footer is
    // `position: static` and scrolls with the page (owner decision
    // 2026-09-13), so it covers nothing and every consumer must reserve
    // ZERO. Reading the computed position rather than matching the
    // breakpoint here keeps the two in step: the media query stays the
    // single place that decides when the footer stops being pinned, and
    // this code cannot drift out from under it. The `resize` listener is
    // what re-evaluates it when a device is rotated across 768px.
    //
    // COALESCED, and the `position` read is cached against the breakpoint.
    //
    // This runs on `resize` AND on a ResizeObserver, and iOS Safari — the platform the installable
    // app targets — fires `resize` repeatedly while the address bar collapses during a scroll. An
    // uncoalesced handler that calls getComputedStyle and then reads offsetHeight forces two
    // synchronous layout flushes per event and then invalidates a custom property the whole
    // document depends on. rAF with a pending flag collapses a burst into one measurement per
    // frame; the media query listener keeps the `position` read out of the hot path entirely, since
    // the breakpoint is the only thing that can change the answer.
    if (footerEl) {
        const phone = window.matchMedia('(max-width: 768px)');
        let covers = !phone.matches;
        let pending = false;
        const measure = () => {
            pending = false;
            document.documentElement.style.setProperty(
                '--krt-footer-height',
                (covers ? footerEl.offsetHeight : 0) + 'px',
            );
        };
        const applyFooterHeight = () => {
            if (pending) return;
            pending = true;
            window.requestAnimationFrame(measure);
        };
        const onBreakpoint = () => {
            // Only here does the computed position need reading — the media query is what decides
            // whether the footer is pinned, so this stays the single source and cannot drift.
            covers = window.getComputedStyle(footerEl).position === 'fixed';
            applyFooterHeight();
        };
        onBreakpoint();
        if (typeof phone.addEventListener === 'function') {
            phone.addEventListener('change', onBreakpoint);
        }
        window.addEventListener('resize', applyFooterHeight);
        if (typeof ResizeObserver !== 'undefined') {
            new ResizeObserver(applyFooterHeight).observe(footerEl);
        }
    }

    const sidebarEl = document.getElementById('sidebar');
    const overlayEl = document.getElementById('sidebar-overlay');
    const closeBtn = document.getElementById('close-sidebar');

    const closeSidebar = function (e) {
        if (e && e.currentTarget && e.currentTarget.tagName !== 'A') {
            e.preventDefault();
            e.stopPropagation();
        }
        if (sidebarEl) sidebarEl.classList.remove('open');
        if (overlayEl) overlayEl.classList.remove('visible');
    };

    if (closeBtn) closeBtn.addEventListener('click', closeSidebar);
    if (overlayEl) {
        overlayEl.addEventListener('click', closeSidebar);
        overlayEl.addEventListener('touchstart', closeSidebar, { passive: false });
    }

    document.querySelectorAll('.sidebar-links a, .sidebar-sublinks a').forEach((link) => {
        link.addEventListener('click', closeSidebar);
    });

    // Localise UTC timestamps. Extracted into a function and also re-run on
    // `krt:swapped` so fragment-swapped content (krtFetch.swap) is formatted too —
    // a one-shot DOMContentLoaded pass would leave swapped-in rows raw (#573).
    function formatUtcTimes(root) {
        (root || document).querySelectorAll('.utc-time').forEach((el) => {
            const text = el.textContent.trim();
            if (text && (text.endsWith('Z') || text.includes('T'))) {
                let dateStr = text;
                if (!dateStr.endsWith('Z')) dateStr += 'Z';
                const date = new Date(dateStr);
                if (!isNaN(date)) {
                    el.textContent = new Intl.DateTimeFormat(undefined, {
                        year: 'numeric',
                        month: '2-digit',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                        timeZone: 'Europe/Berlin',
                    }).format(date);
                }
            }
        });
    }
    formatUtcTimes(document);
    document.addEventListener('krt:swapped', function (e) {
        formatUtcTimes(e && e.detail ? e.detail.container : document);
    });

    // Auto-submit the admin-only squadron switcher when the dropdown
    // selection changes - no separate submit click required. The
    // <noscript> button stays as the no-JS fallback.
    const switcherForm = document.getElementById('squadron-switcher-form');
    if (switcherForm) {
        const switcherSelect = document.getElementById('squadron-switcher-select');
        if (switcherSelect) {
            switcherSelect.addEventListener('change', function () {
                switcherForm.submit();
            });
        }
    }

    // Generic Auto-Submit for filter forms
    const filterForm = document.getElementById('filter-form');
    if (filterForm) {
        const filterInputs = filterForm.querySelectorAll('input, select');
        filterInputs.forEach((input) => {
            input.addEventListener('change', function () {
                filterForm.submit();
            });
        });

        // Hide submit button for Non-JS Fallback
        const submitBtn = filterForm.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.style.display = 'none';
        }
    }
});
