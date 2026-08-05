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

/* global stackEntriesI18n, bulkI18n, bulkRebookI18n, inventoryConflictI18n, bookOutI18n, umbuchenI18n, assocI18n, showInventoryToast, openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote */

// Which Lager view is active (REQ-INV-030): the Material <-> Items switch is server-rendered
// navigation, so the authoritative state is the page URL's view= parameter — which every filter
// re-swap also carries (history.replaceState keeps the address bar in sync).
function lagerIsItemsView() {
    try {
        return new URLSearchParams(window.location.search).get('view') === 'items';
    } catch {
        return false;
    }
}

// The grouping key of a tree group / bulk select-all control: material rows carry
// data-material-id, game-item rows data-game-item-id (REQ-INV-030). Exactly one is present.
function groupKeyOf(el) {
    return el.getAttribute('data-material-id') || el.getAttribute('data-game-item-id');
}

// Attribute selector matching the leaf checkboxes belonging to a group select-all control —
// keyed on whichever catalog attribute the control carries (see groupKeyOf).
function bulkGroupSelector(groupCb) {
    const materialId = groupCb.getAttribute('data-material-id');
    if (materialId) {
        return '.inventory-item-checkbox[data-material-id="' + materialId + '"]';
    }
    return (
        '.inventory-item-checkbox[data-game-item-id="' +
        groupCb.getAttribute('data-game-item-id') +
        '"]'
    );
}

// Source of truth for the bulk selection (REQ-INV-034): the ids the user has picked, kept in a Set
// that is DECOUPLED from the lazily-loaded leaf checkboxes so "Alle markieren" can select entries in
// still-collapsed stacks and beyond a stack's first page. A loaded checkbox's checked state is
// derived from this set (applyBulkSelectionToLoaded); the bulk-checkout reads the set directly.
const bulkSelectedIds = new Set();

// The bulk selection as a plain array — what the bulk-checkout modal + POST consume. Kept under the
// original name so the modal-open / execute call sites are unchanged.
function getCheckedItemIds() {
    return Array.from(bulkSelectedIds);
}

// Reflects the current selection onto the checkboxes inside `root` (the whole document, or a freshly
// injected stack fragment): a box is checked exactly when its entry id is in the selection set.
function applyBulkSelectionToLoaded(root) {
    (root || document).querySelectorAll('.inventory-item-checkbox').forEach(function (cb) {
        const id = cb.getAttribute('data-id');
        cb.checked = !!id && bulkSelectedIds.has(id);
    });
}

function updateBulkCheckoutState() {
    const count = bulkSelectedIds.size;
    const btn = document.getElementById('bulkCheckoutBtn');
    const rebookBtn = document.getElementById('bulkRebookBtn');
    const countSpan = document.getElementById('bulkCheckoutCount');
    if (btn) btn.disabled = count === 0;
    // Massen-Umbuchen (REQ-INV-036) acts on the same selection, so it shares the enabled state.
    if (rebookBtn) rebookBtn.disabled = count === 0;
    if (countSpan) countSpan.textContent = count > 0 ? '(' + count + ')' : '';
    // Update group-select-all checkboxes — they mirror only their currently-loaded leaf boxes (a
    // collapsed group has none loaded, so it stays unchecked even under a view-wide select-all; the
    // authoritative total is the count span).
    document.querySelectorAll('.group-select-all').forEach(function (groupCb) {
        const groupBoxes = document.querySelectorAll(bulkGroupSelector(groupCb));
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

// A single leaf checkbox was toggled by hand: sync the set, then reconcile the derived UI.
function onEntryCheckboxToggle(cb) {
    const id = cb.getAttribute('data-id');
    if (id) {
        if (cb.checked) bulkSelectedIds.add(id);
        else bulkSelectedIds.delete(id);
    }
    syncSelectAllButtonToSelection();
    updateBulkCheckoutState();
}

function toggleGroupCheckboxes(groupCb) {
    const groupBoxes = document.querySelectorAll(bulkGroupSelector(groupCb));
    groupBoxes.forEach(function (cb) {
        cb.checked = groupCb.checked;
        const id = cb.getAttribute('data-id');
        if (id) {
            if (groupCb.checked) bulkSelectedIds.add(id);
            else bulkSelectedIds.delete(id);
        }
    });
    syncSelectAllButtonToSelection();
    updateBulkCheckoutState();
}

// ── "Alle markieren" — select every entry of the current filtered view (REQ-INV-034) ───────────
// The grouped tree lazy-loads and paginates each stack, so ticking only the on-screen boxes would
// silently miss collapsed stacks and later pages. This fetches the complete matching id set from the
// /inventory/my/entry-ids proxy (same filter surface as the table) and drives the selection from it,
// so the follow-up "Markierte ausbuchen" spans the whole filtered view. The button is a toggle:
// while a selection is active it reads "Auswahl aufheben" and clears instead.
let bulkSelectAllInFlight = false;

// Swaps the toggle button between its "select all" and "clear selection" labels + data-state.
function setSelectAllButtonState(on) {
    const btn = document.getElementById('bulkSelectAllBtn');
    if (!btn) return;
    btn.setAttribute('data-state', on ? 'on' : 'off');
    const label = btn.getAttribute(on ? 'data-text-clear' : 'data-text-select');
    if (label) btn.textContent = label;
}

// Once the selection is empty (e.g. the user unticked the last box) the toggle must fall back to its
// "select all" label so it never sits on "clear" with nothing to clear.
function syncSelectAllButtonToSelection() {
    if (bulkSelectedIds.size === 0) setSelectAllButtonState(false);
}

// Clears the whole bulk selection and resets the derived UI + toggle label.
function clearBulkSelection() {
    bulkSelectedIds.clear();
    applyBulkSelectionToLoaded(document);
    setSelectAllButtonState(false);
    updateBulkCheckoutState();
}

// Builds the /inventory/my/entry-ids request from the page's own filter state + active view, exactly
// as filterMyInventory builds the table fragment URL, so the returned id set matches what the table
// shows. Resolves to the array of matching entry ids.
function fetchAllMatchingEntryIds() {
    const itemsView = lagerIsItemsView();
    const activeMaterials = collectMyChecked('matCheck');
    const activeGameItems = collectMyChecked('gameItemCheck');
    const activeJobOrders = collectMyChecked('jobOrderCheck');
    const activeMissions = collectMyChecked('missionCheck');
    const minQualitySelect = document.getElementById('minQuality');
    const minQuality = minQualitySelect ? minQualitySelect.value : '';
    const personalOnly = personalFlagChecked('personalOnly', 'itemPersonalOnly');
    const nonPersonalOnly = personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly');

    const url = new URL(window.location.origin + '/inventory/my/entry-ids');
    if (itemsView) url.searchParams.append('view', 'items');
    activeMaterials.forEach((m) => url.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => url.searchParams.append('gameItemIds', g));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));
    if (personalOnly) url.searchParams.append('personalOnly', 'true');
    if (nonPersonalOnly) url.searchParams.append('nonPersonalOnly', 'true');

    return fetch(url, {
        method: 'GET',
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
        credentials: 'same-origin',
    }).then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
    });
}

async function toggleSelectAllInView() {
    const btn = document.getElementById('bulkSelectAllBtn');
    // Already selected → this click clears the selection instead.
    if (btn && btn.getAttribute('data-state') === 'on') {
        clearBulkSelection();
        return;
    }
    if (bulkSelectAllInFlight) return;
    bulkSelectAllInFlight = true;
    if (btn) btn.disabled = true;
    try {
        const ids = await fetchAllMatchingEntryIds();
        bulkSelectedIds.clear();
        (Array.isArray(ids) ? ids : []).forEach(function (id) {
            if (id) bulkSelectedIds.add(String(id));
        });
        applyBulkSelectionToLoaded(document);
        setSelectAllButtonState(bulkSelectedIds.size > 0);
        updateBulkCheckoutState();
    } catch (e) {
        console.error('Failed to select all inventory entries', e);
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(bulkI18n.selectAllFailed);
        }
    } finally {
        bulkSelectAllInFlight = false;
        if (btn) btn.disabled = false;
    }
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
    // Collect every earmarked order across the checked entries before the write (a full bulk
    // book-out zeroes their order slices), deduped, so those order collections refresh too.
    const affectedOrderIds = [];
    ids.forEach(function (itemId) {
        collectLeafOrderIds(itemId).forEach(function (orderId) {
            if (affectedOrderIds.indexOf(orderId) < 0) affectedOrderIds.push(orderId);
        });
    });
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
            // filterMyInventory() re-swaps the table and clears the bulk selection (set, count,
            // button + toggle label) at its start, so no manual reset is needed here.
            filterMyInventory();
            broadcastInventoryChanged();
            broadcastOrdersChanged(affectedOrderIds);
            broadcastBoardChanged();
        },
    });
}

