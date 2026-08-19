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
 * Self-healing waiting page (REQ-SEC-017). Polls the caller's own approval status and reacts to
 * whichever verdict lands, so a decision propagates live instead of only on the next login — the
 * frontend half of the same fix that stopped BackendRoleSyncFilter from pinning a PENDING verdict
 * for the session's whole 720h lifetime.
 *
 * ACTIVE forwards into the tool. REJECTED is terminal (the backend refuses to decide anything but a
 * still-PENDING registration), so the poll stops AND swaps the waiting copy for the rejection copy
 * in place: stopping alone would leave a rejected member reading "waiting for an administrator"
 * indefinitely, which is exactly the bug this page had. The server renders the same swap directly
 * for a caller who is already REJECTED when the page loads, and then omits this script entirely.
 *
 * The poll deliberately outlives the filter's approval re-check interval, so every tick is a genuine
 * backend read. It pauses while the tab is hidden (a waiting page sits in a background tab for
 * hours).
 *
 * URLs and user-facing text come from the template via data-* attributes / rendered markup, so this
 * file carries neither a hardcoded path nor a hardcoded string.
 */
(function () {
    const POLL_INTERVAL_MS = 20000;
    const STATE_ACTIVE = 'ACTIVE';
    const STATE_REJECTED = 'REJECTED';

    const root = document.getElementById('pending-approval');
    if (!root) {
        return;
    }
    // Default to '' rather than leaving these `string | undefined`: the guard
    // below rejects the empty string exactly as it rejects a missing attribute,
    // so the behaviour is unchanged and the polling closures below (hoisted
    // function declarations, which do not inherit the guard's narrowing) see a
    // plain string.
    const statusUrl = root.dataset.statusUrl || '';
    const homeUrl = root.dataset.homeUrl || '';
    if (!statusUrl || !homeUrl) {
        return;
    }
    const approvedNotice = document.getElementById('pending-approval-approved');
    const waitingBlock = document.getElementById('pending-approval-waiting');
    const rejectedBlock = document.getElementById('pending-approval-rejected');

    /** @type {number | null} */
    let timer = null;
    let stopped = false;

    function schedule() {
        window.clearTimeout(timer ?? undefined);
        if (stopped || document.hidden) {
            return;
        }
        timer = window.setTimeout(poll, POLL_INTERVAL_MS);
    }

    function onApproved() {
        stopped = true;
        window.clearTimeout(timer ?? undefined);
        if (approvedNotice) {
            approvedNotice.hidden = false;
        }
        window.location.assign(homeUrl);
    }

    function onRejected() {
        stopped = true;
        window.clearTimeout(timer ?? undefined);
        // Both blocks are in the DOM already (the template renders the pair and hides one), so this
        // is a pure visibility swap — no fetch, no reload, and no user-facing string in this file.
        if (waitingBlock) {
            waitingBlock.hidden = true;
        }
        if (rejectedBlock) {
            rejectedBlock.hidden = false;
        }
    }

    function poll() {
        fetch(statusUrl, {
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        })
            .then(function (response) {
                // A 401 carries the X-Reauthenticate contract and the browser is being sent to the
                // login flow anyway; anything else non-OK is a transient hiccup worth retrying.
                return response.ok ? response.json() : null;
            })
            .then(function (data) {
                const status = data ? data.approvalStatus : null;
                if (status === STATE_ACTIVE) {
                    onApproved();
                    return;
                }
                if (status === STATE_REJECTED) {
                    onRejected();
                    return;
                }
                schedule();
            })
            .catch(function () {
                schedule();
            });
    }

    document.addEventListener('visibilitychange', function () {
        if (stopped) {
            return;
        }
        if (document.hidden) {
            window.clearTimeout(timer ?? undefined);
        } else {
            // Back in the foreground: check immediately rather than waiting out a full interval.
            poll();
        }
    });

    schedule();
})();
