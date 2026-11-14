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

/** @import { PatchChangeMessages } from '/js/patchchange/patch-change-messages.js' */

import { krtModal } from '/js/kernel/krt-modal.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

/** The page's path, also the base of its relay endpoints. */
const BASE = '/admin/settings/patch-change';

/** The label-correction dialog. */
const MODAL_ID = 'pc-correct-modal';

/**
 * Writes a corrected label into its history row.
 *
 * @param {HTMLElement} row the history row
 * @param {string} label the corrected label
 */
function showLabel(row, label) {
    row.setAttribute('data-label', label);
    const cell = row.querySelector('.pc-row-label');
    if (cell) {
        cell.textContent = label;
    }
}

/**
 * Installs the label correction of the history: „Bezeichnung korrigieren“ opens the dialog with
 * the row's label, the save echoes the version the row showed when the dialog opened, a success
 * updates the row in place, and a stale version reports the conflict and reloads the history
 * (REQ-PATCH-008).
 *
 * @param {KrtPageSections} sections the page's section seam
 * @param {PatchChangeMessages} i18n the page's localized strings
 * @param {() => void} onCorrected runs after a correction, for what else names the label
 */
export function initLabelCorrection(sections, i18n, onCorrected) {
    const formEl = document.getElementById('pc-correct-form');
    const inputEl = document.getElementById('pc-correct-label');
    const submitEl = document.getElementById('pc-correct-submit');
    if (
        !(formEl instanceof HTMLFormElement) ||
        !(inputEl instanceof HTMLInputElement) ||
        !(submitEl instanceof HTMLButtonElement)
    ) {
        return;
    }
    /** @type {HTMLFormElement} */
    const form = formEl;
    /** @type {HTMLInputElement} */
    const input = inputEl;
    /** @type {HTMLButtonElement} */
    const submit = submitEl;

    /**
     * The patch change under correction and the version its row showed when the dialog opened.
     *
     * @type {{ id: string, version: number } | null}
     */
    let editing = null;

    /** Closes the dialog and forgets the patch change under correction. */
    function close() {
        editing = null;
        krtModal.close(MODAL_ID);
    }

    document.addEventListener('click', (event) => {
        const target = event.target;
        if (!(target instanceof Element) || !target.closest('[data-pc-correct]')) {
            return;
        }
        const row = target.closest('tr[data-patch-change-id]');
        if (!(row instanceof HTMLElement)) {
            return;
        }
        editing = { id: row.dataset.patchChangeId || '', version: Number(row.dataset.version) };
        input.value = row.dataset.label || '';
        krtModal.open(MODAL_ID, { focus: input });
    });

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const current = editing;
        if (!current || !form.reportValidity()) {
            return;
        }
        const row = document.getElementById(`pc-row-${current.id}`);
        sections.write({
            method: 'PATCH',
            url: `${BASE}/${encodeURIComponent(current.id)}/label`,
            payload: { label: input.value.trim(), version: current.version },
            successMessage: i18n.corrected,
            errorMessage: i18n.correctError,
            submitter: submit,
            containerSelector: row,
            onSuccess(/** @type {ApiDto<'PatchChangeDto'> | null} */ body) {
                close();
                if (row && body && body.label) {
                    showLabel(row, body.label);
                }
                onCorrected();
            },
            onConflict() {
                close();
                showFrontendErrorToast(i18n.conflict);
                sections.refresh('history', { broadcast: false });
                return true;
            },
        });
    });
}
