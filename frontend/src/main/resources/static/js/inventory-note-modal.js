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
 * Shared inventory note-modal handler family (#906 Q12), deduplicated out of the former
 * per-page copies in inventory-my.js and inventory-admin.js. It owns the whole note-modal
 * flow — open/close, the live character counter, the toast helper, save/remove, the
 * PUT /inventory/{id}/note submit through krtFetch.write and DOM version sync — plus the single
 * `activeNoteButton` state cell that ties the modal to the row it edits.
 *
 * Consumed by BOTH the personal inventory ("Mein Lager", inventory-my.html + inventory-my.js)
 * and the squadron-wide admin inventory ("Lager", inventory-admin.html + inventory-admin.js)
 * pages. It is loaded as a classic synchronous script that shares the one global scope with the
 * page module, so both page modules' delegated krtEvents note bindings resolve these functions
 * (and mutate `activeNoteButton`) at event-fire time. The loader tag sits between each page's
 * th:inline noteI18n bootstrap block and the page-module loader, so the shared module parses
 * first and the `noteI18n` dictionary is already present as a bare global by the time any of
 * these functions runs (it is read at call time, never at parse time).
 */

/* global noteI18n, inventoryConflictI18n */

let activeNoteButton = null;

function openNoteModal(btn) {
    activeNoteButton = btn;
    const modal = document.getElementById('noteModal');
    const ta = document.getElementById('noteModalTextarea');
    const note = btn.getAttribute('data-note') || '';
    ta.value = note;
    updateNoteCounter();
    modal.style.display = 'flex';
    setTimeout(function () {
        ta.focus();
    }, 0);
}

function closeNoteModal() {
    const modal = document.getElementById('noteModal');
    modal.style.display = 'none';
    activeNoteButton = null;
}

function updateNoteCounter() {
    const ta = document.getElementById('noteModalTextarea');
    const counter = document.getElementById('noteModalCounter');
    if (ta && counter) counter.textContent = (ta.value ? ta.value.length : 0) + ' / 1000';
}

function showInventoryToast(type, msg) {
    // Delegate to the shared toast fragment globals (templates/fragments/toast.html).
    // There is no page-local #toast element; routing through the globals is the only
    // path that actually surfaces a notification.
    if (type === 'error') {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(msg);
        }
    } else if (typeof window.showFrontendSuccessToast === 'function') {
        window.showFrontendSuccessToast(msg);
    }
}

function saveNote() {
    if (!activeNoteButton) return;
    const ta = document.getElementById('noteModalTextarea');
    submitNoteUpdate(ta.value || '');
}

function removeNote() {
    if (!activeNoteButton) return;
    // showKrtConfirm is the shared promise-based confirm from fragments/toast.html (rendered
    // on this page); if the fragment is ever absent, remove directly rather than dead-ending
    // the button behind a dialog that can never appear.
    if (typeof window.showKrtConfirm !== 'function') {
        submitNoteUpdate('');
        return;
    }
    window
        .showKrtConfirm(noteI18n.confirmTitle, noteI18n.confirmMessage)
        .then(function (confirmed) {
            if (confirmed) submitNoteUpdate('');
        });
}

function submitNoteUpdate(noteValue) {
    const btn = activeNoteButton;
    if (!btn) return;
    const id = btn.getAttribute('data-id');
    // Serialize per inventory row so rapidly re-opening + saving the same note queues instead of
    // racing a stale version into a 409; the version is read lazily inside runNoteUpdate at send
    // time, after the previous save synced the fresh version onto the row's controls.
    if (window.krtFetch && typeof window.krtFetch.serialize === 'function') {
        return window.krtFetch.serialize('inv-note:' + id, function () {
            return runNoteUpdate(btn, id, noteValue);
        });
    }
    return runNoteUpdate(btn, id, noteValue);
}

