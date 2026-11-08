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

/* global CAT_CONFLICT, MSG_UPDATE_SUCCESS, MSG_UPDATE_ERROR, MSG_CREATE_SUCCESS, MSG_CREATE_ERROR, MSG_OVERRIDE_RELEASED */

import { krtAutocomplete } from '/js/kernel/autocomplete.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { bindCatalogueList } from '/js/catalogue/catalogue-list.js';

/** @type {string[]} */
const materialNames = [];

/** Refills the material-name list from the page's datalist. */
function readMaterialNames() {
    const dataList = /** @type {HTMLDataListElement | null} */ (
        document.getElementById('materialNames-data')
    );
    materialNames.length = 0;
    if (dataList) {
        Array.from(dataList.options).forEach((o) => {
            materialNames.push(o.value);
        });
    }
}

document.addEventListener('DOMContentLoaded', () => {
    readMaterialNames();
    const inpMaterials = /** @type {HTMLInputElement | null} */ (
        document.getElementById('filterMaterials')
    );
    if (inpMaterials) krtAutocomplete(inpMaterials, materialNames);
});

bindCatalogueList({
    formSelector: '#materials-filter',
    containerSelector: '#materials-results',
    listUrl: '/admin/materials',
});

/**
 * Saves one inline-edited field of a material row.
 *
 * @param {HTMLInputElement | HTMLSelectElement} selectElement the changed select or checkbox
 */
function updateMaterial(selectElement) {
    const closestRow = selectElement.closest('tr');
    if (!closestRow) {
        return;
    }
    const tr = closestRow;
    const matId = /** @type {string} */ (tr.getAttribute('data-mat-id'));
    const updateType = selectElement.getAttribute('data-update-type');

    selectElement.disabled = true;

    /**
     * Builds the update payload from the row's current version and the control's value.
     *
     * @returns {Record<string, unknown>} the request body
     */
    function buildRequestBody() {
        /** @type {Record<string, unknown>} */
        const requestBody = {
            updateType,
            version: parseInt(/** @type {string} */ (tr.getAttribute('data-version')), 10),
        };
        const checkbox = /** @type {HTMLInputElement} */ (selectElement);
        if (updateType === 'QUANTITY_TYPE') {
            requestBody.quantityType = selectElement.value;
        } else if (updateType === 'CATEGORY') {
            requestBody.categoryId = selectElement.value || null;
        } else if (updateType === 'REFINED') {
            requestBody.refinedMaterialId = selectElement.value || null;
        } else if (updateType === 'MANUAL_RAW') {
            requestBody.isManualRawMaterial = checkbox.checked;
        } else if (updateType === 'JOB_ORDER') {
            requestBody.isJobOrder = checkbox.checked;
        } else if (updateType === 'VISIBILITY') {
            requestBody.isVisible = checkbox.checked;
        }
        return requestBody;
    }

    krtFetch
        .write({
            method: 'PUT',
            url: `/admin/materials/${encodeURIComponent(matId)}/ajax`,
            payload: buildRequestBody,
            serialize: `admin-material:${matId}`,
            containerSelector: tr,
            successMessage: MSG_UPDATE_SUCCESS,
            errorMessage: MSG_UPDATE_ERROR,
            conflict: CAT_CONFLICT,
            onSuccess(/** @type {ApiDto<'MaterialDto'> | null} */ material) {
                showOverrides(tr, material);
            },
        })
        .finally(() => {
            selectElement.disabled = false;
        });
}

krtEvents.on('change', 'admin-materials-update', (el) => {
    updateMaterial(/** @type {HTMLInputElement | HTMLSelectElement} */ (el));
});

/**
 * Shows each override marker of a material row exactly when the saved material holds that override.
 *
 * @param {HTMLTableRowElement} tr the material row
 * @param {ApiDto<'MaterialDto'> | null} material the material as saved
 */
function showOverrides(tr, material) {
    if (!material) {
        return;
    }
    /** @type {Record<string, boolean>} */
    const overrides = {
        RESOURCE_GROUP: material.resourceGroupManual === true,
        REFINED_MATERIAL: material.refinedMaterialManual === true,
    };
    tr.querySelectorAll('[data-p4k-override]').forEach((marker) => {
        const field = marker.getAttribute('data-p4k-override') || '';
        /** @type {HTMLElement} */ (marker).hidden = !overrides[field];
    });
}

/**
 * Hands an overridden field of a material back to the P4K import.
 *
 * @param {HTMLElement} button the marker's release button
 */
function releaseOverride(button) {
    const tr = button.closest('tr');
    if (!tr) {
        return;
    }
    const matId = /** @type {string} */ (tr.getAttribute('data-mat-id'));
    const field = /** @type {string} */ (button.getAttribute('data-p4k-field'));
    const version = /** @type {string} */ (tr.getAttribute('data-version'));
    krtFetch.write({
        method: 'DELETE',
        url:
            `/admin/materials/${encodeURIComponent(matId)}/p4k-overrides/` +
            `${encodeURIComponent(field)}/ajax?version=${encodeURIComponent(version)}`,
        serialize: `admin-material:${matId}`,
        submitter: button,
        containerSelector: tr,
        successMessage: MSG_OVERRIDE_RELEASED,
        errorMessage: MSG_UPDATE_ERROR,
        conflict: CAT_CONFLICT,
        onSuccess(/** @type {ApiDto<'MaterialDto'> | null} */ material) {
            showOverrides(tr, material);
        },
    });
}

