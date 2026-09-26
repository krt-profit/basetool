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

/* global KRT_ORDERS_AGE_YELLOW, KRT_ORDERS_AGE_RED, KRT_ORDERS_LIVESYNC_UPDATES, KRT_ORDERS_SECTION_REFRESH_ERROR */

const ORDERS_SECTIONS = {
    queue: { container: '#orders-results', fragmentValue: 'results' },
};

(function () {
    if (!window.krtFetch || typeof window.krtFetch.sectionWrite !== 'function') {
        return;
    }
    const ordersQueueSeam = window.krtFetch.sectionWrite({
        dict() {
            return {
                'orders.section.refresh.error':
                    typeof KRT_ORDERS_SECTION_REFRESH_ERROR !== 'undefined'
                        ? KRT_ORDERS_SECTION_REFRESH_ERROR
                        : '',
            };
        },
        keys: { refreshErrorKey: 'orders.section.refresh.error' },
        sections: ORDERS_SECTIONS,
        pageUrl() {
            return window.location.pathname + window.location.search;
        },
        broadcast(keys) {
            if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
                window.krtLiveSync.sendChanged('orders', keys);
            }
        },
    });
    window.krtRefreshOrdersQueue = ordersQueueSeam.refresh;

    if (window.krtLiveSync && window.krtLiveSync.createReceiver) {
        window.krtLiveSync.createReceiver({
            topic: 'orders',
            sections: ORDERS_SECTIONS,
            coalesceMs: 1500,
            refresh(keys) {
                if (window.krtRefreshOrdersQueue) {
                    window.krtRefreshOrdersQueue(keys, { broadcast: false });
                }
            },
            busyTest() {
                return window.__ordersDragging === true;
            },
            pill: {
                label() {
                    return typeof KRT_ORDERS_LIVESYNC_UPDATES !== 'undefined'
                        ? KRT_ORDERS_LIVESYNC_UPDATES
                        : undefined;
                },
            },
        });
    }
})();

function colorOrderAges(root) {
    const now = new Date();
    (root || document).querySelectorAll('.order-id-display').forEach((el) => {
        const utcDateStr = el.getAttribute('data-utc');
        if (utcDateStr) {
            const date = new Date(utcDateStr);
            if (!isNaN(date)) {
                const diffDays = Math.floor(
                    (now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24),
                );
                if (diffDays >= KRT_ORDERS_AGE_RED) {
                    el.classList.add('text-danger');
                    el.style.fontWeight = 'bold';
                } else if (diffDays >= KRT_ORDERS_AGE_YELLOW) {
                    el.classList.add('text-warning');
                    el.style.fontWeight = 'bold';
                }
            }
        }
    });
}

const ORDERS_MATERIALS_EXPANDED_KEY = 'orders_materials_expanded';

function readExpandedOrderMaterials() {
    try {
        return JSON.parse(localStorage.getItem(ORDERS_MATERIALS_EXPANDED_KEY) || '[]');
    } catch (_e) {
        return [];
    }
}

function writeExpandedOrderMaterials(values) {
    try {
        localStorage.setItem(ORDERS_MATERIALS_EXPANDED_KEY, JSON.stringify(values));
    } catch (_e) {}
}

function applyOrderMaterialsState(orderId, expanded) {
    const body = document.querySelector(
        '[data-order-materials="' +
            (window.CSS && CSS.escape ? CSS.escape(orderId) : orderId) +
            '"]',
    );
    const btn = document.querySelector(
        '[data-trigger="ord-toggle-materials"][data-order-id="' +
            (window.CSS && CSS.escape ? CSS.escape(orderId) : orderId) +
            '"]',
    );
    if (body) {
        body.classList.toggle('krtm-display-none-5790', !expanded);
    }
    if (btn) {
        btn.setAttribute('aria-expanded', String(expanded));
        const icon = btn.querySelector('.toggle-icon');
        if (icon) icon.textContent = expanded ? '▼' : '▶';
    }
}