// Close bulk modal on outside click
window.addEventListener('click', function (event) {
    const bulkModal = document.getElementById('bulkCheckoutModal');
    if (event.target === bulkModal) closeBulkCheckoutModal();
    const rebookModal = document.getElementById('bulkRebookModal');
    if (event.target === rebookModal) closeBulkRebookModal();
});

// ── Massen-Umbuchen (REQ-INV-036) ──────────────────────────────────────────────────────────────
// The bulk counterpart of the single-row Umbuchen modal: the SAME marked selection (bulkSelectedIds),
// moved in full instead of discarded. Two deliberate differences from the single-row dialog: there is
// no amount input — the selection spans collapsed stacks and later pages (REQ-INV-034), so a per-row
// quantity could not be reviewed before submitting — and the two personal directions are explicit
// radio options rather than one direction inferred from the source row, because a bulk selection can
// mix personal and shared stock. Rows already sitting in the chosen target state are skipped
// server-side and reported back, so the toast never claims more than actually moved.
let bulkRebookInFlight = false;

// The mode radio's current value; LOCATION is both the default and the fallback.
function bulkRebookMode() {
    const checked = document.querySelector('input[name="bulkRebookMode"]:checked');
    return checked ? checked.value : 'LOCATION';
}

// The page-local showInventoryToast targets a #toast element that does not exist on this page (see
// executeBulkCheckout), so the bulk-rebook errors go through the working global toast.
function showBulkRebookError(message) {
    if (typeof window.showFrontendErrorToast === 'function') {
        window.showFrontendErrorToast(message);
    }
}

// The caller's own user id, read from the tree's data-user-id (both the Material and the Items view
// render it). Read at call time, never cached: the tree lives inside the swapped fragment, so a
// stored reference would go stale on every re-swap.
function currentInventoryUserId() {
    const table = document.getElementById('inventoryTable');
    return table ? table.getAttribute('data-user-id') : null;
}

