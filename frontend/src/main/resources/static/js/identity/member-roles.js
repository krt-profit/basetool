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

/**
 * The role set the backend answers a role change with.
 *
 * @typedef {{ roles?: string[], version?: number }} MemberRoleSet
 */

/**
 * Wires the role toggles of the member page (REQ-SEC-088): each toggle assigns or withdraws one
 * role in place, guarded by the role-set counter in `data-roles-version`. Toggles run one after
 * the other, each echoing the counter the previous one answered with.
 */
function bindRoleToggles() {
    const panelElement = document.querySelector('[data-member-roles]');
    if (!(panelElement instanceof HTMLElement)) {
        return;
    }
    const panel = panelElement;
    const userId = panel.getAttribute('data-user-id') || '';
    /** @type {HTMLInputElement[]} */
    const toggles = Array.prototype.slice.call(panel.querySelectorAll('input[data-role-toggle]'));

    /**
     * Shows the roles of an answered role set on the toggles and stores its counter.
     *
     * @param {MemberRoleSet | null | undefined} roleSet the backend's answer
     */
    function apply(roleSet) {
        if (!roleSet) {
            return;
        }
        if (roleSet.version != null) {
            panel.setAttribute('data-roles-version', String(roleSet.version));
        }
        const held = roleSet.roles || [];
        toggles.forEach((toggle) => {
            toggle.checked = held.indexOf(toggle.value) >= 0;
        });
    }

    toggles.forEach((toggle) => {
        toggle.addEventListener('change', () => {
            const assigned = toggle.checked;
            krtFetch.write({
                method: 'PUT',
                url: `/api/proxy/users/${encodeURIComponent(userId)}/roles/${encodeURIComponent(
                    toggle.value,
                )}`,
                payload: () => ({
                    assigned,
                    version: Number(panel.getAttribute('data-roles-version') || '0'),
                }),
                serialize: 'member-roles',
                successMessage: panel.getAttribute('data-saved') || undefined,
                errorMessage: panel.getAttribute('data-error') || undefined,
                onSuccess(body) {
                    apply(/** @type {MemberRoleSet | null} */ (body));
                },
                onError() {
                    toggle.checked = !assigned;
                    return false;
                },
                onNetworkError() {
                    toggle.checked = !assigned;
                    return false;
                },
            });
        });
    });
}

bindRoleToggles();
