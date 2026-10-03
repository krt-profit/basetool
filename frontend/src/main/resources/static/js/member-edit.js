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

// @ts-check

(function () {
    'use strict';

    /**
     * Wires the Stammdaten / Mitgliedschaften / Datenauskunft tabs: arrow-key navigation and the
     * `tab` query-parameter deeplink. The edit form stays visible for every tab whose panel it
     * contains.
     */
    function bindTabs() {
        const nav = document.querySelector('.member-edit-tabs[role="tablist"]');
        const formElement = document.getElementById('member-edit-form');
        if (!nav || !formElement) {
            return;
        }
        const form = formElement;
        /** @type {HTMLElement[]} */
        const tabs = Array.prototype.slice.call(nav.querySelectorAll('.tab[data-tab]'));
        const keys = tabs.map(function (tab) {
            return tab.getAttribute('data-tab') || '';
        });

        /**
         * Shows the panel of one tab and hides the others.
         *
         * @param {string} key the `data-tab` value of the tab to show
         */
        function apply(key) {
            let formNeeded = false;
            tabs.forEach(function (tab) {
                const on = tab.getAttribute('data-tab') === key;
                tab.classList.toggle('active', on);
                tab.setAttribute('aria-selected', String(on));
                tab.tabIndex = on ? 0 : -1;
                const panel = document.getElementById(tab.getAttribute('aria-controls') || '');
                if (panel) {
                    panel.hidden = !on;
                    if (on && form.contains(panel)) {
                        formNeeded = true;
                    }
                }
            });
            form.hidden = !formNeeded;
        }

        /**
         * Reads the tab named by the `tab` query parameter.
         *
         * @returns {string} that tab's key, or the first tab's when the parameter names none
         */
        function requested() {
            const key = new URLSearchParams(window.location.search).get('tab');
            return key && keys.indexOf(key) >= 0 ? key : keys[0];
        }

        /**
         * Shows a tab and records it in the address bar.
         *
         * @param {string} key the tab to show
         */
        function show(key) {
            if (keys.indexOf(key) < 0) {
                return;
            }
            apply(key);
            const url = new URL(window.location.href);
            if (key === keys[0]) {
                url.searchParams.delete('tab');
            } else {
                url.searchParams.set('tab', key);
            }
            history.replaceState(history.state, '', url);
        }

        tabs.forEach(function (tab) {
            tab.addEventListener('click', function () {
                show(tab.getAttribute('data-tab') || '');
            });
        });
        nav.addEventListener('keydown', function (event) {
            if (!(event instanceof KeyboardEvent)) {
                return;
            }
            if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
                return;
            }
            const index = tabs.indexOf(/** @type {HTMLElement} */ (document.activeElement));
            if (index < 0) {
                return;
            }
            event.preventDefault();
            const step = event.key === 'ArrowRight' ? 1 : tabs.length - 1;
            const next = tabs[(index + step) % tabs.length];
            next.focus();
            show(next.getAttribute('data-tab') || '');
        });
        apply(requested());
    }

    /** Shows and clears the optional second Staffel slot and keeps both selects distinct. */
    function bindStaffelSlots() {
        const sel1 = /** @type {HTMLSelectElement | null} */ (
            document.getElementById('staffel1-select')
        );
        const sel2 = /** @type {HTMLSelectElement | null} */ (
            document.getElementById('staffel2-select')
        );
        const slot2 = document.getElementById('staffel-slot-2');
        const addBtn = document.getElementById('staffel-add-2');
        const removeBtn = document.getElementById('staffel-remove-2');
        if (!sel1 || !sel2 || !slot2 || !addBtn || !removeBtn) {
            return;
        }
        const first = sel1;
        const second = sel2;
        const slot = slot2;
        const add = addBtn;

        /**
         * Toggles the second slot against its add button.
         *
         * @param {boolean} show whether the second slot is shown
         */
        function showSlot2(show) {
            slot.hidden = !show;
            add.hidden = show;
        }

        /** Resets the second slot's Staffel and role flags. */
        function clearSlot2() {
            second.value = '';
            slot.querySelectorAll('input[type=checkbox]').forEach(function (box) {
                /** @type {HTMLInputElement} */ (box).checked = false;
            });
        }

        /** Disables in each select the Staffel the other one has picked. */
        function syncDuplicateOptions() {
            const v1 = first.value;
            const v2 = second.value;
            Array.prototype.forEach.call(
                second.options,
                function (/** @type {HTMLOptionElement} */ o) {
                    o.disabled = o.value !== '' && o.value === v1;
                },
            );
            Array.prototype.forEach.call(
                first.options,
                function (/** @type {HTMLOptionElement} */ o) {
                    o.disabled = o.value !== '' && o.value === v2;
                },
            );
        }

        add.addEventListener('click', function () {
            showSlot2(true);
            syncDuplicateOptions();
            second.focus();
        });
        removeBtn.addEventListener('click', function () {
            clearSlot2();
            showSlot2(false);
            syncDuplicateOptions();
            add.focus();
        });
        first.addEventListener('change', syncDuplicateOptions);
        second.addEventListener('change', syncDuplicateOptions);
        syncDuplicateOptions();
    }

    /** Keeps the "n / max" counter under every textarea with `data-char-counter` current. */
    function bindCharCounters() {
        document.querySelectorAll('textarea[data-char-counter]').forEach(function (element) {
            const area = /** @type {HTMLTextAreaElement} */ (element);
            const counter = document.getElementById(area.getAttribute('data-char-counter') || '');
            const value = counter ? counter.querySelector('[data-char-count]') : null;
            if (!value) {
                return;
            }
            const target = value;
            const update = function () {
                target.textContent = String(area.value.length);
            };
            area.addEventListener('input', update);
            update();
        });
    }

    bindTabs();
    bindStaffelSlots();
    bindCharCounters();
})();
