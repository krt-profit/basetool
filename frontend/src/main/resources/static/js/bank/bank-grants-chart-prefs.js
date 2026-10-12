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

function storageKey() {
    const main = document.querySelector('main[data-user-id]');
    const uid = main ? main.getAttribute('data-user-id') : 'unknown';
    return `bank_grants_view_${uid}`;
}

function onGrantsPage() {
    return !!document.querySelector('[data-role="bank-grants-filter"]');
}

function readSaved() {
    try {
        const raw = localStorage.getItem(storageKey());
        if (raw === null) {
            return null;
        }
        const parsed = JSON.parse(raw) || {};
        return {
            view: parsed.view === 'employee' ? 'employee' : 'account',
            accountId: typeof parsed.accountId === 'string' ? parsed.accountId : null,
            userId: typeof parsed.userId === 'string' ? parsed.userId : null,
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

function urlState() {
    const params = new URLSearchParams(window.location.search);
    const view = (params.get('view') || '').toLowerCase() === 'employee' ? 'employee' : 'account';
    return {
        view,
        accountId: view === 'account' ? params.get('accountId') : null,
        userId: view === 'employee' ? params.get('userId') : null,
    };
}

document.addEventListener('DOMContentLoaded', () => {
    if (!onGrantsPage()) {
        return;
    }
    if (/[?&](view|accountId|userId)=/.test(window.location.search)) {
        writeSaved(urlState());
        return;
    }
    const saved = readSaved();
    if (saved === null) {
        writeSaved(urlState());
        return;
    }
    if (saved.view === 'employee' || saved.accountId || saved.userId) {
        const params = new URLSearchParams();
        params.set('view', saved.view);
        if (saved.view === 'account' && saved.accountId) {
            params.set('accountId', saved.accountId);
        }
        if (saved.view === 'employee' && saved.userId) {
            params.set('userId', saved.userId);
        }
        window.location.replace(`/bank/grants?${params.toString()}`);
    }
});

document.addEventListener('change', (event) => {
    const target = /** @type {Element} */ (event.target);
    const select = /** @type {HTMLSelectElement | null} */ (
        target.closest ? target.closest('[data-role="bank-grants-filter"]') : null
    );
    if (!select) {
        return;
    }
    const view = select.getAttribute('data-view') === 'employee' ? 'employee' : 'account';
    writeSaved({
        view,
        accountId: view === 'account' && select.value ? select.value : null,
        userId: view === 'employee' && select.value ? select.value : null,
    });
});

(function () {
    const CHART_CONTAINER_IDS = ['bank-chart-results', 'org-unit-bank-chart-results'];

    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return `bank_chart_range_${uid}`;
    }

    function chartContainer() {
        for (let i = 0; i < CHART_CONTAINER_IDS.length; i++) {
            const el = document.getElementById(CHART_CONTAINER_IDS[i]);
            if (el) {
                return el;
            }
        }
        return null;
    }

    function renderedRange() {
        const active = document.querySelector('.bank-chart-range-btn.is-active[data-range]');
        return active ? active.getAttribute('data-range') : null;
    }

    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            if (raw === null) {
                return null;
            }
            const parsed = JSON.parse(raw) || {};
            return typeof parsed.range === 'string' ? parsed.range : null;
        } catch {
            return null;
        }
    }

    function writeSaved(range) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify({ range }));
        } catch {}
    }

    document.addEventListener('DOMContentLoaded', () => {
        const container = chartContainer();
        if (!container) {
            return;
        }
        if (/[?&]chartRange=/.test(window.location.search)) {
            const rendered = renderedRange();
            if (rendered) {
                writeSaved(rendered);
            }
            return;
        }
        const saved = readSaved();
        if (saved === null || saved === renderedRange()) {
            return;
        }
        const link = /** @type {HTMLAnchorElement | null} */ (
            container.querySelector(`a.bank-chart-range-btn[data-range="${saved}"]`)
        );
        if (link) {
            link.click();
        }
    });

    document.addEventListener('krt:swapped', (event) => {
        const container = event.detail && event.detail.container;
        if (!container || CHART_CONTAINER_IDS.indexOf(container.id) === -1) {
            return;
        }
        const rendered = renderedRange();
        if (rendered) {
            writeSaved(rendered);
        }
    });
})();
