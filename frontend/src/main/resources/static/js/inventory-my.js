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

/*
 * Page module for the personal inventory ("Mein Lager") page (templates/inventory-my.html),
 * extracted verbatim from the former end-of-body inline script (#924 Part 2).
 *
 * Covers: bulk checkout and Massen-Umbuchen (modals + krtFetch writes), the note-modal bindings,
 * the multi-select filter dropdowns + AJAX fragment refresh of #myInventoryTableContainer
 * (outerHTML swap), the Umbuchen (rebooking/transfer) modal with its personal <-> shared mode
 * (REQ-INV-007), the personal Lager's live-sync room, and the page's delegated krtEvents bindings
 * plus the direct submit listeners on the stable modal forms. The tree, the book-out modal, the
 * allocation chips and the cross-room live-sync pokes are the Lager behaviour this page shares
 * with inventory-admin.js; they live in inventory-common.js (FE-SIMP-03) and are wired below
 * through myLager.
 *
 * Localized strings come from the small th:inline bootstrap block that precedes this script's
 * loader tag in inventory-my.html; this file must load as a classic synchronous script at the
 * same end-of-body position (after the modals, the toast fragment and inventory-common.js), never
 * with defer.
 */

/* global bulkI18n, bulkRebookI18n, inventoryConflictI18n, umbuchenI18n, assocI18n, showInventoryToast, openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote */

// The Lager behaviour shared with the global page (inventory-common.js). Named `myLager`, not a
// name inventory-admin.js also declares: both are classic scripts sharing one global lexical
// environment (ADR-0069), and the type checker reports a cross-file redeclaration (TS6200).
// inventory-common.js loads ahead of this script (see the header), so the module is installed.
const myLager = /** @type {KrtInventoryApi} */ (window.krtInventory).createLager({
    triggerPrefix: 'inv-my',
    basePath: '/inventory/my',
    stackPerOwner: false,
    stackPersonalFlag: true,
    refreshTable: function () {
        filterMyInventory();
    },
    notifyInventoryChanged: function () {
        broadcastInventoryChanged();
    },
    // Reflect the current bulk selection on the freshly injected checkboxes (REQ-INV-034): a stack
    // expanded after "Alle markieren" (or after ticking others) must come up already checked, and
    // the count/group state must stay consistent.
    onStackEntriesLoaded: function (content) {
        applyBulkSelectionToLoaded(content);
        updateBulkCheckoutState();
    },
});

/**
 * The value of one of this page's form controls, or '' when the page does not render it.
 *
 * @param {string} id the element id
 * @returns {string} the control's value
 */
function myFieldValue(id) {
    const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
    return el ? el.value : '';
}

/**
 * Writes one of this page's form controls, when the page renders it.
 *
 * @param {string} id the element id
 * @param {unknown} value the value, written as its string form ('' for null / undefined)
 */
function setMyFieldValue(id, value) {
    const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
    if (el) el.value = value == null ? '' : String(value);
}

/**
 * Sets an element's inline display (a modal's `flex` / `none`), when the page renders it.
 *
 * @param {string} id the element id
 * @param {string} display the CSS display value
 */
function setMyDisplay(id, display) {
    const el = document.getElementById(id);
    if (el) el.style.display = display;
}

// Attribute selector matching the leaf checkboxes belonging to a group select-all control —
// keyed on whichever catalog attribute the control carries (see groupKeyOf in inventory-common.js).
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
    /** @type {NodeListOf<HTMLInputElement>} */ (
        (root || document).querySelectorAll('.inventory-item-checkbox')
    ).forEach(function (cb) {
        const id = cb.getAttribute('data-id');
        cb.checked = !!id && bulkSelectedIds.has(id);
    });
}

