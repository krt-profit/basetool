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

function meFillProgressBars() {
    const cards = document.querySelectorAll('.eligibility-card');
    cards.forEach(function (card) {
        const fractions = card.querySelectorAll(
            '.checks-table tbody tr .check-status-ok, .checks-table tbody tr .check-status-missing',
        );
        let achieved = 0,
            required = 0;
        fractions.forEach(function (span) {
            const parts = (span.textContent || '').split('/');
            if (parts.length === 2) {
                const a = parseInt(parts[0].trim(), 10);
                const r = parseInt(parts[1].trim(), 10);
                if (Number.isFinite(a) && Number.isFinite(r)) {
                    achieved += a;
                    required += r;
                }
            }
        });
        const pct = required > 0 ? Math.min(100, Math.round((achieved / required) * 100)) : 0;
        const fill = card.querySelector('.eligibility-progress-fill');
        const text = card.querySelector('.me-progress-text');
        const bar = card.querySelector('.eligibility-progress');
        if (fill) /** @type {HTMLElement} */ (fill).style.width = pct + '%';
        if (text) text.textContent = achieved + ' / ' + required + ' (' + pct + '%)';
        if (bar) bar.setAttribute('aria-valuenow', String(pct));
    });
}

function meApplyOpenFilter() {
    const input = document.getElementById('me-filter-open');
    const onlyOpen = !!(input && /** @type {HTMLInputElement} */ (input).checked);
    const rows = document.querySelectorAll('.checks-table tbody tr[data-me-satisfied]');
    rows.forEach(function (row) {
        const satisfied = row.getAttribute('data-me-satisfied') === 'true';
        row.classList.toggle('checks-row-hidden', onlyOpen && satisfied);
    });
}

const ME_FILTER_PREF_KEY = 'promotion_my_evaluations_filter';

function meReadFilterPref() {
    try {
        const raw = localStorage.getItem(ME_FILTER_PREF_KEY);
        return raw === null ? null : JSON.parse(raw);
    } catch (_e) {
        return null;
    }
}

function meWriteFilterPref(value) {
    try {
        localStorage.setItem(ME_FILTER_PREF_KEY, JSON.stringify(value));
    } catch (_e) {}
}

function mePersistOpenFilter() {
    const input = document.getElementById('me-filter-open');
    if (input) {
        meWriteFilterPref({ onlyOpen: /** @type {HTMLInputElement} */ (input).checked });
    }
}

function meRestoreOpenFilter() {
    const input = document.getElementById('me-filter-open');
    const saved = meReadFilterPref();
    if (input && saved && typeof saved.onlyOpen === 'boolean') {
        /** @type {HTMLInputElement} */ (input).checked = saved.onlyOpen;
    }
}

function meOnOpenFilterChange() {
    mePersistOpenFilter();
    meApplyOpenFilter();
}

document.addEventListener('DOMContentLoaded', function () {
    meFillProgressBars();
    meRestoreOpenFilter();
    meApplyOpenFilter();
    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('change', 'me-filter-open', meOnOpenFilterChange);
    }
});
