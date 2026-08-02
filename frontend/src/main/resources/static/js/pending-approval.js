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
 * Self-healing waiting page (REQ-SEC-017). Polls the caller's own approval status and sends them
 * into the tool the moment an admin approves, so an approval propagates live instead of only on the
 * next login — the frontend half of the same fix that stopped BackendRoleSyncFilter from pinning a
 * PENDING verdict for the session's whole 720h lifetime.
 *
 * The poll deliberately outlives the filter's approval re-check interval, so every tick is a genuine
 * backend read. It pauses while the tab is hidden (a waiting page sits in a background tab for
 * hours) and stops for good on REJECTED, which is terminal — there is nothing left to wait for.
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

    let timer = null;
    let stopped = false;

    function schedule() {
        window.clearTimeout(timer);
        if (stopped || document.hidden) {
            return;
        }
        timer = window.setTimeout(poll, POLL_INTERVAL_MS);
    }

    function onApproved() {
        stopped = true;
        window.clearTimeout(timer);
        if (approvedNotice) {
            approvedNotice.hidden = false;
        }
        window.location.assign(homeUrl);
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
                    stopped = true;
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
            window.clearTimeout(timer);
        } else {
            // Back in the foreground: check immediately rather than waiting out a full interval.
            poll();
        }
    });

    schedule();
})();
