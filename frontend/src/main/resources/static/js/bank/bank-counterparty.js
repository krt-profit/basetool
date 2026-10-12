// @ts-check
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

import { setMovementControlActive } from '/js/bank/bank-controls.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';

/**
 * Fills a counterparty org-unit <select> from the chosen user's memberships (REQ-BANK-044). It
 * stays disabled while loading or without a user, and auto-selects a single membership.
 *
 * @param {HTMLInputElement | HTMLSelectElement} userSelect the counterparty user picker (native
 *     select or combobox value input)
 * @param {HTMLSelectElement} orgSelect the dependent org-unit picker
 */
async function fillCounterpartyOrgUnits(userSelect, orgSelect) {
    const placeholder = orgSelect.querySelector('option[value=""]');
    orgSelect.replaceChildren();
    if (placeholder) {
        orgSelect.appendChild(placeholder);
    }
    orgSelect.value = '';
    orgSelect.disabled = true;
    const userId = userSelect.value;
    if (!userId) {
        return;
    }
    let memberships;
    try {
        memberships = await krtFetch.getJson(
            `/users/${encodeURIComponent(userId)}/memberships?allKinds=true`,
        );
    } catch {
        memberships = [];
    }
    if (!Array.isArray(memberships) || memberships.length === 0) {
        return;
    }
    memberships.forEach((membership) => {
        const option = document.createElement('option');
        option.value = membership.orgUnitId;
        option.textContent = membership.orgUnitName;
        orgSelect.appendChild(option);
    });
    orgSelect.disabled = false;
    if (memberships.length === 1) {
        orgSelect.value = memberships[0].orgUnitId;
    }
}

/**
 * Resets a counterparty org-unit select to just its placeholder option and clears the selection.
 *
 * @param {HTMLSelectElement} select the org-unit select
 */
function resetCounterpartyOrgUnitOptions(select) {
    const placeholder = select.querySelector('option[value=""]');
    select.replaceChildren();
    if (placeholder) {
        select.appendChild(placeholder);
    }
    select.value = '';
}

/**
 * Switches a counterparty block between a registered tool user and an external free-text name
 * (REQ-BANK-044). External enables the name input and offers all active org units from
 * `[data-bank-all-orgunits]`; registered enables the user lookup and disables the unit select
 * until a user is chosen.
 *
 * @param {HTMLInputElement} toggle the "kein Tool-Account" checkbox
 */
export function toggleCounterpartyExternal(toggle) {
    const section = toggle.closest('[data-counterparty-section]');
    if (!section) {
        return;
    }
    const external = toggle.checked && !toggle.disabled;
    const registeredRow = /** @type {HTMLElement | null} */ (
        section.querySelector('[data-cp-registered-row]')
    );
    const externalRow = /** @type {HTMLElement | null} */ (
        section.querySelector('[data-cp-external-row]')
    );
    const userSelect = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
        section.querySelector('[data-counterparty-user]')
    );
    const nameInput = /** @type {HTMLInputElement | null} */ (
        section.querySelector('[data-counterparty-name]')
    );
    const orgSelect = /** @type {HTMLSelectElement | null} */ (
        section.querySelector('[data-counterparty-orgunit]')
    );
    if (registeredRow) {
        registeredRow.hidden = external;
    }
    if (userSelect) {
        setMovementControlActive(userSelect, !external);
    }
    if (externalRow) {
        externalRow.hidden = !external;
    }
    if (nameInput) {
        setMovementControlActive(nameInput, external);
    }
    if (orgSelect) {
        resetCounterpartyOrgUnitOptions(orgSelect);
        if (external) {
            const form = toggle.closest('form');
            const source = /** @type {HTMLSelectElement | null} */ (
                form ? form.querySelector('[data-bank-all-orgunits]') : null
            );
            if (source) {
                Array.prototype.forEach.call(source.options, (option) => {
                    if (option.value) {
                        orgSelect.appendChild(option.cloneNode(true));
                    }
                });
            }
            orgSelect.disabled = false;
        } else {
            orgSelect.disabled = true;
        }
    }
}

/**
 * Registers the document listeners of the counterparty block: the user picker that fills the org-unit
 * select and the external-name toggle.
 */
export function initCounterparty() {
    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const userSelect = /** @type {HTMLInputElement | HTMLSelectElement | null} */ (
            target.closest('[data-counterparty-user]')
        );
        if (!userSelect) {
            return;
        }
        const orgSelect = /** @type {HTMLSelectElement | null} */ (
            document.getElementById(
                /** @type {string} */ (userSelect.getAttribute('data-orgunit-target')),
            )
        );
        if (orgSelect) {
            fillCounterpartyOrgUnits(userSelect, orgSelect);
        }
    });

    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const toggle = /** @type {HTMLInputElement | null} */ (
            target.closest('[data-role="bank-cp-external-toggle"]')
        );
        if (toggle) {
            toggleCounterpartyExternal(toggle);
        }
    });
}
