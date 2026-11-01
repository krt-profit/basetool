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

import { krtChrome } from '/js/kernel/krt-chrome.js';
import { krtSearchFold } from '/js/kernel/krt-search.js';
import { NAV_EDIT_EVENT, NAV_RENDERED_EVENT } from '/js/kernel/nav-preferences.js';

document.addEventListener('DOMContentLoaded', () => {
    const phone = window.matchMedia('(max-width: 768px)');
    const sidebarEl = document.getElementById('sidebar');
    const overlayEl = document.getElementById('sidebar-overlay');
    const hamburgerEl = document.getElementById('hamburger');
    const menuTabEl = document.getElementById('mobile-menu-toggle');
    const closeBtn = document.getElementById('close-sidebar');
    const filterEl = /** @type {HTMLInputElement | null} */ (document.getElementById('nav-filter'));
    const adminBarEl = sidebarEl ? sidebarEl.querySelector('.nav-admin-bar') : null;
    const adminModeEl = sidebarEl ? sidebarEl.querySelector('.nav-mode-admin') : null;
    const filterEmptyEl = sidebarEl ? sidebarEl.querySelector('.nav-filter-empty') : null;
    const toPaletteEl = sidebarEl ? sidebarEl.querySelector('.nav-filter-to-palette') : null;
    const personalEl = document.getElementById('nav-personal');
    const userToggleEl = sidebarEl ? sidebarEl.querySelector('.nav-user-toggle') : null;
    const langBtnEl = sidebarEl ? sidebarEl.querySelector('.nav-lang-btn') : null;
    const langMenuEl = document.getElementById('nav-lang-menu');
    const pinnedQuery = window.matchMedia('(min-width: 1280px)');

    /** @type {HTMLElement | null} */
    let returnFocusEl = null;
    let adminMode = false;
    let query = '';

    /**
     * Reads one persisted drawer value, tolerating storage that is blocked or unavailable.
     *
     * @param {string} storageKey the localStorage key
     * @returns {string | null} the stored value, or null when absent or unreadable
     */
    const readState = function (storageKey) {
        try {
            return localStorage.getItem(storageKey);
        } catch (_e) {
            return null;
        }
    };

    /**
     * Persists one drawer value, ignoring storage that is blocked or full.
     *
     * @param {string} storageKey the localStorage key
     * @param {string} value the value to store
     */
    const writeState = function (storageKey, value) {
        try {
            localStorage.setItem(storageKey, value);
        } catch (_e) {}
    };

    /**
     * The visible text of an element with its whitespace collapsed.
     *
     * @param {Element | null} el the element
     * @returns {string} its text, or an empty string
     */
    const textOf = function (el) {
        return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : '';
    };

    /** @type {HTMLAnchorElement | null} */
    let currentLink = null;
    /** @type {Element | null} */
    let activeGroup = null;

    /** Marks the link that opens the page being shown and the group that holds it. */
    const markActive = function () {
        /** @type {HTMLAnchorElement | null} */
        let activeLink = null;
        if (sidebarEl) {
            const here = window.location.pathname;
            let activeLen = -1;
            sidebarEl
                .querySelectorAll(
                    '.nav-top-link, .nav-group:not(.nav-group-filter-only) .nav-link, .nav-personal .nav-link',
                )
                .forEach((link) => {
                    if (!(link instanceof HTMLAnchorElement)) return;
                    const href = link.getAttribute('href');
                    if (!href || href.charAt(0) !== '/') return;
                    const linkPath = href.split('?')[0].split('#')[0];
                    const areaPaths = (link.getAttribute('data-nav-paths') || '')
                        .split(' ')
                        .filter((path) => path.length > 0);
                    [linkPath, ...areaPaths].forEach((path) => {
                        const isMatch =
                            path === '/'
                                ? here === '/'
                                : here === path || here.indexOf(`${path}/`) === 0;
                        if (isMatch && path.length > activeLen) {
                            activeLink = link;
                            activeLen = path.length;
                        }
                    });
                });
        }
        currentLink = /** @type {HTMLAnchorElement | null} */ (activeLink);
        activeGroup = currentLink ? currentLink.closest('.nav-group') : null;
        if (currentLink) {
            currentLink.classList.add('is-active');
            currentLink.setAttribute('aria-current', 'page');
            if (activeGroup) activeGroup.classList.add('is-active');
        }
    };

    /** @type {Element[]} */
    let groups = [];
    /** @type {Map<Element, boolean>} */
    const groupOpen = new Map();

    /**
     * Whether "Anpassen" is on, which shows every group's links.
     *
     * @returns {boolean} true while the sidebar is in edit mode
     */
    const editing = function () {
        return !!sidebarEl && sidebarEl.classList.contains('is-editing');
    };

    /**
     * Shows or hides a group's links and mirrors the state on its head.
     *
     * @param {Element} group the `.nav-group`
     * @param {boolean} open whether the links are shown
     */
    const renderGroup = function (group, open) {
        const shown = open || editing();
        const head = group.querySelector('.nav-group-head');
        const links = group.querySelector('.nav-group-links');
        if (head) head.setAttribute('aria-expanded', String(shown));
        if (links instanceof HTMLElement) links.hidden = !shown;
    };

    /**
     * Sets every group to its default for the current device class: on a phone only the group
     * holding the current page is open (accordion), elsewhere every group is open unless the
     * member collapsed it before. Groups that were open before a re-render keep their state.
     *
     * @param {Map<string, boolean>} [previous] the open state by group key before a re-render
     */
    const initGroups = function (previous) {
        groups.forEach((group) => {
            const key = group.getAttribute('data-group-key');
            const collapsedByDefault = group.getAttribute('data-collapsed') === 'true';
            let open;
            if (previous && key !== null && previous.has(key)) {
                open = group === activeGroup || !!previous.get(key);
            } else if (phone.matches) {
                open = group === activeGroup;
            } else {
                const stored = readState(`krt.sidebar.${key}`);
                open =
                    group === activeGroup ||
                    (stored === null ? !collapsedByDefault : stored !== 'closed');
            }
            groupOpen.set(group, open);
            if (!query) renderGroup(group, open);
        });
    };

    /**
     * Collects the groups of the sidebar, counts their links and binds their heads.
     *
     * @param {Map<string, boolean>} [previous] the open state by group key before a re-render
     */
    const bindGroups = function (previous) {
        groups = sidebarEl ? Array.from(sidebarEl.querySelectorAll('.nav-group')) : [];
        groupOpen.clear();
        groups.forEach((group) => {
            const count = group.querySelector('.nav-group-count');
            if (count) count.textContent = String(group.querySelectorAll('.nav-link').length);
            const head = group.querySelector('.nav-group-head');
            if (!head) return;
            head.addEventListener('click', () => {
                if (query || editing()) return;
                const open = !groupOpen.get(group);
                if (open && phone.matches) {
                    const mode = group.closest('.nav-mode');
                    groups.forEach((other) => {
                        if (other !== group && other.closest('.nav-mode') === mode) {
                            groupOpen.set(other, false);
                            renderGroup(other, false);
                        }
                    });
                }
                groupOpen.set(group, open);
                renderGroup(group, open);
                if (!phone.matches) {
                    writeState(
                        `krt.sidebar.${group.getAttribute('data-group-key')}`,
                        open ? 'open' : 'closed',
                    );
                }
            });
        });
        initGroups(previous);
    };

    markActive();
    bindGroups();
    if (typeof phone.addEventListener === 'function') {
        phone.addEventListener('change', () => initGroups());
    }

    /** Applies the admin mode and the menu filter to the list. */
    const renderList = function () {
        if (!sidebarEl) return;
        const filtering = query.length > 0;
        sidebarEl.classList.toggle('is-filtering', filtering);
        sidebarEl.classList.toggle('is-admin-mode', adminMode);
        if (adminBarEl instanceof HTMLElement) adminBarEl.hidden = filtering || !adminMode;
        document.querySelectorAll('.mobile-tab-slots[data-tabbar-mode]').forEach((slots) => {
            if (slots instanceof HTMLElement) {
                slots.hidden = (slots.dataset.tabbarMode === 'admin') !== adminMode;
            }
        });
        let anyHit = false;
        groups.forEach((group) => {
            const filterOnly = group.classList.contains('nav-group-filter-only');
            const links = Array.from(group.querySelectorAll('.nav-link'));
            if (!(group instanceof HTMLElement)) return;
            if (!filtering) {
                group.hidden = filterOnly;
                links.forEach((link) => {
                    if (link instanceof HTMLElement) link.hidden = false;
                });
                renderGroup(group, !!groupOpen.get(group));
                return;
            }
            const groupHit = krtSearchFold(
                textOf(group.querySelector('.nav-group-label')),
            ).includes(query);
            let hits = 0;
            links.forEach((link) => {
                const terms = `${textOf(link)} ${link.getAttribute('data-nav-keywords') || ''}`;
                const hit = groupHit || krtSearchFold(terms).includes(query);
                if (link instanceof HTMLElement) link.hidden = !hit;
                if (hit) hits++;
            });
            group.hidden = hits === 0;
            if (hits > 0) {
                anyHit = true;
                renderGroup(group, true);
            }
        });
        if (filterEmptyEl instanceof HTMLElement) filterEmptyEl.hidden = !filtering || anyHit;
        if (toPaletteEl instanceof HTMLElement) toPaletteEl.hidden = !filtering;
    };

    /**
     * Switches the list between the main groups and the administration groups.
     *
     * @param {boolean} on whether the administration groups are shown
     */
    const setAdminMode = function (on) {
        adminMode = on && !!adminModeEl;
        query = '';
        if (filterEl) filterEl.value = '';
        setPersonalOpen(false);
        renderList();
        document.dispatchEvent(
            new CustomEvent('krt:nav-admin-mode', { detail: { admin: adminMode } }),
        );
    };

    /**
     * Shows or hides the personal panel above the user row.
     *
     * @param {boolean} open whether the panel is shown
     */
    const setPersonalOpen = function (open) {
        if (personalEl) personalEl.hidden = !open;
        if (userToggleEl) userToggleEl.setAttribute('aria-expanded', String(open));
    };

    /**
     * Shows or hides the language menu.
     *
     * @param {boolean} open whether the menu is shown
     */
    const setLangOpen = function (open) {
        if (langMenuEl) langMenuEl.hidden = !open;
        if (langBtnEl) langBtnEl.setAttribute('aria-expanded', String(open));
    };

    /**
     * Finds the tab that marks the area of the page being shown: the one whose own path, or one of
     * the paths of the tabbed page it opens, is the longest prefix of the current path.
     *
     * @returns {Element | null} the tab, or null when no tab covers the page
     */
    const findActiveTab = function () {
        const here = window.location.pathname;
        /** @type {Element | null} */
        let best = null;
        let bestLen = -1;
        document.querySelectorAll('.mobile-tab[data-tab-path]').forEach((tab) => {
            const paths = [
                tab.getAttribute('data-tab-path') || '',
                ...(tab.getAttribute('data-nav-paths') || '').split(' '),
            ].filter((path) => path.length > 0);
            paths.forEach((path) => {
                if ((here === path || here.indexOf(`${path}/`) === 0) && path.length > bestLen) {
                    best = tab;
                    bestLen = path.length;
                }
            });
        });
        return best;
    };
    /** @type {Element | null} */
    let activeTab = findActiveTab();
    /**
     * Mirrors the drawer state on its openers and on the tab bar.
     *
     * @param {boolean} open whether the drawer is open
     */
    const setOpeners = function (open) {
        if (hamburgerEl) hamburgerEl.setAttribute('aria-expanded', String(open));
        if (menuTabEl) {
            menuTabEl.setAttribute('aria-expanded', String(open));
            menuTabEl.classList.toggle('is-active', open);
        }
        if (activeTab) activeTab.classList.toggle('is-active', !open);
    };
    setOpeners(false);

    /**
     * Opens the drawer and moves focus into it.
     *
     * @param {HTMLElement | null} [focus] the element to focus; the menu filter when omitted
     */
    const openSidebar = function (focus) {
        if (!sidebarEl) return;
        if (isDocked()) {
            const docked = focus || filterEl;
            if (docked) docked.focus({ preventScroll: true });
            return;
        }
        const active = document.activeElement;
        returnFocusEl = active instanceof HTMLElement ? active : null;
        sidebarEl.removeAttribute('inert');
        sidebarEl.classList.add('open');
        if (overlayEl) overlayEl.classList.add('visible');
        setOpeners(true);
        const target = focus || (phone.matches ? null : filterEl);
        if (target) target.focus({ preventScroll: true });
    };

    /**
     * Closes the drawer.
     *
     * @param {boolean} restoreFocus whether focus returns to the control that opened it
     */
    const closeSidebar = function (restoreFocus) {
        if (!sidebarEl || isDocked()) return;
        sidebarEl.classList.remove('open');
        sidebarEl.setAttribute('inert', '');
        if (overlayEl) overlayEl.classList.remove('visible');
        setOpeners(false);
        setLangOpen(false);
        if (restoreFocus && returnFocusEl && returnFocusEl.isConnected) {
            returnFocusEl.focus({ preventScroll: true });
        }
        returnFocusEl = null;
    };

    const isOpen = function () {
        return !!sidebarEl && sidebarEl.classList.contains('open');
    };

    /**
     * Whether the labelled sidebar is fixed beside the page: the pinned mode from 1280 px.
     *
     * @returns {boolean} true while the sidebar is docked and always reachable
     */
    const isDocked = function () {
        return document.body.dataset.navMode === 'pinned' && pinnedQuery.matches;
    };

    /**
     * Makes the sidebar reachable while docked and inert again when it falls back to the drawer.
     */
    const syncDocked = function () {
        if (!sidebarEl) return;
        if (isDocked()) {
            sidebarEl.classList.remove('open');
            if (overlayEl) overlayEl.classList.remove('visible');
            setOpeners(false);
            sidebarEl.removeAttribute('inert');
        } else if (!isOpen()) {
            sidebarEl.setAttribute('inert', '');
        }
    };

    krtChrome.nav = {
        open(focus) {
            openSidebar(focus || null);
        },
        close() {
            closeSidebar(false);
        },
        openAdmin() {
            setAdminMode(true);
            const list = document.getElementById('nav-list');
            if (list) list.scrollTop = 0;
            openSidebar(null);
        },
        isAdminMode() {
            return adminMode;
        },
        setAdminMode(on) {
            setAdminMode(on);
            const list = document.getElementById('nav-list');
            if (list) list.scrollTop = 0;
        },
    };
    syncDocked();
    if (typeof pinnedQuery.addEventListener === 'function') {
        pinnedQuery.addEventListener('change', syncDocked);
    }
    document.addEventListener('krt:nav-mode-changed', syncDocked);

    const shownLink = /** @type {HTMLAnchorElement | null} */ (currentLink);
    if (sidebarEl && adminModeEl) {
        adminMode =
            window.location.pathname === '/admin' ||
            window.location.pathname.indexOf('/admin/') === 0 ||
            (!!shownLink && adminModeEl.contains(shownLink));
    }
    if (shownLink && personalEl && personalEl.contains(shownLink)) {
        setPersonalOpen(true);
    }
    renderList();

    document.addEventListener(NAV_RENDERED_EVENT, () => {
        /** @type {Map<string, boolean>} */
        const previous = new Map();
        groups.forEach((group) => {
            const key = group.getAttribute('data-group-key');
            if (key !== null) previous.set(key, !!groupOpen.get(group));
        });
        markActive();
        bindGroups(previous);
        renderList();
        activeTab = findActiveTab();
        setOpeners(isOpen());
    });
    document.addEventListener(NAV_EDIT_EVENT, renderList);

    [hamburgerEl, menuTabEl].forEach((opener) => {
        if (!opener) return;
        opener.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            if (isOpen()) {
                closeSidebar(true);
            } else {
                openSidebar();
            }
        });
    });

    if (closeBtn) {
        closeBtn.addEventListener('click', (e) => {
            e.preventDefault();
            closeSidebar(true);
        });
    }
    if (overlayEl) {
        overlayEl.addEventListener('click', () => {
            closeSidebar(true);
        });
        overlayEl.addEventListener(
            'touchstart',
            (e) => {
                e.preventDefault();
                closeSidebar(true);
            },
            { passive: false },
        );
    }

    if (sidebarEl) {
        sidebarEl.addEventListener('click', (e) => {
            const target = e.target instanceof Element ? e.target : null;
            const link = target ? target.closest('a[href]') : null;
            if (link && !link.closest('.nav-lang-menu')) {
                closeSidebar(false);
                return;
            }
            if (langMenuEl && !langMenuEl.hidden && target && !target.closest('.nav-lang')) {
                setLangOpen(false);
            }
        });
    }

    if (filterEl) {
        filterEl.addEventListener('input', () => {
            query = krtSearchFold(filterEl.value.trim());
            renderList();
        });
        filterEl.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                const first = sidebarEl
                    ? sidebarEl.querySelector(
                          '.nav-mode .nav-group:not([hidden]) .nav-link:not([hidden])',
                      )
                    : null;
                if (query && first instanceof HTMLAnchorElement) first.click();
            }
        });
    }

    if (toPaletteEl) {
        toPaletteEl.addEventListener('click', () => {
            const text = filterEl ? filterEl.value.trim() : '';
            closeSidebar(false);
            if (krtChrome.palette) krtChrome.palette.open(text);
        });
    }

    if (sidebarEl) {
        sidebarEl.querySelectorAll('.nav-admin-entry, .nav-admin-back').forEach((btn) => {
            btn.addEventListener('click', () => {
                setAdminMode(!adminMode);
                const list = document.getElementById('nav-list');
                if (list) list.scrollTop = 0;
            });
        });
    }

    if (userToggleEl) {
        userToggleEl.addEventListener('click', () => {
            setPersonalOpen(!!personalEl && personalEl.hidden === true);
        });
    }

    if (langBtnEl) {
        langBtnEl.addEventListener('click', (e) => {
            e.stopPropagation();
            setLangOpen(!!langMenuEl && langMenuEl.hidden === true);
        });
    }

    document.addEventListener('keydown', (e) => {
        if (e.key !== 'Escape' || e.defaultPrevented) return;
        if (langMenuEl && !langMenuEl.hidden) {
            setLangOpen(false);
            if (langBtnEl instanceof HTMLElement) langBtnEl.focus();
            e.preventDefault();
            return;
        }
        if (isOpen()) {
            e.preventDefault();
            closeSidebar(true);
        }
    });

    if (
        window.location.pathname === '/admin' ||
        window.location.pathname.indexOf('/admin/') === 0
    ) {
        document.body.classList.add('admin-mode');
    }

    const footerEl = document.querySelector('body > .krt-footer');
    if (footerEl && footerEl !== document.body.lastElementChild) {
        document.body.appendChild(footerEl);
    }

    if (footerEl instanceof HTMLElement) {
        let covers = false;
        let pending = false;
        const measure = () => {
            pending = false;
            document.documentElement.style.setProperty(
                '--krt-footer-height',
                `${covers ? footerEl.offsetHeight : 0}px`,
            );
        };
        const applyFooterHeight = () => {
            if (pending) return;
            pending = true;
            window.requestAnimationFrame(measure);
        };
        const onBreakpoint = () => {
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

    /**
     * Rewrites every `.utc-time` element below `root` from its ISO UTC text to Berlin local time.
     *
     * @param {ParentNode} root the subtree to format
     */
    function formatUtcTimes(root) {
        root.querySelectorAll('.utc-time').forEach((el) => {
            const text = (el.textContent || '').trim();
            if (text && (text.endsWith('Z') || text.includes('T'))) {
                let dateStr = text;
                if (!dateStr.endsWith('Z')) dateStr += 'Z';
                const date = new Date(dateStr);
                if (!isNaN(date.getTime())) {
                    el.textContent =
                        el.getAttribute('data-format') === 'short'
                            ? new Intl.DateTimeFormat(undefined, {
                                  weekday: 'short',
                                  month: '2-digit',
                                  day: '2-digit',
                                  hour: '2-digit',
                                  minute: '2-digit',
                                  timeZone: 'Europe/Berlin',
                              }).format(date)
                            : new Intl.DateTimeFormat(undefined, {
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
    document.addEventListener('krt:swapped', (e) => {
        const container = e && e.detail ? e.detail.container : null;
        formatUtcTimes(container instanceof Element ? container : document);
    });

    const switcherForm = document.getElementById('squadron-switcher-form');
    const switcherSelect = document.getElementById('squadron-switcher-select');
    if (switcherForm instanceof HTMLFormElement && switcherSelect) {
        switcherSelect.addEventListener('change', () => {
            switcherForm.submit();
        });
    }
});
