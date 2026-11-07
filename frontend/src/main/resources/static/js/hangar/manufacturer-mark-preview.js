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

/**
 * Shows the Fan Kit logo or abbreviation badge of the manufacturer a ship-type picker points at
 * (REQ-P4K-022): the manufacturer of the chosen ship type, else the chosen manufacturer. The
 * server renders one hidden mark per manufacturer, keyed by `data-manufacturer-mark-id`.
 *
 * @param {HTMLSelectElement} manufacturerSelect the manufacturer filter of the picker
 * @param {HTMLSelectElement} shipTypeSelect the ship-type select, options carrying
 *     `data-manufacturer-id`
 * @param {HTMLElement | null} preview the element holding the server-rendered marks
 * @returns {() => void} shows the mark of the current choice; call it after setting a value
 */
export function bindManufacturerMarkPreview(manufacturerSelect, shipTypeSelect, preview) {
    const refresh = () => {
        if (!preview) return;
        const option = shipTypeSelect.selectedOptions[0];
        const chosen =
            (option && option.getAttribute('data-manufacturer-id')) || manufacturerSelect.value;
        /** @type {NodeListOf<HTMLElement>} */ (
            preview.querySelectorAll('[data-manufacturer-mark-id]')
        ).forEach((mark) => {
            mark.hidden = !chosen || mark.getAttribute('data-manufacturer-mark-id') !== chosen;
        });
    };
    manufacturerSelect.addEventListener('change', refresh);
    shipTypeSelect.addEventListener('change', refresh);
    refresh();
    return refresh;
}
