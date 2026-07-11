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
 * Page module for the squadron-wide inventory admin view (templates/inventory-admin.html,
 * route /inventory/all), extracted verbatim from the template's two inline script blocks
 * (issue #924 part 2).
 *
 * Covers: the note modal (open/close/counter/save/remove with the krtCsrf retry-on-403 and
 * syncVersion DOM writeback), the multi-select filter widgets with the AJAX table re-swap
 * (filterInventory replaces #tableContainer via outerHTML), material-group / stack tree
 * expansion with localStorage persistence plus lazy stack-entry loading and pagination,
 * the book-out (DISCARD/SELL) and Umbuchen (TRANSFER) modals writing through
 * window.krtFetch, the admin delete-all flow, the association-select PUT with syncVersion,
 * and the delegated inv-admin-* krtEvents bindings.
 *
 * Loaded as a classic synchronous script at the end of the body, immediately after the
 * inline i18n bootstrap block that declares the dictionaries listed in the global directive
 * below; the parse-time DOM lookups (delete-all elements, bookOutForm / umbuchenForm) rely
 * on that document position.
 */
/* global stackEntriesI18n, inventoryConflictI18n, bookOutI18n, umbuchenI18n, assocI18n, showInventoryToast, openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote */

function toggleMultiSelect(id) {
    const el = document.getElementById(id);
    const isOpened = el.classList.contains('open');

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
        allText: header.getAttribute('data-all') || 'Alle',
        selectedTextStr: header.getAttribute('data-selected') || 'gewählt',
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

    const headerSpan = document.getElementById(headerId).querySelector('.selected-text');
    if (count === total || count === 0) {
        headerSpan.innerText = translations.allText;
    } else if (count === 1) {
        headerSpan.innerText = firstChecked;
    } else {
        headerSpan.innerText = count + ' ' + translations.selectedTextStr;
    }
}

document.addEventListener('click', function (e) {
    if (!e.target.closest('.multi-select-container')) {
        document.querySelectorAll('.multi-select-options').forEach(function (opt) {
            if (opt.classList.contains('open')) {
                opt.classList.remove('open');
            }
        });
    }
});

function collectChecked(className) {
    const boxes = document.getElementsByClassName(className);
    const values = [];
    for (let i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) values.push(boxes[i].value);
    }
    return values;
}

function filterInventory() {
    const activeMats = collectChecked('matCheck');
    const activeJobOrders = collectChecked('jobOrderCheck');
    const activeMissions = collectChecked('missionCheck');

    const minQualitySelect = document.getElementById('minQuality');
    const minQuality = minQualitySelect ? minQualitySelect.value : '';

    const container = document.getElementById('tableContainer');
    if (!container) return;

    container.style.opacity = '0.5';
    container.style.pointerEvents = 'none';

    const url = new URL(window.location.origin + '/inventory/all');
    url.searchParams.append('fragment', 'true');

    activeMats.forEach((m) => url.searchParams.append('materialIds', m));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));

    // Update browser URL (without fragment param) so the filter is bookmarkable / reloadable
    const visibleUrl = new URL(window.location.origin + '/inventory/all');
    activeMats.forEach((m) => visibleUrl.searchParams.append('materialIds', m));
    if (minQuality) visibleUrl.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => visibleUrl.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => visibleUrl.searchParams.append('missionIds', m));
    try {
        window.history.replaceState({}, '', visibleUrl.toString());
    } catch {
        /* ignore */
    }

    fetch(url, {
        method: 'GET',
        headers: {
            'X-Requested-With': 'XMLHttpRequest',
        },
    })
        .then((response) => response.text())
        .then((html) => {
            container.outerHTML = html;
            // A fragment swap does not re-fire DOMContentLoaded, so re-apply the persisted tree
            // expansion (REQ-INV-002) — otherwise a filter change or a modal write collapses every
            // row the user had opened.
            restoreExpandedTree();
        })
        .catch((error) => {
            console.error('Error fetching filtered inventory:', error);
            container.style.opacity = '1.0';
            container.style.pointerEvents = 'auto';
        });
}

function resetInventoryFilter() {
    ['matCheck', 'jobOrderCheck', 'missionCheck'].forEach(function (cls) {
        const boxes = document.getElementsByClassName(cls);
        for (let i = 0; i < boxes.length; i++) boxes[i].checked = false;
    });
    ['matAll', 'jobOrderAll', 'missionAll'].forEach(function (id) {
        const el = document.getElementById(id);
        if (el) el.checked = false;
    });
    const minQualitySelect = document.getElementById('minQuality');
    if (minQualitySelect) minQualitySelect.value = '';
    if (document.getElementById('materialHeader'))
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    if (document.getElementById('jobOrderHeader'))
        updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
    if (document.getElementById('missionHeader'))
        updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    filterInventory();
}

