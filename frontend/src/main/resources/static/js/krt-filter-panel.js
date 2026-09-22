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

/*
 * Collapsible filter panels (REQ-FE-021).
 *
 * A list page's filter block is the first thing on screen and, on a phone, often the ONLY thing:
 * the Einsatz and Operationen filters alone fill a viewport before a single row is visible. This
 * collapses every such block behind one toggle.
 *
 * DECLARATIVE ON PURPOSE. A page opts in with markup only — `data-filter-panel` on the block and
 * the shared `filterToggle` fragment beside it — because the alternative was what this file
 * replaces: the same ~40 lines of CSS and ~50 lines of script copied into each page that wanted it,
 * which is how the inventory, the inventory admin and the material-demand pages ended up with three
 * near-identical copies and the other eight pages with none.
 *
 * `hidden` is the collapse mechanism and the panel is rendered EXPANDED. Without JavaScript the
 * filters therefore stay visible and usable; a page that shipped them collapsed in the markup would
 * hide them permanently from a client that never runs this file.
 *
 * THE COUNT IS NOT DECORATION. A collapsed panel that is silently narrowing a list turns "where is
 * my Auftrag?" into a support question, so the toggle carries a chip naming how many filters are
 * active. Everything below exists to keep that number honest — it is recomputed on every input,
 * and `krtFilterPanel.refresh()` lets a page that filters via AJAX re-state it after a swap.
 */