function toggleOrderMaterials(btn) {
    const orderId = btn.getAttribute('data-order-id');
    if (!orderId) return;
    const expandedList = readExpandedOrderMaterials();
    const isExpanded = expandedList.indexOf(orderId) >= 0;
    applyOrderMaterialsState(orderId, !isExpanded);
    if (isExpanded) {
        writeExpandedOrderMaterials(expandedList.filter((id) => id !== orderId));
    } else {
        expandedList.push(orderId);
        writeExpandedOrderMaterials(expandedList);
    }
}

function restoreOrderMaterials(root) {
    const expandedList = readExpandedOrderMaterials();
    (root || document).querySelectorAll('[data-order-materials]').forEach((body) => {
        const orderId = body.getAttribute('data-order-materials');
        applyOrderMaterialsState(orderId, expandedList.indexOf(orderId) >= 0);
    });
}

const ORDERS_SQUADRON_FILTER_KEY = 'orders_squadron_filter';
const ORDERS_SQUADRON_NONE_SENTINEL = '00000000-0000-0000-0000-000000000000';

function readSquadronFilter() {
    try {
        const raw = localStorage.getItem(ORDERS_SQUADRON_FILTER_KEY);
        return raw === null ? null : JSON.parse(raw);
    } catch (_e) {
        return null;
    }
}

function writeSquadronFilter(ids) {
    try {
        localStorage.setItem(ORDERS_SQUADRON_FILTER_KEY, JSON.stringify(ids));
    } catch (_e) {}
}

function squadronBoxes() {
    return Array.prototype.slice.call(document.querySelectorAll('input.sqCheck'));
}

function collectCheckedSquadrons() {
    return squadronBoxes()
        .filter((b) => b.checked)
        .map((b) => b.value);
}

function persistSquadronFilter() {
    writeSquadronFilter(collectCheckedSquadrons());
}

function updateSquadronHeaderText() {
    const header = document.getElementById('squadronHeader');
    const textEl = document.getElementById('squadronSelectedText');
    const boxes = squadronBoxes();
    if (!header || !textEl || boxes.length === 0) return;
    const checked = boxes.filter((b) => b.checked);
    const dataAll = window.krtI18nText(header.getAttribute('data-all'), 'data-all');
    const dataSelected = window.krtI18nText(header.getAttribute('data-selected'), 'data-selected');
    if (checked.length === boxes.length) {
        textEl.textContent = dataAll;
    } else if (checked.length === 1) {
        const option = checked[0].closest('.multi-select-option');
        const label = option ? option.querySelector('span') : null;
        textEl.textContent = label ? label.textContent.trim() : checked.length + ' ' + dataSelected;
    } else {
        textEl.textContent = checked.length + ' ' + dataSelected;
    }
}

function toggleSquadronMulti() {
    const el = document.getElementById('squadronOptions');
    if (!el) return;
    const isOpen = el.classList.contains('open');
    document.querySelectorAll('.multi-select-options').forEach((o) => o.classList.remove('open'));
    if (!isOpen) el.classList.add('open');
}

function toggleSquadronAll() {
    const allBox = document.getElementById('sqAll');
    if (!allBox) return;
    squadronBoxes().forEach((b) => {
        b.checked = allBox.checked;
    });
    updateSquadronHeaderText();
    persistSquadronFilter();
    applyOrdersFilter();
}

function updateSquadronState() {
    const allBox = document.getElementById('sqAll');
    const boxes = squadronBoxes();
    if (allBox) allBox.checked = boxes.length > 0 && boxes.every((b) => b.checked);
    updateSquadronHeaderText();
    persistSquadronFilter();
    applyOrdersFilter();
}

function applyOrdersFilter() {
    const filterForm = document.getElementById('orders-filter-form');
    const resultsContainer = document.getElementById('orders-results');
    if (!filterForm || !resultsContainer || !window.krtFetch) return;
    const data = new FormData(filterForm);
    const params = new URLSearchParams();
    for (const [key, value] of data.entries()) {
        if (key === 'squadronId') continue;
        if (value !== '') params.append(key, value);
    }
    const boxes = squadronBoxes();
    if (boxes.length > 0) {
        const checked = boxes.filter((b) => b.checked);
        if (checked.length === 0) {
            params.append('squadronId', ORDERS_SQUADRON_NONE_SENTINEL);
        } else if (checked.length < boxes.length) {
            checked.forEach((b) => params.append('squadronId', b.value));
        }
    }
    const query = params.toString();
    window.krtFetch.swap({
        url: '/orders' + (query ? '?' + query : ''),
        container: resultsContainer,
        history: true,
    });
}

