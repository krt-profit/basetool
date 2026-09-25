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

/* global CAT_MSG, CAT_CONFLICT, MSG_UPDATE_SUCCESS, MSG_UPDATE_ERROR, MSG_CREATE_SUCCESS, MSG_CREATE_ERROR, krtAutocomplete */

const materialNames = [];

function readMaterialNames() {
    const dataList = /** @type {HTMLDataListElement | null} */ (
        document.getElementById('materialNames-data')
    );
    materialNames.length = 0;
    if (dataList) {
        Array.from(dataList.options).forEach(function (o) {
            materialNames.push(o.value);
        });
    }
}

document.addEventListener('DOMContentLoaded', function () {
    readMaterialNames();
    const inpMaterials = document.getElementById('filterMaterials');
    if (inpMaterials) krtAutocomplete(inpMaterials, materialNames);

    function addCategoryOption(cat) {
        document
            .querySelectorAll('select[data-update-type="CATEGORY"], #cm-category')
            .forEach(function (sel) {
                const opt = document.createElement('option');
                opt.value = cat.id;
                opt.textContent = cat.name;
                sel.appendChild(opt);
            });
    }
    function removeCategoryOption(id) {
        document
            .querySelectorAll('select[data-update-type="CATEGORY"] option, #cm-category option')
            .forEach(function (opt) {
                if (opt.value === id) {
                    opt.remove();
                }
            });
    }

    function buildCategoryRow(cat) {
        const tr = document.createElement('tr');
        tr.setAttribute('data-category-row', '');
        const nameTd = document.createElement('td');
        nameTd.textContent = cat.name;
        const actionTd = document.createElement('td');
        const form = document.createElement('form');
        form.method = 'post';
        form.action = '/admin/materials/categories/' + encodeURIComponent(cat.id) + '/delete';
        form.style.display = 'inline';
        form.setAttribute('data-krt-confirm', '');
        form.setAttribute('data-krt-confirm-message', CAT_MSG.deleteConfirm);
        form.setAttribute('data-category-id', cat.id);
        const btn = document.createElement('button');
        btn.type = 'submit';
        btn.className = 'btn btn-quiet-danger btn-icon';
        btn.title = CAT_MSG.deleteTitle;
        btn.setAttribute('aria-label', CAT_MSG.deleteTitle);
        btn.innerHTML =
            '<svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-trash"/></svg>';
        form.appendChild(btn);
        actionTd.appendChild(form);
        tr.appendChild(nameTd);
        tr.appendChild(actionTd);
        return tr;
    }

    function buildEmptyCategoryRow() {
        const tr = document.createElement('tr');
        tr.setAttribute('data-category-empty', '');
        const td = document.createElement('td');
        td.colSpan = 2;
        td.textContent = CAT_MSG.noEntries;
        tr.appendChild(td);
        return tr;
    }

    document.addEventListener('submit', function (event) {
        const form = event.target.closest('form[data-category-create]');
        if (!form) {
            return;
        }
        event.preventDefault();
        if (!window.krtFetch) {
            form.submit();
            return;
        }
        const nameInput = form.querySelector('input[name="name"]');
        const name = nameInput ? nameInput.value.trim() : '';
        if (!name) {
            if (nameInput) {
                nameInput.focus();
            }
            return;
        }
        window.krtFetch.write({
            method: 'POST',
            url: form.getAttribute('action'),
            payload: { name },
            successMessage: CAT_MSG.createSuccess,
            errorMessage: CAT_MSG.createError,
            conflict: CAT_CONFLICT,
            onSuccess(created) {
                if (!created || created.id == null) {
                    return;
                }
                addCategoryOption(created);
                const tbody = form.closest('.data-section').querySelector('tbody');
                if (tbody) {
                    const emptyRow = tbody.querySelector('[data-category-empty]');
                    if (emptyRow) {
                        emptyRow.remove();
                    }
                    tbody.appendChild(buildCategoryRow(created));
                }
                if (nameInput) {
                    nameInput.value = '';
                }
            },
        });
    });

    document.addEventListener('submit', function (event) {
        const form = event.target.closest('form[data-krt-confirm]');
        if (!form || form.dataset.krtConfirmed === 'true') {
            return;
        }
        event.preventDefault();
        const message = form.getAttribute('data-krt-confirm-message') || '';
        const proceed =
            typeof window.showKrtConfirm === 'function'
                ? window.showKrtConfirm(null, message)
                : Promise.resolve(true);
        proceed.then(function (ok) {
            if (!ok) {
                return;
            }
            const categoryId = form.getAttribute('data-category-id');
            if (window.krtFetch && categoryId) {
                window.krtFetch.write({
                    method: 'POST',
                    url: form.getAttribute('action'),
                    successMessage: CAT_MSG.deleteSuccess,
                    errorMessage: CAT_MSG.deleteError,
                    conflict: CAT_CONFLICT,
                    onSuccess() {
                        removeCategoryOption(categoryId);
                        const row = form.closest('tr');
                        const tbody = row ? row.parentElement : null;
                        if (row) {
                            row.remove();
                        }
                        if (tbody && !tbody.querySelector('tr:not([data-category-empty])')) {
                            tbody.appendChild(buildEmptyCategoryRow());
                        }
                    },
                });
                return;
            }
            form.dataset.krtConfirmed = 'true';
            form.submit();
        });
    });
});

function filterTable(tableId, query) {
    const filter = query.toUpperCase();
    const table = document.getElementById(tableId);
    const tr = table.getElementsByTagName('tr');
    for (let i = 1; i < tr.length; i++) {
        const td = tr[i].getElementsByTagName('td')[0];
        if (td) {
            const txtValue = td.textContent || td.innerText;
            if (txtValue.toUpperCase().indexOf(filter) > -1) {
                tr[i].style.display = '';
            } else {
                tr[i].style.display = 'none';
            }
        }
    }
}

