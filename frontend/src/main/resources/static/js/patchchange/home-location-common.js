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
 * Says what a relocation moved, in the page language (REQ-PATCH-025).
 *
 * @param {string} template the message, `{0}` the location, `{1}` the items, `{2}` the ships
 * @param {ApiDto<'RelocationResultDto'> | null} result what the backend reports as moved
 * @param {string} fallbackName the chosen location's name, for an answer without one
 * @returns {string} the summary
 */
export function relocationSummary(template, result, fallbackName) {
    const lang = document.documentElement.lang;
    const name = result && result.location && result.location.name;
    return format(template, [
        name || fallbackName,
        formatNumber(result && result.rows ? result.rows : 0, 0, lang),
        formatNumber(result && result.ships ? result.ships : 0, 0, lang),
    ]);
}
