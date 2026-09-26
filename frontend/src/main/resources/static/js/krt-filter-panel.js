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

    /** localStorage key prefix; the panel's own name is appended. */
    const STORE_PREFIX = 'krt.filterPanel.';

    /**
     * Page-registered active-filter counters keyed by panel id, used instead of the generic scan of
     * the panel's own controls.
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
        } catch (_e) {}
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
     * Finds the toggle button whose `aria-controls` names the panel's id.
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
     * Counts the active filters: a registered counter if present, otherwise every checked box or
     * radio and every other control with a non-empty value, skipping `data-filter-ignore`.
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
     * Re-renders the count chip on a panel's toggle: the visible digit and its visually-hidden
     * spelled-out label; the chip is hidden at zero.
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
     * Wires one panel: applies the stored state (collapsed when none is stored), renders the count,
     * and installs the listeners.
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
         * Re-renders the count chips; pages that filter via AJAX call this after the swap.
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
