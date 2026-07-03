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
 * flow — open/close, the live character counter, the toast helper, save/remove, the CSRF
 * PUT /inventory/{id}/note submit with retry-on-403 and DOM version sync — plus the single
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

/* global noteI18n */

// These top-level functions are the shared note-modal API: they are referenced not from within
// this file but from the delegated krtEvents note bindings in inventory-my.js and inventory-admin.js
// (classic scripts sharing one global scope). The `exported` directive documents that cross-script
// use so per-file static analysis does not flag them as unused.
/* exported openNoteModal, closeNoteModal, updateNoteCounter, saveNote, removeNote, showInventoryToast */

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
    const version = btn.getAttribute('data-version');
    // #577: CSRF via the shared krtCsrf single source of truth (REQ-FE-002) with a one-shot
    // retry-on-403 (REQ-FE-004) instead of a hand-rolled meta read.
    let headers = window.krtCsrf
        ? window.krtCsrf.headers({ Accept: 'application/json' })
        : { 'Content-Type': 'application/json', Accept: 'application/json' };
    const noteBody = JSON.stringify({
        note: noteValue,
        version: version == null ? null : Number(version),
    });

    function sendNote() {
        return fetch('/inventory/' + id + '/note', {
            method: 'PUT',
            headers: headers,
            credentials: 'same-origin',
            body: noteBody,
        });
    }

    sendNote()
        .then(function (resp) {
            // A bare 403 may be a stale CSRF token; refresh once and retry before treating it as a
            // real authorization failure.
            if (resp.status === 403 && window.krtCsrf && window.krtCsrf.refresh) {
                return window.krtCsrf.refresh().then(function (ok) {
                    if (ok) {
                        headers = window.krtCsrf.headers({ Accept: 'application/json' });
                        return sendNote();
                    }
                    return resp;
                });
            }
            return resp;
        })
        .then(function (resp) {
            if (resp.ok) {
                return resp
                    .json()
                    .then(function (updated) {
                        const trimmed = (noteValue || '').trim();
                        const isEmpty = trimmed.length === 0;
                        showInventoryToast('success', isEmpty ? noteI18n.removed : noteI18n.saved);
                        closeNoteModal();
                        // FRONTEND DOM VERSION SYNC (CLAUDE.md): propagate the incremented version to
                        // every data-version control in the leaf row (note + book-out buttons, the two
                        // association selects) so a subsequent edit does not 409 — replaces the former
                        // document-wide [data-id]/[data-note-for] loop with the shared syncVersion. The
                        // note preview is a sibling carrying data-note-for but no data-version, so it is
                        // left to the preview patch below.
                        if (updated && updated.version != null && window.krtFetch) {
                            window.krtFetch.syncVersion(
                                btn.closest('.tree-row--leaf'),
                                updated.version,
                            );
                        }
                        const noteBtns = document.querySelectorAll(
                            'button.inventory-note-btn[data-id="' + id + '"]',
                        );
                        noteBtns.forEach(function (b) {
                            b.setAttribute(
                                'data-note',
                                isEmpty
                                    ? ''
                                    : updated && updated.note != null
                                      ? updated.note
                                      : trimmed,
                            );
                            // Icon button: keep the glyph; update only the accessible name, the
                            // tooltip and the has-note/outline highlight (never overwrite textContent,
                            // which would wipe the SVG icon).
                            b.setAttribute('aria-label', isEmpty ? noteI18n.add : noteI18n.edit);
                            b.title = isEmpty
                                ? noteI18n.add
                                : updated && updated.note
                                  ? updated.note
                                  : trimmed;
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
                    })
                    .catch(function () {
                        // If the JSON parsing or DOM sync fails for any reason, reload the page
                        // to guarantee consistent data-version attributes (AGENTS.md fallback).
                        window.location.reload();
                    });
            } else if (resp.status === 403) {
                showInventoryToast('error', noteI18n.forbidden);
            } else if (resp.status === 409) {
                showInventoryToast('error', noteI18n.conflict);
                setTimeout(function () {
                    window.location.reload();
                }, 1200);
            } else if (resp.status === 400 || resp.status === 422) {
                showInventoryToast('error', noteI18n.tooLong);
            } else {
                showInventoryToast('error', noteI18n.generic);
            }
        })
        .catch(function () {
            showInventoryToast('error', noteI18n.generic);
        });
}
