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

/*
 * Admin org-structure editor (epic #692, REQ-ORG-014): creates Bereiche and the Organisationsleitung
 * and wires the parent edges (Staffel/SK -> Bereich, Bereich -> OL) via window.krtFetch (CSRF +
 * 403-retry handled centrally). On a successful mutation the page reloads — an admin, low-traffic
 * surface where a reload is the simplest correct refresh (CLAUDE.md no-reload fallback). The backend
 * relays its conflict codes (DUPLICATE_ENTITY, OPTIMISTIC_LOCK, BAD_REQUEST) as problem+json, which
 * krtFetch turns into the right toast.
 */
(function () {
    if (!window.krtFetch) {
        return;
    }
    const i18n = readMessages();

    function readMessages() {
        const holder = document.getElementById('os-i18n');
        const data = holder ? holder.dataset : {};
        return {
            saved: data.saved || 'Saved',
            error: data.error || 'Action failed',
        };
    }

    function fieldValue(id) {
        const el = document.getElementById(id);
        return el ? el.value.trim() : '';
    }

    function emptyToNull(value) {
        return value === '' || value == null ? null : value;
    }

    function reloadOnSuccess() {
        window.location.reload();
    }

    // ---- create the Organisationsleitung -------------------------------------

    const olForm = document.getElementById('ol-form');
    if (olForm) {
        olForm.addEventListener('submit', function (event) {
            event.preventDefault();
            window.krtFetch.write({
                method: 'POST',
                url: '/admin/org-structure/organisationsleitung',
                payload: {
                    name: fieldValue('ol-name'),
                    shorthand: fieldValue('ol-shorthand'),
                    description: emptyToNull(fieldValue('ol-description')),
                },
                successMessage: i18n.saved,
                errorMessage: i18n.error,
                submitter: olForm.querySelector('button[type="submit"]'),
                onSuccess: reloadOnSuccess,
            });
        });
    }

    // ---- create a Bereich ----------------------------------------------------

    const bereichForm = document.getElementById('bereich-form');
    if (bereichForm) {
        bereichForm.addEventListener('submit', function (event) {
            event.preventDefault();
            window.krtFetch.write({
                method: 'POST',
                url: '/admin/org-structure/bereiche',
                payload: {
                    name: fieldValue('bereich-name'),
                    shorthand: fieldValue('bereich-shorthand'),
                    description: emptyToNull(fieldValue('bereich-description')),
                    department: emptyToNull(fieldValue('bereich-department')),
                    parentOrgUnitId: emptyToNull(fieldValue('bereich-parent')),
                },
                successMessage: i18n.saved,
                errorMessage: i18n.error,
                submitter: bereichForm.querySelector('button[type="submit"]'),
                onSuccess: reloadOnSuccess,
            });
        });
    }

    // ---- (re)assign a unit's parent edge -------------------------------------

    document.querySelectorAll('[data-parent-select]').forEach(function (select) {
        select.addEventListener('change', function () {
            const row = select.closest('[data-org-unit-id]');
            if (!row) {
                return;
            }
            const id = row.getAttribute('data-org-unit-id');
            window.krtFetch.write({
                method: 'PATCH',
                url: '/admin/org-structure/org-units/' + encodeURIComponent(id) + '/parent',
                // Serialize per org-unit + read the row version lazily so a rapid second parent
                // change on the same unit queues behind the first and ships the fresh version.
                serialize: 'org-unit:' + id,
                payload: function () {
                    const version = row.getAttribute('data-version');
                    return {
                        parentOrgUnitId: emptyToNull(select.value),
                        version: version == null ? null : Number(version),
                    };
                },
                successMessage: i18n.saved,
                errorMessage: i18n.error,
                submitter: select,
                onSuccess: reloadOnSuccess,
            });
        });
    });
})();
