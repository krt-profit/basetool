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

/*
 * Admin org-structure editor (epic #692, REQ-ORG-014): creates Bereiche and the Organisationsleitung
 * and wires the parent edges (Staffel/SK -> Bereich, Bereich -> OL) via window.krtFetch (CSRF +
 * 403-retry handled centrally). On a successful mutation the affected sections re-render in place
 * through the ?fragment=units / ?fragment=forms seams (REQ-FE-001 — the former full-page reload is
 * gone, #1235) and the change is broadcast to peers (REQ-FE-010). The backend relays its conflict
 * codes (DUPLICATE_ENTITY, OPTIMISTIC_LOCK, BAD_REQUEST) as problem+json, which krtFetch turns into
 * the right toast.
 */

// ---- Live multi-user sync — the org structure (REQ-FE-010 / REQ-FE-015, ADR-0094, #1235) -------
// ORG_STRUCTURE_SECTIONS is the single source of truth shared by this page's write-side broadcast
// and its receive-side refresh (the three-mirror-points rule). Its two keys are a SUBSET of the
// server LiveSyncTopicClass.ORG_STRUCTURE whitelist — the third key, `chart`, belongs to the
// member-visible Organigramm (org-chart.js), which shares the room because both surfaces render the
// same hierarchy. A structural change here therefore also pokes `chart`, and an org-chart position
// edit pokes `units` back (publishing needs no subscription).
const ORG_STRUCTURE_SECTIONS = {
    units: { container: '#org-structure-units', fragmentValue: 'units' },
    forms: { container: '#org-structure-forms', fragmentValue: 'forms' },
};

// The key of the sibling Organigramm section, poked but never rendered by this page.
const ORG_STRUCTURE_CHART_SECTION = 'chart';

(function () {
    if (!window.krtFetch) {
        return;
    }
    const i18n = readMessages();

    const orgStructureSeam = window.krtFetch.sectionWrite({
        dict: function () {
            return { 'admin.orgStructure.refresh.error': i18n.refreshError };
        },
        keys: { refreshErrorKey: 'admin.orgStructure.refresh.error' },
        sections: ORG_STRUCTURE_SECTIONS,
        pageUrl: function () {
            return '/admin/org-structure';
        },
        // Peers re-render whichever of the three sections their own page actually shows: the two
        // editor sections here, the tree on /org-chart.
        broadcast: function (keys) {
            if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
                window.krtLiveSync.sendChanged(
                    'org-structure',
                    keys.concat([ORG_STRUCTURE_CHART_SECTION]),
                );
            }
        },
    });

    // Re-renders the sections a local mutation actually invalidated and tells peers. Replaces the
    // former window.location.reload().
    //
    // A CREATE refreshes both: the new unit appears in the table AND in the create forms' parent
    // pickers. A PARENT-EDGE change refreshes only `units` — it moves a row but adds no unit, so the
    // pickers' option sets are unchanged, and re-rendering `forms` would needlessly wipe whatever
    // the admin had already typed into a create form.
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
            // Global room: the longer coalesce window (#1125) flattens the re-fetch herd.
            coalesceMs: 1500,
            // broadcast:false — applying a peer's signal must never echo it back into a loop.
            refresh: function (keys) {
                orgStructureSeam.refresh(keys, { broadcast: false });
            },
            // Never swap a half-filled create form out from under the admin. The generic busy test
            // only catches a FOCUSED container, but an admin can type a name, tab away to pick a
            // department and still have unsaved text here — so treat any non-empty create input as
            // busy and hold the section behind the "updates available" pill instead.
            busyTest: function (sectionKey) {
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
            saved: data.saved || 'Saved',
            error: data.error || 'Action failed',
            // Surfaced when an in-place section swap bails (e.g. an expired session bounced to the
            // login page); the stale section is then left untouched rather than overwritten.
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

    // ---- delegated bindings ---------------------------------------------------
    // Document-delegated (not bound to the elements directly): BOTH create forms live inside the
    // swapped `forms` fragment and every parent select inside the swapped `units` fragment, so a
    // direct addEventListener would be lost the first time a local or peer change re-renders the
    // section — the exact silent-breakage the former window.location.reload() hid.
    if (!window.krtEvents || typeof window.krtEvents.on !== 'function') {
        return;
    }

    // ---- create the Organisationsleitung -------------------------------------

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

    // ---- create a Bereich ----------------------------------------------------

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

    // ---- (re)assign a unit's parent edge -------------------------------------

    window.krtEvents.on('change', 'os-parent-select', function (select) {
        const row = select.closest('[data-org-unit-id]');
        if (!row) {
            return;
        }
        const id = row.getAttribute('data-org-unit-id');
        window.krtFetch.write({
            method: 'PATCH',
            url: '/admin/org-structure/org-units/' + encodeURIComponent(String(id)) + '/parent',
            // Serialize per org-unit + read the row version lazily so a rapid second parent
            // change on the same unit queues behind the first and ships the fresh version.
            serialize: 'org-unit:' + id,
            payload: function () {
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