document.addEventListener('DOMContentLoaded', function () {
    if (document.getElementsByClassName('matCheck').length > 0) {
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    }
    if (document.getElementsByClassName('jobOrderCheck').length > 0) {
        updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
    }
    if (document.getElementsByClassName('missionCheck').length > 0) {
        updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    }
});

// ===================== Lager tree expand/collapse view-state persistence (REQ-INV-002) =========
// The grouped tree (material group -> owner/location stack -> lazy leaf entries) is re-rendered in
// place on every grouped-table re-swap: a filter change AND — the bug this guards — every modal
// write (book-out and Umbuchen both call filterInventory in their onSuccess). Because a fragment
// swap replaces #inventoryTable wholesale and does NOT re-fire DOMContentLoaded, the expanded state
// must be re-applied explicitly after each swap; otherwise closing a modal collapses every row the
// user had opened. Both the material-group expansion (persisted as expanded_rows_lager_*) and the
// stack expansion (persisted as expanded_stacks_lager_*) live in localStorage, keyed per user, and
// are re-applied by restoreExpandedTree().

// The per-user localStorage suffix taken from the tree's data-user-id, or null when the table is
// absent / anonymous (nothing is then persisted).
function lagerUserId() {
    const table = document.getElementById('inventoryTable');
    const userId = table ? table.getAttribute('data-user-id') : null;
    return userId && userId !== 'unknown' ? userId : null;
}

// localStorage key holding the array of expanded material-group ids.
function groupStorageKey() {
    const userId = lagerUserId();
    return userId ? 'expanded_rows_lager_' + userId : null;
}

// localStorage key holding the array of expanded stack ids.
function stackStorageKey() {
    const userId = lagerUserId();
    return userId ? 'expanded_stacks_lager_' + userId : null;
}

// A stack's identity is exactly the page-less stack-entries URL its data-attributes build, so the
// same stack maps to the same key across re-renders and a /all stack can never collide with a /my
// one (they carry a different path prefix).
function stackKey(headerRow) {
    return buildStackEntriesUrl(headerRow, null);
}

// Reads a persisted expansion array, tolerating absent / corrupt storage.
function readExpanded(key) {
    if (!key) return [];
    try {
        return JSON.parse(localStorage.getItem(key) || '[]');
    } catch (e) {
        console.warn('LocalStorage error', e);
        return [];
    }
}

// Persists an expansion array, tolerating a storage write failure (quota / privacy mode).
function writeExpanded(key, values) {
    if (!key) return;
    try {
        localStorage.setItem(key, JSON.stringify(values));
    } catch (e) {
        console.warn('LocalStorage error', e);
    }
}

// Re-applies the persisted material-group expansion to the freshly rendered tree.
function restoreExpandedGroups() {
    const expandedRows = readExpanded(groupStorageKey());
    if (expandedRows.length === 0) return;
    document.querySelectorAll('.tree-row--group').forEach(function (row) {
        const materialId = row.getAttribute('data-material-id');
        if (materialId && expandedRows.includes(materialId)) {
            const nextRow = row.nextElementSibling;
            const icon = row.querySelector('.toggle-icon');
            if (nextRow && nextRow.classList.contains('tree-group-items')) {
                nextRow.style.display = 'block';
                if (icon) icon.textContent = '▼';
            }
        }
    });
}

// Re-applies the persisted stack expansion and re-triggers the lazy entry load for each restored
// stack (the re-rendered header comes back with data-stack-loaded="false", so the leaf rows —
// carrying the fresh post-write amounts — are fetched again).
function restoreExpandedStacks() {
    const expandedStacks = readExpanded(stackStorageKey());
    if (expandedStacks.length === 0) return;
    document.querySelectorAll('.stack-header').forEach(function (row) {
        if (!expandedStacks.includes(stackKey(row))) return;
        const nextRow = row.nextElementSibling;
        const icon = row.querySelector('.toggle-icon');
        if (nextRow && nextRow.classList.contains('tree-stack-entries')) {
            nextRow.style.display = 'block';
            if (icon) icon.textContent = '▼';
            if (row.getAttribute('data-stack-loaded') !== 'true') {
                loadStackEntries(row, 0);
            }
        }
    });
}

// Restores the whole tree (groups first, then their stacks) — run on initial load and after every
// in-place grouped-table re-swap.
function restoreExpandedTree() {
    restoreExpandedGroups();
    restoreExpandedStacks();
}

function toggleGroup(row) {
    const nextRow = row.nextElementSibling;
    const icon = row.querySelector('.toggle-icon');
    const materialId = row.getAttribute('data-material-id');
    if (!nextRow || !nextRow.classList.contains('tree-group-items')) return;

    const key = groupStorageKey();
    const expandedRows = readExpanded(key);
    if (nextRow.style.display === 'none') {
        nextRow.style.display = 'block';
        if (icon) icon.textContent = '▼';
        if (materialId && !expandedRows.includes(materialId)) {
            expandedRows.push(materialId);
            writeExpanded(key, expandedRows);
        }
    } else {
        nextRow.style.display = 'none';
        if (icon) icon.textContent = '▶';
        if (materialId) {
            writeExpanded(
                key,
                expandedRows.filter((id) => id !== materialId),
            );
        }
    }
}

