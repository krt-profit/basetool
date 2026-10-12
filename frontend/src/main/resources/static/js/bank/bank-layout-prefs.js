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

import { krtFetch } from '/js/kernel/krt-fetch.js';

function viewToggles() {
    return document.querySelector('[data-bank-view-toggles]');
}

function storageKey() {
    const main = document.querySelector('main[data-user-id]');
    const uid = main ? main.getAttribute('data-user-id') : 'unknown';
    return `bank_dashboard_view_${uid}`;
}

/** @returns {HTMLInputElement | null} the dashboard layout toggle */
function layoutCheckbox() {
    return /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[data-bank-view-layout]')
    );
}

/** @returns {HTMLInputElement | null} the dashboard grouping toggle */
function groupCheckbox() {
    return /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[data-bank-view-group]')
    );
}

function renderedState() {
    const layoutEl = layoutCheckbox();
    const groupEl = groupCheckbox();
    return {
        layout: layoutEl && layoutEl.checked ? 'table' : 'card',
        group: groupEl && groupEl.checked ? 'bereich' : 'alpha',
    };
}

function readSaved() {
    try {
        const raw = localStorage.getItem(storageKey());
        if (raw === null) {
            return null;
        }
        const parsed = JSON.parse(raw) || {};
        return {
            layout: parsed.layout === 'table' ? 'table' : 'card',
            group: parsed.group === 'bereich' ? 'bereich' : 'alpha',
        };
    } catch {
        return null;
    }
}

function writeSaved(state) {
    try {
        localStorage.setItem(storageKey(), JSON.stringify(state));
    } catch {}
}

function applyCheckboxStates(state) {
    const layoutEl = layoutCheckbox();
    const groupEl = groupCheckbox();
    if (layoutEl) {
        layoutEl.checked = state.layout === 'table';
    }
    if (groupEl) {
        groupEl.checked = state.group === 'bereich';
    }
}

function reapplyNameFilter() {
    const input = /** @type {HTMLInputElement | null} */ (
        document.querySelector('[data-bank-acc-filter]')
    );
    if (input && input.value) {
        input.dispatchEvent(new Event('input', { bubbles: true }));
    }
}

function fetchView(state) {
    if (!krtFetch || typeof krtFetch.swap !== 'function') {
        window.location.reload();
        return;
    }
    krtFetch
        .swap({
            url: `/bank?layout=${state.layout}&group=${state.group}`,
            container: '#bank-grid-results',
            fragmentValue: 'bankGrid',
            history: true,
        })
        .then(() => {
            reapplyNameFilter();
        });
}

document.addEventListener('DOMContentLoaded', () => {
    if (!viewToggles()) {
        return;
    }
    if (/[?&](layout|group)=/.test(window.location.search)) {
        writeSaved(renderedState());
        return;
    }
    const saved = readSaved();
    if (saved === null) {
        writeSaved(renderedState());
        return;
    }
    const current = renderedState();
    if (saved.layout !== current.layout || saved.group !== current.group) {
        applyCheckboxStates(saved);
        fetchView(saved);
    }
});

document.addEventListener('change', (event) => {
    const target = /** @type {Element} */ (event.target);
    const checkbox = target.closest
        ? target.closest('input[data-bank-view-layout],input[data-bank-view-group]')
        : null;
    if (!checkbox) {
        return;
    }
    const state = renderedState();
    writeSaved(state);
    fetchView(state);
});

(function () {
    function toggleGroup() {
        return document.querySelector('[data-ou-view-toggles]');
    }

    /** @returns {HTMLInputElement | null} the org-unit layout toggle */
    function layoutCheckbox() {
        return /** @type {HTMLInputElement | null} */ (
            document.querySelector('input[data-ou-view-layout]')
        );
    }

    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return `org_unit_bank_view_${uid}`;
    }

    function renderedLayout() {
        const checkbox = layoutCheckbox();
        return checkbox && checkbox.checked ? 'table' : 'card';
    }

    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            if (raw === null) {
                return null;
            }
            const parsed = JSON.parse(raw) || {};
            return parsed.layout === 'table' ? 'table' : 'card';
        } catch {
            return null;
        }
    }

    function writeSaved(layout) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify({ layout }));
        } catch {}
    }

    function reapplyNameFilter() {
        const input = /** @type {HTMLInputElement | null} */ (
            document.querySelector('#ou-panel-konten [data-bank-acc-filter]')
        );
        if (input && input.value) {
            input.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    function fetchView(layout) {
        if (!krtFetch || typeof krtFetch.swap !== 'function') {
            window.location.reload();
            return;
        }
        krtFetch
            .swap({
                url: `/org-unit-bank?layout=${layout}`,
                container: '#ou-acc-results',
                fragmentValue: 'orgUnitBankAccounts',
                history: true,
            })
            .then(() => {
                reapplyNameFilter();
            });
    }

    document.addEventListener('DOMContentLoaded', () => {
        if (!toggleGroup()) {
            return;
        }
        if (/[?&]layout=/.test(window.location.search)) {
            writeSaved(renderedLayout());
            return;
        }
        const saved = readSaved();
        if (saved === null) {
            writeSaved(renderedLayout());
            return;
        }
        if (saved !== renderedLayout()) {
            const checkbox = layoutCheckbox();
            if (checkbox) {
                checkbox.checked = saved === 'table';
            }
            fetchView(saved);
        }
    });

    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const checkbox = /** @type {HTMLInputElement | null} */ (
            target.closest ? target.closest('input[data-ou-view-layout]') : null
        );
        if (!checkbox) {
            return;
        }
        const layout = checkbox.checked ? 'table' : 'card';
        writeSaved(layout);
        fetchView(layout);
    });
})();
