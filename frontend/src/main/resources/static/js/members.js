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
 * Live multi-user sync for the Mitgliederverwaltung roster — the surface issue #1235 calls
 * "Rollen" (REQ-FE-010 / REQ-FE-015, ADR-0094).
 *
 * When any admin edits a member (rank, display name, Staffel membership + its LOGISTICIAN /
 * MISSION_MANAGER flags), deletes one, or triggers the manual Keycloak sync, every other admin's
 * open list re-fetches its OWN search filter and page in place over the shared /ws/sync `members`
 * room. Only the opaque `roster` key crosses the wire — no member data ever rides the socket; each
 * viewer re-pulls its own ADMIN-gated results fragment.
 *
 * The BROADCAST side is server-side (MemberManagementController.liveSyncLocalBus): the edit that
 * invalidates the roster happens on a DIFFERENT page (/members/{id}/edit) and its classic handler
 * redirects, so there is no acting client socket on the list page to publish from.
 *
 * MEMBERS_SECTIONS mirrors the server LiveSyncTopicClass.MEMBERS whitelist (the REQ-FE-010
 * three-mirror-points rule, build-enforced by LiveSyncSectionMapParityTest).
 */
const MEMBERS_SECTIONS = {
    roster: { container: '#members-results', fragmentValue: 'results' },
};

(function () {
    'use strict';

    if (!window.krtLiveSync || typeof window.krtLiveSync.createReceiver !== 'function') {
        return;
    }
    if (!document.getElementById('members-results')) {
        return; // not the list page (the room is also joined from nowhere else)
    }

    window.krtLiveSync.createReceiver({
        topic: 'members',
        sections: MEMBERS_SECTIONS,
        // Global room: the longer coalesce window (#1125) flattens the re-fetch herd — a manual
        // Keycloak sync pokes the room once but every open roster reacts.
        coalesceMs: 1500,
        refresh: function () {
            // Exposed by the members.html bootstrap block; re-swaps the results fragment for the
            // viewer's own current filter + page (history:false), keeping pagination coherent.
            if (typeof window.krtRefreshMembersResults === 'function') {
                window.krtRefreshMembersResults();
            }
        },
    });
})();