// Populates the single org-unit picker with whichever pool the active mode needs: the LOCATION
// destination owner's memberships, or the caller's own when de-personalizing. PERSONALIZE has no
// pool to pick — a personalized row keeps its source stamp — so the picker stays hidden there.
function refreshBulkRebookOrgUnitPicker() {
    const wrapper = document.getElementById('bulkRebookOrgUnitWrapper');
    const select = document.getElementById('bulkRebookOrgUnitId');
    if (!wrapper || !select) return;
    const mode = bulkRebookMode();
    select.innerHTML = '';
    wrapper.style.display = 'none';
    if (mode === 'PERSONALIZE') return;
    const userSelect = document.getElementById('bulkRebookTargetUserId');
    const ownerId =
        mode === 'LOCATION' && userSelect && userSelect.value
            ? userSelect.value
            : currentInventoryUserId();
    if (!ownerId) return;
    // ?allKinds=true surfaces the owner's Bereich/OL memberships too, not just Staffel/SK (the
    // endpoint default) — mirroring the single-row pickers (#1328). Fetched through the frontend's
    // /users/{id}/memberships proxy: the frontend origin maps no /api/v1/users/** route.
    fetch('/users/' + encodeURIComponent(ownerId) + '/memberships?allKinds=true', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            if (!Array.isArray(memberships) || memberships.length < 1) return;
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

// Shows the fields the active mode needs, hides the rest, and repopulates the org-unit picker.
function toggleBulkRebookMode() {
    const mode = bulkRebookMode();
    const transferFields = document.getElementById('bulkRebookTransferFields');
    const hint = document.getElementById('bulkRebookPersonalHint');
    if (transferFields) transferFields.style.display = mode === 'LOCATION' ? 'block' : 'none';
    if (hint) {
        hint.classList.toggle('krtm-hidden', mode === 'LOCATION');
        if (mode === 'PERSONALIZE') hint.textContent = bulkRebookI18n.hintPersonalize;
        else if (mode === 'DEPERSONALIZE') hint.textContent = bulkRebookI18n.hintDepersonalize;
    }
    refreshBulkRebookOrgUnitPicker();
}

function openBulkRebookModal() {
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        showBulkRebookError(bulkRebookI18n.errorEmpty);
        return;
    }
    const msgEl = document.getElementById('bulkRebookModalMessage');
    if (msgEl) msgEl.textContent = bulkRebookI18n.modalMessage.replace('{0}', ids.length);

    // Reset on every open so a previous run's mode and target cannot leak into the next one.
    const locationRadio = document.querySelector('input[name="bulkRebookMode"][value="LOCATION"]');
    if (locationRadio) locationRadio.checked = true;
    const mergeCheckbox = document.getElementById('bulkRebookMergeStock');
    if (mergeCheckbox) mergeCheckbox.checked = false;
    // The destination location opens EMPTY. Unlike the single-row modal there is no current location
    // to seed — the selection can span many — and an empty picker keeps the user from moving stock to
    // a location they never consciously chose.
    const tl = document.getElementById('bulkRebookTargetLocationId');
    if (tl && tl.krtCombobox) tl.krtCombobox.setValue('');
    else if (tl) tl.value = '';
    // Default the destination owner to the caller: a bulk move usually only relocates own stock.
    const tu = document.getElementById('bulkRebookTargetUserId');
    const me = currentInventoryUserId();
    if (tu && me) tu.value = me;

    toggleBulkRebookMode();
    // `.modal` centres its content via display:flex; opening with inline `flex` (not `block`)
    // preserves that centring (matches the canonical .krtm-modal-open = flex, #1328).
    document.getElementById('bulkRebookModal').style.display = 'flex';
}

function closeBulkRebookModal() {
    document.getElementById('bulkRebookModal').style.display = 'none';
}

// Reports the moved/skipped split honestly: a run that only skipped rows (everything already sat at
// the target) must not read as a success, and a partial run must name both numbers.
function reportBulkRebookOutcome(body) {
    const rebooked = body && typeof body.rebooked === 'number' ? body.rebooked : 0;
    const skipped = body && typeof body.skipped === 'number' ? body.skipped : 0;
    if (rebooked === 0) {
        showBulkRebookError(bulkRebookI18n.noneMoved);
        return;
    }
    const message =
        skipped > 0
            ? bulkRebookI18n.successPartial.replace('{0}', rebooked).replace('{1}', skipped)
            : bulkRebookI18n.success.replace('{0}', rebooked);
    if (typeof window.showFrontendSuccessToast === 'function') {
        window.showFrontendSuccessToast(message);
    }
}

function submitBulkRebook(event) {
    if (event) event.preventDefault();
    if (bulkRebookInFlight || !window.krtFetch) return;
    const ids = getCheckedItemIds();
    if (ids.length === 0) {
        closeBulkRebookModal();
        showBulkRebookError(bulkRebookI18n.errorEmpty);
        return;
    }
    const mode = bulkRebookMode();

    let targetUserId = null;
    let targetLocationId = null;
    if (mode === 'LOCATION') {
        targetUserId = document.getElementById('bulkRebookTargetUserId').value || null;
        targetLocationId = document.getElementById('bulkRebookTargetLocationId').value || null;
        // The backend rejects a target-less transfer (REQ-INV-025 parity). Catch it here so the user
        // gets a precise message instead of a generic failure toast.
        if (!targetUserId && !targetLocationId) {
            showBulkRebookError(bulkRebookI18n.errorNoTarget);
            return;
        }
    }

    // The picker is populated asynchronously, so read it only while it is actually shown; otherwise
    // send null and let the backend resolve the destination owner's default pool.
    const orgWrapper = document.getElementById('bulkRebookOrgUnitWrapper');
    const orgSelect = document.getElementById('bulkRebookOrgUnitId');
    const orgUnitId =
        orgWrapper && window.getComputedStyle(orgWrapper).display !== 'none' && orgSelect
            ? orgSelect.value || null
            : null;
    const mergeCheckbox = document.getElementById('bulkRebookMergeStock');

    // Collect every earmarked order across the marked entries before the write: a LOCATION move
    // carries the order slices onto the moved rows, so those order collections must refresh too.
    const affectedOrderIds = [];
    ids.forEach(function (itemId) {
        collectLeafOrderIds(itemId).forEach(function (orderId) {
            if (affectedOrderIds.indexOf(orderId) < 0) affectedOrderIds.push(orderId);
        });
    });

    const submitBtn = document.getElementById('bulkRebookSubmitBtn');
    bulkRebookInFlight = true;
    if (submitBtn) submitBtn.disabled = true;
    window.krtFetch
        .write({
            method: 'POST',
            url: '/inventory/bulk-rebook',
            payload: {
                itemIds: ids,
                mode: mode,
                targetUserId: targetUserId,
                targetLocationId: targetLocationId,
                targetOwningOrgUnitId: orgUnitId,
                mergeStock: !!(mergeCheckbox && mergeCheckbox.checked),
            },
            toast: false,
            errorMessage: bulkRebookI18n.errorFailed,
            conflict: inventoryConflictI18n,
            onSuccess: function (body) {
                closeBulkRebookModal();
                reportBulkRebookOutcome(body);
                // filterMyInventory() re-swaps the grouped table and clears the bulk selection (set,
                // count, buttons + toggle label) at its start, so no manual reset is needed here.
                filterMyInventory();
                broadcastInventoryChanged();
                broadcastOrdersChanged(affectedOrderIds);
                broadcastBoardChanged();
            },
        })
        .then(function () {
            bulkRebookInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

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

// The "Nur persönliche" / "Nur nicht-persönliche" toggles are mutually exclusive: checking one
// clears the other (so they never intersect to an empty result), then re-runs the filter. Setting
// .checked programmatically does not fire another change event, so this never re-enters itself.
// The items view renders its own item-prefixed pair (unique ids in the template source), so the
// counterpart is looked up per id.
function togglePersonalFilter(el) {
    if (el && el.checked) {
        const counterparts = {
            personalOnly: 'nonPersonalOnly',
            nonPersonalOnly: 'personalOnly',
            itemPersonalOnly: 'itemNonPersonalOnly',
            itemNonPersonalOnly: 'itemPersonalOnly',
        };
        const other = document.getElementById(counterparts[el.id]);
        if (other) other.checked = false;
    }
    filterMyInventory();
}

// Reads the personal-flag checkbox shared by both filter forms: the material form renders
// personalOnly/nonPersonalOnly, the items view its item-prefixed pair — only one exists per view.
function personalFlagChecked(materialId, itemId) {
    const el = document.getElementById(materialId) || document.getElementById(itemId);
    return el ? el.checked : false;
}

// ===================== Per-browser filter persistence (REQ-UI-017) =============================
// One JSON object under a single localStorage key holds BOTH views' filter states
// ({material: {...}, items: {...}}), so switching between the Material and the Items view keeps
// each view's own selection. A multi-select dimension stores its checked values, or null when
// zero or all boxes are checked — both mean "no filter" on this page (zero checked is the
// rendered default; all checked sends every id for the same result). Absence of the key keeps
// the server-rendered defaults. The URL wins: this page mirrors its filters into the address
// bar via history.replaceState, so a load WITH filter query params adopts that state and
// re-persists it — only a bare URL restores from storage. All storage access is guarded so
// privacy modes that deny it degrade to the defaults instead of breaking the page.
const MY_INVENTORY_FILTER_KEY = 'inventory_my_filters';

// The filter query params this page mirrors into the URL. The view= switch is server-rendered
// navigation, not a filter, so it never counts as one (a /inventory/my?view=items URL is still
// "bare" and restores the items view's stored filters).
const MY_INVENTORY_FILTER_PARAMS = [
    'materialIds',
    'gameItemIds',
    'minQuality',
    'jobOrderIds',
    'missionIds',
    'personalOnly',
    'nonPersonalOnly',
];

function readMyInventoryFilterPref() {
    try {
        const raw = localStorage.getItem(MY_INVENTORY_FILTER_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_e) {
        return null; // corrupt value / storage unavailable: fall back to the defaults
    }
}

function writeMyInventoryFilterPref(value) {
    try {
        localStorage.setItem(MY_INVENTORY_FILTER_KEY, JSON.stringify(value));
    } catch (_e) {
        /* storage unavailable */
    }
}

// Checked values of a multi-select dimension, or null when zero or all boxes are checked (= "no
// filter"). Storing null — not the full option list — keeps catalog options added later
// included automatically.
function myInventoryFilterSelection(className) {
    const boxes = document.getElementsByClassName(className);
    const picked = [];
    for (let i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) picked.push(boxes[i].value);
    }
    return picked.length === 0 || picked.length === boxes.length ? null : picked;
}

// Snapshot of the ACTIVE view's widget state — only that view's filter form exists in the DOM
// (REQ-INV-030), so the other view's slot is never touched by a persist.
function snapshotMyInventoryFilters() {
    if (lagerIsItemsView()) {
        return {
            gameItems: myInventoryFilterSelection('gameItemCheck'),
            jobOrders: myInventoryFilterSelection('jobOrderCheck'),
            personalOnly: personalFlagChecked('personalOnly', 'itemPersonalOnly'),
            nonPersonalOnly: personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly'),
        };
    }
    const minQualitySelect = document.getElementById('minQuality');
    return {
        materials: myInventoryFilterSelection('matCheck'),
        minQuality: minQualitySelect ? minQualitySelect.value : '',
        jobOrders: myInventoryFilterSelection('jobOrderCheck'),
        missions: myInventoryFilterSelection('missionCheck'),
        personalOnly: personalFlagChecked('personalOnly', 'itemPersonalOnly'),
        nonPersonalOnly: personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly'),
    };
}

// Persists the active view's current widget state into its slot of the shared two-view JSON.
// Called from filterMyInventory, so every filter change — including the reset button's cleared
// state — is stored immediately; a state-preserving re-run (live-sync refresh, modal write) just
// rewrites the same snapshot.
function persistMyInventoryFilters() {
    const stored = readMyInventoryFilterPref() || {};
    stored[lagerIsItemsView() ? 'items' : 'material'] = snapshotMyInventoryFilters();
    writeMyInventoryFilterPref(stored);
}

// Applies a saved multi-select subset to its checkbox family. Saved values whose checkbox no
// longer exists are dropped silently; an entirely-stale subset leaves every box unchecked — this
// page's all-unchecked "no filter" default. The select-all box and the dropdown header text are
// re-synced via updateSelectState. Returns whether any box ended up checked (i.e. the widget now
// differs from the bare-URL rendered default).
function applyMySavedSelection(saved, checkClass, allId, headerId) {
    if (!Array.isArray(saved) || saved.length === 0) return false;
    const boxes = document.getElementsByClassName(checkClass);
    if (boxes.length === 0) return false;
    let any = false;
    for (let i = 0; i < boxes.length; i++) {
        const on = saved.indexOf(boxes[i].value) >= 0;
        boxes[i].checked = on;
        if (on) any = true;
    }
    updateSelectState(allId, checkClass, headerId);
    return any;
}

// Restores the active view's saved filter state on a bare-URL load and returns whether the
// widgets now differ from the rendered default — the caller then triggers the page's existing
// fragment re-fetch exactly once. A load WITH filter query params adopts the server-rendered
// URL state instead and re-persists it (URL wins).
function restoreMyInventoryFilters() {
    let params;
    try {
        params = new URLSearchParams(window.location.search);
    } catch (_e) {
        return false;
    }
    if (MY_INVENTORY_FILTER_PARAMS.some((p) => params.has(p))) {
        persistMyInventoryFilters();
        return false;
    }
    const stored = readMyInventoryFilterPref();
    const saved = stored ? stored[lagerIsItemsView() ? 'items' : 'material'] : null;
    if (!saved || typeof saved !== 'object') return false;
    let changed = false;
    // [saved subset, checkbox class, select-all id, header id] per multi-select family of the
    // active view (the jobOrder family renders item-prefixed all/header ids in the items view).
    let families;
    if (lagerIsItemsView()) {
        families = [
            [saved.gameItems, 'gameItemCheck', 'gameItemAll', 'gameItemHeader'],
            [saved.jobOrders, 'jobOrderCheck', 'itemJobOrderAll', 'itemJobOrderHeader'],
        ];
    } else {
        families = [
            [saved.materials, 'matCheck', 'matAll', 'materialHeader'],
            [saved.jobOrders, 'jobOrderCheck', 'jobOrderAll', 'jobOrderHeader'],
            [saved.missions, 'missionCheck', 'missionAll', 'missionHeader'],
        ];
        const minQualitySelect = document.getElementById('minQuality');
        if (minQualitySelect && typeof saved.minQuality === 'string' && saved.minQuality !== '') {
            minQualitySelect.value = saved.minQuality;
            // A stale quality (no matching option) resets the select to '' — keep the default.
            if (minQualitySelect.value === saved.minQuality) changed = true;
        }
    }
    families.forEach(function (f) {
        if (applyMySavedSelection(f[0], f[1], f[2], f[3])) changed = true;
    });
    // The personal flags are mutually exclusive (see togglePersonalFilter); on a corrupt
    // both-true state personalOnly wins so the pair never intersects to an empty result.
    const personalBox =
        document.getElementById('personalOnly') || document.getElementById('itemPersonalOnly');
    const nonPersonalBox =
        document.getElementById('nonPersonalOnly') ||
        document.getElementById('itemNonPersonalOnly');
    if (personalBox && saved.personalOnly === true) {
        personalBox.checked = true;
        changed = true;
    } else if (nonPersonalBox && saved.nonPersonalOnly === true) {
        nonPersonalBox.checked = true;
        changed = true;
    }
    return changed;
}

// ===================== Filter panel collapse (REQ-INV-037) =====================================
// The filter widgets are a full row above the table and wrap onto lines of their own, which
// pushes the bulk bar and the table down. The panel collapses out of the flow; the preference is
// per browser and lives in the SAME localStorage object as the filter values, under a top-level
// slot. Top-level, not per view, because it describes the page's chrome rather than one view's
// selection — switching Material <-> Items must not silently re-open a panel the user closed.
//
// persistMyInventoryFilters() re-reads the whole object and replaces only its view slot, so the
// two writers never clobber each other.
const MY_INVENTORY_FILTER_PANEL_KEY = 'panelCollapsed';

// Number of dimensions currently narrowing the table. Derived from the very snapshot the
// persistence layer stores, so a filter dimension added there is counted here automatically
// instead of silently missing from the badge. A multi-select is null in that snapshot when zero
// OR all of its boxes are ticked — both mean "no filter" on this page — so "all ticked"
// correctly counts as nothing.
function countActiveMyInventoryFilters() {
    const snapshot = snapshotMyInventoryFilters();
    let active = 0;
    ['materials', 'gameItems', 'jobOrders', 'missions'].forEach(function (dimension) {
        if (Array.isArray(snapshot[dimension]) && snapshot[dimension].length > 0) active++;
    });
    if (typeof snapshot.minQuality === 'string' && snapshot.minQuality !== '') active++;
    if (snapshot.personalOnly === true) active++;
    if (snapshot.nonPersonalOnly === true) active++;
    return active;
}

// Re-renders the count chip on the toggle. Called from filterMyInventory, which every filter
// change — including the reset button — funnels through, so the chip cannot fall behind the
// widgets.
function updateMyFilterCountBadge() {
    const badge = document.getElementById('myFilterCount');
    if (!badge) return;
    const active = countActiveMyInventoryFilters();
    badge.hidden = active === 0;
    const value = document.getElementById('myFilterCountValue');
    if (value) value.textContent = String(active);
    // The bare digit reads out as "Filter 3". The visually-hidden twin spells it out instead,
    // rather than putting the count into a dynamic aria-label — that would shadow the visible
    // "Filter" text and break voice control's "click Filter".
    const label = document.getElementById('myFilterCountLabel');
    if (!label) return;
    const template = badge.getAttribute('data-label') || '';
    label.textContent = active === 0 ? '' : template.replace('{0}', String(active));
}

function setMyFilterPanelCollapsed(collapsed) {
    const toggle = document.getElementById('myFilterToggle');
    const panel = document.getElementById('myFilterPanel');
    if (!toggle || !panel) return;
    panel.hidden = collapsed;
    toggle.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
}

function toggleMyFilterPanel() {
    const panel = document.getElementById('myFilterPanel');
    if (!panel) return;
    const collapsed = !panel.hidden;
    setMyFilterPanelCollapsed(collapsed);
    const stored = readMyInventoryFilterPref() || {};
    stored[MY_INVENTORY_FILTER_PANEL_KEY] = collapsed;
    writeMyInventoryFilterPref(stored);
}

// Applies the stored collapse preference on load. With no preference stored yet the panel
// collapses only when nothing is filtered: opening a narrowed table with its filter row out of
// sight would leave the user hunting for the reason, which is exactly the trap the count chip
// exists to close.
function initMyFilterPanel() {
    if (!document.getElementById('myFilterToggle')) return;
    updateMyFilterCountBadge();
    const stored = readMyInventoryFilterPref();
    const saved = stored ? stored[MY_INVENTORY_FILTER_PANEL_KEY] : undefined;
    setMyFilterPanelCollapsed(
        typeof saved === 'boolean' ? saved : countActiveMyInventoryFilters() === 0,
    );
}

function filterMyInventory() {
    // REQ-INV-030: the rebuilt fragment URL is derived from the page's own filter state PLUS the
    // active view, so a filter change, a modal write and a live-sync peer refresh all re-render
    // whichever view (Material or Items) is on screen. Only the active view's filter form exists
    // in the DOM, so the class-driven collections of the other view are simply empty.
    //
    // The grouped table is swapped wholesale here (filter change, post-write refresh, or a live-sync
    // peer refresh), so the freshly rendered leaf checkboxes come back unchecked. Reset the bulk
    // selection to match (REQ-INV-034): keeping stale ids across a re-render would let a bulk
    // check-out target an entry a peer already removed (backend 404) or an entry no longer in the
    // filtered view. "Alle markieren" itself does not re-swap the table, so a live selection survives
    // drill-down expansion.
    clearBulkSelection();
    // Persist the current filter selection per browser (REQ-UI-017) — every filter change,
    // including the reset button, funnels through here, so the snapshot is always current.
    persistMyInventoryFilters();
    // Same funnel, same reason: the count on the (possibly collapsed) toggle must track the
    // widgets, or a collapsed panel starts hiding an active filter.
    updateMyFilterCountBadge();
    const itemsView = lagerIsItemsView();
    const activeMaterials = collectMyChecked('matCheck');
    const activeGameItems = collectMyChecked('gameItemCheck');
    const activeJobOrders = collectMyChecked('jobOrderCheck');
    const activeMissions = collectMyChecked('missionCheck');
    const minQualitySelect = document.getElementById('minQuality');
    const minQuality = minQualitySelect ? minQualitySelect.value : '';
    const personalOnly = personalFlagChecked('personalOnly', 'itemPersonalOnly');
    const nonPersonalOnly = personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly');

    const container = document.getElementById('myInventoryTableContainer');
    if (!container) return;
    container.style.opacity = '0.5';
    container.style.pointerEvents = 'none';

    const url = new URL(window.location.origin + '/inventory/my');
    url.searchParams.append('fragment', 'true');
    if (itemsView) url.searchParams.append('view', 'items');
    activeMaterials.forEach((m) => url.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => url.searchParams.append('gameItemIds', g));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));
    if (personalOnly) url.searchParams.append('personalOnly', 'true');
    if (nonPersonalOnly) url.searchParams.append('nonPersonalOnly', 'true');

    const visibleUrl = new URL(window.location.origin + '/inventory/my');
    if (itemsView) visibleUrl.searchParams.append('view', 'items');
    activeMaterials.forEach((m) => visibleUrl.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => visibleUrl.searchParams.append('gameItemIds', g));
    if (minQuality) visibleUrl.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => visibleUrl.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => visibleUrl.searchParams.append('missionIds', m));
    if (personalOnly) visibleUrl.searchParams.append('personalOnly', 'true');
    if (nonPersonalOnly) visibleUrl.searchParams.append('nonPersonalOnly', 'true');
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
    ['matCheck', 'gameItemCheck', 'jobOrderCheck', 'missionCheck'].forEach(function (cls) {
        const boxes = document.getElementsByClassName(cls);
        for (let i = 0; i < boxes.length; i++) boxes[i].checked = false;
    });
    ['matAll', 'gameItemAll', 'jobOrderAll', 'itemJobOrderAll', 'missionAll'].forEach(
        function (id) {
            const el = document.getElementById(id);
            if (el) el.checked = false;
        },
    );
    const minQualitySelect = document.getElementById('minQuality');
    if (minQualitySelect) minQualitySelect.value = '';
    ['personalOnly', 'nonPersonalOnly', 'itemPersonalOnly', 'itemNonPersonalOnly'].forEach(
        function (id) {
            const el = document.getElementById(id);
            if (el) el.checked = false;
        },
    );
    if (document.getElementById('materialHeader'))
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    if (document.getElementById('gameItemHeader'))
        updateSelectState('gameItemAll', 'gameItemCheck', 'gameItemHeader');
    if (document.getElementById('jobOrderHeader'))
        updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
    if (document.getElementById('itemJobOrderHeader'))
        updateSelectState('itemJobOrderAll', 'jobOrderCheck', 'itemJobOrderHeader');
    if (document.getElementById('missionHeader'))
        updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    filterMyInventory();
}

// Live peer-sync for the personal Lager (REQ-FE-010 / REQ-FE-015, #1307/#1309). /inventory/my joins
// the same global "inventory" room as the shared Lager, so a change to the viewer's own stock made
// elsewhere (e.g. an admin edits it on /inventory/all, or another tab) refreshes this view, and this
// page's writes tell those other inventory views. One opaque "stock" section = the whole owned table.
const INVENTORY_MY_SECTIONS = {
    stock: { container: '#myInventoryTableContainer', fragmentValue: 'stock' },
};

// Broadcast that this viewer's stock changed; keys derive from the seam map so they can never drift
// from the whitelist, and the relay excludes the origin socket (no self-refresh). Exposed on window
// so the shared note modal (inventory-note-modal.js) can notify from either inventory page.
function broadcastInventoryChanged() {
    if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
        window.krtLiveSync.sendChanged('inventory', Object.keys(INVENTORY_MY_SECTIONS));
    }
}
window.krtNotifyInventoryChanged = broadcastInventoryChanged;

