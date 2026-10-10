// @ts-check
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
    const tabEls = Array.from(document.querySelectorAll('.mobile-tab[data-tab-path]'));

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
                const isMatch =
                    linkPath === '/'
                        ? here === '/'
                        : here === linkPath || here.indexOf(`${linkPath}/`) === 0;
                if (isMatch && linkPath.length > activeLen) {
                    activeLink = link;
                    activeLen = linkPath.length;
                }
            });
    }
    const currentLink = /** @type {HTMLAnchorElement | null} */ (activeLink);
    const activeGroup = currentLink ? currentLink.closest('.nav-group') : null;
    if (currentLink) {
        currentLink.classList.add('is-active');
        currentLink.setAttribute('aria-current', 'page');
        if (activeGroup) activeGroup.classList.add('is-active');
    }

    const groups = sidebarEl ? Array.from(sidebarEl.querySelectorAll('.nav-group')) : [];
    /** @type {Map<Element, boolean>} */
    const groupOpen = new Map();

    /**
     * Shows or hides a group's links and mirrors the state on its head.
     *
     * @param {Element} group the `.nav-group`
     * @param {boolean} open whether the links are shown
     */
    const renderGroup = function (group, open) {
        const head = group.querySelector('.nav-group-head');
        const links = group.querySelector('.nav-group-links');
        if (head) head.setAttribute('aria-expanded', String(open));
        if (links instanceof HTMLElement) links.hidden = !open;
    };

    /**
     * Sets every group to its default for the current device class: on a phone only the group
     * holding the current page is open (accordion), elsewhere every group is open unless the
     * member collapsed it before.
     */
    const initGroups = function () {
        groups.forEach((group) => {
            const key = group.getAttribute('data-group-key');
            let open;
            if (phone.matches) {
                open = group === activeGroup;
            } else {
                open = group === activeGroup || readState(`krt.sidebar.${key}`) !== 'closed';
            }
            groupOpen.set(group, open);
            if (!query) renderGroup(group, open);
        });
    };

    groups.forEach((group) => {
        const count = group.querySelector('.nav-group-count');
        if (count) count.textContent = String(group.querySelectorAll('.nav-link').length);
        const head = group.querySelector('.nav-group-head');
        if (!head) return;
        head.addEventListener('click', () => {
            if (query) return;
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
    initGroups();
    if (typeof phone.addEventListener === 'function') {
        phone.addEventListener('change', initGroups);
    }

    /** Applies the admin mode and the menu filter to the list. */
    const renderList = function () {
        if (!sidebarEl) return;
        const filtering = query.length > 0;
        sidebarEl.classList.toggle('is-filtering', filtering);
        sidebarEl.classList.toggle('is-admin-mode', adminMode);
        if (adminBarEl instanceof HTMLElement) adminBarEl.hidden = filtering || !adminMode;
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
            const groupHit = textOf(group.querySelector('.nav-group-label'))
                .toLowerCase()
                .includes(query);
            let hits = 0;
            links.forEach((link) => {
                const hit = groupHit || textOf(link).toLowerCase().includes(query);
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

    const markActiveTab = function () {
        const here = window.location.pathname;
        /** @type {Element | null} */
        let best = null;
        let bestLen = -1;
        tabEls.forEach((tab) => {
            const path = tab.getAttribute('data-tab-path') || '';
            if ((here === path || here.indexOf(`${path}/`) === 0) && path.length > bestLen) {
                best = tab;
                bestLen = path.length;
            }
        });
        return best;
    };
    const activeTab = /** @type {Element | null} */ (markActiveTab());

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
        if (!sidebarEl) return;
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

    window.krtNav = {
        open(focus) {
            openSidebar(focus || null);
        },
        close() {
            closeSidebar(false);
        },
    };

    if (sidebarEl && adminModeEl) {
        adminMode =
            window.location.pathname.indexOf('/admin/') === 0 ||
            (!!currentLink && adminModeEl.contains(currentLink));
    }
    if (currentLink && personalEl && personalEl.contains(currentLink)) {
        setPersonalOpen(true);
    }
    renderList();

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
            query = filterEl.value.trim().toLowerCase();
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
            if (window.krtPalette) window.krtPalette.open(text);
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

    if (window.location.pathname.indexOf('/admin/') === 0) {
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
