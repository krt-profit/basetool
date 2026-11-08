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

import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { krtModal } from '/js/kernel/krt-modal.js';

/** @typedef {'SET' | 'CLEAR' | 'RELEASE'} LinkAction */

/** Milliseconds a typed filter waits before its list reloads. */
export const TYPING_DELAY_MS = 300;

/** The success toast of each link action, by dictionary key. */
const LINK_TOASTS = { SET: 'toastLinked', CLEAR: 'toastCleared', RELEASE: 'toastReleased' };

/**
 * Looks up an element by id.
 *
 * @param {string} id the element id
 * @returns {HTMLElement | null} the element, or null
 */
export function byId(id) {
    return document.getElementById(id);
}

/**
 * Builds the label reader of a page's dictionary.
 *
 * @param {() => Record<string, string | undefined> | undefined} read reads the page's dictionary
 * @param {string} name the dictionary's global name, for the missing-key report
 * @returns {(key: string) => string} reads one label
 */
export function dictionaryLabels(read, name) {
    return (key) => krtI18nText((read() || {})[key], `${name}.${key}`);
}

/**
 * Reads the trimmed value of a filter input or select.
 *
 * @param {string} id the element id
 * @returns {string} the value, empty when the element is missing
 */
export function filterValue(id) {
    const el = byId(id);
    return el instanceof HTMLInputElement || el instanceof HTMLSelectElement ? el.value.trim() : '';
}

/**
 * The newest read of each render target; an answer whose read a later one replaced is dropped.
 *
 * @type {Map<string, object>}
 */
const latestReads = new Map();

/**
 * Reads a JSON answer for one render target (REQ-FE-013): a later read of the same target aborts
 * this one through the krtFetch supersede key, and an answer that still arrives after it is
 * dropped, so a slower earlier read never overwrites a newer one.
 *
 * @param {string} url the proxy URL
 * @param {string} errorLabel the toast shown when the read fails
 * @param {string} target the render target the answer fills, unique on the page
 * @returns {Promise<any>} the parsed body; null after a toast when it is no data, and null without
 *     one when a later read of the target superseded it
 */
export function readJson(url, errorLabel, target) {
    const ticket = {};
    latestReads.set(target, ticket);
    const current = () => latestReads.get(target) === ticket;
    return krtFetch.getJson(url, { key: target }).then(
        (body) => (current() ? body : null),
        () => {
            if (current()) showFrontendErrorToast(errorLabel);
            return null;
        },
    );
}

/**
 * Builds a page's JSON reader: {@link readJson} with the page's load-error toast.
 *
 * @param {() => string} errorLabel reads the toast shown when a read fails
 * @returns {(url: string, target: string) => Promise<any>} reads one render target's answer
 */
export function jsonReader(errorLabel) {
    return (url, target) => readJson(url, errorLabel(), target);
}

/**
 * Drops the answer of the read still pending for a render target, for a target the page empties
 * without reading.
 *
 * @param {string} target the render target
 */
export function forgetRead(target) {
    latestReads.set(target, {});
}

/**
 * The id of a listed entry, as the maps and URLs use it.
 *
 * @param {{ id?: string }} entry the entry
 * @returns {string} the id, empty when the entry carries none
 */
export function idOf(entry) {
    return entry.id || '';
}

/**
 * Replaces one rendered table row by fresh markup, in place.
 *
 * @param {Element} row the row to replace
 * @param {KrtHtml} markup the new row's markup
 * @returns {Element | null} the new row, or null when the markup holds none
 */
export function swapRow(row, markup) {
    const holder = document.createElement('tbody');
    krtHtml.set(holder, markup);
    const fresh = holder.firstElementChild;
    if (fresh) row.replaceWith(fresh);
    return fresh;
}

/**
 * Reloads a list shortly after a text filter stops changing.
 *
 * @param {string} id the input id
 * @param {() => void} load reloads the list
 */
export function bindTypedFilter(id, load) {
    const el = byId(id);
    if (!el) return;
    /** @type {number | undefined} */
    let timer;
    el.addEventListener('input', () => {
        clearTimeout(timer);
        timer = window.setTimeout(load, TYPING_DELAY_MS);
    });
}

/**
 * Shows a link picker's candidates, or its empty state when there are none.
 *
 * @param {string} listId the candidate list's id
 * @param {string} emptyId the empty state's id
 * @param {KrtHtml[]} items the candidates' markup, each with a `data-candidate-id` button
 */
export function showCandidates(listId, emptyId, items) {
    const empty = byId(emptyId);
    if (empty) empty.hidden = items.length > 0;
    const list = byId(listId);
    if (list) krtHtml.set(list, items);
}

/**
 * Hands a click on a candidate's pick button to the picker.
 *
 * @param {string} listId the candidate list's id
 * @param {(candidateId: string | null, button: HTMLButtonElement) => void} pick links the candidate
 */
function bindCandidateClick(listId, pick) {
    const list = byId(listId);
    if (!list) return;
    list.addEventListener('click', (e) => {
        const target = e.target instanceof Element ? e.target : null;
        const btn = target ? target.closest('button[data-candidate-id]') : null;
        if (btn instanceof HTMLButtonElement) pick(btn.dataset.candidateId || null, btn);
    });
}

