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

/**
 * Client-side account-name filter (REQ-BANK-046): hides every `[data-filter-name]` item in the
 * `data-filter-scope` container that does not contain the term (case-insensitive), hides empty
 * groups, and shows the `data-filter-empty` note when a non-empty list is filtered to nothing.
 *
 * @param {HTMLInputElement} input the account-filter search box that fired the event
 */
function applyAccountNameFilter(input) {
    const scope = document.querySelector(
        /** @type {string} */ (input.getAttribute('data-filter-scope')),
    );
    if (!scope) {
        return;
    }
    const term = input.value.trim().toLowerCase();
    const items = /** @type {NodeListOf<HTMLElement>} */ (
        scope.querySelectorAll('[data-filter-name]')
    );
    let visible = 0;
    items.forEach((item) => {
        const name = (item.getAttribute('data-filter-name') || '').toLowerCase();
        const match = term === '' || name.indexOf(term) !== -1;
        item.hidden = !match;
        if (match) {
            visible += 1;
        }
    });
    /** @type {NodeListOf<HTMLElement>} */ (
        scope.querySelectorAll('[data-bank-acc-group]')
    ).forEach((groupEl) => {
        const anyVisible = Array.prototype.some.call(
            groupEl.querySelectorAll('[data-filter-name]'),
            (item) => {
                return !item.hidden;
            },
        );
        groupEl.hidden = !anyVisible;
    });
    const emptySelector = input.getAttribute('data-filter-empty');
    const empty = /** @type {HTMLElement | null} */ (
        emptySelector ? document.querySelector(emptySelector) : null
    );
    if (empty) {
        empty.hidden = items.length === 0 || visible > 0;
    }
}

/**
 * Toggles a collapsible region: flips the control's `aria-expanded` and shows or hides its
 * `aria-controls` target.
 *
 * @param {HTMLElement} control the button carrying aria-expanded + aria-controls
 */
function toggleAriaRegion(control) {
    const expanded = control.getAttribute('aria-expanded') === 'true';
    control.setAttribute('aria-expanded', expanded ? 'false' : 'true');
    const id = control.getAttribute('aria-controls');
    const target = id ? document.getElementById(id) : null;
    if (target) {
        target.hidden = expanded;
    }
}

/**
 * Registers the document listeners of the account list: the name filter and the expandable booking
 * rows.
 */
export function initAccountList() {
    document.addEventListener('input', (event) => {
        const target = /** @type {Element} */ (event.target);
        const input = /** @type {HTMLInputElement | null} */ (
            target.closest ? target.closest('[data-bank-acc-filter]') : null
        );
        if (input) {
            applyAccountNameFilter(input);
        }
    });

    document.addEventListener('click', (event) => {
        const target = /** @type {Element} */ (event.target);
        const toggle = /** @type {HTMLElement | null} */ (
            target.closest ? target.closest('[data-trigger="bank-row-expand"]') : null
        );
        if (toggle) {
            toggleAriaRegion(toggle);
            return;
        }
        if (target.closest('a, button, input, select, textarea, label')) {
            return;
        }
        const row = target.closest('tr.bank-booking-row.is-expandable');
        if (row) {
            const btn = /** @type {HTMLElement | null} */ (
                row.querySelector('[data-trigger="bank-row-expand"]')
            );
            if (btn) {
                toggleAriaRegion(btn);
            }
        }
    });
}
