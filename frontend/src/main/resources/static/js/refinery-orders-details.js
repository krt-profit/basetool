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
 * Refinery-order detail/edit page module (/refinery-orders/{id}), extracted verbatim from the
 * former inline script of refinery-orders-details.html (ADR-0069, follow-up to #924).
 *
 * Owns the edit form's dynamic behaviour: per-row SCU calculation, output-material display and
 * yield-badge refresh (via the shared refinery-yield-badge.js module), add/remove/renumber of
 * material rows, the store-dialog receiver -> owning-org-unit picker rebuild (#596), the
 * refining-method rating readout, the live ends-at and profit previews, and the #575 in-place
 * update/store/cancel submits through window.krtFetch.submitForm (navigate to the JSON targetUrl
 * on success, inline toast + stay on failure; the classic form-POST is the no-JS fallback).
 *
 * The localized MSG_* and label strings, the RATING_LEVELS / SPEED_LEVELS dicts, the server-injected
 * MATERIAL_YIELD_BONUSES map and STORE_INHERITED_ORG_UNIT_ID / ROUNDING_MODE values are defined
 * by the inline Thymeleaf bootstrap block of refinery-orders-details.html, which executes
 * immediately before this classic script.
 */

/* global MATERIAL_YIELD_BONUSES, MATERIAL_YIELD_BONUS_HELP, MATERIAL_ENTRY_TITLE_LABEL, MATERIAL_REMOVE_LABEL, MSG_SAVING, MSG_REFINERY_UPDATE_FAILED, MSG_REFINERY_STORE_FAILED, MSG_REFINERY_CANCEL_FAILED, MSG_CANCEL_CONFIRM, MSG_CANCEL_TITLE, MSG_CANCEL_DISMISS, STORE_INHERITED_ORG_UNIT_ID, STORE_ORG_UNIT_PLACEHOLDER, RATING_LEVELS, SPEED_LEVELS, showFrontendErrorToast */

// Initialize the shared yield-badge module with the server-rendered map for this order's
// refinery. Subsequent location/material changes update the badge via the module's helpers
// (refinery-yield-badge.js) without a page reload.
window.krtRefineryYield.init(MATERIAL_YIELD_BONUSES, MATERIAL_YIELD_BONUS_HELP);

// In-place writes (#575): the refinery update/store/cancel navigate away on success (to the
// list), so intercept the forms, POST FormData (the browser serializes the dynamic goods/store
// editor) with X-Requested-With + krtCsrf, and navigate to the JSON targetUrl. On a
// validation/backend failure the page STAYS with an inline toast instead of the POST->redirect
// reflash. The classic form-POST handlers are the no-JS fallback (krtCsrf absent).
// Migrated to krtFetch.submitForm (S10, REQ-FE-009): the shared foundation owns the CSRF header
// (no Content-Type so the browser sets the multipart boundary), the bare-403 refresh-and-retry,
// X-Reauthenticate and the double-submit guard (submitter). This helper keeps only its page
// behaviour: navigate away to the JSON targetUrl on success (toast:false — an edit/store/cancel
// leaves the page) with resetUnsavedChanges, and the onFailure store-button restore + inline
// error toast. The classic form-POST handlers stay the no-JS fallback (krtFetch absent). The
// store-button restore must fire on BOTH failure paths: an !ok response (onError) AND a
// transport-layer network failure (onNetworkError, which the response-side onError never sees).
// The single `fail` handler covers both and returns true so the foundation shows no second toast.
function _submitRefinery(form, errorMessage, onFailure, submitter) {
    if (!window.krtFetch) {
        form.submit();
        return;
    }
    function fail() {
        if (onFailure) onFailure();
        showFrontendErrorToast(errorMessage);
        return true;
    }
    window.krtFetch.submitForm({
        form: form,
        submitter: submitter,
        toast: false,
        errorMessage: errorMessage,
        onError: fail,
        onNetworkError: fail,
        onSuccess: function (body) {
            if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
            if (body && body.targetUrl) window.location.assign(body.targetUrl);
            else window.location.reload();
        },
    });
}

function calcScu(index) {
    const unitInput = document.getElementById('outputQuantity_' + index);
    const scuInput = document.getElementById('outputQuantityScu_' + index);
    if (unitInput && scuInput) {
        let valStr = unitInput.value.replace(/\./g, '').replace(',', '.');
        const val = parseInt(valStr);
        if (!isNaN(val)) {
            const scu = val / 100.0;
            scuInput.value = scu.toLocaleString('de-DE', {
                minimumFractionDigits: 3,
                maximumFractionDigits: 3,
                useGrouping: false,
            });
        } else {
            scuInput.value = '';
        }
    }
}

function closeStoreModal() {
    if (typeof window.resetUnsavedChanges === 'function') {
        window.resetUnsavedChanges();
    }
    document.getElementById('storeModal').style.display = 'none';
}

function openStoreModal() {
    document.getElementById('storeModal').style.display = 'flex';
}

function duplicateStoreItem(btn) {
    const container = document.getElementById('storeItemsContainer');
    const blockToCopy = btn.closest('.store-item-block');
    const newBlock = blockToCopy.cloneNode(true);

    const allBlocks = container.querySelectorAll('.store-item-block');
    const newIndex = allBlocks.length;

    const nameRegex = /items\[\d+\]/g;

    newBlock.querySelectorAll('input, select, textarea').forEach((el) => {
        if (el.name) {
            el.name = el.name.replace(nameRegex, `items[${newIndex}]`);
        }
        if (el.id) {
            el.id = el.id.replace(/_\d+$/, `_${newIndex}`);
        }
        if (el.id && el.id.startsWith('storeAmount_')) {
            el.value = '';
        }
        if (el.id && el.id.startsWith('storeNote_')) {
            el.value = '';
        }
    });

    newBlock.querySelectorAll('label').forEach((el) => {
        if (el.htmlFor) {
            el.htmlFor = el.htmlFor.replace(/_\d+$/, `_${newIndex}`);
        }
    });

    // The receiver picker is a server-side searchable combobox (remote-users, #1193); cloning its
    // enhanced DOM yields a dead control. Carry over the source row's chosen receiver, then swap the
    // dead clone for a fresh <select data-krt-combobox> from the pristine template (re-enhanced
    // below). Resolve the control by its preserved id (works whether the source was enhanced or
    // still a plain select). Because the template no longer preloads the roster, the source's chosen
    // receiver is carried over by SEEDING one option (value + the visible committed label) so the
    // fresh combobox shows the same receiver instead of resetting to empty.
    const tpl = document.getElementById('store-user-select-tpl');
    const sourceUser = blockToCopy.querySelector('[id^="storeUser_"]');
    const sourceUserValue = sourceUser ? sourceUser.value : '';
    const sourceUserInput = blockToCopy.querySelector('.krt-combobox__input');
    const sourceUserLabel = sourceUserInput ? sourceUserInput.value : '';
    const clonedUser = newBlock.querySelector('[id^="storeUser_"]');
    if (tpl && clonedUser) {
        const freshUser = tpl.content.firstElementChild.cloneNode(true);
        freshUser.id = `storeUser_${newIndex}`;
        freshUser.name = `items[${newIndex}].userId`;
        if (sourceUserValue) {
            const seed = document.createElement('option');
            seed.value = sourceUserValue;
            seed.textContent = sourceUserLabel || sourceUserValue;
            seed.selected = true;
            freshUser.appendChild(seed);
        }
        freshUser.value = sourceUserValue;
        (clonedUser.closest('.krt-combobox') || clonedUser).replaceWith(freshUser);
    }

    const btnInNewBlock = newBlock.querySelector('button');
    if (btnInNewBlock) {
        btnInNewBlock.textContent = '-';
        btnInNewBlock.onclick = function () {
            newBlock.remove();
            reindexStoreItems();
        };
        btnInNewBlock.setAttribute('title', 'Aufteilung entfernen');
    }

    blockToCopy.after(newBlock);
    reindexStoreItems();
    // Re-derive the split row's earmark lock from its own checkbox (REQ-INV-035): cloneNode copies
    // the server-rendered `checked` ATTRIBUTE, not the user's live toggle, while `disabled` (a
    // reflected property) does come across — so without this the clone can end up with a locked
    // job-order picker and an unticked personal box.
    syncStorePersonalJobOrder(newBlock);
    // Upgrade the freshly inserted receiver <select> into a searchable combobox (idempotent;
    // skips controls already enhanced). reindexStoreItems() has stamped its final id/name.
    if (window.krtEnhanceComboboxes) {
        window.krtEnhanceComboboxes(newBlock);
    }
}

function reindexStoreItems() {
    const container = document.getElementById('storeItemsContainer');
    const allBlocks = container.querySelectorAll('.store-item-block');
    allBlocks.forEach((block, index) => {
        const nameRegex = /items\[\d+\]/g;
        block.querySelectorAll('input, select, textarea').forEach((el) => {
            if (el.name) {
                el.name = el.name.replace(nameRegex, `items[${index}]`);
            }
            if (el.id) {
                el.id = el.id.replace(/_\d+$/, `_${index}`);
            }
        });
        block.querySelectorAll('label').forEach((el) => {
            if (el.htmlFor) {
                el.htmlFor = el.htmlFor.replace(/_\d+$/, `_${index}`);
            }
        });
    });
}

// REQ-INV-035: personal stock never carries an earmark, so while a store row's personal-entry box
// is ticked its job-order picker is disabled AND cleared (a disabled select submits nothing, so the
// row reaches the backend without a job order); unticking restores the choice. Mirrors
// syncPersonalAllocations on the Einbuchen page and _prodSyncPersonalAllocate in the
// item-production modal.
function syncStorePersonalJobOrder(block) {
    if (!block) return;
    const personalCb = block.querySelector('[id^="storePersonal_"]');
    const jobOrderSelect = block.querySelector('[id^="storeJobOrder_"]');
    if (!jobOrderSelect) return;
    const personal = !!(personalCb && personalCb.checked);
    jobOrderSelect.disabled = personal;
    if (personal) {
        jobOrderSelect.value = '';
    }
}

// #596: rebuild a store row's owning-org-unit <select> from the picked receiver's OrgUnit
// memberships. Re-selects the order's inherited default when the receiver belongs to it, else
// keeps a prior still-valid pick, else leaves the placeholder selected to force a choice.
function rebuildOrgUnitOptions(selectEl, options) {
    const previous = selectEl.value;
    while (selectEl.firstChild) {
        selectEl.removeChild(selectEl.firstChild);
    }
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = STORE_ORG_UNIT_PLACEHOLDER;
    selectEl.appendChild(placeholder);
    let hasInherited = false;
    let hasPrevious = false;
    options.forEach((o) => {
        const opt = document.createElement('option');
        opt.value = o.orgUnitId;
        opt.textContent = o.orgUnitName;
        selectEl.appendChild(opt);
        if (o.orgUnitId === STORE_INHERITED_ORG_UNIT_ID) hasInherited = true;
        if (previous && o.orgUnitId === previous) hasPrevious = true;
    });
    if (hasInherited) selectEl.value = STORE_INHERITED_ORG_UNIT_ID;
    else if (hasPrevious) selectEl.value = previous;
    else selectEl.value = '';
}

// When the receiving member of a store row changes, refresh that row's owning-org-unit picker
// from the new member's memberships (proxied via the frontend AJAX endpoint); when its personal
// marker is toggled, re-sync that row's job-order picker (REQ-INV-035). Delegated on the items
// container so split (duplicated) rows are covered too. The initial pass covers a flashed-back
// form that re-renders with the box already ticked.
(function () {
    const storeItemsContainer = document.getElementById('storeItemsContainer');
    if (!storeItemsContainer) return;
    storeItemsContainer.querySelectorAll('.store-item-block').forEach(syncStorePersonalJobOrder);
    storeItemsContainer.addEventListener('change', (e) => {
        const sel = e.target;
        if (!sel || !sel.id) return;
        if (sel.id.startsWith('storePersonal_')) {
            syncStorePersonalJobOrder(sel.closest('.store-item-block'));
            return;
        }
        if (!sel.id.startsWith('storeUser_')) return;
        const index = sel.id.substring('storeUser_'.length);
        const orgSelect = document.getElementById('storeOrgUnit_' + index);
        if (!orgSelect || !sel.value) return;
        fetch('/refinery-orders/users/' + encodeURIComponent(sel.value) + '/org-units', {
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
        })
            .then((r) => (r.ok ? r.json() : []))
            .then((opts) => rebuildOrgUnitOptions(orgSelect, Array.isArray(opts) ? opts : []))
            .catch(() => rebuildOrgUnitOptions(orgSelect, []));
    });
})();

function updateOutputMaterial(selectElement) {
    const entryBlock = selectElement.closest('.material-entry');
    const outputDisplay = entryBlock.querySelector('span[id^="outputMaterialDisplay_"]');

    // The input-material picker is a server-side searchable combobox (REQ-FE-016): the selected
    // option's data-refined-name is mirrored onto the hidden input carrying the control's id. The
    // raw <select> fallback covers only the not-yet-enhanced pre-enhancement state, whose sole
    // non-placeholder option is the server-rendered seed (the remote picker preloads no catalog).
    let refinedName = selectElement.dataset.refinedName || '';
    if (!refinedName && selectElement.tagName === 'SELECT') {
        const selectedOption = selectElement.options[selectElement.selectedIndex];
        refinedName = (selectedOption && selectedOption.getAttribute('data-refined-name')) || '';
    }
    if (refinedName) {
        outputDisplay.innerText = refinedName;
        outputDisplay.style.opacity = '1';
    } else {
        outputDisplay.innerText = '-';
        outputDisplay.style.opacity = '0.7';
    }

    // Refresh the yield badge against the in-memory materialId -> bonus map (shared module
    // state). The map is loaded server-side on first paint for the order's current location
    // and refreshed via AJAX whenever the user picks a different refinery in the location
    // dropdown (see the rod-location-change handler at the bottom of this script).
    window.krtRefineryYield.refreshFor(selectElement);
}

document.addEventListener('DOMContentLoaded', function () {
    // Attribute-only selector: matches the raw <select> before enhancement and the hidden
    // <input> carrying the id after it (REQ-FE-016).
    const inputSelects = document.querySelectorAll('[id^="inputMaterialId_"]');
    inputSelects.forEach((select) => {
        if (select.value) {
            updateOutputMaterial(select);
        }
    });
    updateMethodRatings();
    const entries = document.querySelectorAll('.material-entry');
    entries.forEach((_, index) => {
        calcScu(index);
    });
    // Resync every row's badge against the shared module's map. Server-side render uses the
    // same map so this is normally a no-op, but it guarantees the post-save reload state
    // matches the map even if a future refactor breaks the parity between Thymeleaf and JS.
    window.krtRefineryYield.refreshAll();
});

function setStartedAtNow() {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    const hours = String(now.getHours()).padStart(2, '0');
    const minutes = String(now.getMinutes()).padStart(2, '0');

    const dateInput = document.querySelector('.datetime-split-group .date-part');
    const timeInput = document.querySelector('.datetime-split-group .time-part');

    if (dateInput && timeInput) {
        dateInput.value = `${year}-${month}-${day}`;
        timeInput.value = `${hours}:${minutes}`;
        dateInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
    updateEndsAt();
}

function addMaterialRow() {
    const container = document.getElementById('materials-container');
    const entries = container.querySelectorAll('.material-entry');
    const count = entries.length;

    const template = entries[0].cloneNode(true);

    // The input-material picker is an enhanced combobox (REQ-FE-016); its clone is dead
    // (listeners dropped, duplicated ARIA ids, no native <select> left to re-enhance).
    // Build a fresh EMPTY select — the remote combobox (remote-materials-raw) searches the
    // raw catalog on demand, so no options are preloaded — carry over row 0's id/name
    // (renumbered by the loop below), and let krtEnhanceComboboxes upgrade it once the row
    // is inserted.
    const clonedPicker = template.querySelector('.krt-combobox');
    if (clonedPicker) {
        const hiddenField = clonedPicker.querySelector('input[type="hidden"]');
        const freshSelect = document.createElement('select');
        if (hiddenField) {
            freshSelect.id = hiddenField.id;
            freshSelect.name = hiddenField.name;
        }
        freshSelect.required = true;
        freshSelect.setAttribute('data-trigger', 'rod-update-output');
        freshSelect.setAttribute('data-krt-combobox', 'remote-materials-raw');
        clonedPicker.parentNode.replaceChild(freshSelect, clonedPicker);
        // The enhancer re-pointed row 0's label (for="krt-cb-N-input") and minted its id; the
        // clone carries both, which the /_\d+$/ renumbering below cannot fix — strip the id
        // (else every added row duplicates it) and re-bind the label to the rebuilt select's
        // field id so the renumber loop and the fresh enhancement pick it up cleanly.
        const clonedLabel = freshSelect.parentNode.querySelector('label');
        if (clonedLabel) {
            clonedLabel.removeAttribute('id');
            if (hiddenField) {
                clonedLabel.setAttribute('for', hiddenField.id);
            }
        }
    }

    // Renumber the title in the header. Source is row 0 ("Material #1") so without an
    // update the cloned row would also read "Material #1" until the page is reloaded.
    const title = template.querySelector('.material-entry-title');
    if (title) {
        title.textContent = MATERIAL_ENTRY_TITLE_LABEL + ' #' + (count + 1);
    }

    // Row 0 has no Remove button (the very first material can't be removed) — inject one
    // into the cloned row's header. Wired through the delegated 'rod-remove-material'
    // krtEvents handler via data-trigger, matching the server-rendered button.
    if (!template.querySelector('.remove-btn')) {
        const header = template.querySelector('.material-entry-header');
        if (header) {
            const removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'btn btn-quiet-danger remove-btn btn-icon';
            removeBtn.style.cssText = 'padding: 0.25rem 0.5rem; font-size: 0.8rem;';
            removeBtn.setAttribute('data-trigger', 'rod-remove-material');
            // Icon-only trash button, matching the server-rendered remove button in the
            // material-entry header. Label moves to title/aria-label; the SVG references the
            // sprite injected by the sidebar.
            removeBtn.setAttribute('title', MATERIAL_REMOVE_LABEL);
            removeBtn.setAttribute('aria-label', MATERIAL_REMOVE_LABEL);
            removeBtn.innerHTML =
                '<svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-trash"/></svg>';
            header.appendChild(removeBtn);
        }
    }

    const inputs = template.querySelectorAll('input, select');
    inputs.forEach((input) => {
        if (input.id) {
            input.id = input.id.replace(/_\d+$/, '_' + count);
        }
        if (input.name) {
            input.name = input.name.replace(/\[\d+\]/, '[' + count + ']');
        }
        // The delegated rod-calc-scu handler reads data-index to know which row's
        // SCU field to update; without renumbering, calcScu() always targets row 0.
        if (input.hasAttribute('data-index')) {
            input.setAttribute('data-index', count);
        }
        if (input.tagName.toLowerCase() === 'select') {
            input.selectedIndex = 0;
        } else {
            input.value = '';
        }
    });

    const displaySpan = template.querySelector('span[id^="outputMaterialDisplay_"]');
    if (displaySpan) {
        displaySpan.id = displaySpan.id.replace(/_\d+$/, '_' + count);
        displaySpan.innerText = '-';
        displaySpan.style.opacity = '0.7';
    }

    // A cloned row inherits the source row's yield badge — but the new row's material is
    // empty so the badge has no meaning. setYieldBadge() with undefined removes it; the
    // badge re-renders as soon as the user picks a material that has a yield row.
    const yieldBadge = template.querySelector('span[id^="yieldBonus_"]');
    if (yieldBadge) {
        yieldBadge.id = 'yieldBonus_' + count;
        yieldBadge.remove();
    }

    const labels = template.querySelectorAll('label');
    labels.forEach((label) => {
        const forAttr = label.getAttribute('for');
        if (forAttr) {
            label.setAttribute('for', forAttr.replace(/_\d+$/, '_' + count));
        }
    });

    container.appendChild(template);
    // Manually built DOM: the global enhancer does not see it, so upgrade the rebuilt
    // material select in place (REQ-FE-016).
    if (window.krtEnhanceComboboxes) {
        window.krtEnhanceComboboxes(template);
    }
}

function removeMaterialRow(button) {
    const entry = button.closest('.material-entry');
    entry.remove();

    const container = document.getElementById('materials-container');
    const entries = container.querySelectorAll('.material-entry');
    entries.forEach((entry, index) => {
        const inputs = entry.querySelectorAll('input, select');
        inputs.forEach((input) => {
            if (input.id) {
                input.id = input.id.replace(/_\d+$/, '_' + index);
            }
            if (input.name) {
                input.name = input.name.replace(/\[\d+\]/, '[' + index + ']');
            }
            if (input.hasAttribute('data-index')) {
                input.setAttribute('data-index', index);
            }
        });
        const displaySpan = entry.querySelector('span[id^="outputMaterialDisplay_"]');
        if (displaySpan) {
            displaySpan.id = displaySpan.id.replace(/_\d+$/, '_' + index);
        }
        const yieldBadge = entry.querySelector('span[id^="yieldBonus_"]');
        if (yieldBadge) {
            yieldBadge.id = 'yieldBonus_' + index;
        }
        const title = entry.querySelector('.material-entry-title');
        if (title) {
            title.textContent = MATERIAL_ENTRY_TITLE_LABEL + ' #' + (index + 1);
        }
        const labels = entry.querySelectorAll('label');
        labels.forEach((label) => {
            const forAttr = label.getAttribute('for');
            if (forAttr) {
                label.setAttribute('for', forAttr.replace(/_\d+$/, '_' + index));
            }
        });
    });
}

function updateMethodRatings() {
    const methodSelect = document.getElementById('refiningMethodId');
    const ratingsDiv = document.getElementById('methodRatings');
    if (!methodSelect || !ratingsDiv) return;

    const selectedOption = methodSelect.options[methodSelect.selectedIndex];

    if (selectedOption && selectedOption.value !== '') {
        document.getElementById('ratingYieldVal').innerText =
            RATING_LEVELS[selectedOption.getAttribute('data-yield')] || '-';
        document.getElementById('ratingCostVal').innerText =
            RATING_LEVELS[selectedOption.getAttribute('data-cost')] || '-';
        document.getElementById('ratingSpeedVal').innerText =
            SPEED_LEVELS[selectedOption.getAttribute('data-speed')] || '-';
        ratingsDiv.style.display = 'flex';
    } else {
        ratingsDiv.style.display = 'none';
    }
}

function updateEndsAt() {
    const startedAtInput = document.getElementById('startedAt');
    const durationHoursInput = document.getElementById('durationHours');
    const durationMinutesInput = document.getElementById('durationMinutes');
    const endsAtDisplay = document.querySelector('#endsAtDisplay span');

    if (!startedAtInput || !durationHoursInput || !durationMinutesInput || !endsAtDisplay) return;

    const startedAt = startedAtInput.value;
    const hours = parseInt(durationHoursInput.value) || 0;
    const minutes = parseInt(durationMinutesInput.value) || 0;

    if (startedAt) {
        const startDate = new Date(startedAt);
        if (!isNaN(startDate)) {
            const totalMinutes = hours * 60 + minutes;
            const endDate = new Date(startDate.getTime() + totalMinutes * 60000);

            const day = String(endDate.getDate()).padStart(2, '0');
            const month = String(endDate.getMonth() + 1).padStart(2, '0');
            const year = endDate.getFullYear();
            const hoursDisplay = String(endDate.getHours()).padStart(2, '0');
            const minutesDisplay = String(endDate.getMinutes()).padStart(2, '0');

            endsAtDisplay.innerText = `${day}.${month}.${year} ${hoursDisplay}:${minutesDisplay}`;
        } else {
            endsAtDisplay.innerText = '-';
        }
    } else {
        endsAtDisplay.innerText = '-';
    }
}

document.addEventListener('DOMContentLoaded', function () {
    const hiddenStartedAt = document.getElementById('startedAt');
    const dHours = document.getElementById('durationHours');
    const dMinutes = document.getElementById('durationMinutes');

    if (hiddenStartedAt) {
        hiddenStartedAt.addEventListener('change', updateEndsAt);
        hiddenStartedAt.addEventListener('input', updateEndsAt);
    }
    if (dHours) dHours.addEventListener('input', updateEndsAt);
    if (dMinutes) dMinutes.addEventListener('input', updateEndsAt);

    updateEndsAt();
});

document.addEventListener('DOMContentLoaded', function () {
    updateMethodRatings();
});

/**
 * Aktualisiert das read-only "Gewinn/Verlust"-Feld live aus oreSales - expenses - otherExpenses.
 * Server bleibt Source of Truth; dies ist lediglich eine UI-Vorschau.
 */
function updateProfitPreview() {
    const expensesEl = document.getElementById('expenses');
    const otherExpensesEl = document.getElementById('otherExpenses');
    const oreSalesEl = document.getElementById('oreSales');
    const preview = document.getElementById('profitPreview');
    if (!preview) return;
    const expenses = parseFloat(expensesEl && expensesEl.value) || 0;
    const otherExpenses = parseFloat(otherExpensesEl && otherExpensesEl.value) || 0;
    const oreSales = parseFloat(oreSalesEl && oreSalesEl.value) || 0;
    const profit = Math.round(oreSales - expenses - otherExpenses);
    preview.value = profit.toLocaleString();
    preview.classList.toggle('text-danger', profit < 0);
    preview.classList.toggle('text-muted', profit >= 0);
}
document.addEventListener('DOMContentLoaded', updateProfitPreview);

// CSP-safe delegated bindings (replaces the 14 inline on*= handlers above — preserves the
// input/change/submit semantics so the page behaves identically to the prior version).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('input', 'rod-update-profit', updateProfitPreview);
    // Restore "0" if the user clears one of the money fields and tabs away. Uses
    // `focusout` (which bubbles) instead of `blur` because event delegation listens
    // on `document` and `blur` does not bubble. The profit-preview re-runs after the
    // restore so the read-only Gewinn/Verlust field reflects the implicit 0 immediately.
    window.krtEvents.on('focusout', 'rod-update-profit', function (el) {
        if (el.value.trim() === '') {
            el.value = '0';
            updateProfitPreview();
        }
    });
    window.krtEvents.on('click', 'rod-set-started-now', setStartedAtNow);
    window.krtEvents.on('change', 'rod-update-method', updateMethodRatings);
    window.krtEvents.on('click', 'rod-add-material', addMaterialRow);
    window.krtEvents.on('click', 'rod-remove-material', function (el) {
        removeMaterialRow(el);
    });
    window.krtEvents.on('change', 'rod-update-output', function (el) {
        updateOutputMaterial(el);
    });
    window.krtEvents.on('change', 'rod-location-change', function (el) {
        window.krtRefineryYield.onLocationChange(el);
    });
    window.krtEvents.on('input', 'rod-calc-scu', function (el) {
        calcScu(el.getAttribute('data-index'));
    });
    window.krtEvents.on('click', 'rod-open-store', openStoreModal);
    window.krtEvents.on('click', 'rod-close-store', closeStoreModal);
    window.krtEvents.on('click', 'rod-duplicate-store', function (el) {
        duplicateStoreItem(el);
    });
    window.krtEvents.on('submit', 'rod-disable-submit', function (el) {
        // Disable the submit button + relabel it so a double-click does not file the form twice
        // (the old inline onsubmit handler did the same thing).
        const btn = el.querySelector('button[type=submit]');
        if (btn) {
            btn.disabled = true;
            // Relabel only the text <span> so the leading <svg> save icon survives;
            // overwriting btn.innerText would wipe the icon out of the icon+text button.
            const btnLabel = btn.querySelector('span');
            if (btnLabel) {
                btnLabel.textContent = MSG_SAVING;
            } else {
                btn.innerText = MSG_SAVING;
            }
        }
    });
}

// #575 in-place form interceptors. Bound at parse time (the forms are rendered above this
// script). rod-* controls stay document-delegated (they survive); these just replace the native
// POST->redirect with FormData + navigate/toast.
(function () {
    const mainForm = document.getElementById('refineryOrderMainForm');
    if (mainForm) {
        mainForm.addEventListener('submit', (e) => {
            e.preventDefault();
            _submitRefinery(mainForm, MSG_REFINERY_UPDATE_FAILED, null, e.submitter);
        });
    }
    const storeForm = document.getElementById('storeForm');
    if (storeForm) {
        storeForm.addEventListener('submit', (e) => {
            e.preventDefault();
            // rod-disable-submit (document-delegated) disables + relabels the button after this
            // handler; capture the original label now so a failed store can restore it (no reload).
            const btn = storeForm.querySelector('button[type=submit]');
            const btnLabel = btn ? btn.querySelector('span') : null;
            const originalLabel = btnLabel ? btnLabel.textContent : null;
            _submitRefinery(storeForm, MSG_REFINERY_STORE_FAILED, function () {
                if (btn) btn.disabled = false;
                if (btnLabel && originalLabel != null) btnLabel.textContent = originalLabel;
            });
        });
    }
    const cancelForm = document.getElementById('refineryCancelForm');
    if (cancelForm) {
        cancelForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (typeof window.showKrtConfirm === 'function') {
                const ok = await window.showKrtConfirm(
                    MSG_CANCEL_TITLE,
                    MSG_CANCEL_CONFIRM,
                    MSG_CANCEL_TITLE,
                    MSG_CANCEL_DISMISS,
                );
                if (!ok) return;
            }
            _submitRefinery(cancelForm, MSG_REFINERY_CANCEL_FAILED, null, e.submitter);
        });
    }
})();
