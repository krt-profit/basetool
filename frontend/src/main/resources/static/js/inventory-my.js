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
 * Page module for the personal inventory ("Mein Lager") page (templates/inventory-my.html),
 * extracted verbatim from the former end-of-body inline script (#924 Part 2).
 *
 * Covers: bulk checkout (modal + POST /inventory/bulk-checkout via krtFetch), the note modal
 * (PUT /inventory/{id}/note with CSRF retry-on-403 and DOM version sync), the multi-select
 * filter dropdowns + AJAX fragment refresh of #myInventoryTableContainer (outerHTML swap),
 * tree group/stack expand-collapse with localStorage persistence and lazy stack-entry
 * pagination (REQ-INV-002), the book-out (ausbuchen) modal incl. terminal lookup for SELL,
 * the Umbuchen (rebooking/transfer) modal (REQ-INV-007), association select updates, and the
 * delegated krtEvents bindings plus the two direct submit listeners on the stable modal forms.
 *
 * Localized strings come from the small th:inline bootstrap block that precedes this script's
 * loader tag in inventory-my.html; this file must load as a classic synchronous script at the
 * same end-of-body position (after the modals and the toast fragment), never with defer.
 */

/* global stackEntriesI18n, bulkI18n, inventoryConflictI18n, bookOutI18n, umbuchenI18n, assocI18n, showInventoryToast, openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote */

function getCheckedItemIds() {
    const boxes = document.querySelectorAll('.inventory-item-checkbox:checked');
    const ids = [];
    boxes.forEach(function (cb) {
        ids.push(cb.getAttribute('data-id'));
    });
    return ids;
}

function updateBulkCheckoutState() {
    const ids = getCheckedItemIds();
    const btn = document.getElementById('bulkCheckoutBtn');
    const countSpan = document.getElementById('bulkCheckoutCount');
    if (btn) btn.disabled = ids.length === 0;
    if (countSpan) countSpan.textContent = ids.length > 0 ? '(' + ids.length + ')' : '';
    // Update group-select-all checkboxes
    document.querySelectorAll('.group-select-all').forEach(function (groupCb) {
        const materialId = groupCb.getAttribute('data-material-id');
        const groupBoxes = document.querySelectorAll(
            '.inventory-item-checkbox[data-material-id="' + materialId + '"]',
        );
        let allChecked = groupBoxes.length > 0;
        groupBoxes.forEach(function (cb) {
            if (!cb.checked) allChecked = false;
        });
        groupCb.checked = allChecked;
        groupCb.indeterminate =
            !allChecked &&
            Array.from(groupBoxes).some(function (cb) {
                return cb.checked;
            });
    });
}

function toggleGroupCheckboxes(groupCb) {
    const materialId = groupCb.getAttribute('data-material-id');
    const groupBoxes = document.querySelectorAll(
        '.inventory-item-checkbox[data-material-id="' + materialId + '"]',
    );
    groupBoxes.forEach(function (cb) {
        cb.checked = groupCb.checked;
    });
    updateBulkCheckoutState();
}

function openBulkCheckoutModal() {
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        showInventoryToast('error', bulkI18n.errorEmpty);
        return;
    }
    const msg = bulkI18n.modalMessage.replace('{0}', ids.length);
    const msgEl = document.getElementById('bulkCheckoutModalMessage');
    if (msgEl) msgEl.textContent = msg;
    document.getElementById('bulkCheckoutModal').style.display = 'flex';
}

function closeBulkCheckoutModal() {
    document.getElementById('bulkCheckoutModal').style.display = 'none';
}

