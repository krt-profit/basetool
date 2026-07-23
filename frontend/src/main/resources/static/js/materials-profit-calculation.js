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

/*
 * Materials profit-calculation page module, extracted verbatim from the former inline script of
 * materials-profit-calculation.html (ADR-0069, follow-up to #924).
 *
 * Drives the ship + star-system multi-select filters, fetches the profit table from the
 * read-only /api/proxy endpoint and renders it, provides client-side column sorting, and
 * binds everything through the shared krtEvents delegated actions (profit-update / -sort /
 * -toggle-multi / -toggle-all / -update-state).
 *
 * The four Thymeleaf-interpolated status strings (window.krtProfitI18n) stay inline in the
 * page bootstrap this module reads.
 */

function toggleMultiSelect(id) {
    const el = document.getElementById(id);
    const isOpened = el.classList.contains('open');

    // Close all others
    document.querySelectorAll('.multi-select-options').forEach(function (opt) {
        opt.classList.remove('open');
    });

    if (!isOpened) {
        el.classList.add('open');
    }
}

function getTranslations(headerId) {
    const header = document.getElementById(headerId);
    return {
        allText: header.getAttribute('data-all'),
        selectedTextStr: header.getAttribute('data-selected'),
    };
}

function toggleSelectAll(allId, checkClass, headerId) {
    const isAllChecked = document.getElementById(allId).checked;
    const checkboxes = document.getElementsByClassName(checkClass);
    for (let i = 0; i < checkboxes.length; i++) {
        checkboxes[i].checked = isAllChecked;
    }
    updateSelectedText(checkboxes, headerId);
}

function updateSelectState(allId, checkClass, headerId) {
    const checkboxes = document.getElementsByClassName(checkClass);
    let allChecked = true;
    for (let i = 0; i < checkboxes.length; i++) {
        if (!checkboxes[i].checked) {
            allChecked = false;
            break;
        }
    }
    document.getElementById(allId).checked = allChecked;
    updateSelectedText(checkboxes, headerId);
}

function updateSelectedText(checkboxes, headerId) {
    const translations = getTranslations(headerId);
    let count = 0;
    const total = checkboxes.length;
    let firstChecked = null;
    for (let i = 0; i < checkboxes.length; i++) {
        if (checkboxes[i].checked) {
            count++;
            if (!firstChecked) firstChecked = checkboxes[i].previousElementSibling.innerText;
        }
    }

    const textElement = document.getElementById(headerId).querySelector('.selected-text');
    if (count === total) {
        textElement.innerText = translations.allText;
    } else if (count === 1) {
        textElement.innerText = firstChecked;
    } else {
        textElement.innerText = count + ' ' + translations.selectedTextStr;
    }
}

function sortTable(n, forcedDir) {
    const table = document.getElementById('resultsTable');
    let rows, switching, i, x, y, shouldSwitch, dir;
    switching = true;

    if (forcedDir) {
        dir = forcedDir;
    } else {
        dir = table.getAttribute('data-sort-dir') === 'asc' ? 'desc' : 'asc';
        if (table.getAttribute('data-sort-col') !== n.toString()) {
            dir = 'asc';
        }
    }

    table.setAttribute('data-sort-dir', dir);
    table.setAttribute('data-sort-col', n);

    while (switching) {
        switching = false;
        rows = table.getElementsByTagName('tbody')[0].getElementsByTagName('tr');

        for (i = 0; i < rows.length - 1; i++) {
            if (rows[i].cells.length < 2) continue; // Skip loading or no data rows

            shouldSwitch = false;
            x = rows[i].getElementsByTagName('td')[n];
            y = rows[i + 1].getElementsByTagName('td')[n];

            if (!x || !y) continue;

            const valX = x.textContent || x.innerText;
            const valY = y.textContent || y.innerText;

            if (n >= 1 && n <= 6) {
                // Numerische Sortierung
                let numX = parseFloat(
                    valX
                        .replace(/[^-0-9,.]/g, '')
                        .replace(/\./g, '')
                        .replace(',', '.'),
                );
                if (n === 4) {
                    // Marge hat Punkt als Dezimaltrenner durch toFixed(2)
                    numX = parseFloat(valX.replace(/[^0-9.-]/g, ''));
                }
                let numY = parseFloat(
                    valY
                        .replace(/[^-0-9,.]/g, '')
                        .replace(/\./g, '')
                        .replace(',', '.'),
                );
                if (n === 4) {
                    numY = parseFloat(valY.replace(/[^0-9.-]/g, ''));
                }

                if (isNaN(numX)) numX = -Infinity;
                if (isNaN(numY)) numY = -Infinity;

                if (dir === 'asc') {
                    if (numX > numY) {
                        shouldSwitch = true;
                        break;
                    }
                } else {
                    if (numX < numY) {
                        shouldSwitch = true;
                        break;
                    }
                }
            } else {
                // Alphabetische Sortierung
                if (dir === 'asc') {
                    if (valX.toLowerCase() > valY.toLowerCase()) {
                        shouldSwitch = true;
                        break;
                    }
                } else {
                    if (valX.toLowerCase() < valY.toLowerCase()) {
                        shouldSwitch = true;
                        break;
                    }
                }
            }
        }
        if (shouldSwitch) {
            rows[i].parentNode.insertBefore(rows[i + 1], rows[i]);
            switching = true;
        }
    }
    updateSortIndicators(n, dir);
}