function toggleStack(row) {
    const nextRow = row.nextElementSibling;
    const icon = row.querySelector('.toggle-icon');
    if (!nextRow || !nextRow.classList.contains('tree-stack-entries')) return;
    const key = stackStorageKey();
    const expandedStacks = readExpanded(key);
    const id = stackKey(row);
    if (nextRow.style.display === 'none') {
        nextRow.style.display = 'block';
        if (icon) icon.textContent = '▼';
        // Persist so a later in-place re-swap (filter change or modal write) re-opens this stack.
        if (id && !expandedStacks.includes(id)) {
            expandedStacks.push(id);
            writeExpanded(key, expandedStacks);
        }
        // Append-only Lager: a stack's entries are not inlined. Fetch them on first
        // expand from /inventory/all/stack/entries (ADR-0003, REQ-INV-002); subsequent
        // toggles just reveal the already-loaded rows.
        if (row.getAttribute('data-stack-loaded') !== 'true') {
            loadStackEntries(row, 0);
        }
    } else {
        nextRow.style.display = 'none';
        if (icon) icon.textContent = '▶';
        if (id) {
            writeExpanded(
                key,
                expandedStacks.filter((k) => k !== id),
            );
        }
    }
}

// Builds the lazy stack-entries fetch URL from the stack-key data-attributes the server
// stamped on the stack-header row. A global stack is per-owner, so userId is part of the
// key; the global Lager is non-personal, so no personal flag is sent. An absent dimension
// is omitted so the backend's null-safe match selects rows where it is itself absent.
function buildStackEntriesUrl(headerRow, page) {
    const params = new URLSearchParams();
    params.set('materialId', headerRow.getAttribute('data-material-id'));
    params.set('userId', headerRow.getAttribute('data-user-id'));
    params.set('locationId', headerRow.getAttribute('data-location-id'));
    const quality = headerRow.getAttribute('data-quality');
    if (quality !== null && quality !== '') params.set('quality', quality);
    const jobOrderId = headerRow.getAttribute('data-job-order-id');
    if (jobOrderId) params.set('jobOrderId', jobOrderId);
    const missionId = headerRow.getAttribute('data-mission-id');
    if (missionId) params.set('missionId', missionId);
    const owningOrgUnitId = headerRow.getAttribute('data-owning-org-unit-id');
    if (owningOrgUnitId) params.set('owningOrgUnitId', owningOrgUnitId);
    if (page != null) params.set('page', page);
    return '/inventory/all/stack/entries?' + params.toString();
}

// Replaces a stack's entries container with a single status line (loading / error),
// built via textContent so the i18n string is never interpreted as HTML.
function setStackEntriesStatus(content, message, isError) {
    content.innerHTML = '';
    const div = document.createElement('div');
    div.className = 'stack-entries-status';
    if (isError) div.classList.add('hud-box-error');
    div.style.padding = '1rem 2.5rem';
    div.style.color = 'var(--color-gray-2)';
    div.textContent = message;
    content.appendChild(div);
}

// Fetches one page of a stack's entries and injects the server-rendered fragment. The
// injected rows carry the same data-trigger hooks as before, so the page's delegated
// krtEvents handlers (book-out, note, association) keep working without re-binding.
function loadStackEntries(headerRow, page) {
    const entriesRow = headerRow.nextElementSibling;
    if (!entriesRow) return;
    const content = entriesRow.querySelector('.stack-entries-content');
    if (!content) return;
    setStackEntriesStatus(content, stackEntriesI18n.loading, false);
    fetch(buildStackEntriesUrl(headerRow, page), {
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
    })
        .then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.text();
        })
        .then(function (html) {
            content.innerHTML = html;
            headerRow.setAttribute('data-stack-loaded', 'true');
        })
        .catch(function (e) {
            console.error('Failed to load stack entries', e);
            setStackEntriesStatus(content, stackEntriesI18n.error, true);
        });
}

// Pagination handler: re-fetches the target page for the stack owning the clicked button
// and replaces the entries container in place.
function goToStackEntriesPage(btn) {
    const entriesRow = btn.closest('.tree-stack-entries');
    if (!entriesRow) return;
    const headerRow = entriesRow.previousElementSibling;
    if (!headerRow) return;
    const page = parseInt(btn.getAttribute('data-page'), 10);
    loadStackEntries(headerRow, isNaN(page) ? 0 : page);
}

// Restore the persisted tree expansion on initial load (the same restore runs after each in-place
// grouped-table re-swap — see filterInventory).
document.addEventListener('DOMContentLoaded', restoreExpandedTree);

