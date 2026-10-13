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

/* global LOCATION_MSG, LOCATION_CONFLICT */

import { krtAutocomplete } from '/js/kernel/autocomplete.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtHooks } from '/js/kernel/krt-hooks.js';

document.addEventListener('DOMContentLoaded', () => {
    const dataList = /** @type {HTMLDataListElement | null} */ (
        document.getElementById('locationNames-data')
    );
    const locationNames = dataList
        ? Array.from(dataList.options).map((o) => {
              return o.value;
          })
        : [];
    const inpLocations = /** @type {HTMLInputElement | null} */ (
        document.getElementById('filterLocations')
    );
    if (inpLocations) krtAutocomplete(inpLocations, locationNames);
});

/**
 * Hides every table row whose first cell does not contain the query, case-insensitively.
 *
 * @param {string} tableId id of the table to filter
 * @param {string} query the text to look for
 */
function filterTable(tableId, query) {
    const filter = query.toUpperCase();
    const table = document.getElementById(tableId);
    if (!table) {
        return;
    }
    const tr = table.getElementsByTagName('tr');
    for (let i = 1; i < tr.length; i++) {
        const td = tr[i].getElementsByTagName('td')[0];
        if (td) {
            const txtValue = td.textContent || td.innerText;
            if (txtValue.toUpperCase().indexOf(filter) > -1) {
                tr[i].style.display = '';
            } else {
                tr[i].style.display = 'none';
            }
        }
    }
}

krtHooks.filterTable = filterTable;

document.addEventListener('DOMContentLoaded', () => {
    /**
     * Sets a toggle button's label from its on/off label attributes.
     *
     * @param {Element | null} btn the toggle button
     * @param {boolean | undefined} flagValue the flag the label reflects
     */
    function patchButton(btn, flagValue) {
        if (!btn) {
            return;
        }
        btn.textContent = flagValue
            ? btn.getAttribute('data-label-on')
            : btn.getAttribute('data-label-off');
    }

    /**
     * Patches both toggle forms of a location row from the updated location.
     *
     * @param {Element | null} row the location's table row
     * @param {ApiDto<'LocationDto'> | null} updated the location as saved
     */
    function patchRow(row, updated) {
        if (!row || !updated) {
            return;
        }
        const visForm = row.querySelector('form[data-location-toggle="visibility"]');
        const homeForm = row.querySelector('form[data-location-toggle="home"]');
        if (visForm) {
            patchButton(visForm.querySelector('button'), updated.hidden);
            const visInput = /** @type {HTMLInputElement | null} */ (
                visForm.querySelector('input[name="hidden"]')
            );
            if (visInput) {
                visInput.value = String(!updated.hidden);
            }
        }
        if (homeForm) {
            patchButton(homeForm.querySelector('button'), updated.homeLocation);
            const homeInput = /** @type {HTMLInputElement | null} */ (
                homeForm.querySelector('input[name="homeLocation"]')
            );
            if (homeInput) {
                homeInput.value = String(!updated.homeLocation);
            }
        }
    }

    document.addEventListener('submit', (event) => {
        const form = /** @type {HTMLFormElement | null} */ (
            /** @type {Element} */ (event.target).closest('form[data-location-toggle]')
        );
        if (!form) {
            return;
        }
        event.preventDefault();

        const row = form.closest('tr');
        const button = /** @type {HTMLButtonElement | null} */ (
            form.querySelector('button[type="submit"]')
        );
        if (button) {
            button.disabled = true;
        }
        krtFetch
            .write({
                method: 'POST',
                url: /** @type {string} */ (form.getAttribute('action')),
                successMessage: LOCATION_MSG.success,
                errorMessage: LOCATION_MSG.error,
                conflict: LOCATION_CONFLICT,
                onSuccess(updated) {
                    patchRow(row, updated);
                },
            })
            .finally(() => {
                if (button) {
                    button.disabled = false;
                }
            });
    });
});
