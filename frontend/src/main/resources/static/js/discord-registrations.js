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
 * Approve / reject / link pending Discord registrations in place: each button goes through
 * window.krtFetch, removes the row on success (and re-inserts an empty-state row when the last one is
 * gone), and drives three modals (open/cancel/confirm, backdrop + Escape close) — the reject-reason
 * modal, the link-to-existing-account modal (a server-searched remote-users account picker,
 * REQ-SEC-026) and the reopen-confirmation modal. Requires window.krtFetch; without it the handler
 * returns early (the server-rendered rows stay as the no-JS state).
 *
 * The page carries two tables: the pending queue and the rejected registrations. Reopening moves a
 * row out of the second and into the first without a reload (REQ-SEC-034). The inserted queue row is
 * rebuilt from the server's response rather than cloned from the rejected row, so its version and
 * fields are the backend's rather than a stale copy of what the page was rendered with.
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
    const rejectedBody = document.getElementById('rejectedBody');
    const modal = document.getElementById('reject-modal');
    const reasonInput = document.getElementById('reject-reason');
    const linkModal = document.getElementById('link-modal');
    const reopenModal = document.getElementById('reopen-modal');
    const reopenReasonInput = document.getElementById('reopen-reason');
    let rejectTarget = null;
    let linkTarget = null;
    let reopenTarget = null;

    // The account picker's <select> is progressively enhanced by krt-searchable-select.js, which
    // moves the id onto a hidden input — so it is looked up lazily on open/confirm, never cached at
    // load time (the cached reference would point at the removed original <select>).
    function linkPicker() {
        return document.getElementById('link-target');
    }

    // Table-agnostic: the pending queue and the rejected table differ only in their tbody, their
    // empty-state row id and text, and their column count (the rejected table carries an extra
    // "rejected at" column), so one helper drives both.
    function removeRowFrom(row, tbody, emptyId, colSpan, emptyText) {
        row.remove();
        if (!tbody || tbody.querySelector('tr[data-id]')) {
            return;
        }
        const empty = document.createElement('tr');
        empty.id = emptyId;
        const cell = document.createElement('td');
        cell.colSpan = colSpan;
        cell.textContent = emptyText;
        empty.appendChild(cell);
        tbody.appendChild(empty);
    }

    function removeRow(row) {
        removeRowFrom(row, body, 'registrationsEmpty', 4, DISCORD_MSG.empty);
    }

    function removeRejectedRow(row) {
        removeRowFrom(row, rejectedBody, 'rejectedEmpty', 5, DISCORD_MSG.rejectedEmpty);
    }

    function pad(value) {
        return String(value).padStart(2, '0');
    }

    // Mirrors the server-side #temporals.format(..., 'dd.MM.yyyy HH:mm', 'UTC') so a row inserted by
    // JS reads identically to a server-rendered one. An unparsable value renders empty rather than
    // a string of NaNs.
    function formatUtc(iso) {
        if (!iso) {
            return '';
        }
        const date = new Date(iso);
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        return (
            pad(date.getUTCDate()) +
            '.' +
            pad(date.getUTCMonth() + 1) +
            '.' +
            date.getUTCFullYear() +
            ' ' +
            pad(date.getUTCHours()) +
            ':' +
            pad(date.getUTCMinutes())
        );
    }

    function actionButton(action, className, label) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = className;
        button.setAttribute('data-action', action);
        button.textContent = label;
        return button;
    }

    // Builds a queue row matching the server-rendered markup so the delegated click handler and the
    // empty-state bookkeeping keep working on it unchanged.
    function insertPendingRow(reg) {
        if (!body || !reg || !reg.id) {
            return;
        }
        const placeholder = document.getElementById('registrationsEmpty');
        if (placeholder) {
            placeholder.remove();
        }
        const row = document.createElement('tr');
        row.id = 'reg-row-' + reg.id;
        row.setAttribute('data-id', reg.id);
        if (reg.version !== null && reg.version !== undefined) {
            row.setAttribute('data-version', String(reg.version));
        }

        const nameCell = document.createElement('td');
        nameCell.textContent = reg.username == null ? '' : reg.username;
        row.appendChild(nameCell);

        const nickCell = document.createElement('td');
        const nick = document.createElement('span');
        if (reg.serverNickname) {
            nick.textContent = reg.serverNickname;
        } else {
            nick.className = 'reg-when';
            nick.setAttribute('aria-hidden', 'true');
            nick.textContent = '—';
        }
        nickCell.appendChild(nick);
        row.appendChild(nickCell);

        const whenCell = document.createElement('td');
        whenCell.className = 'reg-when';
        whenCell.textContent = formatUtc(reg.registeredAt);
        row.appendChild(whenCell);

        const actionsCell = document.createElement('td');
        const actions = document.createElement('div');
        actions.className = 'reg-actions';
        actions.appendChild(actionButton('approve', 'btn btn--cta', DISCORD_MSG.approve));
        actions.appendChild(actionButton('link', 'btn btn-outline', DISCORD_MSG.link));
        actions.appendChild(actionButton('reject', 'btn btn-outline-danger', DISCORD_MSG.reject));
        actionsCell.appendChild(actions);
        row.appendChild(actionsCell);

        body.appendChild(row);
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

    function openLink(row) {
        linkTarget = row;
        const picker = linkPicker();
        if (picker && picker.krtCombobox) {
            picker.krtCombobox.setValue('');
        }
        linkModal.style.display = 'flex';
    }

    function closeLink() {
        linkModal.style.display = 'none';
        linkTarget = null;
    }

    function openReopen(row) {
        reopenTarget = row;
        if (reopenReasonInput) {
            reopenReasonInput.value = '';
        }
        reopenModal.style.display = 'flex';
    }

    function closeReopen() {
        reopenModal.style.display = 'none';
        reopenTarget = null;
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
        const action = btn.getAttribute('data-action');
        if (action === 'approve') {
            approve(row);
        } else if (action === 'link') {
            openLink(row);
        } else {
            openReject(row);
        }
    });

    if (rejectedBody) {
        rejectedBody.addEventListener('click', function (event) {
            const btn = event.target.closest('button[data-action="reopen"]');
            if (!btn) {
                return;
            }
            const row = btn.closest('tr[data-id]');
            if (row) {
                openReopen(row);
            }
        });
    }

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

    const linkCancelBtn = document.getElementById('link-cancel');
    if (linkCancelBtn) {
        linkCancelBtn.addEventListener('click', closeLink);
    }
    const linkConfirmBtn = document.getElementById('link-confirm');
    if (linkConfirmBtn) {
        linkConfirmBtn.addEventListener('click', function () {
            if (!linkTarget) {
                return;
            }
            const picker = linkPicker();
            const targetUserId = picker && picker.value ? picker.value : '';
            if (!targetUserId) {
                if (window.showFrontendErrorToast) {
                    window.showFrontendErrorToast(DISCORD_MSG.linkNoTarget);
                }
                return;
            }
            const row = linkTarget;
            const id = row.getAttribute('data-id');
            const version = row.getAttribute('data-version');
            window.krtFetch.write({
                method: 'POST',
                url: '/admin/discord-registrations/' + encodeURIComponent(id) + '/link',
                payload: {
                    targetUserId: targetUserId,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.linkError,
                onSuccess: function () {
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(DISCORD_MSG.linked);
                    }
                    closeLink();
                    removeRow(row);
                },
            });
        });
    }

    const reopenCancelBtn = document.getElementById('reopen-cancel');
    if (reopenCancelBtn) {
        reopenCancelBtn.addEventListener('click', closeReopen);
    }
    const reopenConfirmBtn = document.getElementById('reopen-confirm');
    if (reopenConfirmBtn) {
        reopenConfirmBtn.addEventListener('click', function () {
            if (!reopenTarget) {
                return;
            }
            const row = reopenTarget;
            const id = row.getAttribute('data-id');
            const version = row.getAttribute('data-version');
            const reason = reopenReasonInput ? reopenReasonInput.value.trim() : '';
            window.krtFetch.write({
                method: 'POST',
                url: '/admin/discord-registrations/' + encodeURIComponent(id) + '/reopen',
                payload: {
                    reason: reason === '' ? null : reason,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.reopenError,
                onSuccess: function (data) {
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(DISCORD_MSG.reopened);
                    }
                    closeReopen();
                    removeRejectedRow(row);
                    insertPendingRow(data);
                },
            });
        });
    }

    window.addEventListener('click', function (event) {
        if (event.target === modal) {
            closeReject();
        } else if (event.target === linkModal) {
            closeLink();
        } else if (event.target === reopenModal) {
            closeReopen();
        }
    });
    document.addEventListener('keydown', function (event) {
        if (event.key !== 'Escape') {
            return;
        }
        if (window.getComputedStyle(modal).display === 'flex') {
            closeReject();
        } else if (linkModal && window.getComputedStyle(linkModal).display === 'flex') {
            closeLink();
        } else if (reopenModal && window.getComputedStyle(reopenModal).display === 'flex') {
            closeReopen();
        }
    });
});