// Cross-feature live-sync (#1309): an inventory write also changes surfaces in OTHER rooms.
// broadcastOrdersChanged tells each affected job order's detail viewers to re-pull their material
// collection (its stock column tracks the earmark roll-up) and — for item rows — the order-detail
// Item-Bestand panel (`item-stock`, REQ-ORDERS-028), and broadcastBoardChanged tells the
// Materialbörse to re-pull its board after a stock-reducing write (the backend clamps an offer down
// to the remaining stock). The actor is not in those rooms, so there is no self-refresh; a section
// whose container the page does not render is a harmless no-op.
function broadcastOrdersChanged(orderIds) {
    if (!window.krtLiveSync || typeof window.krtLiveSync.sendChanged !== 'function') return;
    let touchedAnyOrder = false;
    (orderIds || []).forEach(function (orderId) {
        if (orderId) {
            touchedAnyOrder = true;
            window.krtLiveSync.sendChanged('order:' + orderId, [
                'materials',
                'aggregated',
                'item-stock',
            ]);
        }
    });
    // The cross-order material-demand overview (REQ-ORDERS-034) reads the same earmarked stock as
    // the per-order material list, so a write that changes an order's linked stock also changes the
    // aggregated `Bestand` column. It lives in the global `orders` room, hence one extra publish
    // rather than one per order.
    if (touchedAnyOrder) {
        window.krtLiveSync.sendChanged('orders', ['demand']);
    }
}
function broadcastBoardChanged() {
    if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
        window.krtLiveSync.sendChanged('materialboard', ['board']);
    }
}
// The job-order target-ids currently earmarked on an entry's leaf row (read before a stock write).
function collectLeafOrderIds(itemId) {
    const leaf = document.querySelector('.tree-row--leaf[data-item-id="' + itemId + '"]');
    if (!leaf) return [];
    const ids = [];
    leaf.querySelectorAll(
        '.assoc-split[data-assoc-field="JOB_ORDER"] [data-assoc-chip][data-target-id]',
    ).forEach(function (chip) {
        const id = chip.getAttribute('data-target-id');
        if (id && ids.indexOf(id) < 0) ids.push(id);
    });
    return ids;
}

