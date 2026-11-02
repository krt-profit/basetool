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

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

/**
 * @typedef {object} InstantToggleOpts
 * @property {string} selector CSS selector of the toggle checkboxes
 * @property {string} idAttribute attribute carrying the target id
 * @property {(id: string) => string} urlFor builds the PATCH URL for an id
 * @property {(checked: boolean) => object} bodyFor builds the JSON payload for the new state
 * @property {{ on: string, off: string, saved: string, error: string }} labels localized strings
 * @property {KrtConflictStrings} conflict localized strings of the conflict dialog
 */

/**
 * Wires a family of boolean toggles that save on change: the label flips at once and the state is
 * PATCHed through krtFetch.write (REQ-FE-002). On a failure the checkbox and label revert and an
 * error toast shows; a 409 reverts and opens the conflict dialog. The listener is delegated, so
 * toggles of re-rendered rows work without re-wiring.
 *
 * @param {InstantToggleOpts} opts the toggle family
 */
export function wireInstantToggle(opts) {
    document.addEventListener('change', (e) => {
        const checkbox = e.target instanceof Element ? e.target.closest(opts.selector) : null;
        if (!(checkbox instanceof HTMLInputElement)) return;
        const id = checkbox.getAttribute(opts.idAttribute) || '';
        const checked = checkbox.checked;
        const labelSpan = checkbox.parentElement
            ? checkbox.parentElement.querySelector('.toggle-state-text')
            : null;
        /**
         * Shows a state on the checkbox and its label.
         *
         * @param {boolean} on whether the toggle is on
         */
        const show = function (on) {
            checkbox.checked = on;
            if (labelSpan) labelSpan.textContent = on ? opts.labels.on : opts.labels.off;
        };
        show(checked);
        krtFetch.write({
            method: 'PATCH',
            url: opts.urlFor(id),
            payload: opts.bodyFor(checked),
            submitter: checkbox,
            successMessage: opts.labels.saved,
            errorMessage: opts.labels.error,
            conflict: opts.conflict,
            onError(status) {
                show(!checked);
                if (status === 409) return false;
                showFrontendErrorToast(opts.labels.error);
                return true;
            },
            onNetworkError() {
                show(!checked);
                return false;
            },
        });
    });
}
