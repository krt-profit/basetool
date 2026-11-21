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
import { krtPageSections } from '/js/kernel/krt-page-sections.js';

/** The admin dashboard section the backend announces every change of the queue in (REQ-UI-034). */
const QUEUE_SECTION = 'discord-registrations';

/**
 * @typedef {object} Decision
 * @property {Element} row the registration's row
 * @property {string} action the decision's path segment below the registration
 * @property {Record<string, unknown>} [payload] the body sent beside the version
 * @property {string} success the localized success toast
 * @property {string} error the localized failure toast
 * @property {boolean} [stays] whether the row stays on its list, as after a merge
 * @property {() => void} [close] closes the dialog the decision was confirmed in
 */

document.addEventListener('DOMContentLoaded', () => {
    const panels = document.getElementById('reg-panels');
    if (!panels) {
        return;
    }
    const queue = krtPageSections.create({
        topicClass: 'ADMIN_DASHBOARD',
        topic: 'admin-dashboard',
        sections: {
            'discord-registrations': {
                container: '#reg-panels',
                fragmentValue: 'panels',
                refreshWhileModalOpen: true,
            },
        },
        dict() {
            return { refreshError: DISCORD_MSG.loadError };
        },
        keys: { refreshErrorKey: 'refreshError' },
        pageUrl() {
            return window.location.pathname;
        },
        receiver: { coalesceMs: 1500 },
    });
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
    /** @type {string | null} */
    let rejectTarget = null;
    /** @type {string | null} */
    let linkTarget = null;
    /** @type {string | null} */
    let mergeTarget = null;
    /** @type {string | null} */
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
     * @param {string | null} rowId the element id of the row a dialog was opened for
     * @returns {Element | null} that row as the panels show it now, or null once it left them
     */
    function currentRow(rowId) {
        return rowId ? document.getElementById(rowId) : null;
    }

    /**
     * @param {string} bodyId id of the table body whose rows are counted
     * @param {string} tableId id of the table shown while rows exist
     * @param {string} emptyId id of the empty state shown otherwise
     * @param {string} countKey `data-reg-count` key of the tab chip
     */
    function syncList(bodyId, tableId, emptyId, countKey) {
        const tbody = document.getElementById(bodyId);
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

    /** Brings both lists' tables, empty states and counts in line with their rows. */
    function syncLists() {
        syncList('registrationsBody', 'registrationsTable', 'registrationsEmpty', 'open');
        syncList('rejectedBody', 'rejectedTable', 'rejectedEmpty', 'rejected');
    }

    /**
     * Re-renders both panels from the server; the backend announces the change to the other
     * viewers itself.
     *
     * @returns {Promise<boolean[]>} whether the panels were re-rendered
     */
    function rerender() {
        return queue.refresh(QUEUE_SECTION, { broadcast: false });
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

    /** @returns {HTMLElement | undefined} the tab of the list shown now */
    function selectedTab() {
        return tabs.find((tab) => tab.getAttribute('aria-selected') === 'true');
    }

    /**
     * Moves focus from a row about to leave the panels to the selected tab, so a focused row control
     * neither holds back the re-render of a live-sync frame nor drops focus to the page.
     */
    function focusSelectedTab() {
        const selected = selectedTab();
        if (selected) {
            selected.focus();
        }
    }

    document.addEventListener('krt:swapped', (event) => {
        const detail = /** @type {CustomEvent<{ container?: Element }>} */ (event).detail;
        if (!detail || detail.container !== panels) {
            return;
        }
        const selected = selectedTab();
        if (selected) {
            selectTab(selected, false);
        }
        syncLists();
    });

    /**
     * Sends one decision through the queue's write seam and re-renders both panels afterwards. A
     * conflict re-renders them too, so the next try carries the current version without a reload.
     *
     * @param {Decision} decision the decision to send
     */
    function decide(decision) {
        const row = decision.row;
        const id = row.getAttribute('data-id') || '';
        const version = row.getAttribute('data-version');
        const close = decision.close;
        queue.write({
            method: 'POST',
            url: `/admin/discord-registrations/${encodeURIComponent(id)}/${decision.action}`,
            payload: Object.assign({}, decision.payload, {
                version: version == null ? null : Number(version),
            }),
            toast: false,
            errorMessage: decision.error,
            onSuccess() {
                showFrontendSuccessToast(decision.success);
                if (close) {
                    close();
                }
                if (!decision.stays) {
                    row.remove();
                    syncLists();
                }
                return rerender();
            },
            onConflict() {
                showFrontendErrorToast(DISCORD_MSG.conflict);
                if (close) {
                    close();
                }
                rerender();
            },
        });
    }

    /**
     * Closes a dialog whose registration left the panels while it was open and says so.
     *
     * @param {() => void} close closes the dialog
     */
    function targetGone(close) {
        showFrontendErrorToast(DISCORD_MSG.conflict);
        close();
    }

    /**
     * @param {Element} row the row to reject
     */
    function openReject(row) {
        rejectTarget = row.id;
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
        linkTarget = row.id;
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
        mergeTarget = row.id;
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
        reopenTarget = row.id;
        if (reopenReasonInput) {
            reopenReasonInput.value = '';
        }
        krtModal.open(reopenModal);
    }

    function closeReopen() {
        krtModal.close(reopenModal);
        reopenTarget = null;
    }

    panels.addEventListener('click', (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const btn = target ? target.closest('button[data-action]') : null;
        const row = btn ? btn.closest('tr[data-id]') : null;
        if (!btn || !row) {
            return;
        }
        const action = btn.getAttribute('data-action');
        if (action === 'approve') {
            focusSelectedTab();
            decide({
                row,
                action: 'approve',
                success: DISCORD_MSG.approved,
                error: DISCORD_MSG.approveError,
            });
        } else if (action === 'link') {
            openLink(row);
        } else if (action === 'merge') {
            openMerge(row);
        } else if (action === 'reject') {
            openReject(row);
        } else if (action === 'reopen') {
            openReopen(row);
        }
    });

    const cancelBtn = document.getElementById('reject-cancel');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', closeReject);
    }
    const confirmBtn = document.getElementById('reject-confirm');
    if (confirmBtn) {
        confirmBtn.addEventListener('click', () => {
            const row = currentRow(rejectTarget);
            if (!row) {
                targetGone(closeReject);
                return;
            }
            const reason = reasonInput ? reasonInput.value.trim() : '';
            decide({
                row,
                action: 'reject',
                payload: { reason: reason === '' ? null : reason },
                success: DISCORD_MSG.rejected,
                error: DISCORD_MSG.rejectError,
                close: closeReject,
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
            const row = currentRow(linkTarget);
            if (!row) {
                targetGone(closeLink);
                return;
            }
            const picker = linkPicker();
            const targetUserId = picker && picker.value ? picker.value : '';
            if (!targetUserId) {
                showFrontendErrorToast(DISCORD_MSG.linkNoTarget);
                return;
            }
            decide({
                row,
                action: 'link',
                payload: { targetUserId },
                success: DISCORD_MSG.linked,
                error: DISCORD_MSG.linkError,
                close: closeLink,
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
            const row = currentRow(mergeTarget);
            if (!row) {
                targetGone(closeMerge);
                return;
            }
            const picker = mergePicker();
            const sourceUserId = picker && picker.value ? picker.value : '';
            if (!sourceUserId) {
                showFrontendErrorToast(DISCORD_MSG.mergeNoSource);
                return;
            }
            decide({
                row,
                action: 'merge',
                payload: { sourceUserId },
                success: DISCORD_MSG.merged,
                error: DISCORD_MSG.mergeError,
                stays: true,
                close: closeMerge,
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
            const row = currentRow(reopenTarget);
            if (!row) {
                targetGone(closeReopen);
                return;
            }
            const reason = reopenReasonInput ? reopenReasonInput.value.trim() : '';
            decide({
                row,
                action: 'reopen',
                payload: { reason: reason === '' ? null : reason },
                success: DISCORD_MSG.reopened,
                error: DISCORD_MSG.reopenError,
                close: closeReopen,
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
