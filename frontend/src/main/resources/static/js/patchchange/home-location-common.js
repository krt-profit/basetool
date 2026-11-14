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

import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { formatNumber } from '/js/kernel/krt-number-format.js';
import { format } from '/js/patchchange/patch-change-messages.js';

/**
 * Marks the rows of a home-location preview that stay where they are: the row of the chosen home
 * location shows its „bleibt“ chip and is dimmed, every other row moves (REQ-PATCH-021).
 *
 * @param {ParentNode} root the subtree holding the preview table
 * @param {string | null} locationId the chosen home location, or null before a choice
 */
export function markStaying(root, locationId) {
    root.querySelectorAll('.hl-preview tbody tr').forEach((row) => {
        if (!(row instanceof HTMLElement)) {
            return;
        }
        const stays = locationId !== null && row.dataset.locationId === locationId;
        row.classList.toggle('is-staying', stays);
        const chip = row.querySelector('[data-hl-stays]');
        if (chip instanceof HTMLElement) {
            chip.hidden = !stays;
        }
    });
}

/**
 * The localized strings of a relocation summary; each count pattern has `{0}` for the count.
 *
 * @typedef {object} RelocationMessages
 * @property {string} success what moved, `{0}` the location, `{1}` the items, `{2}` the ships
 * @property {string} rowsOne the items count for exactly one item
 * @property {string} rowsOther the items count for any other number
 * @property {string} shipsOne the ships count for exactly one ship
 * @property {string} shipsOther the ships count for any other number
 */

/**
 * Reads the strings of a relocation summary from a page's i18n holder.
 *
 * @param {DOMStringMap} data the holder's dataset
 * @returns {RelocationMessages} the strings; a missing one is reported and shows its key
 */
export function readRelocationMessages(data) {
    return {
        success: krtI18nText(data.success, 'data-success'),
        rowsOne: krtI18nText(data.rowsOne, 'data-rows-one'),
        rowsOther: krtI18nText(data.rowsOther, 'data-rows-other'),
        shipsOne: krtI18nText(data.shipsOne, 'data-ships-one'),
        shipsOther: krtI18nText(data.shipsOther, 'data-ships-other'),
    };
}

/**
 * Says what a relocation moved, in the page language (REQ-PATCH-025).
 *
 * @param {RelocationMessages} messages the summary and its count patterns
 * @param {ApiDto<'RelocationResultDto'> | null} result what the backend reports as moved
 * @param {string} fallbackName the chosen location's name, for an answer without one
 * @returns {string} the summary
 */
export function relocationSummary(messages, result, fallbackName) {
    const lang = document.documentElement.lang;
    const name = result && result.location && result.location.name;
    const rows = result && result.rows ? result.rows : 0;
    const ships = result && result.ships ? result.ships : 0;
    return format(messages.success, [
        name || fallbackName,
        countOf(rows, messages.rowsOne, messages.rowsOther, lang),
        countOf(ships, messages.shipsOne, messages.shipsOther, lang),
    ]);
}

/**
 * Formats a count with its singular or plural pattern.
 *
 * @param {number} count the count
 * @param {string} one the pattern for exactly one
 * @param {string} other the pattern for any other count
 * @param {string} lang the page language
 * @returns {string} the count phrase
 */
function countOf(count, one, other, lang) {
    return format(count === 1 ? one : other, [formatNumber(count, 0, lang)]);
}