document.addEventListener('DOMContentLoaded', function () {
    const matSelect = document.getElementById('materialId');
    if (matSelect) {
        matSelect.addEventListener('change', function () {
            filterJobOrdersByMaterial(this.value);
        });
    }
});

function filterJobOrdersByMaterial(matId) {
    const jobSelect = document.getElementById('jobOrderId');
    if (!jobSelect) return;

    let hasSelectedValidOption = false;

    for (let i = 1; i < jobSelect.options.length; i++) {
        const option = jobSelect.options[i];

        if (!matId) {
            option.style.display = '';
            option.disabled = false;
            if (option.selected) hasSelectedValidOption = true;
        } else {
            const materialsStr = option.getAttribute('data-materials');
            if (materialsStr) {
                const materials = materialsStr.split(',');
                if (materials.includes(matId)) {
                    option.style.display = '';
                    option.disabled = false;
                    if (option.selected) hasSelectedValidOption = true;
                } else {
                    option.style.display = 'none';
                    option.disabled = true;
                }
            } else {
                option.style.display = 'none';
                option.disabled = true;
            }
        }
    }

    if (jobSelect.selectedIndex > 0 && !hasSelectedValidOption) {
        jobSelect.value = '';
    }
}

function updateAmountFromTarget() {
    const targetAmountInput = document.getElementById('targetAmount');
    const amountInput = document.getElementById('amount');
    const maxAmountInput = document.getElementById('maxAmount');

    if (targetAmountInput && amountInput && maxAmountInput) {
        const target = window.krtScuInput.parse(targetAmountInput.value);
        const max = parseFloat(maxAmountInput.value) || 0;

        if (!isNaN(target)) {
            const diff = Math.max(0, max - target);
            amountInput.value = Number(diff.toFixed(3));
        }
    }
}

function updateTargetFromAmount() {
    const targetAmountInput = document.getElementById('targetAmount');
    const amountInput = document.getElementById('amount');
    const maxAmountInput = document.getElementById('maxAmount');

    if (targetAmountInput && amountInput && maxAmountInput) {
        const amount = window.krtScuInput.parse(amountInput.value);
        const max = parseFloat(maxAmountInput.value) || 0;

        if (!isNaN(amount)) {
            const diff = Math.max(0, max - amount);
            targetAmountInput.value = Number(diff.toFixed(3));
        }
    }
}

function toggleBookOutTypeFields() {
    const typeSell = document.querySelector('input[name="type"][value="SELL"]').checked;
    const sellFields = document.getElementById('sellFields');
    const terminal = document.getElementById('terminal');
    const sellAmount = document.getElementById('sellAmount');
    if (typeSell) {
        sellFields.style.display = 'block';
        terminal.required = true;
        sellAmount.required = true;
    } else {
        sellFields.style.display = 'none';
        terminal.required = false;
        sellAmount.required = false;
    }
    const submitBtn = document.getElementById('bookOutSubmitBtn');
    if (submitBtn) {
        submitBtn.textContent = submitBtn.getAttribute(
            typeSell ? 'data-text-sell' : 'data-text-discard',
        );
    }
}

// ===================== Umbuchen (rebooking) modal — transfer relocated from Ausbuchen =========
// The squadron-wide /all view rebooks only between Ort/Nutzer/OrgUnit (the former book-out
// TRANSFER); the personal<->shared toggle is owner-scoped and lives on /inventory/my.
let umbuchenItemId = null;
let umbuchenInFlight = false;

function updateUmbuchenAmountFromTarget() {
    const targetEl = document.getElementById('umbuchenTargetAmount');
    const amountEl = document.getElementById('umbuchenAmount');
    const maxEl = document.getElementById('umbuchenMaxAmount');
    if (targetEl && amountEl && maxEl) {
        const target = window.krtScuInput.parse(targetEl.value);
        const max = parseFloat(maxEl.value) || 0;
        if (!isNaN(target)) {
            amountEl.value = Number(Math.max(0, max - target).toFixed(3));
        }
    }
}

function updateUmbuchenTargetFromAmount() {
    const targetEl = document.getElementById('umbuchenTargetAmount');
    const amountEl = document.getElementById('umbuchenAmount');
    const maxEl = document.getElementById('umbuchenMaxAmount');
    if (targetEl && amountEl && maxEl) {
        const amount = window.krtScuInput.parse(amountEl.value);
        const max = parseFloat(maxEl.value) || 0;
        if (!isNaN(amount)) {
            targetEl.value = Number(Math.max(0, max - amount).toFixed(3));
        }
    }
}