async function executeBulkCheckout() {
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        closeBulkCheckoutModal();
        showInventoryToast('error', bulkI18n.errorEmpty);
        return;
    }
    closeBulkCheckoutModal();
    if (!window.krtFetch) {
        return;
    }
    // #577 part 2: route through the new POST /inventory/bulk-checkout frontend proxy (the
    // former direct browser call to the backend /api/v1/... path had no frontend route, so the
    // bulk action never reached the backend). krtFetch handles CSRF + retry-on-403 and drives
    // the OPTIMISTIC_LOCK reload-confirm; on success the grouped table is re-swapped in place
    // and the bulk bar reset, instead of a full reload.
    await window.krtFetch.write({
        method: 'POST',
        url: '/inventory/bulk-checkout',
        payload: { itemIds: ids },
        toast: false,
        errorMessage: bulkI18n.errorFailed,
        conflict: inventoryConflictI18n,
        onSuccess: function () {
            // Use the working global toast (the page-local showInventoryToast targets a #toast
            // element that does not exist) so the count-substituted confirmation actually shows —
            // the single book-out + the krtFetch error path already use this same toast.
            if (typeof window.showFrontendSuccessToast === 'function') {
                window.showFrontendSuccessToast(bulkI18n.success.replace('{0}', ids.length));
            }
            const bulkBtn = document.getElementById('bulkCheckoutBtn');
            const bulkCount = document.getElementById('bulkCheckoutCount');
            if (bulkBtn) {
                bulkBtn.disabled = true;
            }
            if (bulkCount) {
                bulkCount.textContent = '';
            }
            filterMyInventory();
        },
    });
}

// Close bulk modal on outside click
window.addEventListener('click', function (event) {
    const bulkModal = document.getElementById('bulkCheckoutModal');
    if (event.target === bulkModal) closeBulkCheckoutModal();
});

function toggleMultiSelect(id) {
    const el = document.getElementById(id);
    if (!el) return;
    const isOpened = el.classList.contains('open');
    document.querySelectorAll('.multi-select-options').forEach(function (opt) {
        opt.classList.remove('open');
    });
    if (!isOpened) el.classList.add('open');
}

function getMsTranslations(headerId) {
    const header = document.getElementById(headerId);
    return {
        allText: header ? header.getAttribute('data-all') || 'Alle' : 'Alle',
        selectedTextStr: header ? header.getAttribute('data-selected') || 'gewählt' : 'gewählt',
    };
}

function toggleSelectAll(allId, checkClass, headerId) {
    const isAllChecked = document.getElementById(allId).checked;
    const checkboxes = document.getElementsByClassName(checkClass);
    for (let i = 0; i < checkboxes.length; i++) checkboxes[i].checked = isAllChecked;
    updateMsSelectedText(checkboxes, headerId);
}

function updateSelectState(allId, checkClass, headerId) {
    const checkboxes = document.getElementsByClassName(checkClass);
    let allChecked = checkboxes.length > 0;
    for (let i = 0; i < checkboxes.length; i++) {
        if (!checkboxes[i].checked) {
            allChecked = false;
            break;
        }
    }
    const allEl = document.getElementById(allId);
    if (allEl) allEl.checked = allChecked;
    updateMsSelectedText(checkboxes, headerId);
}

function updateMsSelectedText(checkboxes, headerId) {
    const translations = getMsTranslations(headerId);
    let count = 0;
    let firstChecked = null;
    for (let i = 0; i < checkboxes.length; i++) {
        if (checkboxes[i].checked) {
            count++;
            if (!firstChecked && checkboxes[i].previousElementSibling) {
                firstChecked = checkboxes[i].previousElementSibling.innerText;
            }
        }
    }
    const headerSpan = document.getElementById(headerId).querySelector('.selected-text');
    if (count === 0) headerSpan.innerText = translations.allText;
    else if (count === 1) headerSpan.innerText = firstChecked;
    else headerSpan.innerText = count + ' ' + translations.selectedTextStr;
}

document.addEventListener('click', function (e) {
    if (!e.target.closest('.multi-select-container')) {
        document.querySelectorAll('.multi-select-options').forEach(function (opt) {
            if (opt.classList.contains('open')) opt.classList.remove('open');
        });
    }
});

function collectMyChecked(className) {
    const boxes = document.getElementsByClassName(className);
    const values = [];
    for (let i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) values.push(boxes[i].value);
    }
    return values;
}