function updateBulkCheckoutState() {
    const count = bulkSelectedIds.size;
    const btn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkCheckoutBtn')
    );
    const rebookBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkRebookBtn')
    );
    const countSpan = document.getElementById('bulkCheckoutCount');
    if (btn) btn.disabled = count === 0;
    // Massen-Umbuchen (REQ-INV-036) acts on the same selection, so it shares the enabled state.
    if (rebookBtn) rebookBtn.disabled = count === 0;
    if (countSpan) countSpan.textContent = count > 0 ? '(' + count + ')' : '';
    // Update group-select-all checkboxes — they mirror only their currently-loaded leaf boxes (a
    // collapsed group has none loaded, so it stays unchecked even under a view-wide select-all; the
    // authoritative total is the count span).
    /** @type {NodeListOf<HTMLInputElement>} */ (
        document.querySelectorAll('.group-select-all')
    ).forEach(function (groupCb) {
        const groupBoxes = /** @type {NodeListOf<HTMLInputElement>} */ (
            document.querySelectorAll(bulkGroupSelector(groupCb))
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
    const groupBoxes = /** @type {NodeListOf<HTMLInputElement>} */ (
        document.querySelectorAll(bulkGroupSelector(groupCb))
    );
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
    const btn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkSelectAllBtn')
    );
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
    const itemsView = myLager.lagerIsItemsView();
    const activeMaterials = collectMyChecked('matCheck');
    const activeGameItems = collectMyChecked('gameItemCheck');
    const activeLocations = collectMyChecked('locCheck');
    const activeJobOrders = collectMyChecked('jobOrderCheck');
    const activeMissions = collectMyChecked('missionCheck');
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    const minQuality = minQualitySelect ? minQualitySelect.value : '';
    const personalOnly = personalFlagChecked('personalOnly', 'itemPersonalOnly');
    const nonPersonalOnly = personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly');

    const url = new URL(window.location.origin + '/inventory/my/entry-ids');
    if (itemsView) url.searchParams.append('view', 'items');
    activeMaterials.forEach((m) => url.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => url.searchParams.append('gameItemIds', g));
    activeLocations.forEach((l) => url.searchParams.append('locationIds', l));
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
    const btn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkSelectAllBtn')
    );
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
    const msg = bulkI18n.modalMessage.replace('{0}', String(ids.length));
    const msgEl = document.getElementById('bulkCheckoutModalMessage');
    if (msgEl) msgEl.textContent = msg;
    setMyDisplay('bulkCheckoutModal', 'flex');
}

function closeBulkCheckoutModal() {
    setMyDisplay('bulkCheckoutModal', 'none');
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
        myLager.collectLeafOrderIds(itemId).forEach(function (orderId) {
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
                window.showFrontendSuccessToast(
                    bulkI18n.success.replace('{0}', String(ids.length)),
                );
            }
            // filterMyInventory() re-swaps the table and clears the bulk selection (set, count,
            // button + toggle label) at its start, so no manual reset is needed here.
            filterMyInventory();
            broadcastInventoryChanged();
            myLager.broadcastOrdersChanged(affectedOrderIds);
            myLager.broadcastBoardChanged();
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
    const checked = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="bulkRebookMode"]:checked')
    );
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
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('bulkRebookOrgUnitId')
    );
    if (!wrapper || !select) return;
    const mode = bulkRebookMode();
    select.innerHTML = '';
    wrapper.style.display = 'none';
    if (mode === 'PERSONALIZE') return;
    const userSelect = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookTargetUserId')
    );
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
    if (msgEl) msgEl.textContent = bulkRebookI18n.modalMessage.replace('{0}', String(ids.length));

    // Reset on every open so a previous run's mode and target cannot leak into the next one.
    const locationRadio = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="bulkRebookMode"][value="LOCATION"]')
    );
    if (locationRadio) locationRadio.checked = true;
    const mergeCheckbox = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookMergeStock')
    );
    if (mergeCheckbox) mergeCheckbox.checked = false;
    // The destination location opens EMPTY. Unlike the single-row modal there is no current location
    // to seed — the selection can span many — and an empty picker keeps the user from moving stock to
    // a location they never consciously chose.
    const tl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookTargetLocationId')
    );
    if (tl && tl.krtCombobox) tl.krtCombobox.setValue('');
    else if (tl) tl.value = '';
    // Default the destination owner to the caller: a bulk move usually only relocates own stock.
    const tu = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookTargetUserId')
    );
    const me = currentInventoryUserId();
    if (tu && me) tu.value = me;

    toggleBulkRebookMode();
    // `.modal` centres its content via display:flex; opening with inline `flex` (not `block`)
    // preserves that centring (matches the canonical .krtm-modal-open = flex, #1328).
    setMyDisplay('bulkRebookModal', 'flex');
}

