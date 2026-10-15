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

import { assignFormAction, safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { resetUnsavedChanges } from '/js/kernel/unsaved-changes.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import {
    createShipSelection,
    formatMessage,
    presetUnitSelect,
    reportBulkUnitOutcome,
} from '/js/hangar/ship-unit-change.js';

const RESULTS = '#member-hangar-results';

const MEMBER_HANGAR_SECTIONS = { ships: { container: RESULTS } };

/**
 * Wires the admin member hangar: the add, edit and delete dialogs (REQ-HANGAR-011) and the unit
 * change of one ship or a selection (REQ-HANGAR-012) write through the member's admin paths, swap
 * the ship list in place and tell the member's open hangar pages through hangar:{memberId}; a
 * change made there refreshes this list the same way.
 */
function initMemberHangar() {
    const root = document.getElementById('member-hangar');
    const modal = document.getElementById('member-ship-modal');
    const deleteModal = document.getElementById('member-ship-delete-modal');
    const form = /** @type {HTMLFormElement | null} */ (
        document.getElementById('member-ship-form')
    );
    const title = document.getElementById('member-ship-modal-title');
    const deleteBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('member-ship-delete-btn')
    );
    const deleteConfirm = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('member-ship-delete-confirm')
    );
    if (!root || !modal || !deleteModal || !form || !title || !deleteBtn || !deleteConfirm) {
        return;
    }
    const shipModal = modal;
    const confirmModal = deleteModal;
    const shipForm = form;
    const modalTitle = title;
    const modalDeleteBtn = deleteBtn;
    const confirmBtn = deleteConfirm;
    const baseUrl = root.dataset.baseUrl || '';

    /**
     * Reads a localized message the page put on its root element.
     *
     * @param {string} key the data attribute name in camel case
     * @returns {string} the message, empty when missing
     */
    function message(key) {
        return root ? root.dataset[key] || '' : '';
    }

    /**
     * Looks up a control of the ship form by id; the form always renders them.
     *
     * @param {string} id the element id
     * @returns {HTMLInputElement} the control
     */
    function field(id) {
        return /** @type {HTMLInputElement} */ (document.getElementById(id));
    }

    const insuranceKinds = Array.from(
        /** @type {NodeListOf<HTMLInputElement>} */ (
            shipForm.querySelectorAll('input[name="insuranceKind"]')
        ),
    );
    if (insuranceKinds.length) {
        insuranceKinds[0].required = true;
    }
    const monthsRow = document.getElementById('member-ship-insurance-months-row');
    const months = field('member-ship-insurance-months');

    /**
     * Derives the stored insurance value (0, LTI or a month count) from the segmented control and
     * shows the month field only for "Monate".
     *
     * @returns {string} the stored value, or an empty string while it is incomplete
     */
    function insuranceValue() {
        const checked = insuranceKinds.find((radio) => radio.checked);
        const kind = checked ? checked.value : '';
        const count = parseInt(months.value, 10);
        if (monthsRow) monthsRow.hidden = kind !== 'MONTHS';
        months.required = kind === 'MONTHS';
        if (kind === 'NONE') return '0';
        if (kind === 'LTI') return 'LTI';
        if (kind === 'MONTHS' && !Number.isNaN(count)) return String(count);
        return '';
    }

    /**
     * Presets the segmented control and the month field from a stored insurance value.
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
        months.value = kind === 'MONTHS' ? value : '';
        insuranceValue();
    }

    insuranceKinds.forEach((radio) => {
        radio.addEventListener('change', () => {
            insuranceValue();
            if (radio.checked && radio.value === 'MONTHS') months.focus();
        });
    });
    months.addEventListener('input', insuranceValue);

    const ownerSlot = document.getElementById('member-ship-owner-picker-slot');

    /**
     * Shows the owner picker in the add dialog only; an edit never changes the unit.
     *
     * @param {boolean} visible whether the dialog adds a ship
     */
    function showOwnerPicker(visible) {
        if (!ownerSlot) return;
        ownerSlot.hidden = !visible;
        const select = /** @type {HTMLSelectElement | null} */ (ownerSlot.querySelector('select'));
        if (!select) return;
        select.disabled = !visible;
        if (visible) select.value = '';
    }

    function closeShipModal() {
        resetUnsavedChanges();
        krtModal.close(shipModal);
    }

    /**
     * Re-reads the ship list in place and copies its total into the page head.
     */
    function refreshList() {
        const query = new URLSearchParams(window.location.search).toString();
        krtFetch.swap({
            url: `${baseUrl}${query ? `?${query}` : ''}`,
            container: RESULTS,
            history: false,
        });
    }

    const resultsEl = document.getElementById('member-hangar-results');
    const memberTopic = resultsEl ? resultsEl.dataset.liveSyncTopic || '' : '';

    /**
     * Re-reads the list after one of this page's writes and tells the member's other open hangar
     * pages.
     */
    function afterWrite() {
        refreshList();
        if (memberTopic) {
            krtLiveSync.sendChanged(memberTopic, Object.keys(MEMBER_HANGAR_SECTIONS));
        }
    }

    if (memberTopic) {
        krtLiveSync.createReceiver({
            topic: memberTopic,
            sections: MEMBER_HANGAR_SECTIONS,
            coalesceMs: 1500,
            refresh() {
                refreshList();
            },
        });
    }

    document.addEventListener('krt:swapped', (e) => {
        const container = e && e.detail ? e.detail.container : null;
        if (!container || container.id !== 'member-hangar-results') return;
        const stats = document.getElementById('member-hangar-stats');
        const badge = document.getElementById('member-hangar-count');
        const total = stats ? stats.getAttribute('data-total') : null;
        if (badge && total !== null) badge.textContent = total;
        renderSelection();
    });

    /**
     * Opens the dialog blank for a new ship of the member.
     *
     * @param {HTMLElement} btn the add button
     */
    function openAdd(btn) {
        modalTitle.textContent = shipForm.getAttribute('data-title-add');
        assignFormAction(
            shipForm,
            safeSameOriginUrl(btn.getAttribute('data-action'), shipForm.action),
        );
        field('member-ship-name').value = '';
        field('member-ship-type').value = '';
        field('member-ship-location').value = '';
        field('member-ship-fitted').checked = false;
        field('member-ship-version').value = '';
        setInsurance('');
        modalDeleteBtn.hidden = true;
        showOwnerPicker(true);
        krtModal.open(shipModal);
    }

    /**
     * Opens the dialog filled from the clicked row's ship.
     *
     * @param {HTMLElement} btn the row's edit button
     */
    function openEdit(btn) {
        const action = btn.getAttribute('data-action') || '';
        modalTitle.textContent = shipForm.getAttribute('data-title-edit');
        assignFormAction(shipForm, safeSameOriginUrl(action, shipForm.action));
        field('member-ship-name').value = btn.getAttribute('data-name') ?? '';
        field('member-ship-type').value = btn.getAttribute('data-type') ?? '';
        field('member-ship-location').value = btn.getAttribute('data-loc') || '';
        field('member-ship-fitted').checked = btn.getAttribute('data-fitted') === 'true';
        field('member-ship-version').value = btn.getAttribute('data-version') ?? '';
        setInsurance(btn.getAttribute('data-ins'));
        modalDeleteBtn.setAttribute('data-action', action.replace(/\/update$/, '/delete'));
        modalDeleteBtn.hidden = false;
        showOwnerPicker(false);
        krtModal.open(shipModal);
    }

    krtEvents.on('click', 'member-hangar-add', openAdd);
    krtEvents.on('click', 'member-hangar-edit', openEdit);

    shipModal.querySelectorAll('.close-member-ship-modal').forEach((el) => {
        el.addEventListener('click', closeShipModal);
    });
    confirmModal.querySelectorAll('.close-member-ship-delete').forEach((el) => {
        el.addEventListener('click', () => krtModal.close(confirmModal));
    });

    shipForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const action = shipForm.getAttribute('action') || shipForm.action;
        const isUpdate = /\/update$/.test(action);
        const versionRaw = field('member-ship-version').value;
        const owner = /** @type {HTMLSelectElement | null} */ (
            shipForm.querySelector('select[name="owningOrgUnitId"]')
        );
        const submitBtn = /** @type {HTMLButtonElement | null} */ (
            shipForm.querySelector('button[type="submit"]')
        );
        if (submitBtn) submitBtn.disabled = true;
        krtFetch
            .write({
                method: 'POST',
                url: safeSameOriginUrl(action, action),
                payload: {
                    name: field('member-ship-name').value || null,
                    shipTypeId: field('member-ship-type').value || null,
                    insurance: insuranceValue() || null,
                    locationId: field('member-ship-location').value || null,
                    fitted: field('member-ship-fitted').checked,
                    version: isUpdate && versionRaw ? Number(versionRaw) : null,
                    owningOrgUnitId: !isUpdate && owner && owner.value ? owner.value : null,
                },
                successMessage: message(isUpdate ? 'updateSuccess' : 'addSuccess'),
                errorMessage: message(isUpdate ? 'updateError' : 'addError'),
                onSuccess() {
                    closeShipModal();
                    afterWrite();
                },
            })
            .then(() => {
                if (submitBtn) submitBtn.disabled = false;
            });
    });

    modalDeleteBtn.addEventListener('click', () => {
        krtModal.open(confirmModal);
    });

    confirmBtn.addEventListener('click', () => {
        const action = modalDeleteBtn.getAttribute('data-action') || '';
        confirmBtn.disabled = true;
        krtFetch
            .write({
                method: 'POST',
                url: safeSameOriginUrl(action, action),
                successMessage: message('deleteSuccess'),
                errorMessage: message('deleteError'),
                onSuccess() {
                    krtModal.close(confirmModal);
                    closeShipModal();
                    afterWrite();
                },
            })
            .then(() => {
                confirmBtn.disabled = false;
            });
    });

    const manufacturer = /** @type {HTMLSelectElement} */ (
        document.getElementById('member-ship-manufacturer')
    );
    const shipType = /** @type {HTMLSelectElement} */ (document.getElementById('member-ship-type'));
    const allTypes = Array.from(shipType.options);
    manufacturer.addEventListener('change', () => {
        const chosen = manufacturer.value;
        shipType.replaceChildren(
            allTypes[0],
            ...allTypes
                .slice(1)
                .filter((o) => !chosen || o.getAttribute('data-manufacturer-id') === chosen),
        );
    });

    const conflict = {
        title: message('conflictTitle'),
        reloadDetailFallback: message('conflictDetail'),
        reloadLabel: message('conflictReload'),
        dismissLabel: message('conflictDismiss'),
        reloadQuestion: message('conflictQuestion'),
    };

    const unitModal = document.getElementById('member-ship-unit-modal');
    const unitForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('member-ship-unit-form')
    );
    const unitSelect = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('member-ship-unit-select')
    );
    const unitBulkMessage = document.getElementById('member-ship-unit-bulk-message');
    let unitVersion = '';
    let unitCurrent = '';
    /** @type {string[] | null} */
    let bulkShipIds = null;

    /**
     * Opens the "Einheit ändern" dialog preset to the clicked ship's unit (REQ-HANGAR-012).
     *
     * @param {HTMLElement} btn the row's unit button
     */
    function openUnitModal(btn) {
        if (!unitModal || !unitForm || !unitSelect) return;
        assignFormAction(
            unitForm,
            safeSameOriginUrl(btn.getAttribute('data-action'), unitForm.action),
        );
        bulkShipIds = null;
        if (unitBulkMessage) unitBulkMessage.hidden = true;
        unitCurrent = btn.getAttribute('data-org-unit-id') || '';
        unitVersion = btn.getAttribute('data-org-unit-version') || '';
        presetUnitSelect(unitSelect, unitCurrent, btn.getAttribute('data-org-unit-name') || '');
        krtModal.open(unitModal);
    }

    krtEvents.on('click', 'member-hangar-change-unit', openUnitModal);

    const shipSelection = createShipSelection({
        results: RESULTS,
        selectAllId: 'member-hangar-select-all',
        bar: document.getElementById('member-hangar-selection-bar'),
        count: document.getElementById('member-hangar-selection-count'),
    });
    const selectedShips = shipSelection.selected;
    const shipChecks = shipSelection.checks;
    const renderSelection = shipSelection.render;

    krtEvents.on('click', 'member-hangar-clear-selection', () => {
        selectedShips.clear();
        renderSelection();
    });

    /**
     * Opens the "Einheit ändern" dialog for the selected ships, preset to their unit when they all
     * share one and to "Keine Einheit" otherwise.
     */
    function openBulkUnitModal() {
        if (!unitModal || !unitSelect || selectedShips.size === 0) return;
        const picked = shipChecks().filter((c) => selectedShips.has(c.value));
        bulkShipIds = picked.map((c) => c.value);
        const units = new Set(picked.map((c) => c.getAttribute('data-org-unit-id') || ''));
        const shared = units.size === 1 ? picked[0] : null;
        unitCurrent = shared ? shared.getAttribute('data-org-unit-id') || '' : '';
        unitVersion = '';
        presetUnitSelect(
            unitSelect,
            unitCurrent,
            shared ? shared.getAttribute('data-org-unit-name') || '' : '',
        );
        if (unitBulkMessage) {
            unitBulkMessage.textContent = formatMessage(message('bulkUnitMessage'), [
                bulkShipIds.length,
            ]);
            unitBulkMessage.hidden = false;
        }
        krtModal.open(unitModal);
    }

    krtEvents.on('click', 'member-hangar-open-bulk-unit', openBulkUnitModal);

    if (unitModal) {
        unitModal.querySelectorAll('.close-member-ship-unit').forEach((el) => {
            el.addEventListener('click', () => krtModal.close(unitModal));
        });
    }

    if (unitForm && unitSelect && unitModal) {
        const modalEl = unitModal;
        const formEl = unitForm;
        const selectEl = unitSelect;
        formEl.addEventListener('submit', (e) => {
            e.preventDefault();
            const chosen = selectEl.value;
            if (chosen === unitCurrent) {
                krtModal.close(modalEl);
                return;
            }
            const submitBtn = /** @type {HTMLButtonElement | null} */ (
                formEl.querySelector('button[type="submit"]')
            );
            if (submitBtn) submitBtn.disabled = true;
            const bulkAction = formEl.getAttribute('data-bulk-action') || '';
            const request = bulkShipIds
                ? krtFetch.write({
                      method: 'POST',
                      url: safeSameOriginUrl(bulkAction, bulkAction),
                      payload: { shipIds: bulkShipIds, orgUnitId: chosen || null },
                      toast: false,
                      errorMessage: message('bulkUnitError'),
                      conflict,
                      onSuccess(body) {
                          krtModal.close(modalEl);
                          reportBulkUnitOutcome(body, {
                              none: message('bulkUnitNone'),
                              success: message('bulkUnitSuccess'),
                              partial: message('bulkUnitSuccessPartial'),
                          });
                          selectedShips.clear();
                          renderSelection();
                          afterWrite();
                      },
                  })
                : krtFetch.write({
                      method: 'POST',
                      url: safeSameOriginUrl(formEl.getAttribute('action'), formEl.action),
                      payload: {
                          orgUnitId: chosen || null,
                          owningOrgUnitVersion: unitVersion === '' ? null : Number(unitVersion),
                      },
                      successMessage: message('unitSuccess'),
                      errorMessage: message('unitError'),
                      conflict,
                      onSuccess() {
                          krtModal.close(modalEl);
                          afterWrite();
                      },
                  });
            request.then(() => {
                if (submitBtn) submitBtn.disabled = false;
            });
        });
    }

    krtFetch.bindSwap({ container: RESULTS, history: true });
}

document.addEventListener('DOMContentLoaded', initMemberHangar);