function filterMyInventory() {
    const activeMaterials = collectMyChecked('matCheck');
    const activeJobOrders = collectMyChecked('jobOrderCheck');
    const activeMissions = collectMyChecked('missionCheck');
    const minQualitySelect = document.getElementById('minQuality');
    const minQuality = minQualitySelect ? minQualitySelect.value : '';
    const personalOnlyEl = document.getElementById('personalOnly');
    const personalOnly = personalOnlyEl ? personalOnlyEl.checked : false;

    const container = document.getElementById('myInventoryTableContainer');
    if (!container) return;
    container.style.opacity = '0.5';
    container.style.pointerEvents = 'none';

    const url = new URL(window.location.origin + '/inventory/my');
    url.searchParams.append('fragment', 'true');
    activeMaterials.forEach((m) => url.searchParams.append('materialIds', m));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));
    if (personalOnly) url.searchParams.append('personalOnly', 'true');

    const visibleUrl = new URL(window.location.origin + '/inventory/my');
    activeMaterials.forEach((m) => visibleUrl.searchParams.append('materialIds', m));
    if (minQuality) visibleUrl.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => visibleUrl.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => visibleUrl.searchParams.append('missionIds', m));
    if (personalOnly) visibleUrl.searchParams.append('personalOnly', 'true');
    try {
        window.history.replaceState({}, '', visibleUrl.toString());
    } catch {
        /* ignore */
    }

    fetch(url, { method: 'GET', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then((response) => response.text())
        .then((html) => {
            container.outerHTML = html;
            // A fragment swap does not re-fire DOMContentLoaded, so re-apply the persisted tree
            // expansion (REQ-INV-002) — otherwise a filter change or a modal write collapses every
            // row the user had opened.
            restoreExpandedTree();
        })
        .catch((error) => {
            console.error('Error fetching filtered personal inventory:', error);
            container.style.opacity = '1.0';
            container.style.pointerEvents = 'auto';
        });
}

function resetMyInventoryFilter() {
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
    const personalOnlyEl = document.getElementById('personalOnly');
    if (personalOnlyEl) personalOnlyEl.checked = false;
    if (document.getElementById('materialHeader'))
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    if (document.getElementById('jobOrderHeader'))
        updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
    if (document.getElementById('missionHeader'))
        updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    filterMyInventory();
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
// The grouped tree (material group -> location stack -> lazy leaf entries) is re-rendered in place
// on every grouped-table re-swap: a filter change AND — the bug this guards — every modal write
// (book-out, Umbuchen and bulk-checkout all call filterMyInventory in their onSuccess). Because a
// fragment swap replaces #inventoryTable wholesale and does NOT re-fire DOMContentLoaded, the
// expanded state must be re-applied explicitly after each swap; otherwise closing a modal collapses
// every row the user had opened. Both the material-group expansion (persisted as
// expanded_rows_lager_*) and the location-stack expansion (persisted as expanded_stacks_lager_*)
// live in localStorage, keyed per user, and are re-applied by restoreExpandedTree().

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
// same stack maps to the same key across re-renders and a /my stack can never collide with a /all
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

// Re-applies the persisted location-stack expansion and re-triggers the lazy entry load for each
// restored stack (the re-rendered header comes back with data-stack-loaded="false", so the leaf
// rows — carrying the fresh post-write amounts — are fetched again).
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
    if (window.getComputedStyle(nextRow).display === 'none') {
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
    if (window.getComputedStyle(nextRow).display === 'none') {
        nextRow.style.display = 'block';
        if (icon) icon.textContent = '▼';
        // Persist so a later in-place re-swap (filter change or modal write) re-opens this stack.
        if (id && !expandedStacks.includes(id)) {
            expandedStacks.push(id);
            writeExpanded(key, expandedStacks);
        }
        // Append-only Lager: a stack's entries are not inlined. Fetch them on first
        // expand from /inventory/my/stack/entries (ADR-0003, REQ-INV-002); subsequent
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

// Builds the lazy stack-entries fetch URL from the stack-key data-attributes the
// server stamped on the stack-header row. A null/absent dimension is omitted so the
// backend's null-safe match selects the rows where that association is itself absent.
function buildStackEntriesUrl(headerRow, page) {
    const params = new URLSearchParams();
    params.set('materialId', headerRow.getAttribute('data-material-id'));
    params.set('locationId', headerRow.getAttribute('data-location-id'));
    const quality = headerRow.getAttribute('data-quality');
    if (quality !== null && quality !== '') params.set('quality', quality);
    params.set('personal', headerRow.getAttribute('data-personal') || 'false');
    const owningOrgUnitId = headerRow.getAttribute('data-owning-org-unit-id');
    if (owningOrgUnitId) params.set('owningOrgUnitId', owningOrgUnitId);
    if (page != null) params.set('page', page);
    return '/inventory/my/stack/entries?' + params.toString();
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

// Fetches one page of a stack's entries and injects the server-rendered fragment.
// The injected rows carry the same data-trigger hooks as before, so the page's
// delegated krtEvents handlers (book-out, note, association, bulk-select) keep working
// without re-binding.
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
            // Newly injected checkboxes must be reflected in the bulk-checkout state.
            updateBulkCheckoutState();
        })
        .catch(function (e) {
            console.error('Failed to load stack entries', e);
            setStackEntriesStatus(content, stackEntriesI18n.error, true);
        });
}

// Pagination handler: re-fetches the target page for the stack owning the clicked
// button and replaces the entries container in place.
function goToStackEntriesPage(btn) {
    const entriesRow = btn.closest('.tree-stack-entries');
    if (!entriesRow) return;
    const headerRow = entriesRow.previousElementSibling;
    if (!headerRow) return;
    const page = parseInt(btn.getAttribute('data-page'), 10);
    loadStackEntries(headerRow, isNaN(page) ? 0 : page);
}

// Restore the persisted tree expansion on initial load (the same restore runs after each in-place
// grouped-table re-swap — see filterMyInventory).
document.addEventListener('DOMContentLoaded', restoreExpandedTree);

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

// ===================== Umbuchen (rebooking) modal — REQ-INV-007 =====================
// The item the open Umbuchen modal targets, plus an in-flight guard mirroring the book-out one.
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

/**
 * LOCATION-mode picker: re-populate the target-OrgUnit picker when the destination user changes
 * (the relocated book-out transfer picker). Hidden when the destination user has ≤1 membership —
 * the backend then stamps the single membership (or the home Staffel) automatically.
 */
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

/**
 * PERSONAL de-personalize picker: populate the org-unit picker with the row owner's memberships.
 * Shown only when the owner has ≥2 memberships — a real choice exists and the backend would
 * otherwise reject a null pick with 400; with ≤1 membership it stays hidden and the backend
 * auto-stamps the single membership (or leaves the row ownerless for a membershipless user).
 */
function refreshUmbuchenPersonalOrgUnitPicker(ownerId) {
    const wrapper = document.getElementById('umbuchenPersonalOrgUnitWrapper');
    const select = document.getElementById('umbuchenPersonalOrgUnitId');
    if (!wrapper || !select) return;
    select.innerHTML = '';
    wrapper.style.display = 'none';
    if (!ownerId) return;
    fetch('/api/v1/users/' + encodeURIComponent(ownerId) + '/memberships', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            if (!Array.isArray(memberships) || memberships.length <= 1) return;
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

function toggleUmbuchenMode() {
    const modeInput = document.querySelector('input[name="umbuchenMode"]:checked');
    const mode = modeInput ? modeInput.value : 'LOCATION';
    const transferFields = document.getElementById('umbuchenTransferFields');
    const personalFields = document.getElementById('umbuchenPersonalFields');
    const targetUser = document.getElementById('umbuchenTargetUserId');
    const targetLocation = document.getElementById('umbuchenTargetLocationId');
    if (mode === 'PERSONAL') {
        transferFields.style.display = 'none';
        personalFields.style.display = 'block';
        targetUser.required = false;
        targetLocation.required = false;
    } else {
        transferFields.style.display = 'block';
        personalFields.style.display = 'none';
        targetUser.required = true;
        targetLocation.required = true;
    }
}

function openUmbuchenModal(
    id,
    amount,
    version,
    materialId,
    userId,
    locationId,
    quantityType,
    personal,
    hasAssoc,
) {
    umbuchenItemId = id;
    const isScu = quantityType !== 'PIECE';
    const amountEl = document.getElementById('umbuchenAmount');
    const targetEl = document.getElementById('umbuchenTargetAmount');
    amountEl.setAttribute('step', isScu ? '0.001' : '1');
    targetEl.setAttribute('step', isScu ? '0.001' : '1');
    const targetHint = document.getElementById('umbuchen-target-scu-hint');
    const amountHint = document.getElementById('umbuchen-amount-scu-hint');
    if (targetHint) targetHint.classList.toggle('krtm-hidden', !isScu);
    if (amountHint) amountHint.classList.toggle('krtm-hidden', !isScu);

    // REQ-INV-026: the per-action stock-merge opt-in is offered only for an SCU material (a PIECE
    // rebooking/transfer always merges server-side). Reset it on every open.
    const mergeRow = document.getElementById('umbuchenMergeRow');
    const mergeCheckbox = document.getElementById('umbuchenMergeStock');
    if (mergeCheckbox) mergeCheckbox.checked = false;
    if (mergeRow) mergeRow.classList.toggle('krtm-hidden', !isScu);

    amountEl.value = amount;
    amountEl.max = amount;
    targetEl.value = 0;
    document.getElementById('umbuchenMaxAmount').value = amount;
    document.getElementById('umbuchenVersion').value = version;
    document.getElementById('umbuchenSourcePersonal').value = personal ? 'true' : 'false';
    const amountOf = document.getElementById('umbuchenAmountOfText');
    if (amountOf)
        amountOf.textContent = amountOf.getAttribute('data-template').replace('{0}', amount);

    // LOCATION (transfer) defaults: pre-select the row's own user/location.
    const tu = document.getElementById('umbuchenTargetUserId');
    const tl = document.getElementById('umbuchenTargetLocationId');
    if (tu) tu.value = userId;
    if (tl) tl.value = locationId;
    refreshUmbuchenTransferOrgUnitPicker();

    // PERSONAL mode direction is the opposite of the source row's personal flag.
    const personalizing = !personal; // shared -> personal
    const modeText = document.getElementById('umbuchenModePersonalText');
    const personalLabel = document.getElementById('umbuchenModePersonalLabel');
    const hint = document.getElementById('umbuchenPersonalHint');
    if (modeText)
        modeText.textContent = personalizing
            ? umbuchenI18n.modePersonalize
            : umbuchenI18n.modeDepersonalize;
    if (hint)
        hint.textContent = personalizing
            ? umbuchenI18n.hintPersonalize
            : umbuchenI18n.hintDepersonalize;
    // Personalizing a row bound to a job order / mission is refused by the backend, so hide the
    // PERSONAL mode entirely for an assigned shared row.
    const personalDisabled = personalizing && hasAssoc;
    if (personalLabel) personalLabel.style.display = personalDisabled ? 'none' : '';
    // De-personalize (personal -> shared) offers the owner's org-unit picker; personalize does not.
    if (!personalizing) {
        refreshUmbuchenPersonalOrgUnitPicker(userId);
    } else {
        const w = document.getElementById('umbuchenPersonalOrgUnitWrapper');
        if (w) w.style.display = 'none';
    }

    document.querySelector('input[name="umbuchenMode"][value="LOCATION"]').checked = true;
    toggleUmbuchenMode();
    document.getElementById('umbuchenModal').style.display = 'block';
}

function closeUmbuchenModal() {
    if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
    document.getElementById('umbuchenModal').style.display = 'none';
}

function submitUmbuchen(event) {
    // scu-decimal-input.js canonicalises + validates the amount fields in the capture phase
    // first; respect a block it already issued, then prevent the native (action-less) submit.
    if (event && event.defaultPrevented) return;
    if (event) event.preventDefault();
    if (umbuchenInFlight || !window.krtFetch || !umbuchenItemId) return;
    const modeInput = document.querySelector('input[name="umbuchenMode"]:checked');
    const mode = modeInput ? modeInput.value : 'LOCATION';
    const amountEl = document.getElementById('umbuchenAmount');
    const amount = window.krtScuInput
        ? window.krtScuInput.parse(amountEl.value)
        : parseFloat(amountEl.value);
    const version = parseInt(document.getElementById('umbuchenVersion').value, 10);
    const submitBtn = document.getElementById('umbuchenSubmitBtn');
    // REQ-INV-026: per-action stock-merge opt-in (only rendered for SCU; PIECE always merges).
    const mergeCheckbox = document.getElementById('umbuchenMergeStock');
    const mergeStock = !!(mergeCheckbox && mergeCheckbox.checked);

    let url, payload;
    if (mode === 'PERSONAL') {
        url = '/inventory/' + umbuchenItemId + '/personal-rebook';
        const orgWrapper = document.getElementById('umbuchenPersonalOrgUnitWrapper');
        const orgSelect = document.getElementById('umbuchenPersonalOrgUnitId');
        const orgUnitId =
            orgWrapper && window.getComputedStyle(orgWrapper).display !== 'none' && orgSelect
                ? orgSelect.value || null
                : null;
        payload = {
            amount: amount,
            version: version,
            targetOwningOrgUnitId: orgUnitId,
            mergeStock: mergeStock,
        };
    } else {
        url = '/inventory/' + umbuchenItemId + '/transfer';
        payload = {
            amount: amount,
            type: 'TRANSFER',
            targetUserId: document.getElementById('umbuchenTargetUserId').value || null,
            targetLocationId: document.getElementById('umbuchenTargetLocationId').value || null,
            targetOwningOrgUnitId:
                document.getElementById('umbuchenTargetOwningOrgUnitId').value || null,
            version: version,
            mergeStock: mergeStock,
        };
    }

    umbuchenInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    window.krtFetch
        .write({
            method: 'POST',
            url: url,
            payload: payload,
            successMessage: umbuchenI18n.success,
            errorMessage: umbuchenI18n.error,
            conflict: inventoryConflictI18n,
            onSuccess: function () {
                closeUmbuchenModal();
                filterMyInventory();
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
    // dedicated Umbuchen modal. The backend book-out endpoint still receives the same DTO shape;
    // the transfer-only fields stay null.
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
                filterMyInventory();
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
    if (targetScuHint) targetScuHint.classList.toggle('krtm-hidden', !isScu);
    if (amountScuHint) amountScuHint.classList.toggle('krtm-hidden', !isScu);

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

    // Wir benötigen die anderen Felder für das DTO
    const materialId = selectElement.getAttribute('data-material-id');
    const locationId = selectElement.getAttribute('data-location-id');
    const quality = parseInt(selectElement.getAttribute('data-quality'));
    const amount = parseFloat(selectElement.getAttribute('data-amount'));
    const personal = selectElement.getAttribute('data-personal') === 'true';

    // Finde das andere select (jobOrderId/missionId), da wir beide Werte senden müssen oder null
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
            let updated = null;
            try {
                updated = await response.json();
            } catch {
                /* tolerate empty body */
            }

            if (typeof window.showFrontendSuccessToast === 'function') {
                window.showFrontendSuccessToast(assocI18n.success);
            } else {
                console.info(assocI18n.success);
            }

            // REQ-INV-026: an association change is no longer a pure in-place edit — for a PIECE
            // material the backend now folds matching sibling rows into this one (their amounts
            // summed, the siblings DELETED, delivered reset). When that happens a targeted
            // syncVersion is not enough: the folded-away rows would linger as phantoms and the
            // survivor's amount/data-amount would be stale, and a follow-up edit would re-send the
            // stale amount and silently drop the folded quantity. The returned amount differing from
            // the amount we sent flags the fold, so re-swap the whole grouped table (as the
            // book-out / Umbuchen handlers do). Otherwise keep the lightweight version-only sync:
            // propagate the AUTHORITATIVE new version to every data-version control in the leaf row.
            if (updated && updated.amount != null && updated.amount !== amount) {
                filterMyInventory();
            } else if (updated && updated.version != null && window.krtFetch) {
                window.krtFetch.syncVersion(
                    selectElement.closest('.tree-row--leaf'),
                    updated.version,
                );
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

// CSP-safe delegated bindings (replaces the 34 inline on*= handlers across this template).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'inv-my-toggle-multi', function (el) {
        toggleMultiSelect(el.getAttribute('data-multi-target'));
    });
    window.krtEvents.on('change', 'inv-my-toggle-all', function (el) {
        toggleSelectAll(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterMyInventory();
    });
    window.krtEvents.on('change', 'inv-my-update-state', function (el) {
        updateSelectState(
            el.getAttribute('data-all-id'),
            el.getAttribute('data-check-class'),
            el.getAttribute('data-header-id'),
        );
        filterMyInventory();
    });
    window.krtEvents.on('change', 'inv-my-filter', filterMyInventory);
    window.krtEvents.on('click', 'inv-my-reset-filter', resetMyInventoryFilter);
    window.krtEvents.on('click', 'inv-my-open-bulk', openBulkCheckoutModal);
    window.krtEvents.on('click', 'inv-my-toggle-group', function (el) {
        toggleGroup(el);
    });
    window.krtEvents.on('click', 'inv-my-toggle-stack', function (el) {
        toggleStack(el);
    });
    window.krtEvents.on('click', 'inv-my-stack-page', function (el) {
        goToStackEntriesPage(el);
    });
    window.krtEvents.on('change', 'inv-my-toggle-group-cb', function (el) {
        toggleGroupCheckboxes(el);
    });
    window.krtEvents.on('change', 'inv-my-update-bulk-state', updateBulkCheckoutState);
    window.krtEvents.on('change', 'inv-my-update-assoc', function (el) {
        updateInventoryAssociation(el);
    });
    window.krtEvents.on('click', 'inv-my-bookout', function (el) {
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
    window.krtEvents.on('click', 'inv-my-umbuchen', function (el) {
        openUmbuchenModal(
            el.getAttribute('data-id'),
            el.getAttribute('data-amount'),
            el.getAttribute('data-version'),
            el.getAttribute('data-material-id'),
            el.getAttribute('data-user-id'),
            el.getAttribute('data-location-id'),
            el.getAttribute('data-quantity-type'),
            el.getAttribute('data-personal') === 'true',
            el.getAttribute('data-has-assoc') === 'true',
        );
    });
    window.krtEvents.on('click', 'inv-my-open-note', function (el) {
        openNoteModal(el);
    });
    window.krtEvents.on('click', 'inv-my-close-bookout', closeBookOutModal);
    window.krtEvents.on('input', 'inv-my-amount-from-target', updateAmountFromTarget);
    window.krtEvents.on('input', 'inv-my-target-from-amount', updateTargetFromAmount);
    window.krtEvents.on('change', 'inv-my-toggle-bookout-type', toggleBookOutTypeFields);
    window.krtEvents.on('click', 'inv-my-close-umbuchen', closeUmbuchenModal);
    window.krtEvents.on(
        'input',
        'inv-my-umbuchen-amount-from-target',
        updateUmbuchenAmountFromTarget,
    );
    window.krtEvents.on(
        'input',
        'inv-my-umbuchen-target-from-amount',
        updateUmbuchenTargetFromAmount,
    );
    window.krtEvents.on('change', 'inv-my-toggle-umbuchen-mode', toggleUmbuchenMode);
    window.krtEvents.on(
        'change',
        'inv-my-umbuchen-target-user-changed',
        refreshUmbuchenTransferOrgUnitPicker,
    );
    window.krtEvents.on('click', 'inv-my-close-note', closeNoteModal);
    window.krtEvents.on('input', 'inv-my-update-note-counter', updateNoteCounter);
    window.krtEvents.on('click', 'inv-my-save-note', saveNote);
    window.krtEvents.on('click', 'inv-my-remove-note', removeNote);
    window.krtEvents.on('click', 'inv-my-close-bulk', closeBulkCheckoutModal);
    window.krtEvents.on('click', 'inv-my-execute-bulk', executeBulkCheckout);
}

// The book-out form is a stable top-level element (outside the swapped table container), so a
// direct submit listener bound once survives the grouped-table re-swaps.
let bookOutFormEl = document.getElementById('bookOutForm');
if (bookOutFormEl) {
    bookOutFormEl.addEventListener('submit', submitBookOut);
}
// The Umbuchen form is likewise a stable top-level element; its submit listener (bound once)
// survives the grouped-table re-swaps and runs after scu-decimal-input.js canonicalises/validates
// the amount fields in the capture phase.
let umbuchenFormEl = document.getElementById('umbuchenForm');
if (umbuchenFormEl) {
    umbuchenFormEl.addEventListener('submit', submitUmbuchen);
}
