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

/* global MATERIAL_YIELD_BONUSES, MATERIAL_YIELD_BONUS_HELP, MATERIAL_ENTRY_TITLE_LABEL, MATERIAL_REMOVE_LABEL, MSG_SAVING, MSG_REFINERY_UPDATE_FAILED, MSG_REFINERY_STORE_FAILED, MSG_REFINERY_CANCEL_FAILED, MSG_CANCEL_CONFIRM, MSG_CANCEL_TITLE, MSG_CANCEL_DISMISS, REFINERY_DETAIL_MSG, STORE_INHERITED_ORG_UNIT_ID, STORE_ORG_UNIT_PLACEHOLDER, RATING_LEVELS, SPEED_LEVELS, showFrontendErrorToast */

/**
 * A form control of a material or store row, whose `id`, `name` and `value` the renumbering
 * passes rewrite.
 *
 * @typedef {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} RodFormControl
 */

window.krtRefineryYield.init(MATERIAL_YIELD_BONUSES, MATERIAL_YIELD_BONUS_HELP);

const REFINERY_ORDER_SECTIONS = {
    order: { container: '#refinery-order-results', fragmentValue: 'order' },
    store: { container: '#refinery-store-results', fragmentValue: 'store' },
};

/** @type {KrtSectionWriter | null} */
let refinerySeam = null;

/** The refinery-order:{id} room this page publishes to, or null before the id bootstrap ran. */
function refineryTopic() {
    return window.refineryOrderId ? 'refinery-order:' + window.refineryOrderId : null;
}

/** True while the Einlagern dialog is on screen, i.e. the user is mid-edit in it. */
function refineryStoreModalOpen() {
    const modal = document.getElementById('storeModal');
    return !!modal && window.getComputedStyle(modal).display !== 'none';
}

(function () {
    if (!window.krtFetch || typeof window.krtFetch.sectionWrite !== 'function') {
        return;
    }
    refinerySeam = window.krtFetch.sectionWrite({
        dict() {
            return {
                'refineryorder.section.refresh.error': REFINERY_DETAIL_MSG.sectionRefreshError,
            };
        },
        keys: { refreshErrorKey: 'refineryorder.section.refresh.error' },
        sections: REFINERY_ORDER_SECTIONS,
        pageUrl() {
            return window.refineryOrderId ? '/refinery-orders/' + window.refineryOrderId : null;
        },
        broadcast(keys) {
            const topic = refineryTopic();
            if (
                topic &&
                window.krtLiveSync &&
                typeof window.krtLiveSync.sendChanged === 'function'
            ) {
                window.krtLiveSync.sendChanged(topic, keys);
            }
        },
    });

    if (refineryTopic() && window.krtLiveSync && window.krtLiveSync.createReceiver) {
        window.krtLiveSync.createReceiver({
            topic: refineryTopic(),
            sections: REFINERY_ORDER_SECTIONS,
            refresh(keys) {
                refinerySeam?.refresh(keys, { broadcast: false });
            },
            busyTest() {
                return refineryStoreModalOpen();
            },
            pill: {
                label() {
                    return REFINERY_DETAIL_MSG.livesyncUpdates;
                },
            },
        });
    }
})();

function _submitRefinery(options) {
    const form = options.form;
    if (!window.krtFetch) {
        form.submit();
        return;
    }
    /**
     * @param {any} [_status] the HTTP status, or the network error on a transport failure
     * @param {any} [body] the parsed RFC 7807 problem body, if any
     */
    function fail(_status, body) {
        if (options.onFailure) options.onFailure();
        showFrontendErrorToast(
            body && body.code === 'MISSION_PARTICIPANT_REQUIRED'
                ? REFINERY_DETAIL_MSG.missionParticipantRequired
                : options.errorMessage,
        );
        return true;
    }
    window.krtFetch.submitForm({
        form,
        submitter: options.submitter,
        serialize: 'refinery-order:' + window.refineryOrderId,
        toast: options.successMessage !== undefined,
        successMessage: options.successMessage,
        errorMessage: options.errorMessage,
        onError: fail,
        onNetworkError: fail,
        onSuccess: options.onSuccess,
    });
}

function calcScu(index) {
    const unitInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('outputQuantity_' + index)
    );
    const scuInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('outputQuantityScu_' + index)
    );
    if (unitInput && scuInput) {
        const valStr = unitInput.value.replace(/\./g, '').replace(',', '.');
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
    const modal = document.getElementById('storeModal');
    if (modal) window.krtModal.close(modal);
}

function openStoreModal() {
    const modal = document.getElementById('storeModal');
    if (modal) window.krtModal.open(modal);
}

