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

document.addEventListener('DOMContentLoaded', () => {
    const modal = document.getElementById('ship-modal');
    const closeBtn = document.querySelector('.close-ship-modal');
    const form = document.getElementById('ship-form');
    const modalTitle = document.getElementById('modal-title');
    const filterForm = document.getElementById('hangar-filter-form');

    function currentListUrl() {
        const query = new URLSearchParams(window.location.search).toString();
        return `/hangar${query ? `?${query}` : ''}`;
    }

    function reswapHangar() {
        if (window.krtFetch) {
            window.krtFetch.swap({
                url: currentListUrl(),
                container: '#hangar-results',
                history: false,
            });
        }
    }

    const HANGAR_SECTIONS = { ships: { container: '#hangar-results' } };
    const hangarResults = document.getElementById('hangar-results');
    const hangarTopic = hangarResults ? hangarResults.dataset.liveSyncTopic || '' : '';

    function afterHangarWrite() {
        reswapHangar();
        if (
            hangarTopic &&
            window.krtLiveSync &&
            typeof window.krtLiveSync.sendChanged === 'function'
        ) {
            window.krtLiveSync.sendChanged(hangarTopic, Object.keys(HANGAR_SECTIONS));
        }
    }

    if (
        hangarTopic &&
        window.krtLiveSync &&
        typeof window.krtLiveSync.createReceiver === 'function'
    ) {
        window.krtLiveSync.createReceiver({
            topic: hangarTopic,
            sections: HANGAR_SECTIONS,
            coalesceMs: 1500,
            refresh() {
                reswapHangar();
            },
        });
    }

    /**
     * Copies the swapped list's totals into everything outside the list that shows them: the tab
     * count, the readiness counter, the home-location ship count and the enabled state of the two
     * hangar-wide menu entries.
     */
    function syncHangarStats() {
        const stats = document.getElementById('hangar-stats');
        if (!stats) return;
        const totalRaw = stats.getAttribute('data-total');
        const fittedRaw = stats.getAttribute('data-fitted');
        const total = totalRaw === null || totalRaw === '' ? null : Number(totalRaw);
        const fitted = fittedRaw === null || fittedRaw === '' ? null : Number(fittedRaw);

        const tabCount = document.getElementById('hangar-tab-count-mine');
        if (tabCount && total !== null) {
            tabCount.textContent = String(total);
        }

        const summary = document.getElementById('hangar-fit-summary');
        if (summary) {
            if (total === null || fitted === null || total === 0) {
                summary.hidden = true;
            } else {
                summary.textContent = String(hangarI18n.fitSummaryTemplate || '')
                    .replace('{0}', String(fitted))
                    .replace('{1}', String(total));
                summary.hidden = false;
            }
        }

        const count = total === null ? 0 : total;
        ['set-home-location-btn', 'delete-all-ships-btn'].forEach((id) => {
            const item = document.getElementById(id);
            if (!item) return;
            const empty = count === 0;
            item.disabled = empty;
            if (empty) {
                item.title = item.getAttribute('data-empty-title') || '';
            } else {
                item.removeAttribute('title');
            }
            if (id === 'set-home-location-btn') {
                item.setAttribute('data-ship-count', String(count));
            }
        });
    }

    document.addEventListener('krt:swapped', (e) => {
        const container = e && e.detail ? e.detail.container : null;
        if (container && container.id === 'hangar-results') {
            syncHangarStats();
        }
    });

    const insuranceValue = document.getElementById('ship-insurance');
    const insuranceMonths = document.getElementById('ship-insurance-months');
    const insuranceMonthsRow = document.getElementById('ship-insurance-months-row');
    const insuranceKinds = Array.from(form.querySelectorAll('input[name="insuranceKind"]'));
    if (insuranceKinds.length) {
        insuranceKinds[0].required = true;
    }

    /**
     * Writes the stored insurance value (0, LTI or a month count) derived from the segmented
     * control and the month field into the hidden form field, and shows the month field only
     * for "Monate".
     *
     * @returns {string} the stored value, or an empty string while it is incomplete
     */
    function syncInsurance() {
        const checked = insuranceKinds.find((radio) => radio.checked);
        const kind = checked ? checked.value : '';
        const months = parseInt(insuranceMonths ? insuranceMonths.value : '', 10);
        let value = '';
        if (kind === 'NONE') {
            value = '0';
        } else if (kind === 'LTI') {
            value = 'LTI';
        } else if (kind === 'MONTHS' && !Number.isNaN(months)) {
            value = String(months);
        }
        if (insuranceMonthsRow) insuranceMonthsRow.hidden = kind !== 'MONTHS';
        if (insuranceMonths) insuranceMonths.required = kind === 'MONTHS';
        if (insuranceValue) insuranceValue.value = value;
        return value;
    }

    /**
     * Preselects the segmented control and the month field from a stored insurance value.
     *
     * @param {string | null} stored the stored value: 0, LTI, a month count, or empty for none
     */
    function setInsurance(stored) {
        const value = stored == null ? '' : String(stored).trim();
        let kind = 'MONTHS';
        if (value === '') {
            kind = '';
        } else if (value === '0') {
            kind = 'NONE';
        } else if (value.toUpperCase() === 'LTI') {
            kind = 'LTI';
        }
        insuranceKinds.forEach((radio) => {
            radio.checked = radio.value === kind;
        });
        if (insuranceMonths) insuranceMonths.value = kind === 'MONTHS' ? value : '';
        syncInsurance();
    }

    insuranceKinds.forEach((radio) => {
        radio.addEventListener('change', () => {
            syncInsurance();
            if (radio.checked && radio.value === 'MONTHS' && insuranceMonths) {
                insuranceMonths.focus();
            }
        });
    });
    if (insuranceMonths) insuranceMonths.addEventListener('input', syncInsurance);

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
        setInsurance('');
        document.getElementById('ship-location').value = '';
        document.getElementById('ship-fitted').checked = false;
        document.getElementById('ship-version').value = '';
        modalDeleteBtn.hidden = true;
        openModal();
    }

    function openEditModal(btn) {
        modalTitle.textContent = form.getAttribute('data-title-edit');
        form.action = window.safeSameOriginUrl(btn.getAttribute('data-action'), form.action);
        document.getElementById('ship-name').value = btn.getAttribute('data-name');
        document.getElementById('ship-type').value = btn.getAttribute('data-type');
        setInsurance(btn.getAttribute('data-ins'));
        document.getElementById('ship-location').value = btn.getAttribute('data-loc') || '';
        document.getElementById('ship-fitted').checked = btn.getAttribute('data-fitted') === 'true';
        document.getElementById('ship-version').value = btn.getAttribute('data-version');
        const action = btn.getAttribute('data-action');
        modalDeleteBtn.setAttribute('data-action', action.replace('/update', '/delete'));
        modalDeleteBtn.hidden = false;
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
        deleteForm.addEventListener('submit', (e) => {
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
                        afterHangarWrite();
                    },
                })
                .then(() => {
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
        const resultModal = document.getElementById('import-result-modal');
        const closeResultBtn = document.getElementById('import-result-close');
        const closeResultX = document.getElementById('import-result-close-x');

        function showError(msg) {
            if (window.showFrontendErrorToast) {
                window.showFrontendErrorToast(msg);
            }
        }

        function fillParagraphs(list, names) {
            list.replaceChildren(
                ...names.map((n) => {
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
            const hasSkipped = !!(data.skippedShips && data.skippedShips.length > 0);
            if (hasSkipped) {
                fillParagraphs(skipList, data.skippedShips);
            }
            skipSection.hidden = !hasSkipped;

            const dupSection = document.getElementById('import-dup-section');
            const dupList = document.getElementById('import-dup-list');
            const hasDuplicates = !!(data.duplicateShips && data.duplicateShips.length > 0);
            if (hasDuplicates) {
                fillParagraphs(dupList, data.duplicateShips);
            }
            dupSection.hidden = !hasDuplicates;

            window.krtModal.open(resultModal);
        }

        [closeResultBtn, closeResultX].forEach((btn) => {
            if (!btn) return;
            btn.addEventListener('click', () => {
                window.krtModal.close(resultModal);
                if (document.getElementById('import-res-imported').textContent !== '0') {
                    afterHangarWrite();
                }
            });
        });

        window.addEventListener('click', (e) => {
            if (e.target === resultModal) {
                window.krtModal.close(resultModal);
            }
        });

        function upload(file) {
            const maxBytes = Number(importBtn.getAttribute('data-max-bytes'));
            if (maxBytes > 0 && file.size > maxBytes) {
                showError(importBtn.getAttribute('data-error-toolarge'));
                return;
            }
            if (!window.krtFetch) {
                return;
            }
            const formData = new FormData();
            formData.append('file', file);

            function showFailure(detail) {
                showError(
                    importBtn.getAttribute('data-error-failed') + (detail ? ` (${detail})` : ''),
                );
            }

            importBtn.setAttribute('aria-busy', 'true');
            window.krtFetch
                .submitForm({
                    url: '/hangar/import/ships',
                    method: 'POST',
                    formData,
                    submitter: importBtn,
                    toast: false,
                    onSuccess(data) {
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
                })
                .then(() => {
                    importBtn.removeAttribute('aria-busy');
                    fileInput.value = '';
                });
        }

        if (importBtn && fileInput) {
            importBtn.addEventListener('click', () => {
                fileInput.click();
            });
            fileInput.addEventListener('change', () => {
                const file = fileInput.files && fileInput.files[0];
                if (file) {
                    upload(file);
                }
            });
        }
    })();

    (function () {
        const deleteAllBtn = document.getElementById('delete-all-ships-btn');
        const deleteAllModal = document.getElementById('delete-all-confirm-modal');
        const deleteAllConfirmBtn = document.getElementById('delete-all-confirm-btn');
        const deleteAllCancelBtn = document.getElementById('delete-all-cancel-btn');

        if (!deleteAllBtn || !deleteAllModal) return;

        deleteAllBtn.addEventListener('click', () => {
            if (deleteAllBtn.disabled) return;
            window.krtModal.open(deleteAllModal);
        });

        deleteAllCancelBtn.addEventListener('click', () => {
            window.krtModal.close(deleteAllModal);
        });

        window.addEventListener('click', (e) => {
            if (e.target === deleteAllModal) {
                window.krtModal.close(deleteAllModal);
            }
        });

        deleteAllConfirmBtn.addEventListener('click', () => {
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
                        afterHangarWrite();
                        if (window.showFrontendSuccessToast) {
                            window.showFrontendSuccessToast(
                                deleteAllBtn.getAttribute('data-success'),
                            );
                        }
                    },
                })
                .then(() => {
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
            window.krtEvents.on('click', 'hangar-open-home', (btn) => {
                if (btn && btn.disabled) return;
                refreshHomeBody();
                window.krtModal.open(homeModal);
            });
        }

        homeModal.querySelectorAll('.close-home-cancel, .close-modal-home').forEach((el) => {
            el.addEventListener('click', closeHome);
        });
        window.addEventListener('click', (e) => {
            if (e.target === homeModal) {
                closeHome();
            }
        });

        const homeForm = document.getElementById('home-location-form');
        if (homeForm) {
            homeForm.addEventListener('submit', (e) => {
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
                            afterHangarWrite();
                        },
                    })
                    .then(() => {
                        if (submitBtn) submitBtn.disabled = false;
                    });
            });
        }
    })();

    form.addEventListener('submit', (e) => {
        e.preventDefault();
        const action = form.getAttribute('action') || form.action;
        const isUpdate = /\/update$/.test(action);
        const versionRaw = document.getElementById('ship-version').value;
        const ownerSel = form.querySelector('[name="owningOrgUnitId"]');
        const payload = {
            name: document.getElementById('ship-name').value || null,
            shipTypeId: document.getElementById('ship-type').value || null,
            insurance: syncInsurance() || null,
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
                    afterHangarWrite();
                },
            })
            .then(() => {
                if (submitBtn) submitBtn.disabled = false;
            });
    });

    const manufacturerSelect = document.getElementById('ship-manufacturer');
    const shipTypeSelect = document.getElementById('ship-type');
    const shipTypeAllOptions = Array.from(shipTypeSelect.options);

    manufacturerSelect.addEventListener('change', function () {
        const selectedManufacturer = this.value;

        const defaultOption = shipTypeAllOptions[0];

        shipTypeSelect.replaceChildren();
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

    function filterUrl() {
        const params = new URLSearchParams();
        if (filterForm) {
            const data = new FormData(filterForm);
            for (const [key, value] of data.entries()) {
                if (value !== '') {
                    params.append(key, value);
                }
            }
        }
        const query = params.toString();
        return `/hangar${query ? `?${query}` : ''}`;
    }

    function applyHangarFilter(url) {
        const target = url || filterUrl();
        if (!hangarResults || !window.krtFetch) {
            window.location.assign(target);
            return;
        }
        window.krtFetch.swap({ url: target, container: hangarResults, history: true });
    }

    if (filterForm) {
        filterForm.addEventListener('input', (e) => {
            if (!e.target || e.target.id !== 'hangar-ship-filter') {
                return;
            }
            clearTimeout(hangarFilterTimer);
            hangarFilterTimer = setTimeout(() => {
                applyHangarFilter();
            }, 300);
        });

        filterForm.addEventListener('change', (e) => {
            if (!e.target || e.target.name !== 'fitted') {
                return;
            }
            clearTimeout(hangarFilterTimer);
            applyHangarFilter();
        });

        filterForm.addEventListener('submit', (e) => {
            e.preventDefault();
            clearTimeout(hangarFilterTimer);
            applyHangarFilter();
        });
    }

    if (hangarResults) {
        hangarResults.addEventListener('click', (e) => {
            const clear = e.target.closest
                ? e.target.closest('[data-testid="empty-state-action"]')
                : null;
            if (!clear || !hangarResults.contains(clear)) {
                return;
            }
            e.preventDefault();
            const input = document.getElementById('hangar-ship-filter');
            if (input) {
                input.value = '';
            }
            if (filterForm) {
                filterForm.querySelectorAll('input[name="fitted"]').forEach((radio) => {
                    radio.checked = radio.value === '';
                });
            }
            clearTimeout(hangarFilterTimer);
            applyHangarFilter(clear.getAttribute('href'));
        });
    }

    if (window.krtFetch && typeof window.krtFetch.bindSwap === 'function') {
        window.krtFetch.bindSwap({ container: '#hangar-results', history: true });
    }
});