function refreshUmbuchenTransferOrgUnitPicker() {
    const wrapper = document.getElementById('umbuchenTargetOwningOrgUnitWrapper');
    const select = document.getElementById('umbuchenTargetOwningOrgUnitId');
    const userSelect = document.getElementById('umbuchenTargetUserId');
    if (!wrapper || !select || !userSelect) return;
    const targetUserId = userSelect.value;
    while (select.options.length > 1) select.remove(1);
    select.value = '';
    if (!targetUserId) {
        wrapper.style.display = 'none';
        return;
    }
    fetch('/api/v1/users/' + encodeURIComponent(targetUserId) + '/memberships', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            if (!Array.isArray(memberships) || memberships.length <= 1) {
                wrapper.style.display = 'none';
                return;
            }
            memberships.forEach(function (opt) {
                const o = document.createElement('option');
                o.value = opt.orgUnitId;
                o.textContent = opt.orgUnitName;
                select.appendChild(o);
            });
            wrapper.style.display = 'block';
        })
        .catch(function () {
            wrapper.style.display = 'none';
        });
}

function openUmbuchenModal(id, amount, version, materialId, userId, locationId, quantityType) {
    umbuchenItemId = id;
    const isScu = quantityType !== 'PIECE';
    const amountEl = document.getElementById('umbuchenAmount');
    const targetEl = document.getElementById('umbuchenTargetAmount');
    amountEl.setAttribute('step', isScu ? '0.001' : '1');
    targetEl.setAttribute('step', isScu ? '0.001' : '1');
    const targetHint = document.getElementById('umbuchen-target-scu-hint');
    const amountHint = document.getElementById('umbuchen-amount-scu-hint');
    if (targetHint) targetHint.style.display = isScu ? '' : 'none';
    if (amountHint) amountHint.style.display = isScu ? '' : 'none';
    amountEl.value = amount;
    amountEl.max = amount;
    targetEl.value = 0;
    document.getElementById('umbuchenMaxAmount').value = amount;
    document.getElementById('umbuchenVersion').value = version;
    const amountOf = document.getElementById('umbuchenAmountOfText');
    if (amountOf)
        amountOf.textContent = amountOf.getAttribute('data-template').replace('{0}', amount);
    const umbuchenUser = document.getElementById('umbuchenTargetUserId');
    // The target-user <select> is upgraded into a searchable combobox; its id moves to the
    // hidden input, so use the combobox API to sync both the value and the visible label.
    if (umbuchenUser.krtCombobox) {
        umbuchenUser.krtCombobox.setValue(userId);
    } else {
        umbuchenUser.value = userId;
    }
    document.getElementById('umbuchenTargetLocationId').value = locationId;
    refreshUmbuchenTransferOrgUnitPicker();
    document.getElementById('umbuchenModal').style.display = 'block';
}

function closeUmbuchenModal() {
    if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
    document.getElementById('umbuchenModal').style.display = 'none';
}