function duplicateStoreItem(btn) {
    const container = document.getElementById('storeItemsContainer');
    if (!container) return;
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

    const tpl = /** @type {HTMLTemplateElement | null} */ (
        document.getElementById('store-user-select-tpl')
    );
    const tplSelect = tpl ? tpl.content.firstElementChild : null;
    const sourceUser = blockToCopy.querySelector('[id^="storeUser_"]');
    const sourceUserValue = sourceUser ? sourceUser.value : '';
    const sourceUserInput = blockToCopy.querySelector('.krt-combobox__input');
    const sourceUserLabel = sourceUserInput ? sourceUserInput.value : '';
    const clonedUser = newBlock.querySelector('[id^="storeUser_"]');
    if (tplSelect && clonedUser) {
        const freshUser = /** @type {HTMLSelectElement} */ (tplSelect.cloneNode(true));
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
        btnInNewBlock.setAttribute('title', REFINERY_DETAIL_MSG.storeSplitRemove);
    }

    blockToCopy.after(newBlock);
    reindexStoreItems();
    syncStorePersonalJobOrder(newBlock);
    if (window.krtEnhanceComboboxes) {
        window.krtEnhanceComboboxes(newBlock);
    }
}

function reindexStoreItems() {
    const container = document.getElementById('storeItemsContainer');
    if (!container) return;
    const allBlocks = container.querySelectorAll('.store-item-block');
    allBlocks.forEach((block, index) => {
        const nameRegex = /items\[\d+\]/g;
        const controls = /** @type {NodeListOf<RodFormControl>} */ (
            block.querySelectorAll('input, select, textarea')
        );
        controls.forEach((el) => {
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

document.addEventListener('change', (e) => {
    const sel = /** @type {HTMLSelectElement} */ (e.target);
    if (!sel || !sel.id || !sel.closest || !sel.closest('#storeItemsContainer')) return;
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

function initRefineryStoreSection() {
    const storeItemsContainer = document.getElementById('storeItemsContainer');
    if (!storeItemsContainer) return;
    storeItemsContainer.querySelectorAll('.store-item-block').forEach(syncStorePersonalJobOrder);
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

function initRefineryOrderSection() {
    const inputSelects = /** @type {NodeListOf<KrtRefineryControl>} */ (
        document.querySelectorAll('[id^="inputMaterialId_"]')
    );
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
    updateProfitPreview();

    window.krtRefineryYield.refreshAll();
}

document.addEventListener('DOMContentLoaded', function () {
    initRefineryOrderSection();
    initRefineryStoreSection();
});

document.addEventListener('krt:swapped', function (ev) {
    const container = ev && ev.detail && ev.detail.container;
    if (!container) return;
    if (container.id === 'refinery-order-results') {
        initRefineryOrderSection();
        window.krtRefineryYield.onLocationChange(
            /** @type {KrtRefineryControl | null} */ (document.getElementById('locationId')),
        );
    } else if (container.id === 'refinery-store-results') {
        initRefineryStoreSection();
    }
});

function setStartedAtNow() {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    const hours = String(now.getHours()).padStart(2, '0');
    const minutes = String(now.getMinutes()).padStart(2, '0');

    const dateInput = /** @type {HTMLInputElement | null} */ (
        document.querySelector('.datetime-split-group .date-part')
    );
    const timeInput = /** @type {HTMLInputElement | null} */ (
        document.querySelector('.datetime-split-group .time-part')
    );

    if (dateInput && timeInput) {
        dateInput.value = `${year}-${month}-${day}`;
        timeInput.value = `${hours}:${minutes}`;
        dateInput.dispatchEvent(new Event('input', { bubbles: true }));
    }
    updateEndsAt();
}

function addMaterialRow() {
    const container = document.getElementById('materials-container');
    if (!container) return;
    const entries = container.querySelectorAll('.material-entry');
    const count = entries.length;

    const template = /** @type {HTMLElement} */ (entries[0].cloneNode(true));

    const clonedPicker = template.querySelector('.krt-combobox');
    const pickerParent = clonedPicker ? clonedPicker.parentNode : null;
    if (clonedPicker && pickerParent) {
        const hiddenField = /** @type {HTMLInputElement | null} */ (
            clonedPicker.querySelector('input[type="hidden"]')
        );
        const freshSelect = document.createElement('select');
        if (hiddenField) {
            freshSelect.id = hiddenField.id;
            freshSelect.name = hiddenField.name;
        }
        freshSelect.required = true;
        freshSelect.setAttribute('data-trigger', 'rod-update-output');
        freshSelect.setAttribute('data-krt-combobox', 'remote-materials-raw');
        pickerParent.replaceChild(freshSelect, clonedPicker);
        const clonedLabel = pickerParent.querySelector('label');
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
            removeBtn.setAttribute('data-trigger', 'rod-remove-material');
            removeBtn.setAttribute('title', MATERIAL_REMOVE_LABEL);
            removeBtn.setAttribute('aria-label', MATERIAL_REMOVE_LABEL);
            removeBtn.innerHTML =
                '<svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-trash"/></svg>';
            header.appendChild(removeBtn);
        }
    }

    const inputs = /** @type {NodeListOf<RodFormControl>} */ (
        template.querySelectorAll('input, select')
    );
    inputs.forEach((input) => {
        if (input.id) {
            input.id = input.id.replace(/_\d+$/, '_' + count);
        }
        if (input.name) {
            input.name = input.name.replace(/\[\d+\]/, '[' + count + ']');
        }
        if (input.hasAttribute('data-index')) {
            input.setAttribute('data-index', String(count));
        }
        if (input instanceof HTMLSelectElement) {
            input.selectedIndex = 0;
        } else {
            input.value = '';
        }
    });

    const displaySpan = /** @type {HTMLElement | null} */ (
        template.querySelector('span[id^="outputMaterialDisplay_"]')
    );
    if (displaySpan) {
        displaySpan.id = displaySpan.id.replace(/_\d+$/, '_' + count);
        displaySpan.innerText = '-';
        displaySpan.style.opacity = '0.7';
    }

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
    if (window.krtEnhanceComboboxes) {
        window.krtEnhanceComboboxes(template);
    }
}

function removeMaterialRow(button) {
    const entry = button.closest('.material-entry');
    entry.remove();

    const container = document.getElementById('materials-container');
    if (!container) return;
    const entries = container.querySelectorAll('.material-entry');
    entries.forEach((entry, index) => {
        const inputs = /** @type {NodeListOf<RodFormControl>} */ (
            entry.querySelectorAll('input, select')
        );
        inputs.forEach((input) => {
            if (input.id) {
                input.id = input.id.replace(/_\d+$/, '_' + index);
            }
            if (input.name) {
                input.name = input.name.replace(/\[\d+\]/, '[' + index + ']');
            }
            if (input.hasAttribute('data-index')) {
                input.setAttribute('data-index', String(index));
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
    const methodSelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('refiningMethodId')
    );
    const ratingsDiv = document.getElementById('methodRatings');
    if (!methodSelect || !ratingsDiv) return;

    const yieldVal = document.getElementById('ratingYieldVal');
    const costVal = document.getElementById('ratingCostVal');
    const speedVal = document.getElementById('ratingSpeedVal');
    const selectedOption = methodSelect.options[methodSelect.selectedIndex];

    if (selectedOption && selectedOption.value !== '') {
        if (yieldVal) {
            yieldVal.innerText =
                RATING_LEVELS[selectedOption.getAttribute('data-yield') || ''] || '-';
        }
        if (costVal) {
            costVal.innerText =
                RATING_LEVELS[selectedOption.getAttribute('data-cost') || ''] || '-';
        }
        if (speedVal) {
            speedVal.innerText =
                SPEED_LEVELS[selectedOption.getAttribute('data-speed') || ''] || '-';
        }
        ratingsDiv.style.display = 'flex';
    } else {
        ratingsDiv.style.display = 'none';
    }
}

function updateEndsAt() {
    const startedAtInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('startedAt')
    );
    const durationHoursInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('durationHours')
    );
    const durationMinutesInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('durationMinutes')
    );
    const endsAtDisplay = /** @type {HTMLElement | null} */ (
        document.querySelector('#endsAtDisplay span')
    );

    if (!startedAtInput || !durationHoursInput || !durationMinutesInput || !endsAtDisplay) return;

    const startedAt = startedAtInput.value;
    const hours = parseInt(durationHoursInput.value) || 0;
    const minutes = parseInt(durationMinutesInput.value) || 0;

    if (startedAt) {
        const startDate = new Date(startedAt);
        if (!isNaN(startDate.getTime())) {
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
    const expensesEl = /** @type {HTMLInputElement | null} */ (document.getElementById('expenses'));
    const otherExpensesEl = /** @type {HTMLInputElement | null} */ (
        document.getElementById('otherExpenses')
    );
    const oreSalesEl = /** @type {HTMLInputElement | null} */ (document.getElementById('oreSales'));
    const preview = /** @type {HTMLInputElement | null} */ (
        document.getElementById('profitPreview')
    );
    if (!preview) return;
    const expenses = parseFloat((expensesEl && expensesEl.value) || '') || 0;
    const otherExpenses = parseFloat((otherExpensesEl && otherExpensesEl.value) || '') || 0;
    const oreSales = parseFloat((oreSalesEl && oreSalesEl.value) || '') || 0;
    const profit = Math.round(oreSales - expenses - otherExpenses);
    preview.value = profit.toLocaleString();
    preview.classList.toggle('text-danger', profit < 0);
    preview.classList.toggle('text-muted', profit >= 0);
}

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('input', 'rod-update-profit', updateProfitPreview);
    window.krtEvents.on('focusout', 'rod-update-profit', function (el) {
        const field = /** @type {HTMLInputElement} */ (el);
        if (field.value.trim() === '') {
            field.value = '0';
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
        window.krtRefineryYield.onLocationChange(/** @type {KrtRefineryControl} */ (el));
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
        const btn = /** @type {HTMLButtonElement | null} */ (
            el.querySelector('button[type=submit]')
        );
        if (btn) {
            btn.disabled = true;
            const btnLabel = btn.querySelector('span');
            if (btnLabel) {
                btnLabel.textContent = MSG_SAVING;
            } else {
                btn.innerText = MSG_SAVING;
            }
        }
    });
}

function submitRefineryMainForm(form, submitter) {
    _submitRefinery({
        form,
        submitter,
        successMessage: REFINERY_DETAIL_MSG.updateSuccess,
        errorMessage: MSG_REFINERY_UPDATE_FAILED,
        onSuccess() {
            if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
            return refinerySeam ? refinerySeam.refresh(['order', 'store']) : undefined;
        },
    });
}

function submitRefineryStoreForm(form, submitter) {
    const btn = form.querySelector('button[type=submit]');
    const btnLabel = btn ? btn.querySelector('span') : null;
    const originalLabel = btnLabel ? btnLabel.textContent : null;
    const jobOrderIds = refineryStoreJobOrderIds(form);
    _submitRefinery({
        form,
        submitter,
        successMessage: REFINERY_DETAIL_MSG.storeSuccess,
        errorMessage: MSG_REFINERY_STORE_FAILED,
        onFailure() {
            if (btn) btn.disabled = false;
            if (btnLabel && originalLabel != null) btnLabel.textContent = originalLabel;
        },
        onSuccess() {
            closeStoreModal();
            crossPublishStoredStock(jobOrderIds);
            return refinerySeam ? refinerySeam.refresh(['order', 'store']) : undefined;
        },
    });
}

async function submitRefineryCancelForm(form, submitter) {
    if (typeof window.showKrtConfirm === 'function') {
        const ok = await window.showKrtConfirm(
            MSG_CANCEL_TITLE,
            MSG_CANCEL_CONFIRM,
            MSG_CANCEL_TITLE,
            MSG_CANCEL_DISMISS,
        );
        if (!ok) return;
    }
    _submitRefinery({
        form,
        submitter,
        errorMessage: MSG_REFINERY_CANCEL_FAILED,
        onSuccess(body) {
            if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
            if (refinerySeam) refinerySeam.notify(['order', 'store']);
            if (body && body.targetUrl) window.location.assign(body.targetUrl);
            else window.location.reload();
        },
    });
}

/** The distinct job orders the store dialog's rows earmark their output to (blank rows skipped). */
function refineryStoreJobOrderIds(form) {
    const ids = [];
    form.querySelectorAll('[id^="storeJobOrder_"]').forEach((sel) => {
        const value = /** @type {HTMLSelectElement} */ (sel).value;
        if (value && ids.indexOf(value) < 0) ids.push(value);
    });
    return ids;
}

function crossPublishStoredStock(jobOrderIds) {
    if (!window.krtLiveSync || typeof window.krtLiveSync.sendChanged !== 'function') return;
    jobOrderIds.forEach((jobOrderId) => {
        window.krtLiveSync.sendChanged('order:' + jobOrderId, ['materials', 'aggregated']);
    });
}

document.addEventListener(
    'submit',
    function (e) {
        const form = /** @type {HTMLFormElement} */ (e.target);
        if (!form || !form.id) return;
        if (form.id === 'refineryOrderMainForm') {
            e.preventDefault();
            submitRefineryMainForm(form, e.submitter);
        } else if (form.id === 'storeForm') {
            e.preventDefault();
            submitRefineryStoreForm(form, e.submitter);
        } else if (form.id === 'refineryCancelForm') {
            e.preventDefault();
            submitRefineryCancelForm(form, e.submitter);
        }
    },
    true,
);
