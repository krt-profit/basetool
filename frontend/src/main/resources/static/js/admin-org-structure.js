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

const ORG_STRUCTURE_SECTIONS = {
    units: { container: '#org-structure-units', fragmentValue: 'units' },
    forms: { container: '#org-structure-forms', fragmentValue: 'forms' },
};

const ORG_STRUCTURE_CHART_SECTION = 'chart';

(function () {
    if (!window.krtFetch) {
        return;
    }
    const i18n = readMessages();

    const orgStructureSeam = window.krtFetch.sectionWrite({
        dict() {
            return { 'admin.orgStructure.refresh.error': i18n.refreshError };
        },
        keys: { refreshErrorKey: 'admin.orgStructure.refresh.error' },
        sections: ORG_STRUCTURE_SECTIONS,
        pageUrl() {
            return '/admin/org-structure';
        },
        broadcast(keys) {
            if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
                window.krtLiveSync.sendChanged(
                    'org-structure',
                    keys.concat([ORG_STRUCTURE_CHART_SECTION]),
                );
            }
        },
    });

    function refreshOrgStructureAfterCreate() {
        return orgStructureSeam.refresh(Object.keys(ORG_STRUCTURE_SECTIONS));
    }

    function refreshOrgStructureAfterParentChange() {
        return orgStructureSeam.refresh(['units']);
    }

    if (window.krtLiveSync && typeof window.krtLiveSync.createReceiver === 'function') {
        window.krtLiveSync.createReceiver({
            topic: 'org-structure',
            sections: ORG_STRUCTURE_SECTIONS,
            coalesceMs: 1500,
            refresh(keys) {
                orgStructureSeam.refresh(keys, { broadcast: false });
            },
            busyTest(sectionKey) {
                if (sectionKey !== 'forms') {
                    return false;
                }
                return Array.prototype.some.call(
                    document.querySelectorAll('#ol-form input, #bereich-form input'),
                    function (el) {
                        return el.value.trim() !== '';
                    },
                );
            },
        });
    }

    function readMessages() {
        const holder = document.getElementById('os-i18n');
        const data = holder ? holder.dataset : {};
        return {
            saved: window.krtI18nText(data.saved, 'data-saved'),
            error: window.krtI18nText(data.error, 'data-error'),
            refreshError: data.refreshError || '',
        };
    }

    function fieldValue(id) {
        const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
        return el ? el.value.trim() : '';
    }

    function emptyToNull(value) {
        return value === '' || value == null ? null : value;
    }

    if (!window.krtEvents || typeof window.krtEvents.on !== 'function') {
        return;
    }

    window.krtEvents.on('submit', 'os-create-ol', function (form, event) {
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
            submitter: form.querySelector('button[type="submit"]'),
            onSuccess: refreshOrgStructureAfterCreate,
        });
    });

    window.krtEvents.on('submit', 'os-create-bereich', function (form, event) {
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
            submitter: form.querySelector('button[type="submit"]'),
            onSuccess: refreshOrgStructureAfterCreate,
        });
    });

    window.krtEvents.on('change', 'os-parent-select', function (select) {
        const row = select.closest('[data-org-unit-id]');
        if (!row) {
            return;
        }
        const id = row.getAttribute('data-org-unit-id');
        window.krtFetch.write({
            method: 'PATCH',
            url: '/admin/org-structure/org-units/' + encodeURIComponent(String(id)) + '/parent',
            serialize: 'org-unit:' + id,
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
            onSuccess: refreshOrgStructureAfterParentChange,
        });
    });
})();