window.filterTable = filterTable;

function updateMaterial(selectElement) {
    const tr = selectElement.closest('tr');
    const matId = tr.getAttribute('data-mat-id');
    const updateType = selectElement.getAttribute('data-update-type');
    if (!window.krtFetch) {
        return;
    }

    selectElement.disabled = true;

    function buildRequestBody() {
        const requestBody = {
            updateType,
            version: parseInt(tr.getAttribute('data-version'), 10),
        };
        if (updateType === 'QUANTITY_TYPE') {
            requestBody.quantityType = selectElement.value;
        } else if (updateType === 'CATEGORY') {
            requestBody.categoryId = selectElement.value || null;
        } else if (updateType === 'REFINED') {
            requestBody.refinedMaterialId = selectElement.value || null;
        } else if (updateType === 'MANUAL_RAW') {
            requestBody.isManualRawMaterial = selectElement.checked;
        } else if (updateType === 'JOB_ORDER') {
            requestBody.isJobOrder = selectElement.checked;
        } else if (updateType === 'VISIBILITY') {
            requestBody.isVisible = selectElement.checked;
        }
        return requestBody;
    }

    window.krtFetch
        .write({
            method: 'PUT',
            url: `/admin/materials/${encodeURIComponent(matId)}/ajax`,
            payload: buildRequestBody,
            serialize: 'admin-material:' + matId,
            containerSelector: tr,
            successMessage: MSG_UPDATE_SUCCESS,
            errorMessage: MSG_UPDATE_ERROR,
            conflict: CAT_CONFLICT,
        })
        .finally(() => {
            selectElement.disabled = false;
        });
}

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('change', 'admin-materials-update', function (el) {
        updateMaterial(el);
    });
}

function openCreateMaterialModal() {
    document.getElementById('cm-name').value = '';
    document.getElementById('cm-type').value = 'RAW';
    document.getElementById('cm-quantity-type').value = 'SCU';
    document.getElementById('cm-description').value = '';
    document.getElementById('cm-refined').value = '';
    document.getElementById('cm-category').value = '';
    document.getElementById('cm-manual-raw').checked = true;
    document.getElementById('cm-job-order').checked = false;
    document.getElementById('cm-illegal').checked = false;
    document.getElementById('cm-volatile-qt').checked = false;
    document.getElementById('cm-volatile-time').checked = false;
    window.krtModal.open('modal-create-material', {
        focus: document.getElementById('cm-name'),
    });
}
function closeCreateMaterialModal() {
    window.krtModal.close('modal-create-material');
}
function submitCreateMaterial(btn) {
    const name = document.getElementById('cm-name').value.trim();
    if (!name) {
        document.getElementById('cm-name').focus();
        return;
    }
    const payload = {
        name,
        type: document.getElementById('cm-type').value,
        quantityType: document.getElementById('cm-quantity-type').value,
        description: document.getElementById('cm-description').value || null,
        refinedMaterialId: document.getElementById('cm-refined').value || null,
        categoryId: document.getElementById('cm-category').value || null,
        isManualRawMaterial: document.getElementById('cm-manual-raw').checked,
        isJobOrder: document.getElementById('cm-job-order').checked,
        isIllegal: document.getElementById('cm-illegal').checked,
        isVolatileQt: document.getElementById('cm-volatile-qt').checked,
        isVolatileTime: document.getElementById('cm-volatile-time').checked,
    };

    if (!window.krtFetch) {
        return;
    }
    window.krtFetch.write({
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

function replaceFromDocument(fresh, selector) {
    const current = document.querySelector(selector);
    const next = fresh.querySelector(selector);
    if (current && next) {
        current.replaceWith(document.importNode(next, true));
    }
}

function refreshMaterialsTable() {
    return fetch(window.location.pathname + window.location.search, {
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
    })
        .then(function (res) {
            if (window.krtFetch && window.krtFetch.maybeReauthenticate(res)) {
                return null;
            }
            return res.redirected || !res.ok ? null : res.text();
        })
        .then(function (html) {
            if (html === null) {
                return false;
            }
            const fresh = new DOMParser().parseFromString(html, 'text/html');
            if (!fresh.querySelector('#materialsTable tbody')) {
                return false;
            }
            replaceFromDocument(fresh, '#materialsTable tbody');
            replaceFromDocument(fresh, '#materialNames-data');
            replaceFromDocument(fresh, '#cm-refined');
            readMaterialNames();
            const filterInput = /** @type {HTMLInputElement | null} */ (
                document.getElementById('filterMaterials')
            );
            if (filterInput && filterInput.value) {
                filterTable('materialsTable', filterInput.value);
            }
            const table = document.getElementById('materialsTable');
            document.dispatchEvent(
                new CustomEvent('krt:swapped', { detail: { container: table } }),
            );
            return true;
        })
        .catch(function (error) {
            console.warn('admin-materials: table refresh failed', error);
            return false;
        });
}

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'materials-open-create-modal', openCreateMaterialModal);
    window.krtEvents.on('click', 'materials-close-create-modal', closeCreateMaterialModal);
    window.krtEvents.on('click', 'materials-submit-create', function (btn) {
        submitCreateMaterial(btn);
    });
}

document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
        const overlay = document.getElementById('modal-create-material');
        if (overlay && overlay.classList.contains('krtm-modal-open')) {
            closeCreateMaterialModal();
        }
    }
});
const overlay = document.getElementById('modal-create-material');
if (overlay) {
    overlay.addEventListener('click', function (e) {
        if (e.target === overlay) closeCreateMaterialModal();
    });
}