function closeBulkRebookModal() {
    setMyDisplay('bulkRebookModal', 'none');
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
            ? bulkRebookI18n.successPartial
                  .replace('{0}', String(rebooked))
                  .replace('{1}', String(skipped))
            : bulkRebookI18n.success.replace('{0}', String(rebooked));
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

    /** @type {string | null} */
    let targetUserId = null;
    /** @type {string | null} */
    let targetLocationId = null;
    if (mode === 'LOCATION') {
        targetUserId = myFieldValue('bulkRebookTargetUserId') || null;
        targetLocationId = myFieldValue('bulkRebookTargetLocationId') || null;
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
    const orgSelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('bulkRebookOrgUnitId')
    );
    const orgUnitId =
        orgWrapper && window.getComputedStyle(orgWrapper).display !== 'none' && orgSelect
            ? orgSelect.value || null
            : null;
    const mergeCheckbox = /** @type {HTMLInputElement | null} */ (
        document.getElementById('bulkRebookMergeStock')
    );

    // Collect every earmarked order across the marked entries before the write: a LOCATION move
    // carries the order slices onto the moved rows, so those order collections must refresh too.
    const affectedOrderIds = [];
    ids.forEach(function (itemId) {
        myLager.collectLeafOrderIds(itemId).forEach(function (orderId) {
            if (affectedOrderIds.indexOf(orderId) < 0) affectedOrderIds.push(orderId);
        });
    });

    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('bulkRebookSubmitBtn')
    );
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
                myLager.broadcastOrdersChanged(affectedOrderIds);
                myLager.broadcastBoardChanged();
            },
        })
        .then(function () {
            bulkRebookInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
}

/**
 * Opens one multi-select filter dropdown and closes every other one; a second click closes it.
 *
 * @param {string | null} id the dropdown's element id
 */
function toggleMultiSelect(id) {
    const el = id ? document.getElementById(id) : null;
    if (!el) return;
    const isOpened = el.classList.contains('open');
    document.querySelectorAll('.multi-select-options').forEach(function (opt) {
        opt.classList.remove('open');
    });
    if (!isOpened) el.classList.add('open');
}

/**
 * The localized "all" / "n selected" labels a multi-select header carries as data attributes.
 *
 * @param {string | null} headerId the dropdown header's element id
 * @returns {{ allText: string, selectedTextStr: string }} the labels
 */
function getMsTranslations(headerId) {
    const header = headerId ? document.getElementById(headerId) : null;
    return {
        allText: header ? header.getAttribute('data-all') || 'Alle' : 'Alle',
        selectedTextStr: header ? header.getAttribute('data-selected') || 'gewählt' : 'gewählt',
    };
}

/**
 * A checkbox of this page by id (the select-all box of a multi-select family, a personal flag).
 *
 * @param {string | null} id the element id
 * @returns {HTMLInputElement | null} the checkbox, or null when absent
 */
function myCheckbox(id) {
    return id ? /** @type {HTMLInputElement | null} */ (document.getElementById(id)) : null;
}

/**
 * Applies a family's select-all box to every checkbox of the family.
 *
 * @param {string | null} allId the select-all checkbox id
 * @param {string} checkClass the family's checkbox class
 * @param {string | null} headerId the dropdown header id
 */
function toggleSelectAll(allId, checkClass, headerId) {
    const allBox = myCheckbox(allId);
    const isAllChecked = !!(allBox && allBox.checked);
    const checkboxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
        document.getElementsByClassName(checkClass)
    );
    for (let i = 0; i < checkboxes.length; i++) checkboxes[i].checked = isAllChecked;
    updateMsSelectedText(checkboxes, headerId);
}

/**
 * Re-syncs a family's select-all box and header text with its checkboxes.
 *
 * @param {string | null} allId the select-all checkbox id
 * @param {string} checkClass the family's checkbox class
 * @param {string | null} headerId the dropdown header id
 */
function updateSelectState(allId, checkClass, headerId) {
    const checkboxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
        document.getElementsByClassName(checkClass)
    );
    let allChecked = checkboxes.length > 0;
    for (let i = 0; i < checkboxes.length; i++) {
        if (!checkboxes[i].checked) {
            allChecked = false;
            break;
        }
    }
    const allEl = myCheckbox(allId);
    if (allEl) allEl.checked = allChecked;
    updateMsSelectedText(checkboxes, headerId);
}

/**
 * Writes a multi-select header's summary: "all" when none is checked, the one checked label, or
 * "n selected".
 *
 * @param {HTMLCollectionOf<HTMLInputElement>} checkboxes the family's checkboxes
 * @param {string | null} headerId the dropdown header id
 */
