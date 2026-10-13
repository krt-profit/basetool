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
 * The org-unit kinds each account type may be owned by (REQ-ORG-019); types absent here own no
 * org unit.
 *
 * @type {Record<string, string[]>}
 */
const ACCOUNT_TYPE_OWNER_KINDS = {
    ORG_UNIT: ['SQUADRON', 'SPECIAL_COMMAND'],
    AREA: ['BEREICH'],
    CARTEL: ['ORGANISATIONSLEITUNG'],
};

/**
 * Shows the account-create modal's org-unit picker for owner-carrying account types and filters
 * its options to the allowed kinds. It is required for ORG_UNIT and AREA; a hidden or filtered-out
 * selection is cleared.
 *
 * @param {HTMLSelectElement} select the account-type select
 */
function syncAccountTypeRows(select) {
    const form = select.closest('form');
    if (!form) {
        return;
    }
    const type = select.value;
    const allowedKinds = ACCOUNT_TYPE_OWNER_KINDS[type];
    const orgRow = /** @type {HTMLElement | null} */ (form.querySelector('.bank-row-orgunit'));
    if (!orgRow) {
        return;
    }
    orgRow.style.display = allowedKinds ? '' : 'none';
    const control = orgRow.querySelector('select');
    if (!control) {
        return;
    }
    control.required = type === 'ORG_UNIT' || type === 'AREA';
    let currentStillVisible = false;
    Array.prototype.forEach.call(control.options, (option) => {
        if (!option.value) {
            return;
        }
        const kind = option.getAttribute('data-kind');
        const visible = !!allowedKinds && allowedKinds.indexOf(kind) !== -1;
        option.hidden = !visible;
        option.disabled = !visible;
        if (visible && option.value === control.value) {
            currentStillVisible = true;
        }
    });
    if (!allowedKinds || !currentStillVisible) {
        control.value = '';
    }
}

/**
 * Syncs the account-create modal's org-unit row once and registers the listener that keeps it in step
 * with the chosen account type.
 */
export function initAccountType() {
    document.addEventListener('change', (event) => {
        const target = /** @type {Element} */ (event.target);
        const select = /** @type {HTMLSelectElement | null} */ (
            target.closest('select[data-role="bank-account-type"]')
        );
        if (select) {
            syncAccountTypeRows(select);
        }
    });

    /** @type {NodeListOf<HTMLSelectElement>} */ (
        document.querySelectorAll('select[data-role="bank-account-type"]')
    ).forEach(syncAccountTypeRows);
}
