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
 * Wires a ship-type picker (REQ-P4K-022): the manufacturer select filters the ship-type options,
 * and the Fan Kit logo or abbreviation badge of the chosen ship type's manufacturer, else of the
 * chosen manufacturer, is shown. The server renders one hidden mark per manufacturer, keyed by
 * `data-manufacturer-mark-id`.
 *
 * @param {HTMLSelectElement} manufacturerSelect the manufacturer filter of the picker
 * @param {HTMLSelectElement} shipTypeSelect the ship-type select; its first option is the empty
 *     choice, the others carry `data-manufacturer-id`
 * @param {HTMLElement | null} preview the element holding the server-rendered marks
 * @returns {(shipTypeId: string) => void} chooses a ship type with the filter cleared, so every
 *     type is selectable and the mark is the chosen type's; an empty id chooses none
 */
export function bindShipTypePicker(manufacturerSelect, shipTypeSelect, preview) {
    const allOptions = Array.from(shipTypeSelect.options);
    const showMark = () => {
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
    const filter = () => {
        const manufacturer = manufacturerSelect.value;
        shipTypeSelect.replaceChildren(
            allOptions[0],
            ...allOptions
                .slice(1)
                .filter(
                    (o) => !manufacturer || o.getAttribute('data-manufacturer-id') === manufacturer,
                ),
        );
        showMark();
    };
    manufacturerSelect.addEventListener('change', filter);
    shipTypeSelect.addEventListener('change', showMark);
    showMark();
    return (shipTypeId) => {
        manufacturerSelect.value = '';
        filter();
        shipTypeSelect.value = shipTypeId;
        showMark();
    };
}