function updateMsSelectedText(checkboxes, headerId) {
    const translations = getMsTranslations(headerId);
    let count = 0;
    /** @type {string | null} */
    let firstChecked = null;
    for (let i = 0; i < checkboxes.length; i++) {
        if (checkboxes[i].checked) {
            count++;
            const label = /** @type {HTMLElement | null} */ (checkboxes[i].previousElementSibling);
            if (!firstChecked && label) {
                firstChecked = label.innerText;
            }
        }
    }
    const header = headerId ? document.getElementById(headerId) : null;
    const headerSpan = /** @type {HTMLElement | null} */ (
        header ? header.querySelector('.selected-text') : null
    );
    if (!headerSpan) return;
    if (count === 0) headerSpan.innerText = translations.allText;
    else if (count === 1) headerSpan.innerText = firstChecked || '';
    else headerSpan.innerText = count + ' ' + translations.selectedTextStr;
}

document.addEventListener('click', function (e) {
    const target = /** @type {Element} */ (e.target);
    if (!target.closest('.multi-select-container')) {
        document.querySelectorAll('.multi-select-options').forEach(function (opt) {
            if (opt.classList.contains('open')) opt.classList.remove('open');
        });
    }
});

/**
 * The values of a family's checked boxes.
 *
 * @param {string} className the family's checkbox class
 * @returns {string[]} the checked values
 */
function collectMyChecked(className) {
    const boxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
        document.getElementsByClassName(className)
    );
    /** @type {string[]} */
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
/**
 * Clears the counterpart of a checked personal-flag filter and re-runs the filter.
 *
 * @param {HTMLElement} el the toggled checkbox
 */
function togglePersonalFilter(el) {
    const box = /** @type {HTMLInputElement} */ (el);
    if (box && box.checked) {
        /** @type {Record<string, string>} */
        const counterparts = {
            personalOnly: 'nonPersonalOnly',
            nonPersonalOnly: 'personalOnly',
            itemPersonalOnly: 'itemNonPersonalOnly',
            itemNonPersonalOnly: 'itemPersonalOnly',
        };
        const other = myCheckbox(counterparts[box.id]);
        if (other) other.checked = false;
    }
    filterMyInventory();
}

// Reads the personal-flag checkbox shared by both filter forms: the material form renders
// personalOnly/nonPersonalOnly, the items view its item-prefixed pair — only one exists per view.
/**
 * Whether the personal-flag checkbox of the active view is checked.
 *
 * @param {string} materialId the material view's checkbox id
 * @param {string} itemId the items view's checkbox id
 * @returns {boolean} the flag
 */
function personalFlagChecked(materialId, itemId) {
    const el = myCheckbox(materialId) || myCheckbox(itemId);
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
    'locationIds',
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
    const boxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
        document.getElementsByClassName(className)
    );
    /** @type {string[]} */
    const picked = [];
    for (let i = 0; i < boxes.length; i++) {
        if (boxes[i].checked) picked.push(boxes[i].value);
    }
    return picked.length === 0 || picked.length === boxes.length ? null : picked;
}

