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

/* global DISCORD_MSG */

document.addEventListener('DOMContentLoaded', () => {
    if (!window.krtFetch) {
        return;
    }
    const body = document.getElementById('registrationsBody');
    const rejectedBody = document.getElementById('rejectedBody');
    const modal = document.getElementById('reject-modal');
    const reasonInput = document.getElementById('reject-reason');
    const linkModal = document.getElementById('link-modal');
    const mergeModal = document.getElementById('merge-modal');
    const reopenModal = document.getElementById('reopen-modal');
    const reopenReasonInput = document.getElementById('reopen-reason');
    let rejectTarget = null;
    let linkTarget = null;
    let mergeTarget = null;
    let reopenTarget = null;

    function linkPicker() {
        return document.getElementById('link-target');
    }

    function mergePicker() {
        return document.getElementById('merge-source');
    }

    function syncList(tbody, tableId, emptyId, countKey) {
        const rows = tbody ? tbody.querySelectorAll('tr[data-id]').length : 0;
        const table = document.getElementById(tableId);
        const empty = document.getElementById(emptyId);
        if (table) {
            table.hidden = rows === 0;
        }
        if (empty) {
            empty.hidden = rows > 0;
        }
        const count = document.querySelector(`[data-reg-count="${countKey}"]`);
        if (count) {
            count.textContent = String(rows);
        }
        if (countKey === 'open') {
            const headCount = document.getElementById('registrationsCount');
            if (headCount) {
                headCount.textContent = String(rows);
            }
        }
    }

    function syncPending() {
        syncList(body, 'registrationsTable', 'registrationsEmpty', 'open');
    }

    function syncRejected() {
        syncList(rejectedBody, 'rejectedTable', 'rejectedEmpty', 'rejected');
    }

    function removeRow(row) {
        row.remove();
        syncPending();
    }

    function removeRejectedRow(row) {
        row.remove();
        syncRejected();
    }

    const tabs = Array.prototype.slice.call(document.querySelectorAll('[data-reg-tab]'));

    function selectTab(tab, focus) {
        tabs.forEach((other) => {
            const selected = other === tab;
            other.classList.toggle('active', selected);
            other.setAttribute('aria-selected', selected ? 'true' : 'false');
            other.tabIndex = selected ? 0 : -1;
            const panel = document.getElementById(other.getAttribute('aria-controls'));
            if (panel) {
                panel.hidden = !selected;
            }
        });
        if (focus) {
            tab.focus();
        }
    }

    tabs.forEach((tab, index) => {
        tab.addEventListener('click', () => {
            selectTab(tab, false);
        });
        tab.addEventListener('keydown', (event) => {
            let next = null;
            if (event.key === 'ArrowRight') {
                next = tabs[(index + 1) % tabs.length];
            } else if (event.key === 'ArrowLeft') {
                next = tabs[(index - 1 + tabs.length) % tabs.length];
            } else if (event.key === 'Home') {
                next = tabs[0];
            } else if (event.key === 'End') {
                next = tabs[tabs.length - 1];
            }
            if (next) {
                event.preventDefault();
                selectTab(next, true);
            }
        });
    });

    function pad(value) {
        return String(value).padStart(2, '0');
    }

    function formatUtc(iso) {
        if (!iso) {
            return '';
        }
        const date = new Date(iso);
        if (Number.isNaN(date.getTime())) {
            return '';
        }
        return `${pad(date.getUTCDate())}.${pad(
            date.getUTCMonth() + 1,
        )}.${date.getUTCFullYear()} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}`;
    }

    function actionButton(action, className, label) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = className;
        button.setAttribute('data-action', action);
        button.textContent = label;
        return button;
    }

    function textCell(text, className, label) {
        const cell = document.createElement('td');
        if (className) {
            cell.className = className;
        }
        if (label) {
            cell.setAttribute('data-label', label);
        }
        cell.textContent = text;
        return cell;
    }

    function headerLabel(table, index) {
        const th = table ? table.querySelectorAll('thead th')[index] : null;
        return th ? (th.textContent || '').trim() : '';
    }

    function baseRow(reg, idPrefix, table) {
        const row = document.createElement('tr');
        row.id = idPrefix + reg.id;
        row.setAttribute('data-id', reg.id);
        if (reg.version !== null && reg.version !== undefined) {
            row.setAttribute('data-version', String(reg.version));
        }

        const nameCell = document.createElement('td');
        const name = document.createElement('span');
        name.className = 'cell-title';
        name.textContent = reg.username == null ? '' : reg.username;
        nameCell.appendChild(name);
        row.appendChild(nameCell);

        const nickCell = textCell('', '', headerLabel(table, 1));
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

        row.appendChild(textCell(formatUtc(reg.registeredAt), 'reg-when', headerLabel(table, 2)));
        return row;
    }

    function actionsCell(buttons) {
        const cell = document.createElement('td');
        cell.className = 'cell-actions';
        const actions = document.createElement('div');
        actions.className = 'cluster gap-2 reg-actions';
        buttons.forEach((button) => {
            actions.appendChild(button);
        });
        cell.appendChild(actions);
        return cell;
    }

    function insertPendingRow(reg) {
        if (!body || !reg || !reg.id) {
            return;
        }
        const row = baseRow(reg, 'reg-row-', document.getElementById('registrationsTable'));
        row.appendChild(
            actionsCell([
                actionButton('approve', 'btn btn-success btn-xs', DISCORD_MSG.approve),
                actionButton('link', 'btn btn-ghost btn-xs', DISCORD_MSG.link),
                actionButton('merge', 'btn btn-ghost btn-xs', DISCORD_MSG.merge),
                actionButton('reject', 'btn btn-quiet-danger btn-xs', DISCORD_MSG.reject),
            ]),
        );
        body.appendChild(row);
        syncPending();
    }

    function insertRejectedRow(reg) {
        if (!rejectedBody || !reg || !reg.id) {
            return;
        }
        const table = document.getElementById('rejectedTable');
        const row = baseRow(reg, 'rejected-row-', table);
        row.appendChild(
            textCell(
                reg.decidedAt ? formatUtc(reg.decidedAt) : '—',
                'reg-when',
                headerLabel(table, 3),
            ),
        );
        row.appendChild(
            actionsCell([actionButton('reopen', 'btn btn-ghost btn-xs', DISCORD_MSG.reopen)]),
        );
        rejectedBody.insertBefore(row, rejectedBody.firstChild);
        syncRejected();
    }

    function approve(row) {
        const id = row.getAttribute('data-id');
        const version = row.getAttribute('data-version');
        window.krtFetch.write({
            method: 'POST',
            url: `/admin/discord-registrations/${encodeURIComponent(id)}/approve`,
            payload: { version: version == null ? null : Number(version) },
            toast: false,
            errorMessage: DISCORD_MSG.approveError,
            onSuccess() {
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
        window.krtModal.open(modal);
    }

    function closeReject() {
        window.krtModal.close(modal);
        rejectTarget = null;
    }

    function openLink(row) {
        linkTarget = row;
        const picker = linkPicker();
        if (picker && picker.krtCombobox) {
            picker.krtCombobox.setValue('');
        }
        window.krtModal.open(linkModal);
    }

    function closeLink() {
        window.krtModal.close(linkModal);
        linkTarget = null;
    }

    function openMerge(row) {
        mergeTarget = row;
        const picker = mergePicker();
        if (picker && picker.krtCombobox) {
            picker.krtCombobox.setValue('');
        }
        window.krtModal.open(mergeModal);
    }

    function closeMerge() {
        window.krtModal.close(mergeModal);
        mergeTarget = null;
    }

    function openReopen(row) {
        reopenTarget = row;
        if (reopenReasonInput) {
            reopenReasonInput.value = '';
        }
        window.krtModal.open(reopenModal);
    }

    function closeReopen() {
        window.krtModal.close(reopenModal);
        reopenTarget = null;
    }

    body.addEventListener('click', (event) => {
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
        } else if (action === 'merge') {
            openMerge(row);
        } else {
            openReject(row);
        }
    });

    if (rejectedBody) {
        rejectedBody.addEventListener('click', (event) => {
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
        confirmBtn.addEventListener('click', () => {
            if (!rejectTarget) {
                return;
            }
            const row = rejectTarget;
            const id = row.getAttribute('data-id');
            const version = row.getAttribute('data-version');
            const reason = reasonInput ? reasonInput.value.trim() : '';
            window.krtFetch.write({
                method: 'POST',
                url: `/admin/discord-registrations/${encodeURIComponent(id)}/reject`,
                payload: {
                    reason: reason === '' ? null : reason,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.rejectError,
                onSuccess(data) {
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(DISCORD_MSG.rejected);
                    }
                    closeReject();
                    removeRow(row);
                    insertRejectedRow(data);
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
        linkConfirmBtn.addEventListener('click', () => {
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
                url: `/admin/discord-registrations/${encodeURIComponent(id)}/link`,
                payload: {
                    targetUserId,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.linkError,
                onSuccess() {
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(DISCORD_MSG.linked);
                    }
                    closeLink();
                    removeRow(row);
                },
            });
        });
    }

    const mergeCancelBtn = document.getElementById('merge-cancel');
    if (mergeCancelBtn) {
        mergeCancelBtn.addEventListener('click', closeMerge);
    }

    const mergeConfirmBtn = document.getElementById('merge-confirm');
    if (mergeConfirmBtn) {
        mergeConfirmBtn.addEventListener('click', () => {
            if (!mergeTarget) {
                return;
            }
            const picker = mergePicker();
            const sourceUserId = picker && picker.value ? picker.value : '';
            if (!sourceUserId) {
                if (window.showFrontendErrorToast) {
                    window.showFrontendErrorToast(DISCORD_MSG.mergeNoSource);
                }
                return;
            }
            const row = mergeTarget;
            const id = row.getAttribute('data-id');
            const version = row.getAttribute('data-version');
            window.krtFetch.write({
                method: 'POST',
                url: `/admin/discord-registrations/${encodeURIComponent(id)}/merge`,
                payload: {
                    sourceUserId,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.mergeError,
                onSuccess(data) {
                    if (window.showFrontendSuccessToast) {
                        window.showFrontendSuccessToast(DISCORD_MSG.merged);
                    }
                    if (data && data.version != null) {
                        row.setAttribute('data-version', String(data.version));
                    }
                    closeMerge();
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
        reopenConfirmBtn.addEventListener('click', () => {
            if (!reopenTarget) {
                return;
            }
            const row = reopenTarget;
            const id = row.getAttribute('data-id');
            const version = row.getAttribute('data-version');
            const reason = reopenReasonInput ? reopenReasonInput.value.trim() : '';
            window.krtFetch.write({
                method: 'POST',
                url: `/admin/discord-registrations/${encodeURIComponent(id)}/reopen`,
                payload: {
                    reason: reason === '' ? null : reason,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.reopenError,
                onSuccess(data) {
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

    window.addEventListener('click', (event) => {
        if (event.target === modal) {
            closeReject();
        } else if (event.target === linkModal) {
            closeLink();
        } else if (event.target === reopenModal) {
            closeReopen();
        }
    });
    document.addEventListener('keydown', (event) => {
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