function updateSortIndicators(columnIndex, direction) {
    for (let i = 0; i <= 6; i++) {
        const iconElement = document.getElementById('sort-icon-' + i);
        if (iconElement) {
            if (i === columnIndex) {
                iconElement.innerHTML = direction === 'asc' ? '▲' : '▼';
                iconElement.style.color = 'var(--color-primary)';
            } else {
                iconElement.innerHTML = '↕';
                iconElement.style.color = 'var(--color-gray-2)';
            }
        }
    }
}

async function updateProfitCalculation() {
    const shipId = document.getElementById('shipSelect').value;
    const body = document.getElementById('profitBody');

    if (!shipId) {
        body.innerHTML = `<tr><td colspan="7" class="profit-msg">${window.krtProfitI18n.selectShip}</td></tr>`;
        return;
    }

    body.innerHTML = `<tr><td colspan="7" class="profit-msg-loading">${window.krtProfitI18n.loading}</td></tr>`;

    try {
        let url = `/api/proxy/materials/profit-calculation?shipId=${shipId}`;

        const sysCheckboxes = document.getElementsByClassName('sysCheck');
        const activeSys = [];
        for (let i = 0; i < sysCheckboxes.length; i++) {
            if (sysCheckboxes[i].checked) activeSys.push(sysCheckboxes[i].value);
        }

        activeSys.forEach((s) => {
            url += `&starSystemNames=${encodeURIComponent(s)}`;
        });

        const response = await fetch(url);
        const data = await response.json();

        if (data.length === 0) {
            body.innerHTML = `<tr><td colspan="7" class="profit-msg">${window.krtProfitI18n.noData}</td></tr>`;
            return;
        }

        body.innerHTML = '';
        data.forEach((item) => {
            const tr = document.createElement('tr');

            tr.innerHTML = `
                <td class="text-left">
                    <a href="/materials/${escapeAttr(item.materialId)}">
                        <strong>${escapeHtml(item.materialName)}</strong>
                    </a>
                </td>
                <td>-${formatNumber(item.minBuyPrice)}</td>
                <td>+${formatNumber(item.maxSellPrice)}</td>
                <td>${item.profitPerScu > 0 ? '+' : ''}${formatNumber(item.profitPerScu)}</td>
                <td>${item.marginPercent.toFixed(2)}%</td>
                <td>${formatNumber(item.fullLoadCost)}</td>
                <td>${item.maxProfitFullLoad > 0 ? '+' : ''}${formatNumber(item.maxProfitFullLoad)}</td>
            `;
            body.appendChild(tr);
        });

        // Re-apply sorting if set
        const sortCol = document.getElementById('resultsTable').getAttribute('data-sort-col');
        const sortDir = document.getElementById('resultsTable').getAttribute('data-sort-dir');
        if (sortCol !== null && sortDir !== null) {
            sortTable(parseInt(sortCol), sortDir);
        }
    } catch (error) {
        console.error('Error fetching profit calculation:', error);
        body.innerHTML = `<tr><td colspan="7" class="text-danger profit-msg-error">${window.krtProfitI18n.fetchError}</td></tr>`;
    }
}

function formatNumber(num) {
    return new Intl.NumberFormat('de-DE').format(num);
}

// ===================== Per-browser filter persistence (REQ-UI-017) =============================
// One JSON object under a single localStorage key: {shipId: string|null, systems: [...]|null}.
// `shipId` stores the selected ship (null when none is selected — the server-preselected
// default then stays); `systems` stores the checked star-system subset, or null when zero or
// all boxes are checked (= "no filter", keeping later-added systems included). Absence of the
// key keeps the server-rendered defaults. All storage access is guarded so privacy modes that
// deny it degrade to the defaults instead of breaking the page.
const PROFIT_FILTER_KEY = 'profit_calculation_filters';