// Snapshot of the ACTIVE view's widget state — only that view's filter form exists in the DOM
// (REQ-INV-030), so the other view's slot is never touched by a persist.
function snapshotMyInventoryFilters() {
    if (myLager.lagerIsItemsView()) {
        return {
            gameItems: myInventoryFilterSelection('gameItemCheck'),
            locations: myInventoryFilterSelection('locCheck'),
            jobOrders: myInventoryFilterSelection('jobOrderCheck'),
            personalOnly: personalFlagChecked('personalOnly', 'itemPersonalOnly'),
            nonPersonalOnly: personalFlagChecked('nonPersonalOnly', 'itemNonPersonalOnly'),
        };
    }
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    return {
        materials: myInventoryFilterSelection('matCheck'),
        locations: myInventoryFilterSelection('locCheck'),
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
    stored[myLager.lagerIsItemsView() ? 'items' : 'material'] = snapshotMyInventoryFilters();
    writeMyInventoryFilterPref(stored);
}

// Applies a saved multi-select subset to its checkbox family. Saved values whose checkbox no
// longer exists are dropped silently; an entirely-stale subset leaves every box unchecked — this
// page's all-unchecked "no filter" default. The select-all box and the dropdown header text are
// re-synced via updateSelectState. Returns whether any box ended up checked (i.e. the widget now
// differs from the bare-URL rendered default).
function applyMySavedSelection(saved, checkClass, allId, headerId) {
    if (!Array.isArray(saved) || saved.length === 0) return false;
    const boxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
        document.getElementsByClassName(checkClass)
    );
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
    const saved = stored ? stored[myLager.lagerIsItemsView() ? 'items' : 'material'] : null;
    if (!saved || typeof saved !== 'object') return false;
    let changed = false;
    // [saved subset, checkbox class, select-all id, header id] per multi-select family of the
    // active view (the jobOrder family renders item-prefixed all/header ids in the items view).
    let families;
    if (myLager.lagerIsItemsView()) {
        families = [
            [saved.gameItems, 'gameItemCheck', 'gameItemAll', 'gameItemHeader'],
            [saved.locations, 'locCheck', 'itemLocAll', 'itemLocationHeader'],
            [saved.jobOrders, 'jobOrderCheck', 'itemJobOrderAll', 'itemJobOrderHeader'],
        ];
    } else {
        families = [
            [saved.materials, 'matCheck', 'matAll', 'materialHeader'],
            [saved.locations, 'locCheck', 'locAll', 'locationHeader'],
            [saved.jobOrders, 'jobOrderCheck', 'jobOrderAll', 'jobOrderHeader'],
            [saved.missions, 'missionCheck', 'missionAll', 'missionHeader'],
        ];
        const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
            document.getElementById('minQuality')
        );
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
        /** @type {HTMLInputElement | null} */ (document.getElementById('personalOnly')) ||
        /** @type {HTMLInputElement | null} */ (document.getElementById('itemPersonalOnly'));
    const nonPersonalBox =
        /** @type {HTMLInputElement | null} */ (document.getElementById('nonPersonalOnly')) ||
        /** @type {HTMLInputElement | null} */ (document.getElementById('itemNonPersonalOnly'));
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
// pushes the bulk bar and the table down. The panel collapses out of the flow. The collapse
// preference is per browser and owned by the shared krt-filter-panel.js (localStorage key
// `krt.filterPanel.inventory-my`, one per page rather than per view, so switching Material <->
// Items never silently re-opens a panel the user closed); the filter values themselves stay in
// this page's own filter object (persistMyInventoryFilters).

// Number of dimensions currently narrowing the table. Derived from the very snapshot the
// persistence layer stores, so a filter dimension added there is counted here automatically
// instead of silently missing from the badge. A multi-select is null in that snapshot when zero
// OR all of its boxes are ticked — both mean "no filter" on this page — so "all ticked"
// correctly counts as nothing.
function countActiveMyInventoryFilters() {
    const snapshot = snapshotMyInventoryFilters();
    let active = 0;
    ['materials', 'gameItems', 'locations', 'jobOrders', 'missions'].forEach(function (dimension) {
        if (Array.isArray(snapshot[dimension]) && snapshot[dimension].length > 0) active++;
    });
    if (typeof snapshot.minQuality === 'string' && snapshot.minQuality !== '') active++;
    if (snapshot.personalOnly === true) active++;
    if (snapshot.nonPersonalOnly === true) active++;
    return active;
}

// The collapse itself lives in krt-filter-panel.js (REQ-FE-021). This page supplies only
// the COUNT, because its dimensions are not readable from the panel's own controls, and it
// registers that counter inside DOMContentLoaded rather than here: this file is a
// non-deferred script at the end of the body, so it executes BEFORE the deferred
// krt-filter-panel.js and window.krtFilterPanel does not exist yet at this point. A
// top-level registration would be silently skipped and the panel would fall back to the
// generic scan, which counts the wrong things here.

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
    if (window.krtFilterPanel) window.krtFilterPanel.refresh('myFilterPanel');
    const itemsView = myLager.lagerIsItemsView();
    const activeMaterials = collectMyChecked('matCheck');
    const activeGameItems = collectMyChecked('gameItemCheck');
    const activeLocations = collectMyChecked('locCheck');
    const activeJobOrders = collectMyChecked('jobOrderCheck');
    const activeMissions = collectMyChecked('missionCheck');
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
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
    activeLocations.forEach((l) => url.searchParams.append('locationIds', l));
    if (minQuality) url.searchParams.append('minQuality', minQuality);
    activeJobOrders.forEach((j) => url.searchParams.append('jobOrderIds', j));
    activeMissions.forEach((m) => url.searchParams.append('missionIds', m));
    if (personalOnly) url.searchParams.append('personalOnly', 'true');
    if (nonPersonalOnly) url.searchParams.append('nonPersonalOnly', 'true');

    const visibleUrl = new URL(window.location.origin + '/inventory/my');
    if (itemsView) visibleUrl.searchParams.append('view', 'items');
    activeMaterials.forEach((m) => visibleUrl.searchParams.append('materialIds', m));
    activeGameItems.forEach((g) => visibleUrl.searchParams.append('gameItemIds', g));
    activeLocations.forEach((l) => visibleUrl.searchParams.append('locationIds', l));
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
            window.krtFetch.replaceWithTrustedHtml(container, html);
            // A fragment swap does not re-fire DOMContentLoaded, so re-apply the persisted tree
            // expansion (REQ-INV-002) — otherwise a filter change or a modal write collapses every
            // row the user had opened.
            myLager.restoreExpandedTree();
        })
        .catch((error) => {
            console.error('Error fetching filtered personal inventory:', error);
            container.style.opacity = '1.0';
            container.style.pointerEvents = 'auto';
        });
}

