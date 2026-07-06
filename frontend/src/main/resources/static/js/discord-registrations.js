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
 * Discord-registrations admin page module (/admin/discord-registrations), extracted verbatim from the
 * former inline script of admin/discord-registrations.html (ADR-0069, follow-up to #924).
 *
 * Approve / reject pending Discord registrations in place: each button goes through window.krtFetch,
 * removes the row on success (and re-inserts an empty-state row when the last one is gone), and drives
 * the reject-reason modal (open/cancel/confirm, backdrop + Escape close). Requires window.krtFetch;
 * without it the handler returns early (the server-rendered rows stay as the no-JS state).
 *
 * The Thymeleaf-interpolated toast/empty strings stay inline in the page bootstrap as the DISCORD_MSG
 * global this module reads (renamed from the original in-handler 'MSG' const to avoid a generic
 * top-level global name).
 */

/* global DISCORD_MSG */

document.addEventListener('DOMContentLoaded', function () {
    if (!window.krtFetch) {
        return;
    }
    const body = document.getElementById('registrationsBody');
    const modal = document.getElementById('reject-modal');
    const reasonInput = document.getElementById('reject-reason');
    let rejectTarget = null;

    function removeRow(row) {
        row.remove();
        if (!body.querySelector('tr[data-id]')) {
            const empty = document.createElement('tr');
            empty.id = 'registrationsEmpty';
            const cell = document.createElement('td');
            cell.colSpan = 4;
            cell.textContent = DISCORD_MSG.empty;
            empty.appendChild(cell);
            body.appendChild(empty);
        }
    }

    function approve(row) {
        const id = row.getAttribute('data-id');
        const version = row.getAttribute('data-version');
        window.krtFetch.write({
            method: 'POST',
            url: '/admin/discord-registrations/' + encodeURIComponent(id) + '/approve',
            payload: { version: version == null ? null : Number(version) },
            toast: false,
            errorMessage: DISCORD_MSG.approveError,
            onSuccess: function () {
                if (window.showFrontendSuccessToast) {
                    window.showFrontendSuccessToast(DISCORD_MSG.approved);
                }
                removeRow(row);
            },
        });
    }

    function openReject(row) {
        rejectTarget = row;
        if (reasonInput) {
            reasonInput.value = '';
        }
        modal.style.display = 'flex';
    }

    function closeReject() {
        modal.style.display = 'none';
        rejectTarget = null;
    }

    body.addEventListener('click', function (event) {
        const btn = event.target.closest('button[data-action]');
        if (!btn) {
            return;
        }
        const row = btn.closest('tr[data-id]');
        if (!row) {
            return;
        }
        if (btn.getAttribute('data-action') === 'approve') {
            approve(row);
        } else {
            openReject(row);
        }
    });

    const cancelBtn = document.getElementById('reject-cancel');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', closeReject);
    }
    const confirmBtn = document.getElementById('reject-confirm');
    if (confirmBtn) {
        confirmBtn.addEventListener('click', function () {
            if (!rejectTarget) {
                return;
            }
            const row = rejectTarget;
            const id = row.getAttribute('data-id');
            const version = row.getAttribute('data-version');
            const reason = reasonInput ? reasonInput.value.trim() : '';
            window.krtFetch.write({
                method: 'POST',
                url: '/admin/discord-registrations/' + encodeURIComponent(id) + '/reject',
                payload: {
                    reason: reason === '' ? null : reason,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.rejectError,
                onSuccess: function () {
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(DISCORD_MSG.rejected);
                    }
                    closeReject();
                    removeRow(row);
                },
            });
        });
    }

    window.addEventListener('click', function (event) {
        if (event.target === modal) {
            closeReject();
        }
    });
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && modal.style.display === 'flex') {
            closeReject();
        }
    });
});
