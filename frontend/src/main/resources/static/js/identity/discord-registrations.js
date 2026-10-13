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

import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';

/**
 * @typedef {object} DiscordRegistration
 * @property {string} id
 * @property {number | null} [version]
 * @property {string | null} [username]
 * @property {string | null} [serverNickname]
 * @property {string | null} [registeredAt] ISO instant
 * @property {string | null} [decidedAt] ISO instant
 */

document.addEventListener('DOMContentLoaded', () => {
    const body = document.getElementById('registrationsBody');
    const rejectedBody = document.getElementById('rejectedBody');
    const modal = document.getElementById('reject-modal');
    const reasonInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('reject-reason')
    );
    const linkModal = document.getElementById('link-modal');
    const mergeModal = document.getElementById('merge-modal');
    const reopenModal = document.getElementById('reopen-modal');
    const reopenReasonInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('reopen-reason')
    );
    /** @type {Element | null} */
    let rejectTarget = null;
    /** @type {Element | null} */
    let linkTarget = null;
    /** @type {Element | null} */
    let mergeTarget = null;
    /** @type {Element | null} */
    let reopenTarget = null;

    /** @returns {HTMLInputElement | null} the combobox host of the link dialog */
    function linkPicker() {
        return /** @type {HTMLInputElement | null} */ (document.getElementById('link-target'));
    }

    /** @returns {HTMLInputElement | null} the combobox host of the merge dialog */
    function mergePicker() {
        return /** @type {HTMLInputElement | null} */ (document.getElementById('merge-source'));
    }

    /**
     * @param {HTMLElement | null} tbody the table body whose rows are counted
     * @param {string} tableId id of the table shown while rows exist
     * @param {string} emptyId id of the empty state shown otherwise
     * @param {string} countKey `data-reg-count` key of the tab chip
     */
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

    /**
     * @param {Element} row the pending-registration row to remove
     */
    function removeRow(row) {
        row.remove();
        syncPending();
    }

    /**
     * @param {Element} row the rejected-registration row to remove
     */
    function removeRejectedRow(row) {
        row.remove();
        syncRejected();
    }

    const tabs = /** @type {HTMLElement[]} */ (
        Array.prototype.slice.call(document.querySelectorAll('[data-reg-tab]'))
    );

    /**
     * @param {HTMLElement} tab the tab to select
     * @param {boolean} focus whether the tab also takes focus
     */
    function selectTab(tab, focus) {
        tabs.forEach((other) => {
            const selected = other === tab;
            other.classList.toggle('active', selected);
            other.setAttribute('aria-selected', selected ? 'true' : 'false');
            other.tabIndex = selected ? 0 : -1;
            const panel = document.getElementById(
                /** @type {string} */ (other.getAttribute('aria-controls')),
            );
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
        tab.addEventListener('keydown', (/** @type {KeyboardEvent} */ event) => {
            /** @type {HTMLElement | null} */
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

    /**
     * @param {number | string} value the value to left-pad with zeros
     * @returns {string} the value padded to two characters
     */
    function pad(value) {
        return String(value).padStart(2, '0');
    }

    /**
     * @param {string | null | undefined} iso an ISO instant
     * @returns {string} `dd.MM.yyyy HH:mm` in UTC, or an empty string when absent or invalid
     */
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

    /**
     * @param {string} action the `data-action` value
     * @param {string} className the button classes
     * @param {string} label the button text
     * @returns {HTMLButtonElement} the new button
     */
    function actionButton(action, className, label) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = className;
        button.setAttribute('data-action', action);
        button.textContent = label;
        return button;
    }

    /**
     * @param {string} text the cell text
     * @param {string} className the cell classes, or an empty string
     * @param {string} label the `data-label` for the stacked layout, or an empty string
     * @returns {HTMLTableCellElement} the new cell
     */
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

    /**
     * @param {HTMLElement | null} table the table whose header is read
     * @param {number} index the column index
     * @returns {string} the trimmed header text, or an empty string
     */
    function headerLabel(table, index) {
        const th = table ? table.querySelectorAll('thead th')[index] : null;
        return th ? (th.textContent || '').trim() : '';
    }

    /**
     * @param {DiscordRegistration} reg the registration
     * @param {string} idPrefix prefix of the row id
     * @param {HTMLElement | null} table the table the row joins
     * @returns {HTMLTableRowElement} the row with its name, nickname and date cells
     */
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

    /**
     * @param {HTMLButtonElement[]} buttons the action buttons
     * @returns {HTMLTableCellElement} the actions cell
     */
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

    /**
     * @param {DiscordRegistration | null | undefined} reg the reopened registration
     */
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

    /**
     * @param {DiscordRegistration | null | undefined} reg the rejected registration
     */
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

    /**
     * @param {Element} row the pending-registration row to approve
     */
    function approve(row) {
        const id = /** @type {string} */ (row.getAttribute('data-id'));
        const version = row.getAttribute('data-version');
        krtFetch.write({
            method: 'POST',
            url: `/admin/discord-registrations/${encodeURIComponent(id)}/approve`,
            payload: { version: version == null ? null : Number(version) },
            toast: false,
            errorMessage: DISCORD_MSG.approveError,
            onSuccess() {
                showFrontendSuccessToast(DISCORD_MSG.approved);
                removeRow(row);
            },
        });
    }

    /**
     * @param {Element} row the row to reject
     */
    function openReject(row) {
        rejectTarget = row;
        if (reasonInput) {
            reasonInput.value = '';
        }
        krtModal.open(modal);
    }

    function closeReject() {
        krtModal.close(modal);
        rejectTarget = null;
    }

    /**
     * @param {Element} row the row to link
     */
    function openLink(row) {
        linkTarget = row;
        const picker = linkPicker();
        if (picker && picker.krtCombobox) {
            picker.krtCombobox.setValue('');
        }
        krtModal.open(linkModal);
    }

    function closeLink() {
        krtModal.close(linkModal);
        linkTarget = null;
    }

    /**
     * @param {Element} row the row to merge
     */
    function openMerge(row) {
        mergeTarget = row;
        const picker = mergePicker();
        if (picker && picker.krtCombobox) {
            picker.krtCombobox.setValue('');
        }
        krtModal.open(mergeModal);
    }

    function closeMerge() {
        krtModal.close(mergeModal);
        mergeTarget = null;
    }

    /**
     * @param {Element} row the rejected row to reopen
     */
    function openReopen(row) {
        reopenTarget = row;
        if (reopenReasonInput) {
            reopenReasonInput.value = '';
        }
        krtModal.open(reopenModal);
    }

    function closeReopen() {
        krtModal.close(reopenModal);
        reopenTarget = null;
    }

    /** @type {HTMLElement} */ (body).addEventListener('click', (event) => {
        const btn = /** @type {Element} */ (event.target).closest('button[data-action]');
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
            const btn = /** @type {Element} */ (event.target).closest(
                'button[data-action="reopen"]',
            );
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
            krtFetch.write({
                method: 'POST',
                url: `/admin/discord-registrations/${encodeURIComponent(String(id))}/reject`,
                payload: {
                    reason: reason === '' ? null : reason,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.rejectError,
                onSuccess(data) {
                    showFrontendSuccessToast(DISCORD_MSG.rejected);
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
                showFrontendErrorToast(DISCORD_MSG.linkNoTarget);
                return;
            }
            const row = linkTarget;
            const id = row.getAttribute('data-id');
            const version = row.getAttribute('data-version');
            krtFetch.write({
                method: 'POST',
                url: `/admin/discord-registrations/${encodeURIComponent(String(id))}/link`,
                payload: {
                    targetUserId,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.linkError,
                onSuccess() {
                    showFrontendSuccessToast(DISCORD_MSG.linked);
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
                showFrontendErrorToast(DISCORD_MSG.mergeNoSource);
                return;
            }
            const row = mergeTarget;
            const id = row.getAttribute('data-id');
            const version = row.getAttribute('data-version');
            krtFetch.write({
                method: 'POST',
                url: `/admin/discord-registrations/${encodeURIComponent(String(id))}/merge`,
                payload: {
                    sourceUserId,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.mergeError,
                onSuccess(data) {
                    showFrontendSuccessToast(DISCORD_MSG.merged);
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
            krtFetch.write({
                method: 'POST',
                url: `/admin/discord-registrations/${encodeURIComponent(String(id))}/reopen`,
                payload: {
                    reason: reason === '' ? null : reason,
                    version: version == null ? null : Number(version),
                },
                toast: false,
                errorMessage: DISCORD_MSG.reopenError,
                onSuccess(data) {
                    showFrontendSuccessToast(DISCORD_MSG.reopened);
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
        if (window.getComputedStyle(/** @type {HTMLElement} */ (modal)).display === 'flex') {
            closeReject();
        } else if (linkModal && window.getComputedStyle(linkModal).display === 'flex') {
            closeLink();
        } else if (reopenModal && window.getComputedStyle(reopenModal).display === 'flex') {
            closeReopen();
        }
    });
});