(function () {
    'use strict';

    /** localStorage key prefix; the panel's own name is appended. */
    const STORE_PREFIX = 'krt.filterPanel.';

    /**
     * Per-panel overrides for the active-filter count, keyed by panel id.
     *
     * The generic count below reads the panel's own form controls, which is right for a page whose
     * filters ARE those controls. The inventory's are not: its material and item checkboxes live in
     * a multi-select widget outside the panel and it counts a "location" dimension the DOM cannot
     * see. Those pages register their existing counter here rather than reshaping their filters to
     * suit this file.
     *
     * @type {Map<string, () => number>}
     */
    const counters = new Map();

    /**
     * Reads the stored collapse preference for a panel.
     *
     * @param {HTMLElement} panel the panel element
     * @returns {boolean | null} the stored state, or {@code null} when nothing is stored
     */
    function readPref(panel) {
        try {
            const raw = localStorage.getItem(STORE_PREFIX + panelName(panel));
            return raw === null ? null : raw === '1';
        } catch (_e) {
            // Private mode, blocked site data, or a browser that throws on access. The panel still
            // works; it just forgets. Never let this take the page down.
            return null;
        }
    }

    /**
     * Stores the collapse preference for a panel.
     *
     * @param {HTMLElement} panel the panel element
     * @param {boolean} collapsed whether the panel is now collapsed
     */
    function writePref(panel, collapsed) {
        try {
            localStorage.setItem(STORE_PREFIX + panelName(panel), collapsed ? '1' : '0');
        } catch (_e) {
            /* see readPref */
        }
    }

    /**
     * The storage name of a panel: its {@code data-filter-panel} value, falling back to its id.
     *
     * @param {HTMLElement} panel the panel element
     * @returns {string} a stable per-page name
     */
    function panelName(panel) {
        return panel.getAttribute('data-filter-panel') || panel.id || 'default';
    }

    /**
     * Finds the toggle button that controls a panel.
     *
     * Keyed on `aria-controls` rather than on a second data attribute, so the accessible
     * relationship and the wiring cannot drift apart: a toggle that does not announce what it
     * controls is also a toggle this file will not find.
     *
     * @param {HTMLElement} panel the panel element
     * @returns {HTMLElement | null} the toggle, or {@code null} when the page has none
     */
    function toggleFor(panel) {
        if (!panel.id) return null;
        return /** @type {HTMLElement | null} */ (
            document.querySelector('[aria-controls="' + panel.id + '"]')
        );
    }

    /**
     * Counts how many filters are currently narrowing the list.
     *
     * The generic rule: a checked box or radio counts, and so does any other control carrying a
     * non-empty value. An "Alle" option is empty-valued by convention across this app, so a
     * select left on it correctly counts as nothing. A control the page does not consider a filter
     * — a sort order, a page size — opts out with {@code data-filter-ignore}.
     *
     * @param {HTMLElement} panel the panel element
     * @returns {number} the number of active filters
     */
    function countActive(panel) {
        const custom = counters.get(panel.id);
        if (custom) return custom();
        let active = 0;
        const controls = panel.querySelectorAll('input, select, textarea');
        for (let i = 0; i < controls.length; i++) {
            const el = /** @type {HTMLInputElement} */ (controls[i]);
            if (el.hasAttribute('data-filter-ignore') || el.closest('[data-filter-ignore]'))
                continue;
            const type = (el.getAttribute('type') || '').toLowerCase();
            if (type === 'checkbox' || type === 'radio') {
                if (el.checked) active++;
                continue;
            }
            if (type === 'hidden' || type === 'submit' || type === 'button' || type === 'reset') {
                continue;
            }
            if (el.value !== null && String(el.value).trim() !== '') active++;
        }
        return active;
    }

    /**
     * Re-renders the count chip on a panel's toggle.
     *
     * The chip carries two spans: the bare digit, which a screen reader would announce as
     * "Filter 3", and a visually-hidden twin spelling it out. The count deliberately does NOT go
     * into a dynamic `aria-label` — that would shadow the visible "Filter" text and break voice
     * control's "click Filter".
     *
     * @param {HTMLElement} panel the panel element
     */
    function updateBadge(panel) {
        const toggle = toggleFor(panel);
        if (!toggle) return;
        const chip = toggle.querySelector('[data-filter-count]');
        if (!chip) return;
        const active = countActive(panel);
        /** @type {HTMLElement} */ (chip).hidden = active === 0;
        const value = chip.querySelector('[data-filter-count-value]');
        if (value) value.textContent = String(active);
        const label = chip.querySelector('[data-filter-count-label]');
        if (!label) return;
        const template = chip.getAttribute('data-label') || '';
        label.textContent = active === 0 ? '' : template.replace('{0}', String(active));
    }

    /**
     * Collapses or expands a panel without touching the stored preference.
     *
     * @param {HTMLElement} panel the panel element
     * @param {boolean} collapsed whether to collapse it
     */
    function setCollapsed(panel, collapsed) {
        panel.hidden = collapsed;
        const toggle = toggleFor(panel);
        if (toggle) toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
    }

    /**
     * Wires one panel: applies the stored state, renders the count, and installs the listeners.
     *
     * The default is COLLAPSED (owner decision, 2026-09-14). A list page's filters are consulted
     * far less often than its rows are read, and several pages ship with filters pre-selected — so
     * a "collapse only when nothing is active" default would have left exactly the pages that
     * prompted this change fully expanded. The stored preference wins from the first toggle on, so
     * anyone who wants them open keeps them open.
     *
     * @param {HTMLElement} panel the panel element
     */
    function init(panel) {
        const toggle = toggleFor(panel);
        if (!toggle) return;
        updateBadge(panel);
        const stored = readPref(panel);
        setCollapsed(panel, typeof stored === 'boolean' ? stored : true);
        toggle.addEventListener('click', function () {
            const collapsed = !panel.hidden;
            setCollapsed(panel, collapsed);
            writePref(panel, collapsed);
        });
        // Recount on every edit inside the panel. `input` covers typing, `change` covers boxes,
        // dates and selects; both are cheap and idempotent.
        panel.addEventListener('input', function () {
            updateBadge(panel);
        });
        panel.addEventListener('change', function () {
            updateBadge(panel);
        });
    }

    /** Wires every panel in the document. */
    function initAll() {
        const panels = document.querySelectorAll('[data-filter-panel]');
        for (let i = 0; i < panels.length; i++) {
            init(/** @type {HTMLElement} */ (panels[i]));
        }
    }

    window.krtFilterPanel = {
        /**
         * Registers a page-specific active-filter counter, replacing the generic scan.
         *
         * Call before DOMContentLoaded, or follow it with {@link refresh}.
         *
         * @param {string} panelId the panel's element id
         * @param {() => number} counter returns the number of active filters
         */
        registerCounter(panelId, counter) {
            counters.set(panelId, counter);
        },

        /**
         * Re-renders the count chips. A page that filters via AJAX calls this after the swap, so a
         * collapsed panel never under-reports what it is hiding.
         *
         * @param {string} [panelId] a single panel, or every panel when omitted
         */
        refresh(panelId) {
            if (panelId) {
                const one = document.getElementById(panelId);
                if (one) updateBadge(one);
                return;
            }
            const panels = document.querySelectorAll('[data-filter-panel]');
            for (let i = 0; i < panels.length; i++) {
                updateBadge(/** @type {HTMLElement} */ (panels[i]));
            }
        },
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initAll);
    } else {
        initAll();
    }
})();