/**
 * @template T
 * @typedef {object} LinkRowActions
 * @property {string} rowsId the list body's id
 * @property {string} rowSelector the selector of a listed row
 * @property {(row: HTMLElement) => T | undefined} find the shown entry of a row
 * @property {(entry: T) => void} open opens the picker for an entry
 * @property {(entry: T, action: 'CLEAR' | 'RELEASE', button: HTMLButtonElement) => void} change
 *     sends a clear or a release of the entry's link
 */

/**
 * Hands a link list's row clicks to its actions: a `data-link-open` button opens the picker, a
 * `data-link-action` button sends a clear or a release.
 *
 * @template T
 * @param {LinkRowActions<T>} actions the list and its actions
 */
export function bindLinkRowActions(actions) {
    const rows = byId(actions.rowsId);
    if (!rows) return;
    rows.addEventListener('click', (e) => {
        const target = e.target instanceof Element ? e.target : null;
        const btn = target
            ? target.closest('button[data-link-open], button[data-link-action]')
            : null;
        const row = btn ? btn.closest(actions.rowSelector) : null;
        if (!(btn instanceof HTMLButtonElement) || !(row instanceof HTMLElement)) return;
        const entry = actions.find(row);
        if (!entry) return;
        if (btn.dataset.linkOpen !== undefined) {
            actions.open(entry);
            return;
        }
        const action = btn.dataset.linkAction;
        if (action === 'CLEAR' || action === 'RELEASE') actions.change(entry, action, btn);
    });
}

/**
 * @template T
 * @typedef {object} LinkPickerConfig
 * @property {string} modalId the picker dialog's id
 * @property {string} subjectId the element that names the entry being linked
 * @property {string} searchId the picker's search input
 * @property {string} candidatesId the candidate list's id
 * @property {(entry: T) => string} subject the entry's name in the dialog
 * @property {(entry: T) => string} search the search the picker opens with
 * @property {() => void} loadCandidates loads the candidates of the current search
 * @property {(entry: T, candidateId: string | null, button: HTMLButtonElement) => Promise<boolean>} link
 *     links the entry to a picked candidate and tells whether it was saved
 */

/**
 * @template T
 * @typedef {object} LinkPicker
 * @property {(entry: T) => void} open opens the picker for an entry
 * @property {() => void} dismiss closes an open picker and forgets its entry, so no further pick is
 *     sent against a link that changed meanwhile
 */

/**
 * Wires a link picker dialog: its search reloads the candidates while typing, and a picked
 * candidate is linked to the entry the picker was opened for, closing the dialog once saved.
 *
 * @template T
 * @param {LinkPickerConfig<T>} cfg the dialog and its callbacks
 * @returns {LinkPicker<T>} the picker
 */
export function createLinkPicker(cfg) {
    /** @type {T | null} */
    let entry = null;
    const dismiss = () => {
        if (entry === null) return;
        entry = null;
        krtModal.close(cfg.modalId);
    };
    bindTypedFilter(cfg.searchId, cfg.loadCandidates);
    bindCandidateClick(cfg.candidatesId, (candidateId, btn) => {
        const picked = entry;
        if (picked === null) return;
        cfg.link(picked, candidateId, btn).then((saved) => {
            if (saved) dismiss();
        });
    });
    return {
        open(next) {
            entry = next;
            const subject = byId(cfg.subjectId);
            if (subject) subject.textContent = cfg.subject(next);
            const search = byId(cfg.searchId);
            if (search instanceof HTMLInputElement) search.value = cfg.search(next);
            krtModal.open(cfg.modalId, { focus: search });
            cfg.loadCandidates();
        },
        dismiss,
    };
}

/**
 * @template T
 * @typedef {object} LinkChange
 * @property {{ write: (opts: KrtWriteOpts) => Promise<KrtWriteResult> }} sections the page's section seam
 * @property {string} url the link endpoint of the row
 * @property {Record<string, unknown>} payload the compare-and-set body
 * @property {LinkAction} action the change, for its toast
 * @property {Element | null} submitter the control that sent it
 * @property {(key: string) => string} t the page's label reader
 * @property {() => void} reload reloads the list after a conflict
 * @property {(updated: T) => void} show shows the saved row in place
 */

/**
 * Sends a link change as a compare-and-set on the link the row shows; the saved row is shown in
 * place, and a link that changed meanwhile reloads the list.
 *
 * @template T
 * @param {LinkChange<T>} change the change
 * @returns {Promise<boolean>} whether the change was saved
 */
export function changeLink(change) {
    const { t } = change;
    return change.sections
        .write({
            method: 'POST',
            url: change.url,
            payload: change.payload,
            submitter: change.submitter,
            toast: false,
            errorMessage: t('error'),
            onConflict() {
                showFrontendErrorToast(t('conflict'));
                change.reload();
                return true;
            },
        })
        .then((result) => {
            if (!result.ok) return false;
            change.show(/** @type {T} */ (result.body));
            showFrontendSuccessToast(t(LINK_TOASTS[change.action]));
            return true;
        });
}