krtEvents.on('click', 'admin-materials-release-override', (button) => {
    releaseOverride(/** @type {HTMLElement} */ (button));
});

/**
 * Looks up a text, select or textarea control of the create-material modal.
 *
 * @param {string} id the control's element id
 * @returns {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} the control
 */
function createMaterialField(id) {
    return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (
        document.getElementById(id)
    );
}

/**
 * Looks up a checkbox of the create-material modal.
 *
 * @param {string} id the checkbox's element id
 * @returns {HTMLInputElement} the checkbox
 */
function createMaterialCheckbox(id) {
    return /** @type {HTMLInputElement} */ (document.getElementById(id));
}

/** Resets the create-material form to its defaults and opens the modal. */
function openCreateMaterialModal() {
    createMaterialField('cm-name').value = '';
    createMaterialField('cm-type').value = 'RAW';
    createMaterialField('cm-quantity-type').value = 'SCU';
    createMaterialField('cm-description').value = '';
    createMaterialField('cm-refined').value = '';
    createMaterialField('cm-category').value = '';
    createMaterialCheckbox('cm-manual-raw').checked = true;
    createMaterialCheckbox('cm-job-order').checked = false;
    createMaterialCheckbox('cm-illegal').checked = false;
    createMaterialCheckbox('cm-volatile-qt').checked = false;
    createMaterialCheckbox('cm-volatile-time').checked = false;
    krtModal.open('modal-create-material', {
        focus: document.getElementById('cm-name'),
    });
}
/** Closes the create-material modal. */
function closeCreateMaterialModal() {
    krtModal.close('modal-create-material');
}
/**
 * Creates a material from the modal's fields and refreshes the table on success.
 *
 * @param {HTMLElement} btn the submit button, disabled while the request runs
 */
function submitCreateMaterial(btn) {
    const name = createMaterialField('cm-name').value.trim();
    if (!name) {
        createMaterialField('cm-name').focus();
        return;
    }
    const payload = {
        name,
        type: createMaterialField('cm-type').value,
        quantityType: createMaterialField('cm-quantity-type').value,
        description: createMaterialField('cm-description').value || null,
        refinedMaterialId: createMaterialField('cm-refined').value || null,
        categoryId: createMaterialField('cm-category').value || null,
        isManualRawMaterial: createMaterialCheckbox('cm-manual-raw').checked,
        isJobOrder: createMaterialCheckbox('cm-job-order').checked,
        isIllegal: createMaterialCheckbox('cm-illegal').checked,
        isVolatileQt: createMaterialCheckbox('cm-volatile-qt').checked,
        isVolatileTime: createMaterialCheckbox('cm-volatile-time').checked,
    };

    krtFetch.write({
        method: 'POST',
        url: '/admin/materials/ajax',
        payload,
        submitter: btn || null,
        successMessage: MSG_CREATE_SUCCESS,
        errorMessage: MSG_CREATE_ERROR,
        conflict: CAT_CONFLICT,
        onSuccess() {
            closeCreateMaterialModal();
            return refreshMaterialsTable();
        },
    });
}

/**
 * Replaces the current element matching the selector with the one from the fetched document.
 *
 * @param {ParentNode} fresh the freshly fetched page
 * @param {string} selector the element to swap
 */
function replaceFromDocument(fresh, selector) {
    const current = document.querySelector(selector);
    const next = fresh.querySelector(selector);
    if (current && next) {
        current.replaceWith(document.importNode(next, true));
    }
}

/**
 * Re-fetches the page and swaps the materials table in place.
 *
 * @returns {Promise<boolean>} true when the table was refreshed
 */
function refreshMaterialsTable() {
    return krtFetch
        .get(window.location.pathname + window.location.search)
        .then((res) => {
            return !res || !res.ok ? null : res.text();
        })
        .then((html) => {
            if (html === null) {
                return false;
            }
            const fresh = krtFetch.parseTrustedDocument(html);
            if (!fresh.querySelector('#materials-results #materialsTable tbody')) {
                return false;
            }
            const currentResults = document.getElementById('materials-results');
            const freshResults = fresh.querySelector('#materials-results');
            if (currentResults && freshResults) {
                currentResults.replaceChildren(
                    ...Array.from(freshResults.childNodes, (node) =>
                        document.importNode(node, true),
                    ),
                );
            }
            replaceFromDocument(fresh, '#materialNames-data');
            replaceFromDocument(fresh, '#cm-refined');
            readMaterialNames();
            document.dispatchEvent(
                new CustomEvent('krt:swapped', { detail: { container: currentResults } }),
            );
            return true;
        })
        .catch((error) => {
            console.warn('admin-materials: table refresh failed', error);
            return false;
        });
}

krtEvents.on('click', 'materials-open-create-modal', openCreateMaterialModal);
krtEvents.on('click', 'materials-close-create-modal', closeCreateMaterialModal);
krtEvents.on('click', 'materials-submit-create', (btn) => {
    submitCreateMaterial(btn);
});

document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        const overlay = document.getElementById('modal-create-material');
        if (overlay && overlay.classList.contains('is-open')) {
            closeCreateMaterialModal();
        }
    }
});
const overlay = document.getElementById('modal-create-material');
if (overlay) {
    overlay.addEventListener('click', (e) => {
        if (e.target === overlay) closeCreateMaterialModal();
    });
}