function resetMyInventoryFilter() {
    ['matCheck', 'gameItemCheck', 'locCheck', 'jobOrderCheck', 'missionCheck'].forEach(
        function (cls) {
            const boxes = /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
                document.getElementsByClassName(cls)
            );
            for (let i = 0; i < boxes.length; i++) boxes[i].checked = false;
        },
    );
    [
        'matAll',
        'gameItemAll',
        'locAll',
        'itemLocAll',
        'jobOrderAll',
        'itemJobOrderAll',
        'missionAll',
    ].forEach(function (id) {
        const el = myCheckbox(id);
        if (el) el.checked = false;
    });
    const minQualitySelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('minQuality')
    );
    if (minQualitySelect) minQualitySelect.value = '';
    ['personalOnly', 'nonPersonalOnly', 'itemPersonalOnly', 'itemNonPersonalOnly'].forEach(
        function (id) {
            const el = myCheckbox(id);
            if (el) el.checked = false;
        },
    );
    if (document.getElementById('materialHeader'))
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    if (document.getElementById('gameItemHeader'))
        updateSelectState('gameItemAll', 'gameItemCheck', 'gameItemHeader');
    if (document.getElementById('locationHeader'))
        updateSelectState('locAll', 'locCheck', 'locationHeader');
    if (document.getElementById('itemLocationHeader'))
        updateSelectState('itemLocAll', 'locCheck', 'itemLocationHeader');
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

// #1740 (REQ-INV-039): the allocation popover's order options carry what each order still NEEDS, and
// that figure also moves when nothing here changed — a handover recorded on the order, an edited
// line, an order opened or closed. Those writes poke the global `orders` room, never `inventory`,
// so this page joins it as a second receiver.
//
// It reuses that room's existing `demand` key rather than adding one: this page renders a SUBSET of
// what the room already invalidates (the material-collection precedent), and `demand` is published
// by exactly the writers that move the figure. Re-pulling the owned table is the whole refresh — a
// collapsed stack comes back data-stack-loaded=false, so the freshly labelled options arrive with
// the next expand.
const INVENTORY_MY_ORDER_SECTIONS = {
    demand: { container: '#myInventoryTableContainer' },
};

if (
    window.krtLiveSync &&
    typeof window.krtLiveSync.createReceiver === 'function' &&
    document.getElementById('myInventoryTableContainer')
) {
    window.krtLiveSync.createReceiver({
        topic: 'orders',
        sections: INVENTORY_MY_ORDER_SECTIONS,
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
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('matCheck')
        ).length > 0
    ) {
        updateSelectState('matAll', 'matCheck', 'materialHeader');
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('gameItemCheck')
        ).length > 0
    ) {
        updateSelectState('gameItemAll', 'gameItemCheck', 'gameItemHeader');
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('locCheck')
        ).length > 0
    ) {
        // Same shared-class / per-view-ids shape as jobOrderCheck below: the location filter
        // renders in both views, with item-prefixed ids in the items view.
        if (document.getElementById('itemLocationHeader')) {
            updateSelectState('itemLocAll', 'locCheck', 'itemLocationHeader');
        } else {
            updateSelectState('locAll', 'locCheck', 'locationHeader');
        }
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('jobOrderCheck')
        ).length > 0
    ) {
        // The material and the items view render different header/all ids for the shared
        // jobOrderCheck class (unique ids in the template source); exactly one pair exists.
        if (document.getElementById('itemJobOrderHeader')) {
            updateSelectState('itemJobOrderAll', 'jobOrderCheck', 'itemJobOrderHeader');
        } else {
            updateSelectState('jobOrderAll', 'jobOrderCheck', 'jobOrderHeader');
        }
    }
    if (
        /** @type {HTMLCollectionOf<HTMLInputElement>} */ (
            document.getElementsByClassName('missionCheck')
        ).length > 0
    ) {
        updateSelectState('missionAll', 'missionCheck', 'missionHeader');
    }
    // After the restore, so the count reflects the widgets the user will actually see rather than
    // the bare server-rendered ones. The collapse state itself is krt-filter-panel.js's and was
    // already applied; only the number needs restating.
    if (window.krtFilterPanel) {
        window.krtFilterPanel.registerCounter('myFilterPanel', countActiveMyInventoryFilters);
        window.krtFilterPanel.refresh('myFilterPanel');
    }
    if (filtersRestored) filterMyInventory();
});

