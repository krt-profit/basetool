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
 * Refinery-order create page module (/refinery-orders/new), extracted verbatim from the former
 * inline script of refinery-orders-create.html (ADR-0069, follow-up to #924).
 *
 * Owns the create form's dynamic behaviour: per-row SCU calculation, output-material display and
 * yield-badge refresh (via the shared refinery-yield-badge.js module), add/remove/renumber of
 * material rows (including the #435 import-review flags), the refining-method rating readout, the
 * live ends-at and profit previews, the #575 in-place create submit through
 * window.krtFetch.submitForm, the #591 idempotent re-init after a screenshot-import fragment swap,
 * and the #435 screenshot-import submit that swaps a pre-filled create form into place. The classic
 * form-POSTs stay the no-JS fallback.
 *
 * The localized MSG_RFC_* and label strings, the RATING_LEVELS / SPEED_LEVELS dicts, the server-injected
 * MATERIAL_YIELD_BONUSES map and the ROUNDING_MODE value are defined by the inline Thymeleaf
 * bootstrap block of refinery-orders-create.html, which executes immediately before this script.
 */

/* global MATERIAL_YIELD_BONUSES, MATERIAL_YIELD_BONUS_HELP, MATERIAL_ENTRY_TITLE_LABEL, MATERIAL_REMOVE_LABEL, RATING_LEVELS, SPEED_LEVELS, MSG_RFC_MATERIAL_INVALID, MSG_RFC_CREATE_FAILED, MSG_RFC_IMPORT_FAILED */

// Initialize the shared yield-badge module. On the create form the map is empty until the
// user picks a refinery from the location dropdown (then onLocationChange fetches the map
// via the /refinery-orders/locations/{id}/yields proxy and refreshAll() re-renders every row).
window.krtRefineryYield.init(MATERIAL_YIELD_BONUSES, MATERIAL_YIELD_BONUS_HELP);

function calcScu(index) {
    const unitInput = document.getElementById('outputQuantity_' + index);
    const scuInput = document.getElementById('outputQuantityScu_' + index);
    if (unitInput && scuInput) {
        let valStr = unitInput.value.replace(/\./g, '').replace(',', '.');
        const val = parseFloat(valStr);
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

function updateOutputMaterial(selectElement) {
    const entryBlock = selectElement.closest('.material-entry');
    const outputDisplay = entryBlock.querySelector('span[id^="outputMaterialDisplay_"]');

    // The input-material picker is a searchable combobox (REQ-FE-016): the selected option's
    // data-refined-name is mirrored onto the hidden input carrying the control's id. The raw
    // <select> fallback covers a not-yet-enhanced control (pre-enhancement init pass).
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

    // Refresh the yield badge against the shared module's map. With no location picked the
    // map is empty and the badge stays hidden; once the user picks a refinery the map is
    // populated and the badge appears for the rows whose input material has UEX data.
    window.krtRefineryYield.refreshFor(selectElement);
}

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
    // Rebuild a raw select from the inert #refinery-material-options-template, carry over
    // row 0's id/name (renumbered by the loop below), and let krtEnhanceComboboxes upgrade
    // it once the row is inserted.
    const clonedPicker = template.querySelector('.krt-combobox');
    if (clonedPicker) {
        const optionsTemplate = document.getElementById('refinery-material-options-template');
        const hiddenField = clonedPicker.querySelector('input[type="hidden"]');
        const freshSelect = document.createElement('select');
        freshSelect.innerHTML = optionsTemplate ? optionsTemplate.innerHTML : '';
        if (hiddenField) {
            freshSelect.id = hiddenField.id;
            freshSelect.name = hiddenField.name;
        }
        freshSelect.required = true;
        freshSelect.setAttribute('data-trigger', 'rfc-update-output');
        freshSelect.setAttribute('data-krt-combobox', '');
        clonedPicker.parentNode.replaceChild(freshSelect, clonedPicker);
    }

    // Renumber the title in the header. Source is row 0 ("Material #1") so without an
    // update the cloned row would also read "Material #1" until the page is reloaded.
    const title = template.querySelector('.material-entry-title');
    if (title) {
        title.textContent = MATERIAL_ENTRY_TITLE_LABEL + ' #' + (count + 1);
    }

    // Row 0 has no Remove button (the very first material can't be removed) — inject one
    // into the cloned row's header. Wired through the delegated 'rfc-remove-material'
    // krtEvents handler via data-trigger, matching the server-rendered button.
    if (!template.querySelector('.remove-btn')) {
        const header = template.querySelector('.material-entry-header');
        if (header) {
            const removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'btn btn-quiet-danger remove-btn btn-icon';
            removeBtn.style.cssText = 'padding: 0.25rem 0.5rem; font-size: 0.8rem;';
            removeBtn.setAttribute('data-trigger', 'rfc-remove-material');
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
        // The delegated rfc-calc-scu handler reads data-index to know which row's
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
    // empty so the badge has no meaning. Drop it; the shared module re-creates it as soon as
    // the user picks a material that has a yield row for the current refinery.
    const yieldBadge = template.querySelector('span[id^="yieldBonus_"]');
    if (yieldBadge) {
        yieldBadge.remove();
    }

    // Same for the import review flags (#435): findings, confidence and suggestion chips
    // belong to the SOURCE row's draft data — a fresh row has no import finding, and a
    // cloned chip would still write to the source row's select when clicked.
    template.classList.remove('import-flagged-row');
    const importFlags = template.querySelector('.import-row-flags');
    if (importFlags) {
        importFlags.remove();
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

// CSP-safe delegated bindings (replaces the nine inline on*= handlers above — preserves
// the input/change semantics so the profit-preview / material-output / scu calculations
// re-run on the same DOM events as before).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('input', 'rfc-update-profit', updateProfitPreview);
    // Restore "0" if the user clears one of the money fields and tabs away. Uses
    // `focusout` (which bubbles) instead of `blur` because event delegation listens
    // on `document` and `blur` does not bubble. The profit-preview re-runs after the
    // restore so the read-only Gewinn/Verlust field reflects the implicit 0 immediately.
    window.krtEvents.on('focusout', 'rfc-update-profit', function (el) {
        if (el.value.trim() === '') {
            el.value = '0';
            updateProfitPreview();
        }
    });
    window.krtEvents.on('click', 'rfc-set-started-now', setStartedAtNow);
    window.krtEvents.on('change', 'rfc-update-method', updateMethodRatings);
    window.krtEvents.on('click', 'rfc-add-material', addMaterialRow);
    window.krtEvents.on('click', 'rfc-remove-material', function (el) {
        removeMaterialRow(el);
    });
    window.krtEvents.on('change', 'rfc-update-output', function (el) {
        updateOutputMaterial(el);
    });
    window.krtEvents.on('change', 'rfc-location-change', function (el) {
        window.krtRefineryYield.onLocationChange(el);
    });
    window.krtEvents.on('input', 'rfc-calc-scu', function (el) {
        calcScu(el.getAttribute('data-index'));
    });
    // Screenshot-extract import (#435): hidden file input + styled trigger, submit-on-pick.
    window.krtEvents.on('click', 'rfc-import-pick', function () {
        const fileInput = document.getElementById('refineryImportFile');
        if (fileInput) fileInput.click();
    });
    window.krtEvents.on('change', 'rfc-import-file', function (el) {
        if (el.files && el.files.length > 0) {
            const importForm = document.getElementById('refineryImportForm');
            // requestSubmit (not submit): only it fires the submit event that clears the
            // unsaved-changes dirty flag - values typed into the create form before an
            // import are discarded by design, so no leave-page warning may appear here.
            if (importForm) importForm.requestSubmit();
        }
    });
    // One-click assignment of a ranked suggestion to the row's material select; the change
    // event re-derives the output-material display and the yield badge like a manual pick.
    // The select is resolved RELATIVE to the chip (not via a row index): add/remove
    // renumber the select ids, so a stored index would silently target the wrong row.
    window.krtEvents.on('click', 'rfc-apply-suggestion', function (el) {
        const materialId = el.getAttribute('data-material-id');
        const entry = el.closest('.material-entry');
        // Attribute-only selector: after combobox enhancement the control's id lives on a
        // hidden <input>, not a <select> (REQ-FE-016).
        const select = entry ? entry.querySelector('[id^="inputMaterialId_"]') : null;
        if (!select || !materialId) return;
        // setValue() syncs the hidden value, the visible label and the mirrored metadata;
        // the explicit change dispatch re-derives the output display + yield badge like a
        // manual pick (setValue itself never fires change).
        if (select.krtCombobox) {
            select.krtCombobox.setValue(materialId);
        } else {
            select.value = materialId;
        }
        select.dispatchEvent(new Event('change', { bubbles: true }));
    });
}

// In-place create submit (#575): creating a refinery order navigates away on success (to the
// list), so intercept the form, POST FormData (the browser serializes the dynamic materials
// editor AND omits the disabled non-logistician owner select, falling back to its hidden twin)
// with X-Requested-With + krtCsrf, and navigate to the JSON targetUrl. On a validation/backend
// failure the page STAYS with an inline toast instead of the POST->redirect reflash that loses
// the entered data. The classic form-POST is the no-JS fallback (krtCsrf absent).
// Migrated to krtFetch.submitForm (S10, REQ-FE-009): the shared foundation owns the CSRF header
// (no Content-Type so the browser sets the multipart boundary), the bare-403 refresh-and-retry,
// X-Reauthenticate and the double-submit guard (submitter). This helper keeps only its page
// behaviour: navigate away to the JSON targetUrl on success (toast:false; a create leaves the
// page) with resetUnsavedChanges, and the 400-invalid vs generic inline error toast. The classic
// form-POST is the no-JS fallback (krtFetch absent).
function _submitRefineryCreate(form, submitter) {
    if (!window.krtFetch) {
        form.submit();
        return;
    }
    window.krtFetch.submitForm({
        form: form,
        submitter: submitter,
        toast: false,
        errorMessage: MSG_RFC_CREATE_FAILED,
        onError: function (status) {
            if (window.showFrontendErrorToast) {
                window.showFrontendErrorToast(
                    status === 400 ? MSG_RFC_MATERIAL_INVALID : MSG_RFC_CREATE_FAILED,
                );
            }
            return true;
        },
        onSuccess: function (body) {
            if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
            if (body && body.targetUrl) window.location.assign(body.targetUrl);
            else window.location.reload();
        },
    });
}
// #591: single idempotent re-init for everything that is NOT document-delegated, run on first
// load AND after the screenshot-import swaps #refineryImportFormContainer in place. The delegated
// krtEvents (rfc-*) handlers survive the swap and are deliberately NOT re-bound here (re-binding
// would double-fire them); the datetime widget re-inits itself via datetime-splitter.js's own
// krt:swapped listener. Subsumes the former four one-shot DOMContentLoaded init blocks.
function _reinitRefineryForm(fromSwap) {
    const startedAt = document.getElementById('startedAt');
    const dHours = document.getElementById('durationHours');
    const dMinutes = document.getElementById('durationMinutes');
    if (startedAt) {
        startedAt.removeEventListener('change', updateEndsAt);
        startedAt.addEventListener('change', updateEndsAt);
    }
    if (dHours) {
        dHours.removeEventListener('input', updateEndsAt);
        dHours.addEventListener('input', updateEndsAt);
    }
    if (dMinutes) {
        dMinutes.removeEventListener('input', updateEndsAt);
        dMinutes.addEventListener('input', updateEndsAt);
    }
    updateMethodRatings();
    document.querySelectorAll('.material-entry').forEach((_, index) => calcScu(index));
    // Attribute-only selector: matches the raw <select> before enhancement and the hidden
    // <input> carrying the id after it (REQ-FE-016).
    document.querySelectorAll('[id^="inputMaterialId_"]').forEach((select) => {
        if (select.value) updateOutputMaterial(select);
    });
    if (window.krtRefineryYield) {
        // On first load the page-level krtRefineryYield.init(...) already holds the server's yield
        // map, so refreshAll repaints correctly. After an import SWAP that in-memory map is stale
        // (init ran once at load, location-less), so re-fetch it for the imported location via
        // onLocationChange (which repaints the badges when it resolves); a location-less import
        // falls through to refreshAll.
        const locationSelect = document.getElementById('locationId');
        if (
            fromSwap &&
            locationSelect &&
            locationSelect.value &&
            typeof window.krtRefineryYield.onLocationChange === 'function'
        ) {
            window.krtRefineryYield.onLocationChange(locationSelect);
        } else {
            window.krtRefineryYield.refreshAll();
        }
    }
    updateProfitPreview();
    updateEndsAt();
    const createForm = document.querySelector('form[data-testid="refinery-form"]');
    if (createForm && !createForm._rfcSubmitBound) {
        createForm._rfcSubmitBound = true;
        createForm.addEventListener('submit', function (e) {
            e.preventDefault();
            _submitRefineryCreate(createForm, e.submitter);
        });
    }
}
document.addEventListener('DOMContentLoaded', function () {
    _reinitRefineryForm(false);
});
document.addEventListener('krt:swapped', function (e) {
    const c = e.detail && e.detail.container;
    if (c && c.id === 'refineryImportFormContainer') _reinitRefineryForm(true);
});

// #591: in-place screenshot-import. The picker posts the RefineryExtract as multipart; instead of
// the classic POST->redirect reload, fetch the pre-filled create-form fragment and swap it into
// #refineryImportFormContainer, then dispatch krt:swapped (the datetime splitter + the create-form
// re-init pick up the fresh DOM). A transport/redirect failure shows an inline toast and leaves
// the page intact; the classic multipart form-POST is the no-JS fallback (krtCsrf absent).
async function _submitRefineryImport(form) {
    if (!window.krtCsrf) {
        form.submit();
        return;
    }
    const fd = new FormData(form);
    function buildHeaders() {
        const h = { 'X-Requested-With': 'XMLHttpRequest' };
        const t = window.krtCsrf.token();
        const n = window.krtCsrf.headerName();
        if (t && n) h[n] = t;
        return h;
    }
    let res;
    try {
        res = await fetch(form.action, { method: 'POST', body: fd, headers: buildHeaders() });
        if (res.status === 403 && window.krtCsrf.refresh) {
            const refreshed = await window.krtCsrf.refresh();
            if (refreshed)
                res = await fetch(form.action, {
                    method: 'POST',
                    body: fd,
                    headers: buildHeaders(),
                });
        }
    } catch (_e) {
        if (window.showFrontendErrorToast) window.showFrontendErrorToast(MSG_RFC_IMPORT_FAILED);
        return;
    }
    if (res.redirected || !res.ok) {
        if (window.showFrontendErrorToast) window.showFrontendErrorToast(MSG_RFC_IMPORT_FAILED);
        return;
    }
    const html = await res.text();
    const container = document.getElementById('refineryImportFormContainer');
    if (!container) {
        window.location.reload();
        return;
    }
    container.innerHTML = html;
    document.dispatchEvent(new CustomEvent('krt:swapped', { detail: { container: container } }));
    if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
}
const _refineryImportForm = document.getElementById('refineryImportForm');
if (_refineryImportForm) {
    _refineryImportForm.addEventListener('submit', function (e) {
        e.preventDefault();
        _submitRefineryImport(_refineryImportForm);
    });
}
