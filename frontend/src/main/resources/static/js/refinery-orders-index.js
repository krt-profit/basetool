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

const REFINERY_SECTIONS = {
    queue: { container: '#refinery-orders-results', fragmentValue: 'results' },
};

(function () {
    'use strict';

    const FILTER_PREF_KEY = 'refinery_orders_filter';
    const VIEWS = ['RUNNING', 'READY', 'COMPLETED', 'ALL'];
    const DEFAULT_VIEW = 'RUNNING';
    const TICK_MS = 30000;
    const MINUTE_MS = 60000;

    const form = /** @type {HTMLFormElement | null} */ (
        document.getElementById('refinery-filter-form')
    );
    const results = document.getElementById('refinery-orders-results');
    const indicator = document.getElementById('refinery-loading-indicator');

    /** @type {number | undefined} */
    let debounceTimer;

    /**
     * The filter form's state as a query string, without empty values.
     *
     * @returns {string} the encoded query, possibly empty
     */
    function queryString() {
        if (!form) return '';
        const params = new URLSearchParams();
        for (const [key, value] of new FormData(form).entries()) {
            if (value !== '') params.append(key, String(value));
        }
        return params.toString();
    }

    /**
     * Re-renders the results for the current filter in place.
     *
     * @param {boolean} fromUser whether a filter change caused the load, which keeps the address bar
     *     in sync; a peer refresh keeps the scroll position instead
     */
    function load(fromUser) {
        if (!results || !window.krtFetch) return;
        const query = queryString();
        window.krtFetch.swap({
            url: `/refinery-orders${query ? `?${query}` : ''}`,
            container: results,
            indicator: indicator || undefined,
            history: fromUser,
            preserveScroll: !fromUser,
        });
    }

    if (results && window.krtLiveSync && typeof window.krtLiveSync.createReceiver === 'function') {
        window.krtLiveSync.createReceiver({
            topic: 'refinery',
            sections: REFINERY_SECTIONS,
            coalesceMs: 1500,
            refresh() {
                load(false);
            },
        });
    }

    /**
     * Formats a duration as the list prints it: "45 min", "3 h 20 min", "2 h", "1 d 4 h".
     *
     * @param {number} minutes the duration in minutes
     * @returns {string} the formatted duration
     */
    function formatDuration(minutes) {
        const total = Math.max(0, Math.floor(minutes));
        const days = Math.floor(total / 1440);
        const hours = Math.floor((total % 1440) / 60);
        const mins = total % 60;
        if (days > 0) return hours > 0 ? `${days} d ${hours} h` : `${days} d`;
        if (hours > 0) return mins > 0 ? `${hours} h ${mins} min` : `${hours} h`;
        return `${mins} min`;
    }

    /**
     * Fills `{0}`, `{1}` of a message pattern.
     *
     * @param {string} pattern the raw message pattern
     * @param {string[]} args the values in placeholder order
     * @returns {string} the filled message
     */
    function fill(pattern, args) {
        return args.reduce((text, arg, i) => {
            return text.split(`{${i}}`).join(arg);
        }, pattern);
    }

    /**
     * Moves the progress text, bar and store action of every open row to the current time; a
     * running row whose end has passed turns ready.
     */
    function tick() {
        if (!results) return;
        const table = results.querySelector('.refinery-table');
        if (!table) return;
        const msgReady = table.getAttribute('data-msg-ready') || '{0}';
        const msgReadyUnknown = table.getAttribute('data-msg-ready-unknown') || '';
        const msgEndsIn = table.getAttribute('data-msg-ends-in') || '';
        const now = Date.now();
        table
            .querySelectorAll('tr[data-state="RUNNING"], tr[data-state="READY"]')
            .forEach((node) => {
                const row = /** @type {HTMLElement} */ (node);
                const endsAt = Number(row.getAttribute('data-ends-at'));
                const startedAt = Number(row.getAttribute('data-started-at'));
                const text = row.querySelector('[data-refinery-done-text]');
                const fillBar = /** @type {HTMLElement | null} */ (
                    row.querySelector('.refinery-meter > i')
                );
                if (!row.hasAttribute('data-ends-at') || !isFinite(endsAt)) {
                    if (text && msgReadyUnknown) text.textContent = msgReadyUnknown;
                    return;
                }
                if (now < endsAt) {
                    if (text) {
                        text.textContent = fill(msgEndsIn, [
                            formatDuration(Math.ceil((endsAt - now) / MINUTE_MS)),
                            row.getAttribute('data-ends-label') || '',
                        ]);
                    }
                    if (fillBar && isFinite(startedAt) && endsAt > startedAt) {
                        const percent = Math.max(
                            0,
                            Math.min(
                                100,
                                Math.floor(((now - startedAt) * 100) / (endsAt - startedAt)),
                            ),
                        );
                        fillBar.setAttribute('data-krtm-width', String(percent));
                        fillBar.style.width = `${percent}%`;
                    }
                    return;
                }
                row.setAttribute('data-state', 'READY');
                row.classList.add('refinery-row--ready');
                if (text) {
                    text.classList.add('refinery-done__text--ready');
                    text.textContent = fill(msgReady, [formatDuration((now - endsAt) / MINUTE_MS)]);
                }
                if (fillBar) {
                    fillBar.classList.add('meter__fill--success');
                    fillBar.setAttribute('data-krtm-width', '100');
                    fillBar.style.width = '100%';
                }
                const store = /** @type {HTMLElement | null} */ (
                    row.querySelector('[data-refinery-store]')
                );
                if (store) store.hidden = false;
            });
    }

    /** Copies the segment counters of the swapped results into the toolbar's segment labels. */
    function syncCounts() {
        if (!results || !form) return;
        const source = results.querySelector('[data-refinery-counts]');
        if (!source) return;
        VIEWS.forEach((view) => {
            const key = view.toLowerCase();
            const value = source.getAttribute(`data-${key}`);
            const count = form.querySelector(`[data-testid="segment-view-${key}"] .seg-count`);
            if (count && value !== null) count.textContent = value;
        });
    }

    document.addEventListener('krt:swapped', (event) => {
        const detail = /** @type {CustomEvent} */ (event).detail;
        if (detail && detail.container === results) {
            syncCounts();
            tick();
        }
    });

    if (!form) return;

    /**
     * The view radios of the segment.
     *
     * @returns {HTMLInputElement[]} the radios in document order
     */
    function viewInputs() {
        return /** @type {HTMLInputElement[]} */ (
            Array.prototype.slice.call(form ? form.querySelectorAll('input[name="view"]') : [])
        );
    }

    /**
     * The selected view.
     *
     * @returns {string} the checked radio's value, or the default view
     */
    function currentView() {
        const checked = viewInputs().filter((el) => {
            return el.checked;
        })[0];
        return checked ? checked.value : DEFAULT_VIEW;
    }

    /**
     * Checks the radio of a view.
     *
     * @param {string} view the view to select
     */
    function selectView(view) {
        viewInputs().forEach((el) => {
            el.checked = el.value === view;
        });
    }

    /**
     * The own-orders switch.
     *
     * @returns {HTMLInputElement | null} the checkbox, if rendered
     */
    function onlyMineBox() {
        return /** @type {HTMLInputElement | null} */ (
            form ? form.querySelector('input[name="onlyMine"]') : null
        );
    }

    /**
     * Reads the stored filter.
     *
     * @returns {any} the parsed preference, or null when absent or unreadable
     */
    function readPref() {
        try {
            const raw = localStorage.getItem(FILTER_PREF_KEY);
            return raw === null ? null : JSON.parse(raw);
        } catch (_e) {
            return null;
        }
    }

    /** Stores the segment and the own-orders switch (REQ-UI-017). */
    function persist() {
        const mine = onlyMineBox();
        try {
            localStorage.setItem(
                FILTER_PREF_KEY,
                JSON.stringify({ view: currentView(), onlyMine: !!(mine && mine.checked) }),
            );
        } catch (_e) {}
    }

    /**
     * The view a stored preference selects; a preference from the former status checkboxes is
     * mapped onto its segment.
     *
     * @param {any} saved the stored preference
     * @returns {string | null} the view, or null when the preference names none
     */
    function storedView(saved) {
        if (!saved || typeof saved !== 'object') return null;
        if (typeof saved.view === 'string' && VIEWS.indexOf(saved.view) >= 0) return saved.view;
        if (!Array.isArray(saved.statuses)) return null;
        /** @type {string[]} */
        const statuses = saved.statuses.filter((/** @type {unknown} */ s) => {
            return typeof s === 'string';
        });
        if (statuses.length === 0) return DEFAULT_VIEW;
        const openOnly = statuses.every((s) => {
            return s === 'OPEN' || s === 'IN_PROGRESS';
        });
        if (openOnly) return 'RUNNING';
        if (statuses.length === 1 && statuses[0] === 'COMPLETED') return 'COMPLETED';
        return 'ALL';
    }

    /** Applies the stored filter unless the URL names one, and reloads when it differs. */
    function restore() {
        if (/[?&](view|status|onlyMine)=/.test(window.location.search)) {
            persist();
            return;
        }
        const saved = readPref();
        const view = storedView(saved);
        let differs = false;
        if (view && view !== currentView()) {
            selectView(view);
            differs = true;
        }
        const mine = onlyMineBox();
        if (
            mine &&
            saved &&
            typeof saved.onlyMine === 'boolean' &&
            mine.checked !== saved.onlyMine
        ) {
            mine.checked = saved.onlyMine;
            differs = true;
        }
        if (view && (differs || typeof saved.view !== 'string')) persist();
        if (differs) {
            if (window.krtFilterChips) window.krtFilterChips.refresh(form || undefined);
            load(true);
        }
    }

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        clearTimeout(debounceTimer);
        load(true);
    });

    form.addEventListener('input', (event) => {
        const target = /** @type {HTMLInputElement | null} */ (event.target);
        if (!target || target.type !== 'search') return;
        clearTimeout(debounceTimer);
        debounceTimer = window.setTimeout(() => {
            load(true);
        }, 300);
    });

    form.addEventListener('change', (event) => {
        const target = /** @type {HTMLInputElement | null} */ (event.target);
        if (!target || target.type === 'search') return;
        persist();
        clearTimeout(debounceTimer);
        load(true);
    });

    restore();
    tick();
    window.setInterval(tick, TICK_MS);
})();