// ===================== Umbuchen (rebooking) modal — REQ-INV-007 =====================
// The item the open Umbuchen modal targets, plus an in-flight guard mirroring the book-out one.
let umbuchenItemId = null;
// #1328: the row's current owning org-unit id, used to preset both Umbuchen org-unit pickers so a
// submit that does not touch the picker keeps the stock in its current unit (null = ownerless row).
// The personal picker reads it here; the transfer picker is shared and gets it via myLager.
let umbuchenCurrentOwningOrgUnitId = null;
let umbuchenInFlight = false;

/**
 * PERSONAL de-personalize picker: populate the org-unit picker with the row owner's memberships
 * across all four org-unit kinds (Staffel + SK + Bereich + OL), preset to the row's current owning
 * org unit (or the owner's primary unit when the row has none). Shown whenever the owner has at
 * least one membership; hidden only for a membershipless owner (the shared row is then ownerless).
 */
function refreshUmbuchenPersonalOrgUnitPicker(ownerId) {
    const wrapper = document.getElementById('umbuchenPersonalOrgUnitWrapper');
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('umbuchenPersonalOrgUnitId')
    );
    if (!wrapper || !select) return;
    select.innerHTML = '';
    wrapper.style.display = 'none';
    if (!ownerId) return;
    // #1328: ?allKinds=true so a Bereich/OL-member owner can de-personalize into their Bereich/OL
    // pool, not only Staffel/SK (the endpoint default). Mirrors the bank counterparty picker
    // (REQ-BANK-044). Fetched via the frontend's /users/{id}/memberships proxy — see the
    // transfer picker in inventory-common.js.
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
    const modeInput = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="umbuchenMode"]:checked')
    );
    const mode = modeInput ? modeInput.value : 'LOCATION';
    const transferFields = document.getElementById('umbuchenTransferFields');
    const personalFields = document.getElementById('umbuchenPersonalFields');
    const targetUser = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetUserId')
    );
    const targetLocation = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetLocationId')
    );
    if (!transferFields || !personalFields || !targetUser || !targetLocation) return;
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
    myLager.setUmbuchenCurrentOwningOrgUnit(umbuchenCurrentOwningOrgUnitId);
    const isScu = quantityType !== 'PIECE';
    const amountEl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenAmount')
    );
    const targetEl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetAmount')
    );
    if (!amountEl || !targetEl) return;
    amountEl.setAttribute('step', isScu ? '0.001' : '1');
    targetEl.setAttribute('step', isScu ? '0.001' : '1');
    const targetHint = document.getElementById('umbuchen-target-scu-hint');
    const amountHint = document.getElementById('umbuchen-amount-scu-hint');
    if (targetHint) targetHint.classList.toggle('krtm-hidden', !isScu);
    if (amountHint) amountHint.classList.toggle('krtm-hidden', !isScu);

    // REQ-INV-026: the per-action stock-merge opt-in is offered only for an SCU material (a PIECE
    // rebooking/transfer always merges server-side). Reset it on every open.
    const mergeRow = document.getElementById('umbuchenMergeRow');
    const mergeCheckbox = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenMergeStock')
    );
    if (mergeCheckbox) mergeCheckbox.checked = false;
    if (mergeRow) mergeRow.classList.toggle('krtm-hidden', !isScu);

    amountEl.value = amount ?? '';
    amountEl.max = amount ?? '';
    targetEl.value = '0';
    setMyFieldValue('umbuchenMaxAmount', amount);
    setMyFieldValue('umbuchenVersion', version);
    setMyFieldValue('umbuchenSourcePersonal', personal ? 'true' : 'false');
    const amountOf = document.getElementById('umbuchenAmountOfText');
    if (amountOf)
        amountOf.textContent = (amountOf.getAttribute('data-template') ?? '').replace(
            '{0}',
            amount ?? '',
        );

    // LOCATION (transfer) defaults: pre-select the row's own user/location. The location picker
    // is a REMOTE searchable combobox (remote-locations, REQ-FE-016): the catalog is fetched per
    // query, so the row's location is outside the loaded set — setValue() must carry the label
    // with the id (a bare id would clear the selection, a bare .value write the visible text).
    const tu = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetUserId')
    );
    const tl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenTargetLocationId')
    );
    if (tu) tu.value = userId;
    if (tl && tl.krtCombobox) {
        tl.krtCombobox.setValue(locationId, locationName);
    } else if (tl) {
        tl.value = locationId;
    }
    myLager.refreshUmbuchenTransferOrgUnitPicker();

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

    const locationMode = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="umbuchenMode"][value="LOCATION"]')
    );
    if (locationMode) locationMode.checked = true;
    toggleUmbuchenMode();
    // `.modal` centres its content via `display:flex`; opening with inline `flex` (not `block`)
    // preserves that centring — inline `block` would override the stylesheet flex and pin the
    // dialog to the top of the viewport (matches the canonical .krtm-modal-open = flex, #1328).
    setMyDisplay('umbuchenModal', 'flex');
    // Variante C (REQ-INV-027): build the transfer "Herkunft" picker (the moved row inherits the
    // reduced tags). It lives inside the LOCATION-only transfer fields, so it self-hides in PERSONAL
    // mode; populate after the modal is shown so its initial validity gates the submit button.
    if (window.krtHerkunft) {
        window.krtHerkunft.populate('umbuchen', id);
    }
}

