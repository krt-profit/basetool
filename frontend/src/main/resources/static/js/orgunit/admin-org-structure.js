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

import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

function initAdminOrgStructure() {
    const i18n = readMessages();

    const orgStructure = krtPageSections.create({
        dict() {
            return { 'admin.orgStructure.refresh.error': i18n.refreshError };
        },
        keys: { refreshErrorKey: 'admin.orgStructure.refresh.error' },
        sections: {
            units: { container: '#org-structure-units', fragmentValue: 'units' },
            forms: { container: '#org-structure-forms', fragmentValue: 'forms' },
        },
        pageUrl() {
            return '/admin/org-structure';
        },
        topic: 'org-structure',
        alsoBroadcast: ['chart'],
        receiver: {
            coalesceMs: 1500,
            /**
             * @param {string} sectionKey the section about to be replaced
             * @returns {boolean} true while the section holds unsaved input
             */
            busyTest(sectionKey) {
                if (sectionKey !== 'forms') {
                    return false;
                }
                return Array.prototype.some.call(
                    document.querySelectorAll('#ol-form input, #bereich-form input'),
                    (el) => {
                        return el.value.trim() !== '';
                    },
                );
            },
        },
    });

    function readMessages() {
        const holder = document.getElementById('os-i18n');
        const data = holder ? holder.dataset : {};
        return {
            saved: krtI18nText(data.saved, 'data-saved'),
            error: krtI18nText(data.error, 'data-error'),
            refreshError: data.refreshError || '',
        };
    }

    /**
     * Reads a trimmed input value.
     *
     * @param {string} id the input element id
     * @returns {string} the trimmed value, or an empty string when the input is missing
     */
    function fieldValue(id) {
        const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
        return el ? el.value.trim() : '';
    }

    /**
     * Maps an empty value to null.
     *
     * @param {string | null | undefined} value the value
     * @returns {string | null} the value, or null when empty
     */
    function emptyToNull(value) {
        return value === '' || value == null ? null : value;
    }

    krtEvents.on('submit', 'os-create-ol', (form, event) => {
        event.preventDefault();
        orgStructure.write({
            method: 'POST',
            url: '/admin/org-structure/organisationsleitung',
            payload: {
                name: fieldValue('ol-name'),
                shorthand: fieldValue('ol-shorthand'),
                description: emptyToNull(fieldValue('ol-description')),
            },
            successMessage: i18n.saved,
            errorMessage: i18n.error,
            submitter: form.querySelector('button[type="submit"]'),
            refresh: ['units', 'forms'],
        });
    });

    krtEvents.on('submit', 'os-create-bereich', (form, event) => {
        event.preventDefault();
        orgStructure.write({
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
            submitter: form.querySelector('button[type="submit"]'),
            refresh: ['units', 'forms'],
        });
    });

    krtEvents.on('change', 'os-parent-select', (select) => {
        const row = select.closest('[data-org-unit-id]');
        if (!row) {
            return;
        }
        const id = row.getAttribute('data-org-unit-id');
        orgStructure.write({
            method: 'PATCH',
            url: `/admin/org-structure/org-units/${encodeURIComponent(String(id))}/parent`,
            serialize: `org-unit:${id}`,
            payload() {
                const version = row.getAttribute('data-version');
                return {
                    parentOrgUnitId: emptyToNull(/** @type {HTMLSelectElement} */ (select).value),
                    version: version == null ? null : Number(version),
                };
            },
            successMessage: i18n.saved,
            errorMessage: i18n.error,
            submitter: select,
            refresh: ['units'],
        });
    });
}

initAdminOrgStructure();
