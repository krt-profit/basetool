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
import { reportBulkUnitOutcome } from '/js/hangar/ship-unit-change.js';

/**
 * The origin choice of a ship dialog (REQ-HANGAR-017).
 *
 * @typedef {object} OriginField
 * @property {() => void} reset prepares an add: nothing chosen, a choice required
 * @property {(origin: string, locked: boolean) => void} show prepares an edit with the ship's
 *     origin; a locked ship shows PLEDGE fixed, an unknown one nothing chosen
 * @property {() => string | null} read the chosen origin, or null to keep the stored one
 */

/**
 * Wires the origin radios of a ship dialog's form.
 *
 * @param {HTMLFormElement} form the ship form holding the `shipOriginField` fragment
 * @returns {OriginField} the field
 */
export function bindOriginField(form) {
    const radios = Array.from(
        /** @type {NodeListOf<HTMLInputElement>} */ (form.querySelectorAll('input[name="origin"]')),
    );
    const lockedHint = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-origin-locked-hint]')
    );
    const unknownHint = /** @type {HTMLElement | null} */ (
        form.querySelector('[data-origin-unknown-hint]')
    );

    /**
     * Sets the radios and the hints.
     *
     * @param {string} chosen the origin to tick, empty for none
     * @param {boolean} locked whether the origin is fixed
     * @param {boolean} required whether a choice is required
     */
    function apply(chosen, locked, required) {
        radios.forEach((radio) => {
            radio.checked = radio.value === chosen;
            radio.disabled = locked;
            radio.required = required;
        });
        if (lockedHint) lockedHint.hidden = !locked;
        if (unknownHint) unknownHint.hidden = locked || chosen !== '';
    }

    return {
        reset() {
            apply('', false, true);
            if (unknownHint) unknownHint.hidden = true;
        },
        show(origin, locked) {
            let chosen = origin === 'UNKNOWN' ? '' : origin;
            if (locked) chosen = 'PLEDGE';
            apply(chosen, locked, false);
        },
        read() {
            const chosen = radios.find((radio) => radio.checked && !radio.disabled);
            return chosen ? chosen.value : null;
        },
    };
}

/**
 * The localized texts of a bulk origin change.
 *
 * @typedef {object} BulkOriginTexts
 * @property {string | undefined} none nothing changed
 * @property {string | undefined} success {0} ships changed
 * @property {string | undefined} partial {0} ships changed, {1} skipped
 * @property {string | undefined} error the change failed
 */

/**
 * Marks the selected ships with one origin in one request, reports the counts and hands over to
 * the page's refresh (REQ-HANGAR-018).
 *
 * @param {object} options the change
 * @param {string} options.url the proxy endpoint
 * @param {string[]} options.shipIds the selected ships
 * @param {string} options.origin PLEDGE or IN_GAME
 * @param {BulkOriginTexts} options.texts the localized texts
 * @param {KrtConflictStrings} options.conflict the page's conflict texts for krtFetch
 * @param {HTMLButtonElement | null} options.button the clicked button, disabled while running
 * @param {() => void} options.onDone runs after a successful change
 * @returns {Promise<unknown>} settles when the request is done
 */
export function bulkChangeOrigin({ url, shipIds, origin, texts, conflict, button, onDone }) {
    if (button) button.disabled = true;
    return krtFetch
        .write({
            method: 'POST',
            url,
            payload: { shipIds, origin },
            toast: false,
            errorMessage: texts.error,
            conflict,
            onSuccess(body) {
                reportBulkUnitOutcome(body, texts);
                onDone();
            },
        })
        .then(() => {
            if (button) button.disabled = false;
        });
}
