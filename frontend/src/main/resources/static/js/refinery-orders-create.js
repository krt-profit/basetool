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

/* global MATERIAL_YIELD_BONUSES, MATERIAL_YIELD_BONUS_HELP, MATERIAL_ENTRY_TITLE_LABEL, MATERIAL_REMOVE_LABEL, RATING_LEVELS, SPEED_LEVELS, MSG_RFC_MATERIAL_INVALID, MSG_RFC_CREATE_FAILED, MSG_RFC_MISSION_PARTICIPANT_REQUIRED, MSG_RFC_IMPORT_FAILED, REFINERY_HANDOFF_ID */

window.krtRefineryYield.init(MATERIAL_YIELD_BONUSES, MATERIAL_YIELD_BONUS_HELP);

function calcScu(index) {
    const unitInput = document.getElementById('outputQuantity_' + index);
    const scuInput = document.getElementById('outputQuantityScu_' + index);
    if (unitInput && scuInput) {
        const valStr = unitInput.value.replace(/\./g, '').replace(',', '.');
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

    const clonedPicker = template.querySelector('.krt-combobox');
    if (clonedPicker) {
        const hiddenField = clonedPicker.querySelector('input[type="hidden"]');
        const freshSelect = document.createElement('select');
        if (hiddenField) {
            freshSelect.id = hiddenField.id;
            freshSelect.name = hiddenField.name;
        }
        freshSelect.required = true;
        freshSelect.setAttribute('data-trigger', 'rfc-update-output');
        freshSelect.setAttribute('data-krt-combobox', 'remote-materials-raw');
        clonedPicker.parentNode.replaceChild(freshSelect, clonedPicker);
        const clonedLabel = freshSelect.parentNode.querySelector('label');
        if (clonedLabel) {
            clonedLabel.removeAttribute('id');
            if (hiddenField) {
                clonedLabel.setAttribute('for', hiddenField.id);
            }
        }
    }

    const title = template.querySelector('.material-entry-title');
    if (title) {
        title.textContent = MATERIAL_ENTRY_TITLE_LABEL + ' #' + (count + 1);
    }

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

    const yieldBadge = template.querySelector('span[id^="yieldBonus_"]');
    if (yieldBadge) {
        yieldBadge.remove();
    }

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
 * Updates the read-only profit/loss preview as oreSales - expenses - otherExpenses; the server
 * computes the stored value.
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

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('input', 'rfc-update-profit', updateProfitPreview);
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
    window.krtEvents.on('click', 'rfc-import-pick', function () {
        const fileInput = document.getElementById('refineryImportFile');
        if (fileInput) fileInput.click();
    });
    window.krtEvents.on('change', 'rfc-import-file', function (el) {
        if (el.files && el.files.length > 0) {
            const importForm = document.getElementById('refineryImportForm');
            if (importForm) importForm.requestSubmit();
        }
    });
    window.krtEvents.on('click', 'rfc-apply-suggestion', function (el) {
        const materialId = el.getAttribute('data-material-id');
        const materialName = el.getAttribute('data-material-name') || '';
        const entry = el.closest('.material-entry');
        const select = entry ? entry.querySelector('[id^="inputMaterialId_"]') : null;
        if (!select || !materialId) return;
        function applySuggestion(match) {
            if (select.krtCombobox) {
                if (match) {
                    select.krtCombobox.setValue(materialId, match.name, {
                        quantityType: match.quantityType || '',
                        refinedId: (match.refinedMaterial && match.refinedMaterial.id) || '',
                        refinedName: (match.refinedMaterial && match.refinedMaterial.name) || '',
                    });
                } else {
                    select.krtCombobox.setValue(materialId, materialName);
                }
            } else {
                select.value = materialId;
            }
            select.dispatchEvent(new Event('change', { bubbles: true }));
        }
        fetch('/catalog/material-search?raw=true&q=' + encodeURIComponent(materialName), {
            headers: { Accept: 'application/json' },
        })
            .then(function (r) {
                return r.ok ? r.json() : [];
            })
            .then(function (list) {
                const rows = Array.isArray(list) ? list : [];
                const match =
                    rows.find(function (m) {
                        return m.id === materialId;
                    }) ||
                    rows.find(function (m) {
                        return m.name === materialName;
                    }) ||
                    null;
                applySuggestion(match);
            })
            .catch(function () {
                applySuggestion(null);
            });
    });
}

/**
 * Picks the toast for a failed in-place create; the mission-participant problem code (REQ-SEC-042)
 * takes precedence over the generic 400 message.
 *
 * @param {number} status the HTTP status of the failed response
 * @param {any} body the parsed RFC 7807 problem body, if any
 * @returns {string} the localized message to show
 */
function _refineryCreateErrorMessage(status, body) {
    if (body && body.code === 'MISSION_PARTICIPANT_REQUIRED') {
        return MSG_RFC_MISSION_PARTICIPANT_REQUIRED;
    }
    return status === 400 ? MSG_RFC_MATERIAL_INVALID : MSG_RFC_CREATE_FAILED;
}

function _submitRefineryCreate(form, submitter) {
    if (!window.krtFetch) {
        form.submit();
        return;
    }
    window.krtFetch.submitForm({
        form,
        submitter,
        toast: false,
        errorMessage: MSG_RFC_CREATE_FAILED,
        onError(status, body) {
            if (window.showFrontendErrorToast) {
                window.showFrontendErrorToast(_refineryCreateErrorMessage(status, body));
            }
            return true;
        },
        onSuccess(body) {
            if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
            if (body && body.targetUrl) window.location.assign(body.targetUrl);
            else window.location.reload();
        },
    });
}
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
    document.querySelectorAll('[id^="inputMaterialId_"]').forEach((select) => {
        if (select.value) updateOutputMaterial(select);
    });
    if (window.krtRefineryYield) {
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

function _swapRefineryImportFragment(html) {
    const container = document.getElementById('refineryImportFormContainer');
    if (!container) {
        window.location.reload();
        return;
    }
    window.krtFetch.setTrustedHtml(container, html);
    document.dispatchEvent(new CustomEvent('krt:swapped', { detail: { container } }));
    if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
}

function _applyRefineryImportResult(result) {
    if (!result.ok) return;
    if (result.redirected || typeof result.body !== 'string') {
        if (window.showFrontendErrorToast) window.showFrontendErrorToast(MSG_RFC_IMPORT_FAILED);
        return;
    }
    _swapRefineryImportFragment(result.body);
}

function _refineryImportFailed() {
    if (window.showFrontendErrorToast) window.showFrontendErrorToast(MSG_RFC_IMPORT_FAILED);
    return true;
}

function _submitRefineryImport(form) {
    if (!window.krtFetch) {
        form.submit();
        return;
    }
    window.krtFetch
        .submitForm({
            form,
            method: 'POST',
            accept: 'text/html',
            toast: false,
            onError: _refineryImportFailed,
            onNetworkError: _refineryImportFailed,
        })
        .then(_applyRefineryImportResult);
}
const _refineryImportForm = document.getElementById('refineryImportForm');
if (_refineryImportForm) {
    _refineryImportForm.addEventListener('submit', function (e) {
        e.preventDefault();
        _submitRefineryImport(_refineryImportForm);
    });
}

function _loadRefineryHandoff() {
    if (typeof REFINERY_HANDOFF_ID === 'undefined' || !REFINERY_HANDOFF_ID) return;
    const id = REFINERY_HANDOFF_ID;
    try {
        const params = new URLSearchParams(window.location.search);
        params.delete('handoff');
        const cleaned =
            window.location.pathname + (params.toString() ? '?' + params.toString() : '');
        if (window.history && window.history.replaceState) {
            window.history.replaceState(null, '', cleaned);
        }
    } catch (_e) {}
    if (!window.krtFetch) return;
    window.krtFetch
        .write({
            method: 'POST',
            url: '/refinery-orders/import-handoff?handoff=' + encodeURIComponent(id),
            accept: 'text/html',
            toast: false,
            onError: _refineryImportFailed,
            onNetworkError: _refineryImportFailed,
        })
        .then(_applyRefineryImportResult);
}
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', _loadRefineryHandoff);
} else {
    _loadRefineryHandoff();
}
