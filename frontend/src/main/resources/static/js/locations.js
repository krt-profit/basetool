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

/* global krtAutocomplete, LOCATION_MSG, LOCATION_CONFLICT */

document.addEventListener('DOMContentLoaded', function () {
    const dataList = document.getElementById('locationNames-data');
    const locationNames = dataList
        ? Array.from(dataList.options).map(function (o) {
              return o.value;
          })
        : [];
    const inpLocations = document.getElementById('filterLocations');
    if (inpLocations) krtAutocomplete(inpLocations, locationNames);
});

function filterTable(tableId, query) {
    const filter = query.toUpperCase();
    const table = document.getElementById(tableId);
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

window.filterTable = filterTable;

document.addEventListener('DOMContentLoaded', function () {});

document.addEventListener('DOMContentLoaded', function () {
    function patchButton(btn, flagValue, secondaryWhen) {
        if (!btn) {
            return;
        }
        btn.textContent = flagValue
            ? btn.getAttribute('data-label-on')
            : btn.getAttribute('data-label-off');
        btn.classList.toggle('btn-secondary', flagValue === secondaryWhen);
    }

    function patchRow(row, updated) {
        if (!row || !updated) {
            return;
        }
        const visForm = row.querySelector('form[data-location-toggle="visibility"]');
        const homeForm = row.querySelector('form[data-location-toggle="home"]');
        if (visForm) {
            patchButton(visForm.querySelector('button'), updated.hidden, false);
            const visInput = visForm.querySelector('input[name="hidden"]');
            if (visInput) {
                visInput.value = String(!updated.hidden);
            }
        }
        if (homeForm) {
            patchButton(homeForm.querySelector('button'), updated.homeLocation, true);
            const homeInput = homeForm.querySelector('input[name="homeLocation"]');
            if (homeInput) {
                homeInput.value = String(!updated.homeLocation);
            }
        }
    }

    document.addEventListener('submit', function (event) {
        const form = event.target.closest('form[data-location-toggle]');
        if (!form) {
            return;
        }
        event.preventDefault();
        if (!window.krtFetch) {
            form.submit();
            return;
        }
        const row = form.closest('tr');
        const button = form.querySelector('button[type="submit"]');
        if (button) {
            button.disabled = true;
        }
        window.krtFetch
            .write({
                method: 'POST',
                url: form.getAttribute('action'),
                successMessage: LOCATION_MSG.success,
                errorMessage: LOCATION_MSG.error,
                conflict: LOCATION_CONFLICT,
                onSuccess(updated) {
                    patchRow(row, updated);
                },
            })
            .finally(function () {
                if (button) {
                    button.disabled = false;
                }
            });
    });
});
