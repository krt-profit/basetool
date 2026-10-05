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

    /** Wires the org-unit account tabs: click, arrow keys and the `#tab=` deeplink. */
    function bindTabs() {
        /** @returns {NodeListOf<HTMLElement>} the tab panels */
        function panels() {
            return document.querySelectorAll('[data-tabpanel]');
        }

        /** @returns {NodeListOf<HTMLElement>} the tabs */
        function tabs() {
            return document.querySelectorAll('.tab-nav .tab[data-tab]');
        }

        /**
         * Shows one tab's panel, hides the others and records the tab in the address bar.
         *
         * @param {string | null} requested the tab key; an unknown key selects the first tab
         * @param {boolean} [focus] whether the activated tab takes the focus
         */
        function activate(requested, focus) {
            const ps = panels();
            if (!ps.length) {
                return;
            }
            const ts = Array.from(tabs());
            const keys = ts.map((t) => t.getAttribute('data-tab'));
            let key = requested;
            if (keys.indexOf(key) === -1) {
                key = keys.length ? keys[0] : null;
            }
            if (!key) {
                return;
            }
            ts.forEach((t) => {
                const on = t.getAttribute('data-tab') === key;
                t.classList.toggle('active', on);
                t.setAttribute('aria-selected', on ? 'true' : 'false');
                if (on && focus) {
                    t.focus();
                }
            });
            ps.forEach((p) => {
                p.hidden = p.getAttribute('data-tabpanel') !== key;
            });
            if (history.replaceState) {
                history.replaceState(history.state, '', `#tab=${key}`);
            }
        }

        /**
         * Finds the tab an event started on.
         *
         * @param {Event} e the event
         * @returns {Element | null} the tab, or null
         */
        function tabOf(e) {
            const target = /** @type {Element | null} */ (e.target);
            return target && target.closest ? target.closest('.tab-nav .tab[data-tab]') : null;
        }

        document.addEventListener('click', (e) => {
            const t = tabOf(e);
            if (t) {
                activate(t.getAttribute('data-tab'));
            }
        });
        document.addEventListener('keydown', (e) => {
            if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') {
                return;
            }
            const t = tabOf(e);
            if (!t) {
                return;
            }
            e.preventDefault();
            const ts = Array.from(tabs());
            const i = ts.indexOf(/** @type {HTMLElement} */ (t));
            const n = (i + (e.key === 'ArrowRight' ? 1 : ts.length - 1)) % ts.length;
            activate(ts[n].getAttribute('data-tab'), true);
        });

        /** Activates the tab the `#tab=` fragment names, if any. */
        function applyFromHash() {
            const m = (location.hash.match(/tab=(\w+)/) || [])[1];
            if (m) {
                activate(m);
            }
        }
        applyFromHash();
        document.addEventListener('krt:swapped', applyFromHash);
    }

    document.addEventListener('DOMContentLoaded', bindTabs);
})();