function submitUmbuchen(event) {
    if (event && event.defaultPrevented) return;
    if (event) event.preventDefault();
    if (umbuchenInFlight || !window.krtFetch || !umbuchenItemId) return;
    const amountEl = document.getElementById('umbuchenAmount');
    const amount = window.krtScuInput
        ? window.krtScuInput.parse(amountEl.value)
        : parseFloat(amountEl.value);
    const submitBtn = document.getElementById('umbuchenSubmitBtn');
    const payload = {
        amount: amount,
        type: 'TRANSFER',
        targetUserId: document.getElementById('umbuchenTargetUserId').value || null,
        targetLocationId: document.getElementById('umbuchenTargetLocationId').value || null,
        targetOwningOrgUnitId:
            document.getElementById('umbuchenTargetOwningOrgUnitId').value || null,
        version: parseInt(document.getElementById('umbuchenVersion').value, 10),
    };
    umbuchenInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    window.krtFetch
        .write({
            method: 'POST',
            url: '/inventory/' + umbuchenItemId + '/transfer',
            payload: payload,
            successMessage: umbuchenI18n.success,
            errorMessage: umbuchenI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess: function () {
                closeUmbuchenModal();
                filterInventory();
            },
        })
        .then(function () {
            umbuchenInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

// The item id the open book-out modal targets; set when the modal opens, read by submitBookOut.
let bookOutItemId = null;
// Guards against a second submit (Enter / rapid click) landing while the first write is in
// flight — a duplicate book-out on the same version would otherwise 409.
let bookOutInFlight = false;

// #577 part 2: submit the book-out in place via the shared krtFetch/krtCsrf foundation, reusing
// the existing POST /inventory/{id}/transfer proxy (the backend book-out endpoint, equivalent
// for DISCARD / TRANSFER / SELL). On success the grouped table is re-swapped (the server
// regroups) instead of the page reloading; the classic POST stays the no-JS fallback.
function submitBookOut(event) {
    // scu-decimal-input.js canonicalises + validates the amount fields in the capture phase
    // first; if it found an invalid amount it already blocked the submit (preventDefault +
    // reportValidity). Respect that and do not fire the AJAX write.
    if (event.defaultPrevented) {
        return;
    }
    event.preventDefault();
    if (bookOutInFlight || !window.krtFetch || !bookOutItemId) {
        return;
    }
    const typeInput = document.querySelector('input[name="type"]:checked');
    const type = typeInput ? typeInput.value : 'DISCARD';
    const amountEl = document.getElementById('amount');
    const amount = window.krtScuInput
        ? window.krtScuInput.parse(amountEl.value)
        : parseFloat(amountEl.value);
    const sellAmountEl = document.getElementById('sellAmount');
    // Ausbuchen now only discards or sells — the TRANSFER (Umbuchung) mode moved to the
    // dedicated Umbuchen modal. The transfer-only fields stay null on the book-out payload.
    const payload = {
        amount: amount,
        type: type,
        terminal: type === 'SELL' ? document.getElementById('terminal').value || null : null,
        sellAmount:
            type === 'SELL' && sellAmountEl.value !== '' ? Number(sellAmountEl.value) : null,
        version: parseInt(document.getElementById('version').value, 10),
    };
    const submitBtn = document.getElementById('bookOutSubmitBtn');
    bookOutInFlight = true;
    if (submitBtn) {
        submitBtn.disabled = true;
    }
    window.krtFetch
        .write({
            method: 'POST',
            url: '/inventory/' + bookOutItemId + '/transfer',
            payload: payload,
            successMessage: bookOutI18n.success,
            errorMessage: bookOutI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess: function () {
                closeBookOutModal();
                filterInventory();
            },
        })
        .then(function () {
            bookOutInFlight = false;
            if (submitBtn) {
                submitBtn.disabled = false;
            }
        });
}

function openBookOutModal(id, amount, version, materialId, userId, locationId, quantityType) {
    bookOutItemId = id;
    const bookOutForm = document.getElementById('bookOutForm');
    bookOutForm.action = window.safeSameOriginUrl(
        '/inventory/' + id + '/book-out',
        bookOutForm.action,
    );

    const amountInput = document.getElementById('amount');
    const targetAmountInput = document.getElementById('targetAmount');
    const isScu = quantityType !== 'PIECE';
    if (quantityType === 'PIECE') {
        amountInput.setAttribute('step', '1');
        targetAmountInput.setAttribute('step', '1');
    } else {
        amountInput.setAttribute('step', '0.001');
        targetAmountInput.setAttribute('step', '0.001');
    }
    const targetScuHint = document.getElementById('bookout-target-scu-hint');
    const amountScuHint = document.getElementById('bookout-amount-scu-hint');
    if (targetScuHint) targetScuHint.style.display = isScu ? '' : 'none';
    if (amountScuHint) amountScuHint.style.display = isScu ? '' : 'none';

    amountInput.value = amount;
    document.getElementById('amount').max = amount;
    document.getElementById('targetAmount').value = 0;
    document.getElementById('maxAmount').value = amount;
    const amountOfSpan = document.getElementById('amountOfText');
    if (amountOfSpan) {
        amountOfSpan.textContent = amountOfSpan
            .getAttribute('data-template')
            .replace('{0}', amount);
    }
    document.getElementById('version').value = version;
    document.querySelector('input[name="type"][value="DISCARD"]').checked = true;
    toggleBookOutTypeFields();

    const typeSellRadio = document.querySelector('input[name="type"][value="SELL"]');
    const sellNotPossibleReason = document.getElementById('sellNotPossibleReason');
    typeSellRadio.disabled = true;
    if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'none';

    const terminalSelect = document.getElementById('terminal');
    terminalSelect.innerHTML = '<option value="" disabled selected>...laden...</option>';
    if (materialId) {
        fetch('/api/proxy/materials/' + materialId + '/terminals')
            .then((r) => {
                if (!r.ok) throw new Error('Network response was not ok');
                return r.json();
            })
            .then((data) => {
                terminalSelect.innerHTML =
                    '<option value="" disabled selected>...wählen...</option>';
                if (data && data.length > 0) {
                    typeSellRadio.disabled = false;
                    data.forEach((terminal) => {
                        const opt = document.createElement('option');
                        opt.value = terminal.terminalName;
                        if (terminal.priceSell && terminal.priceSell > 0) {
                            opt.textContent =
                                terminal.terminalName + ' (' + terminal.priceSell + ' aUEC)';
                        } else {
                            opt.textContent = terminal.terminalName;
                        }
                        terminalSelect.appendChild(opt);
                    });
                } else {
                    terminalSelect.innerHTML =
                        '<option value="" disabled selected>Keine Terminals gefunden</option>';
                    typeSellRadio.disabled = true;
                    if (typeSellRadio.checked) {
                        document.querySelector('input[name="type"][value="DISCARD"]').checked =
                            true;
                        toggleBookOutTypeFields();
                    }
                    if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'inline';
                }
            })
            .catch((e) => {
                console.error('Error loading terminals:', e);
                terminalSelect.innerHTML =
                    '<option value="" disabled selected>Fehler beim Laden</option>';
                typeSellRadio.disabled = true;
                if (typeSellRadio.checked) {
                    document.querySelector('input[name="type"][value="DISCARD"]').checked = true;
                    toggleBookOutTypeFields();
                }
                if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'inline';
            });
    } else {
        terminalSelect.innerHTML =
            '<option value="" disabled selected>Kein Material gewählt</option>';
        typeSellRadio.disabled = true;
        if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'inline';
    }

    document.getElementById('bookOutModal').style.display = 'block';
}

function closeBookOutModal() {
    if (typeof window.resetUnsavedChanges === 'function') {
        window.resetUnsavedChanges();
    }
    document.getElementById('bookOutModal').style.display = 'none';
}

window.onclick = function (event) {
    const modal = document.getElementById('bookOutModal');
    if (event.target === modal) {
        modal.style.display = 'none';
    }
};

// ---- Delete All Global Inventory (Admin) ----
(function () {
    const deleteBtn = document.getElementById('delete-all-global-inventory-btn');
    const modal = document.getElementById('delete-all-global-inventory-modal');
    const confirmBtn = document.getElementById('delete-all-global-inventory-confirm-btn');
    const cancelBtn = document.getElementById('delete-all-global-inventory-cancel-btn');

    if (!deleteBtn || !modal || !confirmBtn || !cancelBtn) return;

    function closeModal() {
        modal.style.display = 'none';
    }

    deleteBtn.addEventListener('click', function () {
        modal.style.display = 'flex';
    });

    cancelBtn.addEventListener('click', closeModal);

    window.addEventListener('click', function (e) {
        if (e.target === modal) {
            closeModal();
        }
    });

    confirmBtn.addEventListener('click', async function () {
        if (!window.krtFetch) return;
        confirmBtn.disabled = true;
        cancelBtn.disabled = true;
        try {
            // #577: delete-all in place via the shared krtFetch (CSRF + retry-on-403). On
            // success everything is gone, so re-render the (now empty) grouped table via the
            // existing filter swap instead of a full-page reload.
            await window.krtFetch.write({
                method: 'DELETE',
                url: '/inventory/all',
                toast: false,
                errorMessage: deleteBtn.getAttribute('data-error-failed'),
                onSuccess: function () {
                    showInventoryToast('success', deleteBtn.getAttribute('data-success'));
                    filterInventory();
                },
            });
            closeModal();
        } finally {
            confirmBtn.disabled = false;
            cancelBtn.disabled = false;
        }
    });
})();

async function updateInventoryAssociation(selectElement) {
    const id = selectElement.getAttribute('data-id');
    // Serialize per inventory row so the row's two association selects (jobOrderId + missionId) can
    // be changed back-to-back without the second shipping a stale version and 409-ing: the actual
    // write (which reads the version + builds the dto) runs only after the previous same-row write
    // settled and synced the fresh version back onto the row's data-version controls.
    if (window.krtFetch && typeof window.krtFetch.serialize === 'function') {
        return window.krtFetch.serialize('inv-assoc:' + id, function () {
            return doUpdateInventoryAssociation(selectElement, id);
        });
    }
    return doUpdateInventoryAssociation(selectElement, id);
}

async function doUpdateInventoryAssociation(selectElement, id) {
    const version = parseInt(selectElement.getAttribute('data-version'));

    const materialId = selectElement.getAttribute('data-material-id');
    const locationId = selectElement.getAttribute('data-location-id');
    const quality = parseInt(selectElement.getAttribute('data-quality'));
    const amount = parseFloat(selectElement.getAttribute('data-amount'));
    const personal = selectElement.getAttribute('data-personal') === 'true';

    const tr = selectElement.closest('.tree-row');
    const allSelects = tr.querySelectorAll('.association-select');
    let jobOrderId = null;
    let missionId = null;

    allSelects.forEach((s) => {
        const f = s.getAttribute('data-field');
        if (f === 'jobOrderId' && s.value) jobOrderId = s.value;
        if (f === 'missionId' && s.value) missionId = s.value;
    });

    const dto = {
        materialId: materialId,
        locationId: locationId,
        quality: quality,
        amount: amount,
        personal: personal,
        jobOrderId: jobOrderId,
        missionId: missionId,
        version: version,
    };

    // #577: CSRF via the shared krtCsrf single source of truth (REQ-FE-002) with retry-on-403.
    let headers = window.krtCsrf
        ? window.krtCsrf.headers()
        : { 'Content-Type': 'application/json' };

    function sendAssoc() {
        return fetch('/inventory/' + id + '/update-associations', {
            method: 'PUT',
            headers: headers,
            body: JSON.stringify(dto),
        });
    }

    try {
        let response = await sendAssoc();
        if (response.status === 403 && window.krtCsrf && window.krtCsrf.refresh) {
            const refreshed = await window.krtCsrf.refresh();
            if (refreshed) {
                headers = window.krtCsrf.headers();
                response = await sendAssoc();
            }
        }

        if (response.ok) {
            // Propagate the AUTHORITATIVE new version (from the response DTO) to every
            // data-version control in the leaf row via the shared syncVersion — replaces the
            // brittle client-side version+1 on the selects + book-out button.
            let updated = null;
            try {
                updated = await response.json();
            } catch {
                /* tolerate empty body */
            }
            if (updated && updated.version != null && window.krtFetch) {
                window.krtFetch.syncVersion(
                    selectElement.closest('.tree-row--leaf'),
                    updated.version,
                );
            }

            if (typeof window.showFrontendSuccessToast === 'function') {
                window.showFrontendSuccessToast(assocI18n.success);
            } else {
                console.info(assocI18n.success);
            }
        } else if (response.status === 409) {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.conflict);
            } else {
                console.error(assocI18n.conflict);
            }
            setTimeout(() => location.reload(), 2000);
        } else {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.failed);
            } else {
                console.error(assocI18n.failed);
            }
        }
    } catch (e) {
        console.error(e);
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(assocI18n.failed);
        } else {
            console.error(assocI18n.failed);
        }
    }
}

