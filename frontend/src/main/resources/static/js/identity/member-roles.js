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

import { krtPageSections } from '/js/kernel/krt-page-sections.js';

/**
 * The role set the backend answers a role change with.
 *
 * @typedef {{ roles?: string[], version?: number }} MemberRoleSet
 */

/**
 * Wires the role toggles of the member page (REQ-SEC-088): each toggle assigns or withdraws one
 * role in place, guarded by the role-set counter in `data-roles-version`, and the member's room
 * re-renders the toggles when another admin changes the role set (REQ-FE-015). Toggles run one
 * after the other, each echoing the counter the previous one answered with.
 */
function bindRoleToggles() {
    const panelElement = document.querySelector('[data-member-roles]');
    if (!(panelElement instanceof HTMLElement)) {
        return;
    }
    const panel = panelElement;
    const userId = panel.getAttribute('data-user-id') || '';
    const roles = krtPageSections.create({
        topicClass: 'MEMBER',
        sections: {
            roles: { container: '#member-roles-results', fragmentValue: 'memberRoles' },
        },
        pageUrl() {
            return window.location.pathname;
        },
        topic: `member:${userId}`,
        receiver: { coalesceMs: 1500 },
    });

    /**
     * Returns the element carrying the role-set counter, which a re-render replaces.
     *
     * @returns {HTMLElement | null} the role set, or null while the roles could not be read
     */
    function roleSet() {
        const element = panel.querySelector('[data-member-role-set]');
        return element instanceof HTMLElement ? element : null;
    }

    /**
     * Shows the roles of an answered role set on the toggles and stores its counter.
     *
     * @param {MemberRoleSet | null | undefined} answered the backend's answer
     */
    function apply(answered) {
        const target = roleSet();
        if (!answered || !target) {
            return;
        }
        if (answered.version != null) {
            target.setAttribute('data-roles-version', String(answered.version));
        }
        const held = answered.roles || [];
        target.querySelectorAll('input[data-role-toggle]').forEach((element) => {
            const toggle = /** @type {HTMLInputElement} */ (element);
            toggle.checked = held.indexOf(toggle.value) >= 0;
        });
    }

    /**
     * Writes one toggle's change and announces it to the member's room.
     *
     * @param {HTMLInputElement} toggle the toggle the admin changed
     */
    function write(toggle) {
        const assigned = toggle.checked;
        /** @returns {boolean} false, so the transport shows its own feedback */
        function revert() {
            toggle.checked = !assigned;
            return false;
        }
        roles.write({
            method: 'PUT',
            url: `/api/proxy/users/${encodeURIComponent(userId)}/roles/${encodeURIComponent(
                toggle.value,
            )}`,
            payload: () => {
                const target = roleSet();
                return {
                    assigned,
                    version: Number((target && target.getAttribute('data-roles-version')) || '0'),
                };
            },
            serialize: 'member-roles',
            successMessage: panel.getAttribute('data-saved') || undefined,
            errorMessage: panel.getAttribute('data-error') || undefined,
            onSuccess(body) {
                apply(/** @type {MemberRoleSet | null} */ (body));
                roles.notify('roles');
            },
            onError: revert,
            onConflict: revert,
            onNetworkError: revert,
        });
    }

    panel.addEventListener('change', (event) => {
        const target = event.target;
        if (target instanceof HTMLInputElement && target.hasAttribute('data-role-toggle')) {
            write(target);
        }
    });
}

bindRoleToggles();
