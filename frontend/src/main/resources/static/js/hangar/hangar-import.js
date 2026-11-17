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

import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { formatMessage } from '/js/hangar/ship-unit-change.js';

/**
 * Binds the ship-list import of the own hangar: the file upload, the result dialog and the
 * member's choice for the names that may mean several ships, which re-sends the same file with
 * the choices (REQ-HANGAR-015, REQ-HANGAR-019).
 *
 * @param {() => void} onShipsChanged refreshes the ship list once the dialog closes after an
 *     import created or updated ships
 */
export function initHangarImport(onShipsChanged) {
    const importBtn = document.getElementById('fleetview-import-btn');
    const fileInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('fleetview-file')
    );
    const resultModal = document.getElementById('import-result-modal');
    const applyBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('import-apply-choices')
    );
    const ambiguousList = document.getElementById('import-ambiguous-list');
    if (!importBtn || !fileInput || !resultModal || !applyBtn || !ambiguousList) return;
    const button = importBtn;
    const input = fileInput;
    const modal = resultModal;
    const apply = applyBtn;
    const choiceList = ambiguousList;

    /** @type {File | null} */
    let lastFile = null;
    let shipsChanged = false;

    /**
     * Looks up an element of the import result dialog; the dialog always renders them.
     *
     * @param {string} id the element id
     * @returns {HTMLElement} the element
     */
    function resultElement(id) {
        return /** @type {HTMLElement} */ (document.getElementById(id));
    }

    /**
     * Replaces the list's children with one paragraph per name.
     *
     * @param {HTMLElement} list the list container
     * @param {string[]} names the names to show
     */
    function fillParagraphs(list, names) {
        list.replaceChildren(
            ...names.map((n) => {
                const p = document.createElement('p');
                p.textContent = n == null ? '' : String(n);
                return p;
            }),
        );
    }

    /**
     * Builds one choice row: the upload name with its entry count and a select of its ships.
     *
     * @param {ApiDto<'AmbiguousShipDto'>} ship the ambiguous name
     * @param {number} index the row's position, for the control id
     * @returns {HTMLElement} the row
     */
    function choiceRow(ship, index) {
        const row = document.createElement('div');
        row.className = 'form-group import-choice';
        const id = `import-choice-${index}`;
        const label = document.createElement('label');
        label.htmlFor = id;
        label.textContent = String(ship.name ?? '');
        if ((ship.count ?? 0) > 1) {
            const count = document.createElement('span');
            count.className = 'import-choice__count';
            count.textContent = formatMessage(choiceList.dataset.count, [ship.count ?? 0]);
            label.append(' ', count);
        }
        const select = document.createElement('select');
        select.id = id;
        select.dataset.name = String(ship.name ?? '');
        select.append(new Option(choiceList.dataset.choiceNone || '', ''));
        (ship.options ?? []).forEach((option) => {
            select.append(new Option(String(option.name ?? ''), String(option.id ?? '')));
        });
        select.addEventListener('change', updateApply);
        row.append(label, select);
        return row;
    }

    /**
     * Returns the choices the member made in the dialog.
     *
     * @returns {ApiDto<'HangarImportChoiceDto'>[]} one choice per name with a selected ship
     */
    function selectedChoices() {
        return Array.from(choiceList.querySelectorAll('select'))
            .filter((select) => select.value !== '')
            .map((select) => ({ name: select.dataset.name || '', shipTypeId: select.value }));
    }

    /** Enables the apply button once at least one name has a chosen ship. */
    function updateApply() {
        apply.disabled = selectedChoices().length === 0;
    }

    /**
     * Fills the import result dialog and opens it unless it is already open.
     *
     * @param {ApiDto<'FleetviewImportResponseDto'>} data the import outcome
     */
    function showResult(data) {
        if ((data.importedCount ?? 0) > 0 || (data.updatedCount ?? 0) > 0) {
            shipsChanged = true;
        }
        const ambiguous = data.ambiguousShips ?? [];
        resultElement('import-res-imported').textContent = String(data.importedCount ?? '');
        resultElement('import-res-updated').textContent = String(data.updatedCount ?? '');
        resultElement('import-res-skipped').textContent = String(data.skippedCount ?? '');
        resultElement('import-res-ambiguous').textContent = String(ambiguous.length);
        resultElement('import-res-duplicates').textContent = String(data.duplicateCount ?? '');

        choiceList.replaceChildren(...ambiguous.map(choiceRow));
        resultElement('import-ambiguous-section').hidden = ambiguous.length === 0;
        apply.hidden = ambiguous.length === 0;
        updateApply();

        const skipped = data.skippedShips ?? [];
        fillParagraphs(resultElement('import-skip-list'), skipped);
        resultElement('import-skip-section').hidden = skipped.length === 0;

        const duplicates = data.duplicateShips ?? [];
        fillParagraphs(resultElement('import-dup-list'), duplicates);
        resultElement('import-dup-section').hidden = duplicates.length === 0;

        if (!krtModal.isOpen(modal)) {
            krtModal.open(modal);
        }
    }

    /** Closes the dialog and refreshes the ship list when an import of this dialog changed ships. */
    function closeResult() {
        krtModal.close(modal);
        lastFile = null;
        if (shipsChanged) {
            shipsChanged = false;
            onShipsChanged();
        }
    }

    /**
     * Shows the upload failure toast with an optional detail.
     *
     * @param {string} detail the failure detail, empty for none
     */
    function showFailure(detail) {
        showFrontendErrorToast(
            /** @type {string} */ (button.getAttribute('data-error-failed')) +
                (detail ? ` (${detail})` : ''),
        );
    }

    /**
     * Uploads a ship export (JSON or Hangar Extension CSV) with the member's choices and shows the
     * result dialog.
     *
     * @param {File} file the chosen file
     * @param {ApiDto<'HangarImportChoiceDto'>[]} choices the choices for ambiguous names, empty on
     *     a first import
     * @param {HTMLButtonElement | HTMLElement} submitter the control that started the upload
     * @returns {Promise<void>} settles when the upload is done
     */
    function upload(file, choices, submitter) {
        const formData = new FormData();
        formData.append('file', file);
        if (choices.length > 0) {
            formData.append(
                'choices',
                new Blob([JSON.stringify({ choices })], { type: 'application/json' }),
            );
        }
        submitter.setAttribute('aria-busy', 'true');
        return krtFetch
            .submitForm({
                url: '/hangar/import/ships',
                method: 'POST',
                formData,
                submitter,
                toast: false,
                onSuccess(data) {
                    lastFile = file;
                    showResult(data);
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
                submitter.removeAttribute('aria-busy');
            });
    }

    button.addEventListener('click', () => {
        input.click();
    });
    input.addEventListener('change', () => {
        const file = input.files && input.files[0];
        if (!file) return;
        const maxBytes = Number(button.getAttribute('data-max-bytes'));
        if (maxBytes > 0 && file.size > maxBytes) {
            showFrontendErrorToast(
                /** @type {string} */ (button.getAttribute('data-error-toolarge')),
            );
            input.value = '';
            return;
        }
        upload(file, [], button).then(() => {
            input.value = '';
        });
    });
    apply.addEventListener('click', () => {
        const choices = selectedChoices();
        if (!lastFile || choices.length === 0) return;
        upload(lastFile, choices, apply);
    });
    [resultElement('import-result-close'), resultElement('import-result-close-x')].forEach(
        (btn) => {
            btn.addEventListener('click', closeResult);
        },
    );
    window.addEventListener('click', (e) => {
        if (e.target === modal) {
            closeResult();
        }
    });
}
