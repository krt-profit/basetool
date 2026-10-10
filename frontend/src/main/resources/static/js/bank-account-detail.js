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

(function () {
    'use strict';

    /** Look-back in days of each preset booking-history segment. */
    const PERIOD_DAYS = { '30d': 30, '90d': 90 };

    /**
     * Binds the in-place pager and range anchors of every `[data-bank-swap]` container: a click on a
     * contained page or range link re-renders only that container's fragment (REQ-FE-002).
     */
    function bindDetailSwaps() {
        if (!window.krtFetch) {
            return;
        }
        document.querySelectorAll('[data-bank-swap][id]').forEach((el) => {
            window.krtFetch.bindSwap({
                container: `#${el.id}`,
                fragmentValue: el.getAttribute('data-bank-swap') || undefined,
                history: el.getAttribute('data-bank-swap-history') === 'true',
            });
        });
    }

    /**
     * Formats a UTC calendar day as the `yyyy-MM-dd` value of a date input.
     *
     * @param {Date} date the instant whose UTC day is formatted
     * @returns {string} the ISO calendar date
     */
    function isoDay(date) {
        return date.toISOString().slice(0, 10);
    }

    /**
     * Builds the booking-history swap URL from the filter form's period and the current page size,
     * always restarting at page 0.
     *
     * @param {HTMLFormElement} form the booking-history filter form
     * @returns {string} the URL of the bookings fragment
     */
    function historySwapUrl(form) {
        const base = form.getAttribute('action') || window.location.pathname;
        const params = new URLSearchParams();
        const fromEl = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="from"]')
        );
        const toEl = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="to"]')
        );
        if (fromEl && fromEl.value) {
            params.set('from', fromEl.value);
        }
        if (toEl && toEl.value) {
            params.set('to', toEl.value);
        }
        let size = new URLSearchParams(window.location.search).get('size');
        if (!size) {
            const hidden = /** @type {HTMLInputElement | null} */ (
                form.querySelector('input[name="size"]')
            );
            size = hidden ? hidden.value : '';
        }
        if (size) {
            params.set('size', size);
        }
        params.set('page', '0');
        return `${base}?${params.toString()}`;
    }

    /**
     * Re-renders the booking history for the filter form's period in place.
     *
     * @param {HTMLFormElement} form the booking-history filter form
     */
    function applyHistoryFilter(form) {
        const container = form.getAttribute('data-results');
        if (!window.krtFetch || !container) {
            return;
        }
        window.krtFetch.swap({
            url: historySwapUrl(form),
            container,
            fragmentValue: form.getAttribute('data-fragment') || undefined,
            history: true,
        });
    }

    /**
     * Applies a booking-history segment: a preset fills the date range and re-renders the history,
     * "Zeitraum" reveals the date fields and waits for a date.
     *
     * @param {HTMLFormElement} form the booking-history filter form
     * @param {string} preset the segment value (`30d`, `90d` or `custom`)
     */
    function applyPreset(form, preset) {
        const range = /** @type {HTMLElement | null} */ (
            form.querySelector('[data-bank-history-range]')
        );
        const days = Object.hasOwn(PERIOD_DAYS, preset)
            ? PERIOD_DAYS[/** @type {'30d' | '90d'} */ (preset)]
            : null;
        if (days === null) {
            if (range) {
                range.hidden = false;
                const first = /** @type {HTMLInputElement | null} */ (
                    range.querySelector('input[type="date"]')
                );
                if (first) {
                    first.focus();
                }
            }
            return;
        }
        if (range) {
            range.hidden = true;
        }
        const now = new Date();
        const start = new Date(now.getTime() - days * 24 * 60 * 60 * 1000);
        const fromEl = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="from"]')
        );
        const toEl = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="to"]')
        );
        if (fromEl) {
            fromEl.value = isoDay(start);
        }
        if (toEl) {
            toEl.value = isoDay(now);
        }
        applyHistoryFilter(form);
    }

    document.addEventListener('submit', (event) => {
        const target = /** @type {Element | null} */ (event.target);
        const form =
            target && target.closest ? target.closest('form[data-bank-history-filter]') : null;
        if (!form || !window.krtFetch) {
            return;
        }
        event.preventDefault();
        applyHistoryFilter(/** @type {HTMLFormElement} */ (form));
    });

    document.addEventListener('change', (event) => {
        const target = /** @type {Element | null} */ (event.target);
        if (!target || !target.closest) {
            return;
        }
        const form = /** @type {HTMLFormElement | null} */ (
            target.closest('form[data-bank-history-filter]')
        );
        if (!form) {
            return;
        }
        if (target.matches('input[name="historyPeriod"]')) {
            applyPreset(form, /** @type {HTMLInputElement} */ (target).value);
            return;
        }
        if (target.matches('input[type="date"]')) {
            applyHistoryFilter(form);
        }
    });

    /**
     * Reads the per-user storage key of a tab bar's last selection (REQ-UI-017).
     *
     * @param {Element} nav the tab bar carrying `data-tab-storage`
     * @returns {string | null} the storage key, or null when the bar persists nothing
     */
    function tabStorageKey(nav) {
        const prefix = nav.getAttribute('data-tab-storage');
        if (!prefix) {
            return null;
        }
        const main = document.querySelector('main[data-user-id]');
        return prefix + (main ? main.getAttribute('data-user-id') : 'unknown');
    }

    /**
     * Reads the persisted tab of a tab bar.
     *
     * @param {Element} nav the tab bar
     * @returns {string | null} the saved tab key, or null
     */
    function readSavedTab(nav) {
        const key = tabStorageKey(nav);
        if (!key) {
            return null;
        }
        try {
            return localStorage.getItem(key);
        } catch {
            return null;
        }
    }

    /**
     * Persists the selected tab of a tab bar.
     *
     * @param {Element} nav the tab bar
     * @param {string} tab the selected tab key
     */
    function writeSavedTab(nav, tab) {
        const key = tabStorageKey(nav);
        if (!key) {
            return;
        }
        try {
            localStorage.setItem(key, tab);
        } catch {}
    }

    /**
     * The tab key the user selected on this page view, or null before any choice.
     *
     * @type {string | null}
     */
    let activeTab = null;

    /**
     * Shows the panel of the active tab and marks its tab selected; an unknown key falls back to the
     * first tab.
     *
     * @param {boolean} [focus] whether the selected tab takes focus
     */
    function applyTabs(focus) {
        const nav = document.querySelector('[data-bank-detail-tabs]');
        if (!nav) {
            return;
        }
        const tabs = Array.from(nav.querySelectorAll('.tab[data-tab]'));
        if (!tabs.length) {
            return;
        }
        const keys = tabs.map((t) => {
            return t.getAttribute('data-tab');
        });
        let key = activeTab !== null ? activeTab : readSavedTab(nav);
        if (key === null || keys.indexOf(key) === -1) {
            key = keys[0];
        }
        tabs.forEach((t) => {
            const on = t.getAttribute('data-tab') === key;
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', on ? 'true' : 'false');
            if (on && focus) {
                /** @type {HTMLElement} */ (t).focus();
            }
        });
        document.querySelectorAll('main [data-tabpanel]').forEach((panel) => {
            /** @type {HTMLElement} */ (panel).hidden = panel.getAttribute('data-tabpanel') !== key;
        });
    }

    /**
     * Selects a tab, persists it and updates the panels.
     *
     * @param {Element} tab the tab element
     * @param {boolean} focus whether the tab takes focus
     */
    function selectTab(tab, focus) {
        const nav = tab.closest('[data-bank-detail-tabs]');
        const key = tab.getAttribute('data-tab');
        if (!nav || !key) {
            return;
        }
        activeTab = key;
        writeSavedTab(nav, key);
        applyTabs(focus);
    }

    document.addEventListener('click', (event) => {
        const target = /** @type {Element | null} */ (event.target);
        const tab =
            target && target.closest
                ? target.closest('[data-bank-detail-tabs] .tab[data-tab]')
                : null;
        if (tab) {
            selectTab(tab, false);
        }
    });

    document.addEventListener('keydown', (event) => {
        if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
            return;
        }
        const target = /** @type {Element | null} */ (event.target);
        const tab =
            target && target.closest
                ? target.closest('[data-bank-detail-tabs] .tab[data-tab]')
                : null;
        const nav = tab ? tab.closest('[data-bank-detail-tabs]') : null;
        if (!tab || !nav) {
            return;
        }
        event.preventDefault();
        const tabs = Array.from(nav.querySelectorAll('.tab[data-tab]'));
        const index = tabs.indexOf(tab);
        const next = (index + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length;
        selectTab(tabs[next], true);
    });

    document.addEventListener('DOMContentLoaded', () => {
        bindDetailSwaps();
        applyTabs(false);
    });

    document.addEventListener('krt:swapped', () => {
        bindDetailSwaps();
        applyTabs(false);
    });
})();