// Inbound peer changes: re-fetch this viewer's own filtered owned table in place (filterMyInventory
// preserves the filter + tree expansion; a collapsed stack re-fetches its chips on next expand).
if (
    window.krtLiveSync &&
    typeof window.krtLiveSync.createReceiver === 'function' &&
    document.getElementById('myInventoryTableContainer')
) {
    window.krtLiveSync.createReceiver({
        topic: 'inventory',
        sections: INVENTORY_MY_SECTIONS,
        coalesceMs: 1500,
        refresh: function () {
            filterMyInventory();
        },
    });
}

document.addEventListener('DOMContentLoaded', function () {
    // Restore the persisted per-browser filter state first (REQ-UI-017): on a bare URL the
    // saved selection is applied to the widgets, and — only when it differs from the rendered
    // default — the existing fragment re-fetch runs exactly once at the end of this handler.
    const filtersRestored = restoreMyInventoryFilters();
    if (document.getElementsByClassName('matCheck').length > 0) {
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    }
    if (document.getElementsByClassName('gameItemCheck').length > 0) {
        updateSelectState('gameItemAll', 'gameItemCheck', 'gameItemHeader');
    }
    if (document.getElementsByClassName('jobOrderCheck').length > 0) {
        // The material and the items view render different header/all ids for the shared
        // jobOrderCheck class (unique ids in the template source); exactly one pair exists.
        if (document.getElementById('itemJobOrderHeader')) {
            updateSelectState('itemJobOrderAll', 'jobOrderCheck', 'itemJobOrderHeader');
        } else {
            updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
        }
    }
    if (document.getElementsByClassName('missionCheck').length > 0) {
        updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    }
    // After the restore, so the count reflects the widgets the user will actually see and the
    // no-preference default ("collapse only when nothing is filtered") judges the restored state
    // rather than the bare server-rendered one.
    initMyFilterPanel();
    if (filtersRestored) filterMyInventory();
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

// localStorage key holding the array of expanded group ids. View-scoped (REQ-INV-030): the
// items view persists under expanded_rows_lager_items_* so the Material and the Items tree
// remember their expansion state independently.
function groupStorageKey() {
    const userId = lagerUserId();
    if (!userId) return null;
    return (lagerIsItemsView() ? 'expanded_rows_lager_items_' : 'expanded_rows_lager_') + userId;
}

// localStorage key holding the array of expanded stack ids (view-scoped like groupStorageKey).
function stackStorageKey() {
    const userId = lagerUserId();
    if (!userId) return null;
    return (
        (lagerIsItemsView() ? 'expanded_stacks_lager_items_' : 'expanded_stacks_lager_') + userId
    );
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

// Re-applies the persisted group expansion (material or game-item groups — see groupKeyOf) to
// the freshly rendered tree.
function restoreExpandedGroups() {
    const expandedRows = readExpanded(groupStorageKey());
    if (expandedRows.length === 0) return;
    document.querySelectorAll('.tree-row--group').forEach(function (row) {
        const groupKey = groupKeyOf(row);
        if (groupKey && expandedRows.includes(groupKey)) {
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
    const groupKey = groupKeyOf(row);
    if (!nextRow || !nextRow.classList.contains('tree-group-items')) return;

    const key = groupStorageKey();
    const expandedRows = readExpanded(key);
    if (window.getComputedStyle(nextRow).display === 'none') {
        nextRow.style.display = 'block';
        if (icon) icon.textContent = '▼';
        if (groupKey && !expandedRows.includes(groupKey)) {
            expandedRows.push(groupKey);
            writeExpanded(key, expandedRows);
        }
    } else {
        nextRow.style.display = 'none';
        if (icon) icon.textContent = '▶';
        if (groupKey) {
            writeExpanded(
                key,
                expandedRows.filter((id) => id !== groupKey),
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
// A game-item stack (REQ-INV-030) is addressed by gameItemId with no quality key and
// goes to the item sibling endpoint; the material branch stays byte-identical (its
// param order is also the persisted stack identity — see stackKey).
function buildStackEntriesUrl(headerRow, page) {
    const params = new URLSearchParams();
    const gameItemId = headerRow.getAttribute('data-game-item-id');
    if (gameItemId) {
        params.set('gameItemId', gameItemId);
        params.set('locationId', headerRow.getAttribute('data-location-id'));
    } else {
        params.set('materialId', headerRow.getAttribute('data-material-id'));
        params.set('locationId', headerRow.getAttribute('data-location-id'));
        const quality = headerRow.getAttribute('data-quality');
        if (quality !== null && quality !== '') params.set('quality', quality);
    }
    params.set('personal', headerRow.getAttribute('data-personal') || 'false');
    const owningOrgUnitId = headerRow.getAttribute('data-owning-org-unit-id');
    if (owningOrgUnitId) params.set('owningOrgUnitId', owningOrgUnitId);
    if (page != null) params.set('page', page);
    const path = gameItemId
        ? '/inventory/my/game-item-stack/entries?'
        : '/inventory/my/stack/entries?';
    return path + params.toString();
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
            // The entries are injected via innerHTML (not krtFetch.swap), so no krt:swapped fires —
            // enhance the Variante-C allocation "+ Zuordnen" <select data-krt-combobox> popovers by
            // hand (REQ-INV-027), else they stay raw native selects instead of the HUD combobox and
            // the add-open reset (hidden.krtCombobox.setValue / input focus) has nothing to target.
            if (typeof window.krtEnhanceComboboxes === 'function') {
                window.krtEnhanceComboboxes(content);
            }
            // Reflect the current bulk selection on the freshly injected checkboxes (REQ-INV-034):
            // a stack expanded after "Alle markieren" (or after ticking others) must come up already
            // checked, and the count/group state must stay consistent.
            applyBulkSelectionToLoaded(content);
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
// #1328: the row's current owning org-unit id, used to preset both Umbuchen org-unit pickers so a
// submit that does not touch the picker keeps the stock in its current unit (null = ownerless row).
let umbuchenCurrentOwningOrgUnitId = null;
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
 * (the relocated book-out transfer picker). Offers the destination user's direct memberships across
 * all four org-unit kinds (Staffel + SK + Bereich + OL), preset to the row's current owning org unit
 * (or the target's primary unit otherwise). Shown whenever the destination user has at least one
 * membership; hidden only for a membershipless target (the moved row is then ownerless).
 */
function refreshUmbuchenTransferOrgUnitPicker() {
    const wrapper = document.getElementById('umbuchenTargetOwningOrgUnitWrapper');
    const select = document.getElementById('umbuchenTargetOwningOrgUnitId');
    const userSelect = document.getElementById('umbuchenTargetUserId');
    if (!wrapper || !select || !userSelect) return;
    const targetUserId = userSelect.value;
    select.innerHTML = '';
    if (!targetUserId) {
        wrapper.style.display = 'none';
        return;
    }
    // #1328: ?allKinds=true surfaces the target user's Bereich/OL memberships too (not just
    // Staffel/SK — the endpoint default), mirroring the bank counterparty picker (REQ-BANK-044).
    // The backend resolver already accepts a Bereich/OL owner. The fetch goes through the
    // frontend's /users/{id}/memberships proxy (UserProxyController) — the frontend origin maps
    // no /api/v1/users/** route, so the backend path 404s here and silently hides the picker.
    fetch('/users/' + encodeURIComponent(targetUserId) + '/memberships?allKinds=true', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            // #1328: always show the picker when the target has at least one membership and preset
            // it to the row's current owning org unit (or the target's primary when that unit is not
            // one of the target's memberships — e.g. a cross-user transfer), so submitting without
            // changing it keeps the stock in its current unit. Hidden only for a membershipless
            // target: the row is then ownerless and there is nothing to pick.
            if (!Array.isArray(memberships) || memberships.length < 1) {
                wrapper.style.display = 'none';
                return;
            }
            memberships.forEach(function (opt) {
                const o = document.createElement('option');
                o.value = opt.orgUnitId;
                o.textContent = opt.orgUnitName;
                select.appendChild(o);
            });
            if (
                umbuchenCurrentOwningOrgUnitId &&
                memberships.some(function (m) {
                    return m.orgUnitId === umbuchenCurrentOwningOrgUnitId;
                })
            ) {
                select.value = umbuchenCurrentOwningOrgUnitId;
            }
            wrapper.style.display = 'block';
        })
        .catch(function () {
            wrapper.style.display = 'none';
        });
}

/**
 * PERSONAL de-personalize picker: populate the org-unit picker with the row owner's memberships
 * across all four org-unit kinds (Staffel + SK + Bereich + OL), preset to the row's current owning
 * org unit (or the owner's primary unit when the row has none). Shown whenever the owner has at
 * least one membership; hidden only for a membershipless owner (the shared row is then ownerless).
 */
function refreshUmbuchenPersonalOrgUnitPicker(ownerId) {
    const wrapper = document.getElementById('umbuchenPersonalOrgUnitWrapper');
    const select = document.getElementById('umbuchenPersonalOrgUnitId');
    if (!wrapper || !select) return;
    select.innerHTML = '';
    wrapper.style.display = 'none';
    if (!ownerId) return;
    // #1328: ?allKinds=true so a Bereich/OL-member owner can de-personalize into their Bereich/OL
    // pool, not only Staffel/SK (the endpoint default). Mirrors the bank counterparty picker
    // (REQ-BANK-044). Fetched via the frontend's /users/{id}/memberships proxy — see the
    // transfer picker above.
    fetch('/users/' + encodeURIComponent(ownerId) + '/memberships?allKinds=true', {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (memberships) {
            // #1328: show whenever the owner has ≥1 membership and preset the row's current owning
            // org unit (else the owner's primary), so de-personalizing keeps the current unit unless
            // the owner changes it. Hidden only for a membershipless owner (ownerless shared row).
            if (!Array.isArray(memberships) || memberships.length < 1) return;
            memberships.forEach(function (opt) {
                const o = document.createElement('option');
                o.value = opt.orgUnitId;
                o.textContent = opt.orgUnitName;
                select.appendChild(o);
            });
            if (
                umbuchenCurrentOwningOrgUnitId &&
                memberships.some(function (m) {
                    return m.orgUnitId === umbuchenCurrentOwningOrgUnitId;
                })
            ) {
                select.value = umbuchenCurrentOwningOrgUnitId;
            }
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
    // The transfer "Herkunft" picker only applies to LOCATION mode; recompute after the display
    // toggle so switching to PERSONAL re-enables the submit and switching back re-gates it.
    if (window.krtHerkunft) {
        window.krtHerkunft.recompute('umbuchen');
    }
}

// Opens the Umbuchen (rebooking/transfer) modal for one leaf entry. `locationName` is the row's
// current location label: the target-location picker is a REMOTE combobox (remote-locations), so
// presetting it needs the label alongside the id — the loaded item set cannot resolve it locally.
function openUmbuchenModal(
    id,
    amount,
    version,
    materialId,
    userId,
    locationId,
    locationName,
    quantityType,
    personal,
    hasAssoc,
    owningOrgUnitId,
) {
    umbuchenItemId = id;
    umbuchenCurrentOwningOrgUnitId = owningOrgUnitId || null;
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

    // LOCATION (transfer) defaults: pre-select the row's own user/location. The location picker
    // is a REMOTE searchable combobox (remote-locations, REQ-FE-016): the catalog is fetched per
    // query, so the row's location is outside the loaded set — setValue() must carry the label
    // with the id (a bare id would clear the selection, a bare .value write the visible text).
    const tu = document.getElementById('umbuchenTargetUserId');
    const tl = document.getElementById('umbuchenTargetLocationId');
    if (tu) tu.value = userId;
    if (tl && tl.krtCombobox) {
        tl.krtCombobox.setValue(locationId, locationName);
    } else if (tl) {
        tl.value = locationId;
    }
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
    // `.modal` centres its content via `display:flex`; opening with inline `flex` (not `block`)
    // preserves that centring — inline `block` would override the stylesheet flex and pin the
    // dialog to the top of the viewport (matches the canonical .krtm-modal-open = flex, #1328).
    document.getElementById('umbuchenModal').style.display = 'flex';
    // Variante C (REQ-INV-027): build the transfer "Herkunft" picker (the moved row inherits the
    // reduced tags). It lives inside the LOCATION-only transfer fields, so it self-hides in PERSONAL
    // mode; populate after the modal is shown so its initial validity gates the submit button.
    if (window.krtHerkunft) {
        window.krtHerkunft.populate('umbuchen', id);
    }
}

function closeUmbuchenModal() {
    if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
    if (window.krtHerkunft) {
        window.krtHerkunft.reset('umbuchen');
    }
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
        // Variante C (REQ-INV-027): a transfer carries its reduced tags onto the moved row. An
        // invalid plan already disables the submit button; guard the Enter-key path too.
        if (window.krtHerkunft && !window.krtHerkunft.isValid('umbuchen')) {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.overallocated);
            }
            return;
        }
        const reductions = window.krtHerkunft
            ? window.krtHerkunft.collect('umbuchen')
            : { jobOrderReductions: null, missionReductions: null };
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
            jobOrderReductions: reductions.jobOrderReductions,
            missionReductions: reductions.missionReductions,
        };
    }

    // Read the earmarked orders before the write. Only a LOCATION transfer touches order slices; the
    // PERSONAL rebook is refused on an allocated row, so it only clamps offers (board), not orders.
    const affectedOrderIds = mode === 'PERSONAL' ? [] : collectLeafOrderIds(umbuchenItemId);
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
                broadcastInventoryChanged();
                broadcastOrdersChanged(affectedOrderIds);
                broadcastBoardChanged();
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
    // Variante C (REQ-INV-027): the "Herkunft" picker chooses which order/mission slices (or the
    // rest) the deduction comes from and, for a SELL, which missions get the coupled proceeds. An
    // invalid plan already disables the submit button; guard the Enter-key path too. A null list
    // means "take it from the rest" (SELL → that portion is personal).
    if (window.krtHerkunft && !window.krtHerkunft.isValid('bookout')) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(assocI18n.overallocated);
        }
        return;
    }
    const reductions = window.krtHerkunft
        ? window.krtHerkunft.collect('bookout')
        : { jobOrderReductions: null, missionReductions: null };
    const payload = {
        amount: amount,
        type: type,
        terminal: type === 'SELL' ? document.getElementById('terminal').value || null : null,
        sellAmount:
            type === 'SELL' && sellAmountEl.value !== '' ? Number(sellAmountEl.value) : null,
        version: parseInt(document.getElementById('version').value, 10),
        jobOrderReductions: reductions.jobOrderReductions,
        missionReductions: reductions.missionReductions,
    };
    const submitBtn = document.getElementById('bookOutSubmitBtn');
    // Read the earmarked orders before the write (the leaf is replaced on the post-write re-swap).
    const affectedOrderIds = collectLeafOrderIds(bookOutItemId);
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
                broadcastInventoryChanged();
                broadcastOrdersChanged(affectedOrderIds);
                broadcastBoardChanged();
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

    // Inline `flex` (not `block`) so `.modal`'s flex centring is preserved (see openUmbuchenModal).
    document.getElementById('bookOutModal').style.display = 'flex';
    // Variante C (REQ-INV-027): build the "Herkunft" (deduct-from) picker from this entry's chips
    // now that the modal is shown, so its initial validity gates the submit button.
    if (window.krtHerkunft) {
        window.krtHerkunft.populate('bookout', id);
    }
}

function closeBookOutModal() {
    if (typeof window.resetUnsavedChanges === 'function') {
        window.resetUnsavedChanges();
    }
    if (window.krtHerkunft) {
        window.krtHerkunft.reset('bookout');
    }
    document.getElementById('bookOutModal').style.display = 'none';
}

window.onclick = function (event) {
    const modal = document.getElementById('bookOutModal');
    if (event.target === modal) {
        modal.style.display = 'none';
    }
};

// ── Variante C allocation chips (REQ-INV-027) ──────────────────────────────
// Each .assoc-split (one per dimension per entry) renders its job-order / mission
// allocations as chips + a trailing rest chip, plus a "+ Zuordnen" combobox
// popover. Add / edit / remove call the per-allocation endpoints
// (POST/PATCH/DELETE /inventory/{id}/allocation) and update the split in place
// from the returned InventoryItemDto (chips + rest + version), so the drilled-down
// stack stays expanded and no full-page reload is needed (REQ-FE-001).
const ASSOC_EPS = 0.0005;

// Formats an amount for a chip / rest label: whole for PIECE, three decimals for SCU.
function assocFormatAmount(amount, isPiece) {
    const n = typeof amount === 'number' ? amount : parseFloat(amount);
    if (isNaN(n)) return '0';
    return isPiece ? String(Math.round(n)) : n.toFixed(3);
}

// Hides every open allocation popover except `except` (the one being opened).
function assocCloseAllPops(except) {
    document.querySelectorAll('[data-assoc-pop]').forEach(function (p) {
        if (p !== except) p.classList.add('krtm-hidden');
    });
}

// Anchors a `position: fixed` allocation popover to its trigger in viewport space. The
// popover is fixed (not absolute) so the horizontally-scrolling ancestors
// (#tableContainer.overflow-x-auto + .table-responsive) can't crop it at their bottom edge;
// fixed positioning drops the stylesheet's top/left, so they are recomputed here from the
// trigger wrap's rect (mirrors the old `top: calc(100% + 5px); left: 0`). When the trigger sits
// near the viewport bottom there is no room to drop the popover downward — and a fixed box can't
// be scrolled into view — so it flips above the trigger (bottom-anchored, so a later switch to
// the taller/shorter amount section stays glued to the trigger). Mirrors krt-searchable-select's
// positionListbox (REQ-UI-011). Runs while the popover is visible so offsetHeight is measurable.
function assocPositionPop(pop) {
    const wrap = pop.closest('.assoc-add-wrap');
    if (!wrap) return;
    const rect = wrap.getBoundingClientRect();
    const gap = 5;
    const popHeight = pop.offsetHeight;
    const below = window.innerHeight - rect.bottom;
    const above = rect.top;
    // Flip up only when the popover ACTUALLY fits above. The old test was `above > below` —
    // "more room above" — which happily flips a popover taller than the space above it and
    // leaves its upper end (in pick mode: the combobox) hanging over the viewport top. A
    // `position: fixed` box cannot be scrolled into view, so that part is unreachable, not
    // merely clipped (REQ-UI-011).
    const flipUp = below < popHeight + gap && above >= popHeight + gap;
    // Highest top the popover can take and still end fully inside the viewport. The max() keeps
    // it sane when the popover is taller than the viewport itself — it then starts at the edge.
    const maxTop = Math.max(gap, window.innerHeight - popHeight - gap);
    const wantedTop = flipUp ? rect.top - gap - popHeight : rect.bottom + gap;
    const top = Math.max(gap, Math.min(wantedTop, maxTop));
    pop.style.left = rect.left + 'px';
    if (flipUp) {
        // Bottom-anchored (derived from the clamped top) so a later switch to the taller/shorter
        // amount section keeps the popover's lower edge glued to the trigger.
        pop.style.top = 'auto';
        pop.style.bottom = window.innerHeight - top - popHeight + 'px';
    } else {
        pop.style.bottom = 'auto';
        pop.style.top = top + 'px';
    }
}

// Keeps the currently-open popover glued to its trigger as the window or the table's own
// scroll container moves (capture catches inner-container scrolls, which do not bubble).
// Only one popover is open at a time (assocCloseAllPops), so the first visible one wins.
function assocRepositionOpenPop() {
    const pop = document.querySelector('[data-assoc-pop]:not(.krtm-hidden)');
    if (pop) assocPositionPop(pop);
}

// Switches a popover to its combobox (pick) section.
function assocShowPickSection(pop) {
    const pick = pop.querySelector('[data-assoc-pop-pick]');
    const amount = pop.querySelector('[data-assoc-pop-amount]');
    if (pick) pick.classList.remove('krtm-hidden');
    if (amount) amount.classList.add('krtm-hidden');
}

// Switches a popover to its amount-editor section; `showRemove` reveals Entfernen (edit mode).
function assocShowAmountSection(pop, showRemove) {
    const pick = pop.querySelector('[data-assoc-pop-pick]');
    const amount = pop.querySelector('[data-assoc-pop-amount]');
    if (pick) pick.classList.add('krtm-hidden');
    if (amount) amount.classList.remove('krtm-hidden');
    const removeBtn = pop.querySelector('[data-trigger="inv-my-assoc-remove"]');
    if (removeBtn) removeBtn.classList.toggle('krtm-hidden', !showRemove);
}

// Builds one allocation chip element from a returned allocation DTO.
function assocBuildChip(field, alloc, isPiece) {
    const isOrder = field === 'JOB_ORDER';
    const chip = document.createElement('span');
    chip.className = 'assoc-chip ' + (isOrder ? 'assoc-chip--order' : 'assoc-chip--mission');
    chip.setAttribute('role', 'button');
    chip.setAttribute('tabindex', '0');
    chip.setAttribute('data-trigger', 'inv-my-assoc-edit');
    chip.setAttribute('data-assoc-chip', isOrder ? 'jobOrder' : 'mission');
    chip.setAttribute('data-target-id', isOrder ? alloc.jobOrderId : alloc.missionId);
    chip.setAttribute('data-amount', alloc.amount);
    const label = isOrder ? '#' + alloc.jobOrderDisplayId : alloc.missionName;
    chip.appendChild(document.createTextNode(label + ' · '));
    const amt = document.createElement('span');
    amt.className = 'assoc-chip__amt';
    amt.textContent = assocFormatAmount(alloc.amount, isPiece);
    chip.appendChild(amt);
    return chip;
}

// Recomputes a rest chip's tone + label: 0 -> success, unassigned remainder -> muted "frei",
// over-allocation (negative) -> danger. isPiece formats the amount whole (no decimals) for a
// PIECE material, three decimals for SCU.
function assocUpdateRestChip(el, rest, isPiece) {
    if (!el) return;
    el.classList.remove('chip--success', 'chip--muted', 'chip--danger');
    if (rest == null || Math.abs(rest) <= ASSOC_EPS) {
        el.classList.add('chip--success');
        el.textContent = assocI18n.restZero;
    } else if (rest < 0) {
        el.classList.add('chip--danger');
        el.textContent = assocI18n.restOver.replace('{0}', assocFormatAmount(-rest, isPiece));
    } else {
        el.classList.add('chip--muted');
        el.textContent = assocI18n.restFree.replace('{0}', assocFormatAmount(rest, isPiece));
    }
}

// Re-renders a split's chips + rest from the returned entry DTO and propagates the fresh
// entry version to every data-version control in the leaf row (both dimensions share the token).
function assocRerender(split, dto) {
    const field = split.getAttribute('data-assoc-field');
    const isPiece = split.getAttribute('data-piece') === 'true';
    const isOrder = field === 'JOB_ORDER';
    const allocs = (isOrder ? dto.jobOrderAllocations : dto.missionAllocations) || [];
    const rest = isOrder ? dto.jobOrderRest : dto.missionRest;
    split.querySelectorAll('[data-assoc-chip]').forEach(function (c) {
        c.remove();
    });
    const addWrap = split.querySelector('.assoc-add-wrap');
    allocs.forEach(function (a) {
        split.insertBefore(assocBuildChip(field, a, isPiece), addWrap);
    });
    assocUpdateRestChip(split.querySelector('[data-assoc-rest]'), rest, isPiece);
    if (
        dto.version != null &&
        window.krtFetch &&
        typeof window.krtFetch.syncVersion === 'function'
    ) {
        const leaf = split.closest('.tree-row--leaf');
        if (leaf) window.krtFetch.syncVersion(leaf, dto.version);
    }
}

// Sends the allocation write, serialized per entry so a rapid second edit of the same row waits
// for the fresh version (REQ-INV-026 / REQ-FE-003 avoid a self-inflicted 409).
function assocSubmit(split, pop, method) {
    const entryId = split.getAttribute('data-entry-id');
    const field = split.getAttribute('data-assoc-field');
    const targetId = pop.getAttribute('data-assoc-target');
    const isPiece = split.getAttribute('data-piece') === 'true';
    let amount = null;
    if (method !== 'DELETE') {
        const input = pop.querySelector('[data-assoc-amount-input]');
        amount = parseFloat(input ? input.value : '');
        if (isNaN(amount) || amount <= 0 || (isPiece && amount % 1 !== 0)) {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.amountRequired);
            }
            return;
        }
        amount = Math.round(amount * 1000) / 1000;
    }
    const run = function () {
        // Read the entry version at SEND time, not click time (REQ-FE-003): both chip dimensions
        // share the entry @Version and the inv-assoc key, so a rapid 2nd edit of the same entry is
        // queued behind the 1st — which force-increments the version and syncs it onto data-version
        // via assocRerender. Reading data-version here (inside the serialized task) picks up that
        // fresh value, avoiding a self-inflicted 409.
        const version = parseInt(split.getAttribute('data-version'), 10);
        const body = { field: field, targetId: targetId, amount: amount, version: version };
        return assocSend(entryId, method, body, split, pop);
    };
    if (window.krtFetch && typeof window.krtFetch.serialize === 'function') {
        return window.krtFetch.serialize('inv-assoc:' + entryId, run);
    }
    return run();
}

async function assocSend(entryId, method, body, split, pop) {
    // #577: CSRF via the shared krtCsrf single source of truth (REQ-FE-002) with retry-on-403.
    let headers = window.krtCsrf
        ? window.krtCsrf.headers()
        : { 'Content-Type': 'application/json' };
    function send() {
        return fetch('/inventory/' + entryId + '/allocation', {
            method: method,
            headers: headers,
            body: JSON.stringify(body),
        });
    }
    try {
        let response = await send();
        if (response.status === 403 && window.krtCsrf && window.krtCsrf.refresh) {
            const refreshed = await window.krtCsrf.refresh();
            if (refreshed) {
                headers = window.krtCsrf.headers();
                response = await send();
            }
        }
        if (response.ok) {
            let dto = null;
            try {
                dto = await response.json();
            } catch {
                /* tolerate empty body */
            }
            if (dto) assocRerender(split, dto);
            pop.classList.add('krtm-hidden');
            if (typeof window.showFrontendSuccessToast === 'function') {
                window.showFrontendSuccessToast(assocI18n.saved);
            }
            broadcastInventoryChanged();
            // A job-order earmark change shifts that order's material collection.
            if (body && body.field === 'JOB_ORDER') {
                broadcastOrdersChanged([body.targetId]);
            }
        } else if (response.status === 409) {
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.conflict);
            }
            setTimeout(() => location.reload(), 2000);
        } else if (response.status === 422) {
            // Over-allocation (REQ-INV-027 R5): a toast, not a reload — the pop stays open so the
            // user can lower the amount.
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(assocI18n.overallocated);
            }
        } else if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(assocI18n.failed);
        }
    } catch (e) {
        console.error(e);
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(assocI18n.failed);
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
    window.krtEvents.on('change', 'inv-my-personal-filter', togglePersonalFilter);
    window.krtEvents.on('click', 'inv-my-reset-filter', resetMyInventoryFilter);
    window.krtEvents.on('click', 'inv-my-toggle-filters', toggleMyFilterPanel);
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
    window.krtEvents.on('change', 'inv-my-update-bulk-state', function (el) {
        onEntryCheckboxToggle(el);
    });
    window.krtEvents.on('click', 'inv-my-select-all', toggleSelectAllInView);
    // Variante C allocation chips (REQ-INV-027).
    window.krtEvents.on('click', 'inv-my-assoc-add-open', function (el) {
        const split = el.closest('.assoc-split');
        const pop = split ? split.querySelector('[data-assoc-pop]') : null;
        if (!pop) return;
        const wasHidden = pop.classList.contains('krtm-hidden');
        assocCloseAllPops(pop);
        if (wasHidden) {
            pop.removeAttribute('data-assoc-target');
            assocShowPickSection(pop);
            const hidden = pop.querySelector('input[type="hidden"]');
            if (hidden && hidden.krtCombobox) hidden.krtCombobox.setValue('');
            pop.classList.remove('krtm-hidden');
            assocPositionPop(pop);
            const cbInput = pop.querySelector('.krt-combobox__input');
            if (cbInput) cbInput.focus();
        } else {
            pop.classList.add('krtm-hidden');
        }
    });
    window.krtEvents.on('change', 'inv-my-assoc-pick', function (el) {
        const value = el.value;
        if (!value) return;
        const pop = el.closest('[data-assoc-pop]');
        if (!pop) return;
        pop.setAttribute('data-assoc-target', value);
        pop.setAttribute('data-assoc-mode', 'add');
        assocShowAmountSection(pop, false);
        const input = pop.querySelector('[data-assoc-amount-input]');
        if (input) {
            input.value = '';
            input.focus();
        }
    });
    window.krtEvents.on('click', 'inv-my-assoc-edit', function (el) {
        const split = el.closest('.assoc-split');
        const pop = split ? split.querySelector('[data-assoc-pop]') : null;
        if (!pop) return;
        assocCloseAllPops(pop);
        pop.setAttribute('data-assoc-target', el.getAttribute('data-target-id'));
        pop.setAttribute('data-assoc-mode', 'edit');
        assocShowAmountSection(pop, true);
        const input = pop.querySelector('[data-assoc-amount-input]');
        if (input) input.value = el.getAttribute('data-amount');
        pop.classList.remove('krtm-hidden');
        assocPositionPop(pop);
        if (input) input.focus();
    });
    window.krtEvents.on('click', 'inv-my-assoc-save', function (el) {
        const pop = el.closest('[data-assoc-pop]');
        const split = el.closest('.assoc-split');
        if (!pop || !split) return;
        assocSubmit(split, pop, pop.getAttribute('data-assoc-mode') === 'edit' ? 'PATCH' : 'POST');
    });
    window.krtEvents.on('click', 'inv-my-assoc-remove', function (el) {
        const pop = el.closest('[data-assoc-pop]');
        const split = el.closest('.assoc-split');
        if (!pop || !split) return;
        assocSubmit(split, pop, 'DELETE');
    });
    // Keep the fixed popover anchored to its trigger while the page or the table's own
    // horizontal scroll container moves (capture reaches inner-container scrolls that don't bubble).
    window.addEventListener('scroll', assocRepositionOpenPop, true);
    window.addEventListener('resize', assocRepositionOpenPop);
    // Close popovers on an outside click; keyboard: Enter saves the amount, Enter/Space opens a
    // chip's editor (the chips are role=button but a <span> gets no synthetic click on key press).
    document.addEventListener('click', function (e) {
        if (
            !e.target.closest('[data-assoc-pop]') &&
            !e.target.closest('.assoc-add') &&
            !e.target.closest('[data-assoc-chip]')
        ) {
            assocCloseAllPops(null);
        }
    });
    document.addEventListener('keydown', function (e) {
        if (!e.target || typeof e.target.matches !== 'function') return;
        if (e.key === 'Enter' && e.target.matches('[data-assoc-amount-input]')) {
            e.preventDefault();
            const pop = e.target.closest('[data-assoc-pop]');
            const split = e.target.closest('.assoc-split');
            if (pop && split) {
                assocSubmit(
                    split,
                    pop,
                    pop.getAttribute('data-assoc-mode') === 'edit' ? 'PATCH' : 'POST',
                );
            }
        } else if ((e.key === 'Enter' || e.key === ' ') && e.target.matches('[data-assoc-chip]')) {
            e.preventDefault();
            e.target.click();
        }
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
            el.getAttribute('data-location-name'),
            el.getAttribute('data-quantity-type'),
            el.getAttribute('data-personal') === 'true',
            el.getAttribute('data-has-assoc') === 'true',
            el.getAttribute('data-owning-org-unit-id'),
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
    // Massen-Umbuchen (REQ-INV-036).
    window.krtEvents.on('click', 'inv-my-open-bulk-rebook', openBulkRebookModal);
    window.krtEvents.on('click', 'inv-my-close-bulk-rebook', closeBulkRebookModal);
    window.krtEvents.on('change', 'inv-my-toggle-bulk-rebook-mode', toggleBulkRebookMode);
    window.krtEvents.on(
        'change',
        'inv-my-bulk-rebook-user-changed',
        refreshBulkRebookOrgUnitPicker,
    );
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
// The Massen-Umbuchen form is a stable top-level element too, so one bound submit listener survives
// the grouped-table re-swaps. It carries no amount input, so no scu-decimal capture phase applies.
let bulkRebookFormEl = document.getElementById('bulkRebookForm');
if (bulkRebookFormEl) {
    bulkRebookFormEl.addEventListener('submit', submitBulkRebook);
}