function submitUmbuchen(event) {
    // scu-decimal-input.js canonicalises + validates the amount fields in the capture phase
    // first; respect a block it already issued, then prevent the native (action-less) submit.
    if (event && event.defaultPrevented) return;
    if (event) event.preventDefault();
    if (umbuchenInFlight || !window.krtFetch || !umbuchenItemId) return;
    const modeInput = /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="umbuchenMode"]:checked')
    );
    const mode = modeInput ? modeInput.value : 'LOCATION';
    const amountValue = myFieldValue('umbuchenAmount');
    const amount = window.krtScuInput
        ? window.krtScuInput.parse(amountValue)
        : parseFloat(amountValue);
    const version = parseInt(myFieldValue('umbuchenVersion'), 10);
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('umbuchenSubmitBtn')
    );
    // REQ-INV-026: per-action stock-merge opt-in (only rendered for SCU; PIECE always merges).
    const mergeCheckbox = /** @type {HTMLInputElement | null} */ (
        document.getElementById('umbuchenMergeStock')
    );
    const mergeStock = !!(mergeCheckbox && mergeCheckbox.checked);

    let url, payload;
    if (mode === 'PERSONAL') {
        url = '/inventory/' + umbuchenItemId + '/personal-rebook';
        const orgWrapper = document.getElementById('umbuchenPersonalOrgUnitWrapper');
        const orgSelect = /** @type {HTMLSelectElement | null} */ (
            document.getElementById('umbuchenPersonalOrgUnitId')
        );
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
            targetUserId: myFieldValue('umbuchenTargetUserId') || null,
            targetLocationId: myFieldValue('umbuchenTargetLocationId') || null,
            targetOwningOrgUnitId: myFieldValue('umbuchenTargetOwningOrgUnitId') || null,
            version: version,
            mergeStock: mergeStock,
            jobOrderReductions: reductions.jobOrderReductions,
            missionReductions: reductions.missionReductions,
        };
    }

    // Read the earmarked orders before the write. Only a LOCATION transfer touches order slices; the
    // PERSONAL rebook is refused on an allocated row, so it only clamps offers (board), not orders.
    const affectedOrderIds = mode === 'PERSONAL' ? [] : myLager.collectLeafOrderIds(umbuchenItemId);
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
                myLager.closeUmbuchenModal();
                filterMyInventory();
                broadcastInventoryChanged();
                myLager.broadcastOrdersChanged(affectedOrderIds);
                myLager.broadcastBoardChanged();
            },
        })
        .then(function () {
            umbuchenInFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        });
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
    window.krtEvents.on('click', 'inv-my-open-bulk', openBulkCheckoutModal);
    window.krtEvents.on('change', 'inv-my-toggle-group-cb', function (el) {
        toggleGroupCheckboxes(el);
    });
    window.krtEvents.on('change', 'inv-my-update-bulk-state', function (el) {
        onEntryCheckboxToggle(el);
    });
    window.krtEvents.on('click', 'inv-my-select-all', toggleSelectAllInView);
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
    window.krtEvents.on('change', 'inv-my-toggle-umbuchen-mode', toggleUmbuchenMode);
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

// The Umbuchen form is a stable top-level element (outside the swapped table container); its submit
// listener (bound once) survives the grouped-table re-swaps and runs after scu-decimal-input.js
// canonicalises/validates the amount fields in the capture phase.
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

// The tree, allocation-chip, book-out and shared Umbuchen handlers (inventory-common.js).
myLager.bind();