function restoreSquadronFilter() {
    const saved = readSquadronFilter();
    if (saved === null) {
        updateSquadronHeaderText();
        return false;
    }
    const boxes = squadronBoxes();
    if (boxes.length === 0) return false;
    const serverChecked = boxes
        .filter((b) => b.checked)
        .map((b) => b.value)
        .sort();
    const savedSel = saved.slice().sort();
    const savedSet = {};
    saved.forEach((id) => {
        savedSet[id] = true;
    });
    boxes.forEach((b) => {
        b.checked = savedSet[b.value] === true;
    });
    const allBox = document.getElementById('sqAll');
    if (allBox) allBox.checked = boxes.every((b) => b.checked);
    updateSquadronHeaderText();
    return (
        serverChecked.length !== savedSel.length || serverChecked.some((v, i) => v !== savedSel[i])
    );
}

const ORDERS_STATUS_FILTER_KEY = 'orders_status_filter';

function statusBoxes() {
    return Array.prototype.slice.call(
        document.querySelectorAll('#orders-filter-form input[name="status"]'),
    );
}

function readStatusFilter() {
    try {
        const raw = localStorage.getItem(ORDERS_STATUS_FILTER_KEY);
        return raw === null ? null : JSON.parse(raw);
    } catch (_e) {
        return null;
    }
}

function writeStatusFilter(values) {
    try {
        localStorage.setItem(ORDERS_STATUS_FILTER_KEY, JSON.stringify(values));
    } catch (_e) {}
}

function persistStatusFilter() {
    writeStatusFilter(
        statusBoxes()
            .filter((b) => b.checked)
            .map((b) => b.value),
    );
}

function restoreStatusFilter() {
    const saved = readStatusFilter();
    if (saved === null || saved.length === 0) return false;
    const boxes = statusBoxes();
    if (boxes.length === 0) return false;
    const serverChecked = boxes
        .filter((b) => b.checked)
        .map((b) => b.value)
        .sort();
    const savedSet = {};
    saved.forEach((s) => {
        savedSet[s] = true;
    });
    boxes.forEach((b) => {
        b.checked = savedSet[b.value] === true;
    });
    const savedSel = saved.slice().sort();
    return (
        serverChecked.length !== savedSel.length || serverChecked.some((v, i) => v !== savedSel[i])
    );
}

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'ord-toggle-materials', toggleOrderMaterials);
    window.krtEvents.on('click', 'ord-toggle-squadron-multi', toggleSquadronMulti);
    window.krtEvents.on('change', 'ord-toggle-squadron-all', toggleSquadronAll);
    window.krtEvents.on('change', 'ord-update-squadron-state', updateSquadronState);
}

document.addEventListener('click', (e) => {
    if (!e.target.closest('.multi-select-container')) {
        document
            .querySelectorAll('.multi-select-options.open')
            .forEach((o) => o.classList.remove('open'));
    }
});

document.addEventListener('DOMContentLoaded', () => {
    colorOrderAges(document);
    restoreOrderMaterials(document);
    const statusDiffers = restoreStatusFilter();
    const squadronDiffers = restoreSquadronFilter();
    if ((statusDiffers || squadronDiffers) && window.krtFetch) applyOrdersFilter();

    const filterForm = document.getElementById('orders-filter-form');
    if (filterForm && window.krtFetch) {
        filterForm.addEventListener('submit', (event) => {
            event.preventDefault();
            applyOrdersFilter();
        });
        filterForm.querySelectorAll('input[name="status"]').forEach((el) => {
            el.addEventListener('change', () => {
                persistStatusFilter();
                applyOrdersFilter();
            });
        });
    }
});

document.addEventListener('krt:swapped', (e) => {
    const container = e && e.detail ? e.detail.container : document;
    colorOrderAges(container);
    restoreOrderMaterials(container);
});
