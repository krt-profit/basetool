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

// @ts-check

/* global noteI18n, inventoryConflictI18n */

import {
    showFrontendErrorToast,
    showFrontendSuccessToast,
    showKrtConfirm,
} from '/js/kernel/toast.js';

/** @type {HTMLElement | null} */
let activeNoteButton = null;

/**
 * Opens the note dialog for one note button, prefilled with its note.
 *
 * @param {HTMLElement} btn the note button that was activated
 */
export function openNoteModal(btn) {
    activeNoteButton = btn;
    const modal = document.getElementById('noteModal');
    const ta = /** @type {HTMLTextAreaElement | null} */ (
        document.getElementById('noteModalTextarea')
    );
    if (!ta) return;
    const note = btn.getAttribute('data-note') || '';
    ta.value = note;
    updateNoteCounter();
    window.krtModal.open(modal);
    setTimeout(() => {
        ta.focus();
    }, 0);
}

export function closeNoteModal() {
    const modal = document.getElementById('noteModal');
    window.krtModal.close(modal);
    activeNoteButton = null;
}

export function updateNoteCounter() {
    const ta = /** @type {HTMLTextAreaElement | null} */ (
        document.getElementById('noteModalTextarea')
    );
    const counter = document.getElementById('noteModalCounter');
    if (ta && counter) counter.textContent = `${ta.value ? ta.value.length : 0} / 1000`;
}

export function showInventoryToast(type, msg) {
    if (type === 'error') {
        if (typeof showFrontendErrorToast === 'function') {
            showFrontendErrorToast(msg);
        }
    } else if (typeof showFrontendSuccessToast === 'function') {
        showFrontendSuccessToast(msg);
    }
}

export function saveNote() {
    if (!activeNoteButton) return;
    const ta = /** @type {HTMLTextAreaElement | null} */ (
        document.getElementById('noteModalTextarea')
    );
    if (!ta) return;
    submitNoteUpdate(ta.value || '');
}

export function removeNote() {
    if (!activeNoteButton) return;
    if (typeof showKrtConfirm !== 'function') {
        submitNoteUpdate('');
        return;
    }
    showKrtConfirm(noteI18n.confirmTitle, noteI18n.confirmMessage).then((confirmed) => {
        if (confirmed) submitNoteUpdate('');
    });
}

/**
 * Sends the note of the active button, queued behind earlier writes of the same entry.
 *
 * @param {string} noteValue the note to store; empty removes it
 * @returns {Promise<unknown> | undefined} the write, undefined when no button is active
 */
function submitNoteUpdate(noteValue) {
    const btn = activeNoteButton;
    if (!btn) return;
    const id = /** @type {string} */ (btn.getAttribute('data-id'));
    if (window.krtFetch && typeof window.krtFetch.serialize === 'function') {
        return window.krtFetch.serialize(`inv-note:${id}`, () => {
            return runNoteUpdate(btn, id, noteValue);
        });
    }
    return runNoteUpdate(btn, id, noteValue);
}

/**
 * Writes the note of one entry and patches every note control of it in place.
 *
 * @param {HTMLElement} btn the note button the write started from
 * @param {string} id the entry id
 * @param {string} noteValue the note to store; empty removes it
 * @returns {Promise<unknown>} the write
 */
function runNoteUpdate(btn, id, noteValue) {
    if (!window.krtFetch) return Promise.resolve();
    const version = btn.getAttribute('data-version');
    const trimmed = (noteValue || '').trim();
    const isEmpty = trimmed.length === 0;
    const conflict =
        typeof inventoryConflictI18n !== 'undefined'
            ? Object.assign({}, inventoryConflictI18n, { reloadDetailFallback: noteI18n.conflict })
            : { reloadDetailFallback: noteI18n.conflict };
    return window.krtFetch.write({
        method: 'PUT',
        url: `/inventory/${encodeURIComponent(id)}/note`,
        payload: {
            note: noteValue,
            version: version == null ? null : Number(version),
        },
        successMessage: isEmpty ? noteI18n.removed : noteI18n.saved,
        errorMessage: noteI18n.generic,
        conflict,
        onSuccess(updated) {
            closeNoteModal();
            if (updated && updated.version != null) {
                window.krtFetch.syncVersion(btn.closest('.tree-row--leaf'), updated.version);
            }
            const noteBtns = /** @type {NodeListOf<HTMLElement>} */ (
                document.querySelectorAll(`button.inventory-note-btn[data-id="${id}"]`)
            );
            noteBtns.forEach((b) => {
                b.setAttribute(
                    'data-note',
                    isEmpty ? '' : updated && updated.note != null ? updated.note : trimmed,
                );
                b.setAttribute('aria-label', isEmpty ? noteI18n.add : noteI18n.edit);
                b.title = isEmpty ? noteI18n.add : updated && updated.note ? updated.note : trimmed;
                b.classList.toggle('has-note', !isEmpty);
                b.classList.toggle('btn-outline', !isEmpty);
                b.classList.toggle('btn-ghost', isEmpty);
            });
            const previews = /** @type {NodeListOf<HTMLElement>} */ (
                document.querySelectorAll(`[data-note-for="${id}"]`)
            );
            previews.forEach((p) => {
                if (isEmpty) {
                    p.remove();
                } else {
                    const txt = updated && updated.note ? updated.note : trimmed;
                    const textEl = p.querySelector('.inventory-note-text');
                    if (textEl) {
                        textEl.textContent = txt;
                    } else {
                        p.textContent = txt;
                    }
                    p.title = txt;
                }
            });
            if (typeof window.krtNotifyInventoryChanged === 'function') {
                window.krtNotifyInventoryChanged();
            }
        },
        onError(status) {
            if (status === 409) {
                return false;
            }
            if (status === 403) {
                showInventoryToast('error', noteI18n.forbidden);
            } else if (status === 400 || status === 422) {
                showInventoryToast('error', noteI18n.tooLong);
            } else {
                showInventoryToast('error', noteI18n.generic);
            }
            return true;
        },
    });
}
