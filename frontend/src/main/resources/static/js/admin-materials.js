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
 * Admin material-catalog page module (/admin/materials), extracted verbatim from the former inline
 * script of admin/materials.html (ADR-0069, follow-up to #924).
 *
 * On DOMContentLoaded wires the material-name filter autocomplete and the in-place category
 * management (delegated create + krtFetch.write, KRT-confirm + AJAX delete, live <option> and
 * placeholder-row maintenance across every category <select>). filterTable stays a global for the
 * shared filter-table common-handler; per-row updateMaterial PUTs a single field and refreshes the
 * row version; the create-material modal open/close/submit posts MaterialCreateAjaxRequest and
 * reloads on success.
 *
 * The CAT_MSG / CAT_CONFLICT dicts, the MSG_UPDATE_* and MSG_CREATE_* toast strings and the
 * krtAutocomplete helper are provided by (respectively) the inline Thymeleaf bootstrap block of
 * admin/materials.html and the autocomplete.js module, both loaded before this classic script.
 */

/* global CAT_MSG, CAT_CONFLICT, MSG_UPDATE_SUCCESS, MSG_UPDATE_ERROR, MSG_CREATE_SUCCESS, MSG_CREATE_ERROR, krtAutocomplete */

document.addEventListener('DOMContentLoaded', function () {
    // Material names come from the sibling <datalist id="materialNames-data">
    // rather than a Thymeleaf JS-inline expression; see the HTML comment on
    // the datalist for the Thymeleaf 3.1 truncation bug. th:inline=
    // "javascript" stays on the inline bootstrap block because the toast
    // translation keys (notification.success.save / notification.error.save)
    // still rely on it — those are single primitives, which Thymeleaf's
    // inline parser handles correctly.
    const dataList = document.getElementById('materialNames-data');
    const materialNames = dataList
        ? Array.from(dataList.options).map(function (o) {
              return o.value;
          })
        : [];
    const inpMaterials = document.getElementById('filterMaterials');
    if (inpMaterials) krtAutocomplete(inpMaterials, materialNames);

    // Every category <select> (the per-row CATEGORY dropdowns + the create-modal select) must
    // gain/lose an <option> when a category is added/removed so the page never needs a reload.
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

    // Builds a category-management row (name + delete form) via the DOM API. The trash SVG is a
    // constant markup string (no user data), so the innerHTML is not an injection sink; the
    // category name is set via textContent. The new form is picked up by the delegated handler.
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

    // Rebuilds the "no entries" placeholder row removed by the first create, so deleting the
    // last category restores it instead of leaving a header over an empty body.
    function buildEmptyCategoryRow() {
        const tr = document.createElement('tr');
        tr.setAttribute('data-category-empty', '');
        const td = document.createElement('td');
        td.colSpan = 2;
        td.textContent = CAT_MSG.noEntries;
        tr.appendChild(td);
        return tr;
    }

    // Delegated category create: intercept the form so the new category appears in place (row +
    // every dropdown option). Native submit is the no-JS fallback.
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
            payload: { name: name },
            successMessage: CAT_MSG.createSuccess,
            errorMessage: CAT_MSG.createError,
            conflict: CAT_CONFLICT,
            onSuccess: function (created) {
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

    // Delegated KRT-styled confirm + AJAX delete for data-krt-confirm forms (replaces the former
    // per-row forEach so rows added after load work too; honours the no-native-dialogs rule).
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
                    onSuccess: function () {
                        removeCategoryOption(categoryId);
                        const row = form.closest('tr');
                        const tbody = row ? row.parentElement : null;
                        if (row) {
                            row.remove();
                        }
                        // Restore the placeholder when the last category is gone (the old full
                        // reload re-rendered this server-side empty-state row).
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
        const td = tr[i].getElementsByTagName('td')[0]; // Name is in the first column
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

function updateMaterial(selectElement) {
    const tr = selectElement.closest('tr');
    const matId = tr.getAttribute('data-mat-id');
    const version = tr.getAttribute('data-version');
    const updateType = selectElement.getAttribute('data-update-type');

    selectElement.disabled = true;

    let requestBody = {
        updateType: updateType,
        version: parseInt(version),
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

    const headers = window.krtCsrf
        ? window.krtCsrf.headers()
        : { 'Content-Type': 'application/json', Accept: 'application/json' };

    fetch(`/admin/materials/${matId}/ajax`, {
        method: 'PUT',
        headers: headers,
        body: JSON.stringify(requestBody),
    })
        .then((response) => {
            if (!response.ok) {
                throw new Error('Update failed with status ' + response.status);
            }
            return response.json();
        })
        .then((updatedMaterial) => {
            tr.setAttribute('data-version', updatedMaterial.version);
            if (typeof window.showFrontendSuccessToast === 'function') {
                window.showFrontendSuccessToast(MSG_UPDATE_SUCCESS);
            }
        })
        .catch((error) => {
            console.error('Error updating material:', error);
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(MSG_UPDATE_ERROR);
            }
        })
        .finally(() => {
            selectElement.disabled = false;
        });
}

// CSP-safe delegated binding (replaces onchange="updateMaterial(this)" inline handlers
// on all the per-row checkboxes / selects). The page-local filterTable function picked
// up by the global filter-table common-handler stays unchanged.
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('change', 'admin-materials-update', function (el) {
        updateMaterial(el);
    });
}

// ----------------------------------------------------------------------
// Create-Material modal: open, close, submit. The form mirrors the
// backend MaterialCreateAjaxRequest; the server stamps isManualEntry=true
// so this dialog does not expose that flag. On success we reload to pick
// up the new row plus its "Manuell" badge.
// ----------------------------------------------------------------------
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
    document.getElementById('modal-create-material').classList.add('active');
    // Focus the first input so keyboard users can type immediately
    setTimeout(function () {
        document.getElementById('cm-name').focus();
    }, 50);
}
function closeCreateMaterialModal() {
    document.getElementById('modal-create-material').classList.remove('active');
}
function submitCreateMaterial(btn) {
    const name = document.getElementById('cm-name').value.trim();
    if (!name) {
        document.getElementById('cm-name').focus();
        return;
    }
    const payload = {
        name: name,
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

    const headers = window.krtCsrf
        ? window.krtCsrf.headers()
        : { 'Content-Type': 'application/json', Accept: 'application/json' };

    if (btn) btn.disabled = true;
    fetch('/admin/materials/ajax', {
        method: 'POST',
        headers: headers,
        body: JSON.stringify(payload),
    })
        .then((response) => {
            if (!response.ok) {
                throw new Error('create failed with status ' + response.status);
            }
            return response.json();
        })
        .then(() => {
            if (typeof window.showFrontendSuccessToast === 'function') {
                window.showFrontendSuccessToast(MSG_CREATE_SUCCESS);
            }
            closeCreateMaterialModal();
            setTimeout(function () {
                window.location.reload();
            }, 400);
        })
        .catch((error) => {
            console.error('Error creating material:', error);
            if (typeof window.showFrontendErrorToast === 'function') {
                window.showFrontendErrorToast(MSG_CREATE_ERROR);
            }
        })
        .finally(() => {
            if (btn) btn.disabled = false;
        });
}

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'materials-open-create-modal', openCreateMaterialModal);
    window.krtEvents.on('click', 'materials-close-create-modal', closeCreateMaterialModal);
    window.krtEvents.on('click', 'materials-submit-create', function (btn) {
        submitCreateMaterial(btn);
    });
}

// ESC closes the create modal; click on the overlay (outside the box) closes too.
document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') {
        const overlay = document.getElementById('modal-create-material');
        if (overlay && overlay.classList.contains('active')) {
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
