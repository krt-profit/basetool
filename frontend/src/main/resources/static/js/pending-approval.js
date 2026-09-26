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

(function () {
    const POLL_INTERVAL_MS = 20000;
    const STATE_ACTIVE = 'ACTIVE';
    const STATE_REJECTED = 'REJECTED';

    const root = document.getElementById('pending-approval');
    if (!root) {
        return;
    }
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
            poll();
        }
    });

    schedule();
})();
