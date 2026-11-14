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
import { format } from '/js/patchchange/patch-change-messages.js';

/** The page's path, also the base of its relay endpoints. */
const BASE = '/admin/settings/patch-change';

/** The confirmation dialog of a phase end. */
const MODAL_ID = 'pc-phase-end-modal';

/**
 * Installs the end of a home-location phase from the history: „Abfrage beenden“ opens the
 * confirmation naming the patch, the confirmation ends the phase and the history re-renders in
 * place, and a phase that is no longer open reports the backend's reason and re-renders the
 * history (REQ-PATCH-024).
 *
 * @param {KrtPageSections} sections the page's section seam
 * @param {PatchChangeMessages} i18n the page's localized strings
 */
export function initPhaseEnd(sections, i18n) {
    const formEl = document.getElementById('pc-phase-end-form');
    const textEl = document.getElementById('pc-phase-end-text');
    const submitEl = document.getElementById('pc-phase-end-submit');
    if (
        !(formEl instanceof HTMLFormElement) ||
        !textEl ||
        !(submitEl instanceof HTMLButtonElement)
    ) {
        return;
    }
    /** @type {HTMLElement} */
    const text = textEl;
    /** @type {HTMLButtonElement} */
    const submit = submitEl;

    /**
     * The patch change whose phase is under confirmation.
     *
     * @type {string | null}
     */
    let ending = null;

    /** Closes the dialog and forgets the patch change under confirmation. */
    function close() {
        ending = null;
        krtModal.close(MODAL_ID);
    }

    document.addEventListener('click', (event) => {
        const target = event.target;
        if (!(target instanceof Element) || !target.closest('[data-pc-phase-end]')) {
            return;
        }
        const row = target.closest('tr[data-patch-change-id]');
        if (!(row instanceof HTMLElement)) {
            return;
        }
        ending = row.dataset.patchChangeId || null;
        text.textContent = format(i18n.phaseEndText, [row.dataset.label || '']);
        krtModal.open(MODAL_ID, { focus: submit });
    });

    formEl.addEventListener('submit', (event) => {
        event.preventDefault();
        const id = ending;
        if (!id) {
            return;
        }
        sections.write({
            method: 'POST',
            url: `${BASE}/${encodeURIComponent(id)}/phase-end`,
            successMessage: i18n.phaseEnded,
            errorMessage: i18n.phaseEndError,
            submitter: submit,
            onSuccess() {
                close();
                return sections.refresh('history', { broadcast: false });
            },
            onConflict(/** @type {ApiProblem | null} */ problem) {
                close();
                showFrontendErrorToast(
                    problem && problem.detail ? problem.detail : i18n.phaseEndError,
                );
                sections.refresh('history', { broadcast: false });
                return true;
            },
        });
    });
}
