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

/* global hangarI18n, hangarConflict */

document.addEventListener('DOMContentLoaded', function () {
    const modal = document.getElementById('ship-modal');
    const closeBtn = document.querySelector('.close-ship-modal');
    const form = document.getElementById('ship-form');
    const modalTitle = document.getElementById('modal-title');

    function reswapHangar() {
        if (window.krtFetch) {
            const query = new URLSearchParams(window.location.search).toString();
            window.krtFetch.swap({
                url: '/hangar?' + query,
                container: '#hangar-results',
                history: false,
            });
        }
    }

    function openModal() {
        window.krtModal.open(modal);
    }

    function closeModal() {
        if (typeof window.resetUnsavedChanges === 'function') {
            window.resetUnsavedChanges();
        }
        window.krtModal.close(modal);
    }

    if (closeBtn) closeBtn.addEventListener('click', closeModal);

    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = document.getElementById('delete-confirm-form');
    const closeDeleteBtns = document.querySelectorAll('.close-delete-modal');

    window.addEventListener('click', (e) => {
        if (e.target === modal) closeModal();
        if (deleteModal && e.target === deleteModal) window.krtModal.close(deleteModal);
    });

    const modalDeleteBtn = document.getElementById('modal-delete-btn');

    function openAddModal(btn) {
        modalTitle.textContent = form.getAttribute('data-title-add');
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        document.getElementById('ship-name').value = '';
        document.getElementById('ship-type').value = '';
        document.getElementById('ship-insurance').value = '';
        document.getElementById('ship-location').value = '';
        document.getElementById('ship-fitted').checked = false;
        document.getElementById('ship-version').value = '';
        modalDeleteBtn.style.display = 'none';
        openModal();
    }

    function openEditModal(btn) {
        modalTitle.textContent = form.getAttribute('data-title-edit');
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        document.getElementById('ship-name').value = btn.getAttribute('data-name');
        document.getElementById('ship-type').value = btn.getAttribute('data-type');
        document.getElementById('ship-insurance').value = btn.getAttribute('data-ins');
        document.getElementById('ship-location').value = btn.getAttribute('data-loc') || '';
        document.getElementById('ship-fitted').checked = btn.getAttribute('data-fitted') === 'true';
        document.getElementById('ship-version').value = btn.getAttribute('data-version');
        const action = btn.getAttribute('data-action');
        modalDeleteBtn.setAttribute('data-action', action.replace('/update', '/delete'));
        modalDeleteBtn.style.display = 'block';
        openModal();
    }

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'hangar-add-ship', openAddModal);
        window.krtEvents.on('click', 'hangar-edit-ship', openEditModal);
    }

    modalDeleteBtn.addEventListener('click', () => {
        deleteForm.action = window.safeSameOriginUrl(
            modalDeleteBtn.getAttribute('data-action'),
            deleteForm.action,
        );
        window.krtModal.open(deleteModal);
    });

    if (deleteModal) {
        closeDeleteBtns.forEach((btn) => {
            btn.addEventListener('click', () => {
                window.krtModal.close(deleteModal);
            });
        });
    }

    if (deleteForm) {
        deleteForm.addEventListener('submit', function (e) {
            e.preventDefault();
            const submitBtn = deleteForm.querySelector('button[type="submit"]');
            if (submitBtn) submitBtn.disabled = true;
            window.krtFetch
                .write({
                    method: 'POST',
                    url: window.safeSameOriginUrl(
                        deleteForm.getAttribute('action'),
                        deleteForm.action,
                    ),
                    successMessage: hangarI18n.deleteSuccess,
                    errorMessage: hangarI18n.deleteError,
                    conflict: hangarConflict,
                    onSuccess() {
                        if (deleteModal) window.krtModal.close(deleteModal);
                        closeModal();
                        reswapHangar();
                    },
                })
                .then(function () {
                    if (submitBtn) submitBtn.disabled = false;
                });
        });
    }

    const showModal = document.body.getAttribute('data-show-modal') === 'true';
    if (showModal) {
        openModal();
    }

    (function () {
        const importBtn = document.getElementById('fleetview-import-btn');
        const fileInput = document.getElementById('fleetview-file');
        const statusEl = document.getElementById('fleetview-status');
        const resultModal = document.getElementById('import-result-modal');
        const closeResultBtn = document.getElementById('import-result-close');
        const closeResultX = document.getElementById('import-result-close-x');

        function showStatus(msg, color) {
            statusEl.textContent = msg;
            statusEl.style.color = color || 'var(--color-primary)';
            statusEl.style.display = 'block';
        }

        function hideStatus() {
            statusEl.style.display = 'none';
        }

        function fillParagraphs(list, names) {
            list.replaceChildren(
                ...names.map(function (n) {
                    const p = document.createElement('p');
                    p.textContent = n == null ? '' : String(n);
                    return p;
                }),
            );
        }

        function openResultModal(data) {
            document.getElementById('import-res-imported').textContent = data.importedCount;
            document.getElementById('import-res-skipped').textContent = data.skippedCount;
            document.getElementById('import-res-duplicates').textContent = data.duplicateCount;

            const skipSection = document.getElementById('import-skip-section');
            const skipList = document.getElementById('import-skip-list');
            if (data.skippedShips && data.skippedShips.length > 0) {
                fillParagraphs(skipList, data.skippedShips);
                skipSection.style.display = 'block';
            } else {
                skipSection.style.display = 'none';
            }

            const dupSection = document.getElementById('import-dup-section');
            const dupList = document.getElementById('import-dup-list');
            if (data.duplicateShips && data.duplicateShips.length > 0) {
                fillParagraphs(dupList, data.duplicateShips);
                dupSection.style.display = 'block';
            } else {
                dupSection.style.display = 'none';
            }

            window.krtModal.open(resultModal);
        }

        [closeResultBtn, closeResultX].forEach(function (btn) {
            if (!btn) return;
            btn.addEventListener('click', function () {
                window.krtModal.close(resultModal);
                if (document.getElementById('import-res-imported').textContent !== '0') {
                    reswapHangar();
                }
            });
        });

        window.addEventListener('click', function (e) {
            if (e.target === resultModal) {
                window.krtModal.close(resultModal);
            }
        });

        if (importBtn) {
            importBtn.addEventListener('click', function () {
                const file = fileInput.files[0];
                if (!file) {
                    showStatus(importBtn.getAttribute('data-error-nofile'), 'var(--color-danger)');
                    return;
                }
                const maxBytes = Number(importBtn.getAttribute('data-max-bytes'));
                if (maxBytes > 0 && file.size > maxBytes) {
                    showStatus(
                        importBtn.getAttribute('data-error-toolarge'),
                        'var(--color-danger)',
                    );
                    return;
                }
                if (!window.krtFetch) {
                    return;
                }
                const formData = new FormData();
                formData.append('file', file);

                showStatus(importBtn.getAttribute('data-uploading'));

                function showFailure(detail) {
                    showStatus(
                        importBtn.getAttribute('data-error-failed') +
                            (detail ? ' (' + detail + ')' : ''),
                        'var(--color-danger)',
                    );
                }

                window.krtFetch.submitForm({
                    url: '/hangar/import/ships',
                    method: 'POST',
                    formData,
                    submitter: importBtn,
                    toast: false,
                    onSuccess(data) {
                        hideStatus();
                        fileInput.value = '';
                        openResultModal(data);
                    },
                    onError(status, body) {
                        showFailure(body && body.detail ? body.detail : String(status));
                        return true;
                    },
                    onNetworkError() {
                        showFailure('');
                        return true;
                    },
                });
            });
        }
    })();

    (function () {
        const deleteAllBtn = document.getElementById('delete-all-ships-btn');
        const deleteAllModal = document.getElementById('delete-all-confirm-modal');
        const deleteAllConfirmBtn = document.getElementById('delete-all-confirm-btn');
        const deleteAllCancelBtn = document.getElementById('delete-all-cancel-btn');

        if (!deleteAllBtn || !deleteAllModal) return;

        deleteAllBtn.addEventListener('click', function () {
            window.krtModal.open(deleteAllModal);
        });

        deleteAllCancelBtn.addEventListener('click', function () {
            window.krtModal.close(deleteAllModal);
        });

        window.addEventListener('click', function (e) {
            if (e.target === deleteAllModal) {
                window.krtModal.close(deleteAllModal);
            }
        });

        deleteAllConfirmBtn.addEventListener('click', function () {
            deleteAllConfirmBtn.disabled = true;
            deleteAllCancelBtn.disabled = true;

            window.krtFetch
                .write({
                    method: 'DELETE',
                    url: '/hangar/ships/all',
                    toast: false,
                    errorMessage: deleteAllBtn.getAttribute('data-error-failed'),
                    conflict: hangarConflict,
                    onSuccess() {
                        window.krtModal.close(deleteAllModal);
                        reswapHangar();
                        if (window.showFrontendSuccessToast) {
                            window.showFrontendSuccessToast(
                                deleteAllBtn.getAttribute('data-success'),
                            );
                        }
                    },
                })
                .then(function () {
                    deleteAllConfirmBtn.disabled = false;
                    deleteAllCancelBtn.disabled = false;
                });
        });
    })();

    (function () {
        const homeModal = document.getElementById('home-location-modal');
        if (!homeModal) return;

        function closeHome() {
            window.krtModal.close(homeModal);
        }

        function refreshHomeBody() {
            const body = document.getElementById('home-location-body');
            const trigger = document.querySelector('[data-trigger="hangar-open-home"]');
            if (body && trigger && hangarI18n.homeBodyTemplate) {
                const count = trigger.getAttribute('data-ship-count') || '';
                body.textContent = hangarI18n.homeBodyTemplate.replace('{0}', count);
            }
        }

        if (window.krtEvents && typeof window.krtEvents.on === 'function') {
            window.krtEvents.on('click', 'hangar-open-home', function () {
                refreshHomeBody();
                window.krtModal.open(homeModal);
            });
        }

        homeModal.querySelectorAll('.close-home-cancel, .close-modal-home').forEach(function (el) {
            el.addEventListener('click', closeHome);
        });
        window.addEventListener('click', function (e) {
            if (e.target === homeModal) {
                closeHome();
            }
        });

        const homeForm = document.getElementById('home-location-form');
        if (homeForm) {
            homeForm.addEventListener('submit', function (e) {
                e.preventDefault();
                const submitBtn = homeForm.querySelector('button[type="submit"]');
                const select = homeForm.querySelector('select[name="locationId"]');
                const locationId = select ? select.value : '';
                if (!locationId) {
                    return;
                }
                if (submitBtn) submitBtn.disabled = true;
                window.krtFetch
                    .write({
                        method: 'POST',
                        url: window.safeSameOriginUrl(
                            homeForm.getAttribute('action'),
                            homeForm.action,
                        ),
                        payload: { locationId },
                        successMessage: hangarI18n.homeSuccess,
                        errorMessage: hangarI18n.homeError,
                        conflict: hangarConflict,
                        onSuccess() {
                            closeHome();
                            reswapHangar();
                        },
                    })
                    .then(function () {
                        if (submitBtn) submitBtn.disabled = false;
                    });
            });
        }
    })();

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        const action = form.getAttribute('action') || form.action;
        const isUpdate = /\/update$/.test(action);
        const versionRaw = document.getElementById('ship-version').value;
        const ownerSel = form.querySelector('[name="owningOrgUnitId"]');
        const payload = {
            name: document.getElementById('ship-name').value || null,
            shipTypeId: document.getElementById('ship-type').value || null,
            insurance: document.getElementById('ship-insurance').value || null,
            locationId: document.getElementById('ship-location').value || null,
            fitted: document.getElementById('ship-fitted').checked,
            version: isUpdate && versionRaw ? Number(versionRaw) : null,
            owningOrgUnitId: isUpdate ? null : ownerSel && ownerSel.value ? ownerSel.value : null,
        };
        const submitBtn = form.querySelector('button[type="submit"]');
        if (submitBtn) submitBtn.disabled = true;
        window.krtFetch
            .write({
                method: 'POST',
                url: window.safeSameOriginUrl(action, action),
                payload,
                successMessage: isUpdate ? hangarI18n.updateSuccess : hangarI18n.addSuccess,
                errorMessage: isUpdate ? hangarI18n.updateError : hangarI18n.addError,
                conflict: hangarConflict,
                onSuccess() {
                    closeModal();
                    reswapHangar();
                },
            })
            .then(function () {
                if (submitBtn) submitBtn.disabled = false;
            });
    });

    const manufacturerSelect = document.getElementById('ship-manufacturer');
    const shipTypeSelect = document.getElementById('ship-type');
    const shipTypeAllOptions = Array.from(shipTypeSelect.options);

    manufacturerSelect.addEventListener('change', function () {
        const selectedManufacturer = this.value;

        const defaultOption = shipTypeAllOptions[0];

        shipTypeSelect.innerHTML = '';
        shipTypeSelect.appendChild(defaultOption);

        shipTypeAllOptions.slice(1).forEach((option) => {
            if (
                !selectedManufacturer ||
                option.getAttribute('data-manufacturer-id') === selectedManufacturer
            ) {
                shipTypeSelect.appendChild(option);
            }
        });
    });

    let hangarFilterTimer = null;

    function applyHangarFilter(url) {
        const resultsContainer = document.getElementById('hangar-results');
        const form = document.getElementById('hangar-filter-form');
        if (!resultsContainer || !window.krtFetch) {
            if (url) {
                window.location.assign(url);
            } else if (form) {
                form.submit();
            }
            return Promise.resolve(false);
        }
        let target = url;
        if (!target) {
            const params = new URLSearchParams();
            if (form) {
                const data = new FormData(form);
                for (const [key, value] of data.entries()) {
                    if (value !== '') {
                        params.append(key, value);
                    }
                }
            }
            const query = params.toString();
            target = '/hangar' + (query ? '?' + query : '');
        }
        return window.krtFetch
            .swap({ url: target, container: resultsContainer, history: true })
            .then(function (ok) {
                const input = document.getElementById('hangar-ship-filter');
                if (input) {
                    input.focus();
                    const v = input.value;
                    try {
                        input.setSelectionRange(v.length, v.length);
                    } catch (_ignored) {}
                }
                return ok;
            });
    }

    document.addEventListener('input', function (e) {
        if (!e.target || e.target.id !== 'hangar-ship-filter') {
            return;
        }
        clearTimeout(hangarFilterTimer);
        hangarFilterTimer = setTimeout(function () {
            applyHangarFilter();
        }, 300);
    });

    document.addEventListener('submit', function (e) {
        if (!e.target || e.target.id !== 'hangar-filter-form') {
            return;
        }
        e.preventDefault();
        clearTimeout(hangarFilterTimer);
        applyHangarFilter();
    });

    document.addEventListener('click', function (e) {
        const clear = e.target.closest ? e.target.closest('#hangar-filter-clear') : null;
        if (!clear) {
            return;
        }
        e.preventDefault();
        const input = document.getElementById('hangar-ship-filter');
        if (input) {
            input.value = '';
        }
        applyHangarFilter(clear.getAttribute('href'));
    });

    if (window.krtFetch && typeof window.krtFetch.bindSwap === 'function') {
        window.krtFetch.bindSwap({ container: '#hangar-results', history: true });
    }
});