function runNoteUpdate(btn, id, noteValue) {
    if (!window.krtFetch) return Promise.resolve();
    const version = btn.getAttribute('data-version');
    const trimmed = (noteValue || '').trim();
    const isEmpty = trimmed.length === 0;
    // krtFetch.write (REQ-FE-002): CSRF, the bare-403 refresh-and-retry and the re-auth redirect
    // come from the shared seam. A 409 OPTIMISTIC_LOCK gets its reload-confirm — the one
    // sanctioned reload — instead of the former unconditional timed reload (REQ-FE-001/003).
    const conflict =
        typeof inventoryConflictI18n !== 'undefined'
            ? Object.assign({}, inventoryConflictI18n, { reloadDetailFallback: noteI18n.conflict })
            : { reloadDetailFallback: noteI18n.conflict };
    return window.krtFetch.write({
        method: 'PUT',
        url: '/inventory/' + encodeURIComponent(id) + '/note',
        payload: {
            note: noteValue,
            version: version == null ? null : Number(version),
        },
        successMessage: isEmpty ? noteI18n.removed : noteI18n.saved,
        errorMessage: noteI18n.generic,
        conflict: conflict,
        onSuccess: function (updated) {
            closeNoteModal();
            // FRONTEND DOM VERSION SYNC (CLAUDE.md): propagate the incremented version to every
            // data-version control in the leaf row (note + book-out buttons, the two association
            // selects) so a subsequent edit does not 409 — the shared syncVersion. The note preview
            // is a sibling carrying data-note-for but no data-version, so it is left to the preview
            // patch below.
            if (updated && updated.version != null) {
                window.krtFetch.syncVersion(btn.closest('.tree-row--leaf'), updated.version);
            }
            const noteBtns = document.querySelectorAll(
                'button.inventory-note-btn[data-id="' + id + '"]',
            );
            noteBtns.forEach(function (b) {
                b.setAttribute(
                    'data-note',
                    isEmpty ? '' : updated && updated.note != null ? updated.note : trimmed,
                );
                // Icon button: keep the glyph; update only the accessible name, the tooltip and the
                // has-note/outline highlight (never overwrite textContent, which would wipe the SVG
                // icon).
                b.setAttribute('aria-label', isEmpty ? noteI18n.add : noteI18n.edit);
                b.title = isEmpty ? noteI18n.add : updated && updated.note ? updated.note : trimmed;
                b.classList.toggle('has-note', !isEmpty);
                b.classList.toggle('btn-outline', !isEmpty);
                b.classList.toggle('btn-ghost', isEmpty);
            });
            const previews = document.querySelectorAll('[data-note-for="' + id + '"]');
            previews.forEach(function (p) {
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
            // A note is shown on the shared /all and personal /my Lager alike; tell other viewers
            // to refresh (REQ-FE-010). The page module sets this notifier.
            if (typeof window.krtNotifyInventoryChanged === 'function') {
                window.krtNotifyInventoryChanged();
            }
        },
        onError: function (status) {
            if (status === 409) {
                // krtFetch: conflict confirm for OPTIMISTIC_LOCK, the domain detail otherwise.
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

// Cross-script exports — publish the shared note-modal API on `window`.
//
// openNoteModal / closeNoteModal / updateNoteCounter / saveNote / removeNote / showInventoryToast are
// invoked by name from the delegated krtEvents bindings in inventory-my.js and inventory-admin.js
// (classic scripts sharing this one global scope), never from within this file. Being classic-script
// top-level declarations they are already reachable as bare globals, so the page modules keep calling
// them by name unchanged; the assignments below just make that cross-file contract explicit and give
// per-file static analysis a real reference, so the shared API is not reported as dead code. The
// internal helper submitNoteUpdate is deliberately not exported — it is only called from within this
// file.
window.openNoteModal = openNoteModal;
window.closeNoteModal = closeNoteModal;
window.updateNoteCounter = updateNoteCounter;
window.saveNote = saveNote;
window.removeNote = removeNote;
window.showInventoryToast = showInventoryToast;
