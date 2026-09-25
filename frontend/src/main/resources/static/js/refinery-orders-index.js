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

function refreshRefineryResults() {
    const filterForm = document.getElementById('refinery-filter-form');
    const resultsContainer = document.getElementById('refinery-orders-results');
    if (!filterForm || !resultsContainer || !window.krtFetch) return;
    const params = new URLSearchParams();
    for (const [key, value] of new FormData(filterForm).entries()) {
        if (value !== '') params.append(key, value);
    }
    const query = params.toString();
    window.krtFetch.swap({
        url: '/refinery-orders' + (query ? '?' + query : ''),
        container: resultsContainer,
        history: false,
        preserveScroll: true,
    });
}

if (window.krtLiveSync && typeof window.krtLiveSync.createReceiver === 'function') {
    window.krtLiveSync.createReceiver({
        topic: 'refinery',
        sections: REFINERY_SECTIONS,
        coalesceMs: 1500,
        refresh: refreshRefineryResults,
    });
}

function updateRefineryOrderColors() {
    const nowMs = new Date().getTime();
    document.querySelectorAll('.refinery-order-id-display').forEach((el) => {
        const utcMsStr = el.getAttribute('data-utc');

        if (utcMsStr && utcMsStr !== 'null') {
            const endsAtMs = parseInt(utcMsStr, 10);
            if (!isNaN(endsAtMs) && nowMs > endsAtMs) {
                el.classList.add('text-success');
                el.style.fontWeight = 'bold';
            }
        }
    });
}

function formatLocalDates() {
    document.querySelectorAll('.local-datetime-display').forEach((el) => {
        const utcMsStr = el.getAttribute('data-utc');
        if (utcMsStr && utcMsStr !== 'null') {
            const date = new Date(parseInt(utcMsStr, 10));
            if (!isNaN(date)) {
                const day = String(date.getDate()).padStart(2, '0');
                const month = String(date.getMonth() + 1).padStart(2, '0');
                const hours = String(date.getHours()).padStart(2, '0');
                const minutes = String(date.getMinutes()).padStart(2, '0');
                el.innerText = `${day}.${month}. ${hours}:${minutes}`;
            }
        } else {
            el.innerText = '-';
        }
    });
}

const REFINERY_FILTER_PREF_KEY = 'refinery_orders_filter';

function refineryStatusBoxes() {
    return Array.prototype.slice.call(
        document.querySelectorAll('#refinery-filter-form input[name="status"]'),
    );
}

function refineryOnlyMineBox() {
    return document.querySelector('#refinery-filter-form input[name="onlyMine"]');
}

function readRefineryFilterPref() {
    try {
        const raw = localStorage.getItem(REFINERY_FILTER_PREF_KEY);
        return raw === null ? null : JSON.parse(raw);
    } catch (_e) {
        return null;
    }
}

function writeRefineryFilterPref(value) {
    try {
        localStorage.setItem(REFINERY_FILTER_PREF_KEY, JSON.stringify(value));
    } catch (_e) {}
}

function persistRefineryFilter() {
    const checked = refineryStatusBoxes()
        .filter((b) => b.checked)
        .map((b) => b.value);
    const onlyMine = refineryOnlyMineBox();
    writeRefineryFilterPref({
        statuses: checked.length > 0 ? checked : null,
        onlyMine: !!(onlyMine && onlyMine.checked),
    });
}

function restoreRefineryFilter() {
    const saved = readRefineryFilterPref();
    if (!saved || typeof saved !== 'object') return false;
    let differs = false;
    const boxes = refineryStatusBoxes();
    if (Array.isArray(saved.statuses) && boxes.length > 0) {
        const known = boxes.filter((b) => saved.statuses.indexOf(b.value) >= 0);
        if (known.length > 0) {
            boxes.forEach((b) => {
                const on = saved.statuses.indexOf(b.value) >= 0;
                if (b.checked !== on) differs = true;
                b.checked = on;
            });
        }
    }
    const onlyMine = refineryOnlyMineBox();
    if (onlyMine && typeof saved.onlyMine === 'boolean' && onlyMine.checked !== saved.onlyMine) {
        onlyMine.checked = saved.onlyMine;
        differs = true;
    }
    return differs;
}

document.addEventListener('DOMContentLoaded', () => {
    formatLocalDates();
    updateRefineryOrderColors();
    setInterval(updateRefineryOrderColors, 10000);

    const filterForm = document.getElementById('refinery-filter-form');
    const resultsContainer = document.getElementById('refinery-orders-results');
    if (filterForm && resultsContainer && window.krtFetch) {
        const applyFilter = () => {
            const data = new FormData(filterForm);
            const params = new URLSearchParams();
            for (const [key, value] of data.entries()) {
                if (value !== '') params.append(key, value);
            }
            const query = params.toString();
            window.krtFetch.swap({
                url: '/refinery-orders' + (query ? '?' + query : ''),
                container: resultsContainer,
                history: true,
            });
        };
        filterForm.addEventListener('submit', (event) => {
            event.preventDefault();
            applyFilter();
        });
        filterForm.querySelectorAll('input').forEach((el) => {
            el.addEventListener('change', () => {
                persistRefineryFilter();
                applyFilter();
            });
        });
        if (/[?&](status|onlyMine)=/.test(window.location.search)) {
            persistRefineryFilter();
        } else if (restoreRefineryFilter()) {
            applyFilter();
        }
    }
});

document.addEventListener('krt:swapped', () => {
    formatLocalDates();
    updateRefineryOrderColors();
});