// CSP-safe delegated bindings (replaces the 28 inline on*= handlers across this template).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'inv-admin-toggle-multi', function (el) {
        toggleMultiSelect(el.getAttribute('data-multi-target'));
    });
    window.krtEvents.on('change', 'inv-admin-toggle-all', function (el) {
        toggleSelectAll(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterInventory();
    });
    window.krtEvents.on('change', 'inv-admin-update-state', function (el) {
        updateSelectState(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterInventory();
    });
    window.krtEvents.on('change', 'inv-admin-filter', filterInventory);
    window.krtEvents.on('click', 'inv-admin-reset-filter', resetInventoryFilter);
    window.krtEvents.on('click', 'inv-admin-toggle-group', function (el) {
        toggleGroup(el);
    });
    window.krtEvents.on('click', 'inv-admin-toggle-stack', function (el) {
        toggleStack(el);
    });
    window.krtEvents.on('click', 'inv-admin-stack-page', function (el) {
        goToStackEntriesPage(el);
    });
    window.krtEvents.on('change', 'inv-admin-update-assoc', function (el) {
        updateInventoryAssociation(el);
    });
    window.krtEvents.on('click', 'inv-admin-bookout', function (el) {
        openBookOutModal(
            el.getAttribute('data-id'),
            el.getAttribute('data-amount'),
            el.getAttribute('data-version'),
            el.getAttribute('data-material-id'),
            el.getAttribute('data-user-id'),
            el.getAttribute('data-location-id'),
            el.getAttribute('data-quantity-type'),
        );
    });
    window.krtEvents.on('click', 'inv-admin-umbuchen', function (el) {
        openUmbuchenModal(
            el.getAttribute('data-id'),
            el.getAttribute('data-amount'),
            el.getAttribute('data-version'),
            el.getAttribute('data-material-id'),
            el.getAttribute('data-user-id'),
            el.getAttribute('data-location-id'),
            el.getAttribute('data-quantity-type'),
        );
    });
    window.krtEvents.on('click', 'inv-admin-open-note', function (el) {
        openNoteModal(el);
    });
    window.krtEvents.on('click', 'inv-admin-close-bookout', closeBookOutModal);
    window.krtEvents.on('input', 'inv-admin-amount-from-target', updateAmountFromTarget);
    window.krtEvents.on('input', 'inv-admin-target-from-amount', updateTargetFromAmount);
    window.krtEvents.on('change', 'inv-admin-toggle-bookout-type', toggleBookOutTypeFields);
    window.krtEvents.on('click', 'inv-admin-close-umbuchen', closeUmbuchenModal);
    window.krtEvents.on(
        'input',
        'inv-admin-umbuchen-amount-from-target',
        updateUmbuchenAmountFromTarget,
    );
    window.krtEvents.on(
        'input',
        'inv-admin-umbuchen-target-from-amount',
        updateUmbuchenTargetFromAmount,
    );
    window.krtEvents.on(
        'change',
        'inv-admin-umbuchen-target-user-changed',
        refreshUmbuchenTransferOrgUnitPicker,
    );
    window.krtEvents.on('click', 'inv-admin-close-note', closeNoteModal);
    window.krtEvents.on('input', 'inv-admin-update-note-counter', updateNoteCounter);
    window.krtEvents.on('click', 'inv-admin-save-note', saveNote);
    window.krtEvents.on('click', 'inv-admin-remove-note', removeNote);
}

// The book-out form is a stable top-level element (outside the swapped table container), so a
// direct submit listener bound once survives the grouped-table re-swaps.
const bookOutFormEl = document.getElementById('bookOutForm');
if (bookOutFormEl) {
    bookOutFormEl.addEventListener('submit', submitBookOut);
}
const umbuchenFormEl = document.getElementById('umbuchenForm');
if (umbuchenFormEl) {
    umbuchenFormEl.addEventListener('submit', submitUmbuchen);
}
