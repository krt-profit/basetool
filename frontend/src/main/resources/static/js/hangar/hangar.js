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

// @ts-check

/* global hangarI18n, hangarConflict */

import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { assignFormAction, safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { resetUnsavedChanges } from '/js/kernel/unsaved-changes.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';

document.addEventListener('DOMContentLoaded', () => {
    const modal = document.getElementById('ship-modal');
    const closeBtn = document.querySelector('.close-ship-modal');
    const form = /** @type {HTMLFormElement} */ (document.getElementById('ship-form'));
    const modalTitle = /** @type {HTMLElement} */ (document.getElementById('modal-title'));
    const filterForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('hangar-filter-form')
    );

    /**
     * Looks up a control of the ship form by id; the ship form always renders them.
     *
     * @param {string} id the element id
     * @returns {HTMLInputElement} the control
     */
    function shipField(id) {
        return /** @type {HTMLInputElement} */ (document.getElementById(id));
    }

    function currentListUrl() {
        const query = new URLSearchParams(window.location.search).toString();
        return `/hangar${query ? `?${query}` : ''}`;
    }

    function reswapHangar() {
        krtFetch.swap({
            url: currentListUrl(),
            container: '#hangar-results',
            history: false,
        });
    }

    const HANGAR_SECTIONS = { ships: { container: '#hangar-results' } };
    const hangarResults = document.getElementById('hangar-results');
    const hangarTopic = hangarResults ? hangarResults.dataset.liveSyncTopic || '' : '';

    function afterHangarWrite() {
        reswapHangar();
        if (hangarTopic && typeof krtLiveSync.sendChanged === 'function') {
            krtLiveSync.sendChanged(hangarTopic, Object.keys(HANGAR_SECTIONS));
        }
    }

    if (hangarTopic) {
        krtLiveSync.createReceiver({
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
            const item = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
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

    const insuranceValue = /** @type {HTMLInputElement | null} */ (
        document.getElementById('ship-insurance')
    );
    const insuranceMonths = /** @type {HTMLInputElement | null} */ (
        document.getElementById('ship-insurance-months')
    );
    const insuranceMonthsRow = document.getElementById('ship-insurance-months-row');
    const insuranceKinds = Array.from(
        /** @type {NodeListOf<HTMLInputElement>} */ (
            form.querySelectorAll('input[name="insuranceKind"]')
        ),
    );
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
        krtModal.open(modal);
    }

    function closeModal() {
        resetUnsavedChanges();
        krtModal.close(modal);
    }

    if (closeBtn) closeBtn.addEventListener('click', closeModal);

    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('delete-confirm-form')
    );
    const closeDeleteBtns = document.querySelectorAll('.close-delete-modal');

    window.addEventListener('click', (e) => {
        if (e.target === modal) closeModal();
        if (deleteModal && e.target === deleteModal) krtModal.close(deleteModal);
    });

    const modalDeleteBtn = /** @type {HTMLElement} */ (document.getElementById('modal-delete-btn'));

    /**
     * Opens the ship dialog blank, posting to the clicked button's action.
     *
     * @param {HTMLElement} btn the add button
     */
    function openAddModal(btn) {
        modalTitle.textContent = form.getAttribute('data-title-add');
        assignFormAction(form, safeSameOriginUrl(btn.getAttribute('data-action'), form.action));
        shipField('ship-name').value = '';
        shipField('ship-type').value = '';
        setInsurance('');
        shipField('ship-location').value = '';
        shipField('ship-fitted').checked = false;
        shipField('ship-version').value = '';
        modalDeleteBtn.hidden = true;
        openModal();
    }

    /**
     * Opens the ship dialog filled from the clicked button's data attributes.
     *
     * @param {HTMLElement} btn the edit button
     */
    function openEditModal(btn) {
        modalTitle.textContent = form.getAttribute('data-title-edit');
        assignFormAction(form, safeSameOriginUrl(btn.getAttribute('data-action'), form.action));
        shipField('ship-name').value = btn.getAttribute('data-name') ?? '';
        shipField('ship-type').value = btn.getAttribute('data-type') ?? '';
        setInsurance(btn.getAttribute('data-ins'));
        shipField('ship-location').value = btn.getAttribute('data-loc') || '';
        shipField('ship-fitted').checked = btn.getAttribute('data-fitted') === 'true';
        shipField('ship-version').value = btn.getAttribute('data-version') ?? '';
        const action = /** @type {string} */ (btn.getAttribute('data-action'));
        modalDeleteBtn.setAttribute('data-action', action.replace('/update', '/delete'));
        modalDeleteBtn.hidden = false;
        openModal();
    }

    krtEvents.on('click', 'hangar-add-ship', openAddModal);
    krtEvents.on('click', 'hangar-edit-ship', openEditModal);

    modalDeleteBtn.addEventListener('click', () => {
        if (!deleteForm) return;
        assignFormAction(
            deleteForm,
            safeSameOriginUrl(modalDeleteBtn.getAttribute('data-action'), deleteForm.action),
        );
        krtModal.open(deleteModal);
    });

    if (deleteModal) {
        closeDeleteBtns.forEach((btn) => {
            btn.addEventListener('click', () => {
                krtModal.close(deleteModal);
            });
        });
    }

    if (deleteForm) {
        deleteForm.addEventListener('submit', (e) => {
            e.preventDefault();
            const submitBtn = /** @type {HTMLButtonElement | null} */ (
                deleteForm.querySelector('button[type="submit"]')
            );
            if (submitBtn) submitBtn.disabled = true;
            krtFetch
                .write({
                    method: 'POST',
                    url: safeSameOriginUrl(deleteForm.getAttribute('action'), deleteForm.action),
                    successMessage: hangarI18n.deleteSuccess,
                    errorMessage: hangarI18n.deleteError,
                    conflict: hangarConflict,
                    onSuccess() {
                        if (deleteModal) krtModal.close(deleteModal);
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
        const fileInput = /** @type {HTMLInputElement | null} */ (
            document.getElementById('fleetview-file')
        );
        const resultModal = document.getElementById('import-result-modal');
        const closeResultBtn = document.getElementById('import-result-close');
        const closeResultX = document.getElementById('import-result-close-x');

        /**
         * Looks up an element of the import result dialog; the dialog always renders them.
         *
         * @param {string} id the element id
         * @returns {HTMLElement} the element
         */
        function resultElement(id) {
            return /** @type {HTMLElement} */ (document.getElementById(id));
        }

        function showError(msg) {
            showFrontendErrorToast(msg);
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
            resultElement('import-res-imported').textContent = data.importedCount;
            resultElement('import-res-skipped').textContent = data.skippedCount;
            resultElement('import-res-duplicates').textContent = data.duplicateCount;

            const skipSection = resultElement('import-skip-section');
            const skipList = document.getElementById('import-skip-list');
            const hasSkipped = !!(data.skippedShips && data.skippedShips.length > 0);
            if (hasSkipped) {
                fillParagraphs(skipList, data.skippedShips);
            }
            skipSection.hidden = !hasSkipped;

            const dupSection = resultElement('import-dup-section');
            const dupList = document.getElementById('import-dup-list');
            const hasDuplicates = !!(data.duplicateShips && data.duplicateShips.length > 0);
            if (hasDuplicates) {
                fillParagraphs(dupList, data.duplicateShips);
            }
            dupSection.hidden = !hasDuplicates;

            krtModal.open(resultModal);
        }

        [closeResultBtn, closeResultX].forEach((btn) => {
            if (!btn) return;
            btn.addEventListener('click', () => {
                krtModal.close(resultModal);
                if (resultElement('import-res-imported').textContent !== '0') {
                    afterHangarWrite();
                }
            });
        });

        window.addEventListener('click', (e) => {
            if (e.target === resultModal) {
                krtModal.close(resultModal);
            }
        });

        function upload(file) {
            if (!importBtn || !fileInput) return;
            const button = importBtn;
            const input = fileInput;
            const maxBytes = Number(button.getAttribute('data-max-bytes'));
            if (maxBytes > 0 && file.size > maxBytes) {
                showError(button.getAttribute('data-error-toolarge'));
                return;
            }

            const formData = new FormData();
            formData.append('file', file);

            function showFailure(detail) {
                showError(
                    button.getAttribute('data-error-failed') + (detail ? ` (${detail})` : ''),
                );
            }

            button.setAttribute('aria-busy', 'true');
            krtFetch
                .submitForm({
                    url: '/hangar/import/ships',
                    method: 'POST',
                    formData,
                    submitter: button,
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
                    button.removeAttribute('aria-busy');
                    input.value = '';
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
        const deleteAllBtn = /** @type {HTMLButtonElement | null} */ (
            document.getElementById('delete-all-ships-btn')
        );
        const deleteAllModal = document.getElementById('delete-all-confirm-modal');
        const deleteAllConfirmBtn = /** @type {HTMLButtonElement} */ (
            document.getElementById('delete-all-confirm-btn')
        );
        const deleteAllCancelBtn = /** @type {HTMLButtonElement} */ (
            document.getElementById('delete-all-cancel-btn')
        );

        if (!deleteAllBtn || !deleteAllModal) return;

        deleteAllBtn.addEventListener('click', () => {
            if (deleteAllBtn.disabled) return;
            krtModal.open(deleteAllModal);
        });

        deleteAllCancelBtn.addEventListener('click', () => {
            krtModal.close(deleteAllModal);
        });

        window.addEventListener('click', (e) => {
            if (e.target === deleteAllModal) {
                krtModal.close(deleteAllModal);
            }
        });

        deleteAllConfirmBtn.addEventListener('click', () => {
            deleteAllConfirmBtn.disabled = true;
            deleteAllCancelBtn.disabled = true;

            krtFetch
                .write({
                    method: 'DELETE',
                    url: '/hangar/ships/all',
                    toast: false,
                    errorMessage: /** @type {string} */ (
                        deleteAllBtn.getAttribute('data-error-failed')
                    ),
                    conflict: hangarConflict,
                    onSuccess() {
                        krtModal.close(deleteAllModal);
                        afterHangarWrite();
                        showFrontendSuccessToast(
                            /** @type {string} */ (deleteAllBtn.getAttribute('data-success')),
                        );
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
            krtModal.close(homeModal);
        }

        function refreshHomeBody() {
            const body = document.getElementById('home-location-body');
            const trigger = document.querySelector('[data-trigger="hangar-open-home"]');
            if (body && trigger && hangarI18n.homeBodyTemplate) {
                const count = trigger.getAttribute('data-ship-count') || '';
                body.textContent = hangarI18n.homeBodyTemplate.replace('{0}', count);
            }
        }

        krtEvents.on('click', 'hangar-open-home', (btn) => {
            if (btn && /** @type {HTMLButtonElement} */ (btn).disabled) return;
            refreshHomeBody();
            krtModal.open(homeModal);
        });

        homeModal.querySelectorAll('.close-home-cancel, .close-modal-home').forEach((el) => {
            el.addEventListener('click', closeHome);
        });
        window.addEventListener('click', (e) => {
            if (e.target === homeModal) {
                closeHome();
            }
        });

        const homeForm = /** @type {HTMLFormElement | null} */ (
            document.getElementById('home-location-form')
        );
        if (homeForm) {
            homeForm.addEventListener('submit', (e) => {
                e.preventDefault();
                const submitBtn = /** @type {HTMLButtonElement | null} */ (
                    homeForm.querySelector('button[type="submit"]')
                );
                const select = /** @type {HTMLSelectElement | null} */ (
                    homeForm.querySelector('select[name="locationId"]')
                );
                const locationId = select ? select.value : '';
                if (!locationId) {
                    return;
                }
                if (submitBtn) submitBtn.disabled = true;
                krtFetch
                    .write({
                        method: 'POST',
                        url: safeSameOriginUrl(homeForm.getAttribute('action'), homeForm.action),
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
        const versionRaw = shipField('ship-version').value;
        const ownerSel = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
            form.querySelector('[name="owningOrgUnitId"]')
        );
        const payload = {
            name: shipField('ship-name').value || null,
            shipTypeId: shipField('ship-type').value || null,
            insurance: syncInsurance() || null,
            locationId: shipField('ship-location').value || null,
            fitted: shipField('ship-fitted').checked,
            version: isUpdate && versionRaw ? Number(versionRaw) : null,
            owningOrgUnitId: isUpdate ? null : ownerSel && ownerSel.value ? ownerSel.value : null,
        };
        const submitBtn = /** @type {HTMLButtonElement | null} */ (
            form.querySelector('button[type="submit"]')
        );
        if (submitBtn) submitBtn.disabled = true;
        krtFetch
            .write({
                method: 'POST',
                url: safeSameOriginUrl(action, action),
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

    const manufacturerSelect = /** @type {HTMLSelectElement} */ (
        document.getElementById('ship-manufacturer')
    );
    const shipTypeSelect = /** @type {HTMLSelectElement} */ (document.getElementById('ship-type'));
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

    /** @type {number | null} */
    let hangarFilterTimer = null;

    function filterUrl() {
        const params = new URLSearchParams();
        if (filterForm) {
            const data = new FormData(filterForm);
            for (const [key, value] of data.entries()) {
                if (value !== '') {
                    params.append(key, String(value));
                }
            }
        }
        const query = params.toString();
        return `/hangar${query ? `?${query}` : ''}`;
    }

    /**
     * Swaps the list for the filter form's current state, or for `url` when given.
     *
     * @param {string | null} [url] the list URL to load instead of the form's state
     */
    function applyHangarFilter(url) {
        const target = url || filterUrl();
        if (!hangarResults) {
            window.location.assign(target);
            return;
        }
        krtFetch.swap({ url: target, container: hangarResults, history: true });
    }

    if (filterForm) {
        filterForm.addEventListener('input', (e) => {
            const target = /** @type {HTMLElement | null} */ (e.target);
            if (!target || target.id !== 'hangar-ship-filter') {
                return;
            }
            clearTimeout(hangarFilterTimer ?? undefined);
            hangarFilterTimer = setTimeout(() => {
                applyHangarFilter();
            }, 300);
        });

        filterForm.addEventListener('change', (e) => {
            const target = /** @type {HTMLInputElement | null} */ (e.target);
            if (!target || target.name !== 'fitted') {
                return;
            }
            clearTimeout(hangarFilterTimer ?? undefined);
            applyHangarFilter();
        });

        filterForm.addEventListener('submit', (e) => {
            e.preventDefault();
            clearTimeout(hangarFilterTimer ?? undefined);
            applyHangarFilter();
        });
    }

    if (hangarResults) {
        hangarResults.addEventListener('click', (e) => {
            const target = /** @type {Element} */ (e.target);
            const clear = target.closest
                ? target.closest('[data-testid="empty-state-action"]')
                : null;
            if (!clear || !hangarResults.contains(clear)) {
                return;
            }
            e.preventDefault();
            const input = /** @type {HTMLInputElement | null} */ (
                document.getElementById('hangar-ship-filter')
            );
            if (input) {
                input.value = '';
            }
            if (filterForm) {
                /** @type {NodeListOf<HTMLInputElement>} */ (
                    filterForm.querySelectorAll('input[name="fitted"]')
                ).forEach((radio) => {
                    radio.checked = radio.value === '';
                });
            }
            clearTimeout(hangarFilterTimer ?? undefined);
            applyHangarFilter(clear.getAttribute('href'));
        });
    }

    krtFetch.bindSwap({ container: '#hangar-results', history: true });
});
