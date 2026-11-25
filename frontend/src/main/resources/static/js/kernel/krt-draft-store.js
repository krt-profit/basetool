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

/** The prefix of every form-draft key in the tab's session storage (REQ-FE-041). */
const DRAFT_PREFIX = 'krt-draft:';

/**
 * Builds the storage key of a form's draft. The parts are URI-encoded and joined by `:`, so the
 * member scope stays the first segment after the prefix.
 *
 * @param {string} scope the opaque per-member key the page carries
 * @param {string} orgUnit the active org-unit context key
 * @param {string} formKey the form's `data-krt-draft` key
 * @param {string} path the page path
 * @returns {string} the storage key
 */
export function draftKey(scope, orgUnit, formKey, path) {
    return DRAFT_PREFIX + [scope, orgUnit, formKey, path].map(encodeURIComponent).join(':');
}

/**
 * Parses a stored entry.
 *
 * @param {string} raw the stored text
 * @returns {unknown} the parsed value; null for text that is no JSON
 */
function parseEntry(raw) {
    try {
        return JSON.parse(raw);
    } catch (_) {
        return null;
    }
}

/**
 * Reads a stored draft; an entry that is not a draft is removed.
 *
 * @param {string} key the storage key
 * @returns {KrtDraft | null} the draft, or null when none is stored
 */
export function readDraft(key) {
    const raw = window.sessionStorage.getItem(key);
    if (raw === null) {
        return null;
    }
    const draft = /** @type {Partial<KrtDraft> | null} */ (parseEntry(raw));
    if (draft && typeof draft.version === 'string' && draft.snapshot) {
        return /** @type {KrtDraft} */ (draft);
    }
    window.sessionStorage.removeItem(key);
    return null;
}

/**
 * Stores a draft. A storage that is full or refuses writes keeps no draft.
 *
 * @param {string} key the storage key
 * @param {KrtDraft} draft the draft to store
 * @returns {boolean} whether the draft was stored
 */
export function writeDraft(key, draft) {
    try {
        window.sessionStorage.setItem(key, JSON.stringify(draft));
        return true;
    } catch (_) {
        return false;
    }
}

/**
 * Removes a stored draft.
 *
 * @param {string} key the storage key
 */
export function removeDraft(key) {
    window.sessionStorage.removeItem(key);
}

/**
 * Removes every form draft of the tab, except those of the member scope to keep.
 *
 * @param {string | null} keepScope the member scope whose drafts stay; null removes all
 */
export function removeDrafts(keepScope) {
    const storage = window.sessionStorage;
    const keep = keepScope === null ? null : `${DRAFT_PREFIX}${encodeURIComponent(keepScope)}:`;
    /** @type {string[]} */
    const doomed = [];
    for (let i = 0; i < storage.length; i += 1) {
        const key = storage.key(i);
        if (
            key !== null &&
            key.startsWith(DRAFT_PREFIX) &&
            (keep === null || !key.startsWith(keep))
        ) {
            doomed.push(key);
        }
    }
    doomed.forEach((key) => {
        storage.removeItem(key);
    });
}