function readProfitFilterPref() {
    try {
        const raw = localStorage.getItem(PROFIT_FILTER_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_e) {
        return null; // corrupt value / storage unavailable: fall back to the defaults
    }
}

function writeProfitFilterPref(value) {
    try {
        localStorage.setItem(PROFIT_FILTER_KEY, JSON.stringify(value));
    } catch (_e) {
        /* storage unavailable */
    }
}

// Snapshots the current ship + system selection into localStorage. Called immediately on every
// filter change (ship select, system select-all toggle, single system toggle).
function persistProfitFilters() {
    const shipSelect = document.getElementById('shipSelect');
    const boxes = document.getElementsByClassName('sysCheck');
    const picked = [];
    for (let i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) picked.push(boxes[i].value);
    }
    writeProfitFilterPref({
        shipId: shipSelect && shipSelect.value !== '' ? shipSelect.value : null,
        systems: picked.length === 0 || picked.length === boxes.length ? null : picked,
    });
}

// Applies the saved selection to the widgets before the initial fetch. The saved ship is
// adopted only when its option still exists (a stale id keeps the server-preselected default);
// saved system values whose checkbox no longer exists are dropped silently, and an
// entirely-stale subset falls back to the all-checked default. The select-all box and the
// dropdown header text are re-synced via updateSelectState.
function restoreProfitFilters() {
    const saved = readProfitFilterPref();
    if (!saved || typeof saved !== 'object') return;
    const shipSelect = document.getElementById('shipSelect');
    if (shipSelect && typeof saved.shipId === 'string' && saved.shipId !== '') {
        for (let i = 0; i < shipSelect.options.length; i++) {
            if (shipSelect.options[i].value === saved.shipId) {
                shipSelect.value = saved.shipId;
                break;
            }
        }
    }
    if (Array.isArray(saved.systems) && saved.systems.length > 0) {
        const boxes = document.getElementsByClassName('sysCheck');
        if (boxes.length === 0) return;
        let any = false;
        for (let i = 0; i < boxes.length; i++) {
            const on = saved.systems.indexOf(boxes[i].value) >= 0;
            boxes[i].checked = on;
            if (on) any = true;
        }
        if (!any) {
            // Entirely-stale subset: fall back to the all-checked "no filter" default.
            for (let i = 0; i < boxes.length; i++) boxes[i].checked = true;
        }
        updateSelectState('sysAll', 'sysCheck', 'systemHeader');
    }
}

// Load initial data if default ship is selected
window.addEventListener('DOMContentLoaded', () => {
    // Handle clicks outside of multi-select to close them
    document.addEventListener('click', function (event) {
        if (!event.target.closest('.multi-select-container')) {
            document.querySelectorAll('.multi-select-options').forEach(function (opt) {
                opt.classList.remove('open');
            });
        }
    });

    // Restore the persisted ship + system selection first (REQ-UI-017); the single initial
    // fetch below then already runs with the restored state — no second fetch is triggered.
    restoreProfitFilters();

    const shipId = document.getElementById('shipSelect').value;
    if (shipId) {
        updateProfitCalculation();
    }
});

// CSP-safe delegated bindings (replace the eleven inline on*= handlers above —
// ship-select onchange, multi-select toggle, two "toggle-all + recompute" change patterns
// on the system filter, and the seven sortable-header onclick handlers).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    // Each filter change persists the selection per browser (REQ-UI-017) before the re-fetch.
    window.krtEvents.on('change', 'profit-update', function () {
        persistProfitFilters();
        updateProfitCalculation();
    });
    window.krtEvents.on('click', 'profit-toggle-multi', function (el) {
        toggleMultiSelect(el.getAttribute('data-multi-target'));
    });
    window.krtEvents.on('change', 'profit-toggle-all', function (el) {
        toggleSelectAll(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        persistProfitFilters();
        updateProfitCalculation();
    });
    window.krtEvents.on('change', 'profit-update-state', function (el) {
        updateSelectState(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        persistProfitFilters();
        updateProfitCalculation();
    });
    window.krtEvents.on('click', 'profit-sort', function (el) {
        sortTable(parseInt(el.getAttribute('data-sort-column'), 10));
    });
}
