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

    const ME_FILTER_PREF_KEY = 'promotion_my_evaluations_filter';

    /**
     * Returns the radios of the "Alle · Offen" segment.
     *
     * @returns {HTMLInputElement[]} the segment's radios, empty when the page has no requirements
     */
    function filterRadios() {
        return /** @type {HTMLInputElement[]} */ (
            Array.from(document.querySelectorAll('input[name="meOpen"]'))
        );
    }

    /**
     * Tells whether the segment shows only the open requirements.
     *
     * @returns {boolean} true when "Offen" is selected
     */
    function onlyOpen() {
        const checked = filterRadios().find((r) => {
            return r.checked;
        });
        return !!checked && checked.value === 'OPEN';
    }

    /**
     * Reads the stored filter preference.
     *
     * @returns {{ onlyOpen?: unknown } | null} the stored object, or null when absent or unreadable
     */
    function readPref() {
        try {
            const raw = localStorage.getItem(ME_FILTER_PREF_KEY);
            const parsed = raw === null ? null : JSON.parse(raw);
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch (_e) {
            return null;
        }
    }

    /** Stores the current filter choice (REQ-UI-017). */
    function writePref() {
        try {
            localStorage.setItem(ME_FILTER_PREF_KEY, JSON.stringify({ onlyOpen: onlyOpen() }));
        } catch (_e) {}
    }

    /** Hides met requirements, and steps left without a visible row, while "Offen" is selected. */
    function applyFilter() {
        const open = onlyOpen();
        let visible = 0;
        document.querySelectorAll('#me-requirements tbody.me-step').forEach((step) => {
            let stepVisible = 0;
            step.querySelectorAll('tr[data-me-satisfied]').forEach((row) => {
                const hide = open && row.getAttribute('data-me-satisfied') === 'true';
                /** @type {HTMLElement} */ (row).hidden = hide;
                if (!hide) stepVisible++;
            });
            /** @type {HTMLElement} */ (step).hidden = open && stepVisible === 0;
            visible += stepVisible;
        });
        const empty = document.getElementById('me-open-empty');
        if (empty) empty.hidden = !(open && visible === 0 && filterRadios().length > 0);
    }

    /** Restores the stored choice onto the segment before the first filter pass. */
    function restore() {
        const saved = readPref();
        if (!saved || typeof saved.onlyOpen !== 'boolean') return;
        const want = saved.onlyOpen ? 'OPEN' : 'ALL';
        filterRadios().forEach((r) => {
            r.checked = r.value === want;
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        restore();
        applyFilter();
        filterRadios().forEach((r) => {
            r.addEventListener('change', () => {
                writePref();
                applyFilter();
            });
        });
    });
})();
