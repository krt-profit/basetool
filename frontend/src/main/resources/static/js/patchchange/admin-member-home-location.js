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

import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import {
    markStaying,
    readRelocationMessages,
    relocationSummary,
} from '/js/patchchange/home-location-common.js';
import { format, localizeTimes } from '/js/patchchange/patch-change-messages.js';

/** The confirmation dialog of the choice. */
const MODAL_ID = 'mhl-confirm-modal';

/**
 * The localized strings of the member's home-location tab, read once from the `#mhl-i18n` holder.
 *
 * @typedef {object} MemberHomeLocationMessages
 * @property {string} confirmText the confirmation, `{0}` the patch label, `{1}` the location
 * @property {string} confirmSubmit the confirmation's button, `{0}` the location
 * @property {ReturnType<typeof readRelocationMessages>} relocation the summary of what moved
 * @property {string} error the fallback error of the choice
 * @property {string} refreshError the toast of a failed re-render
 */

/**
 * A location the admin picked, waiting for the confirmation.
 *
 * @typedef {object} PickedLocation
 * @property {string} id the location
 * @property {string} name its curated name
 */

/**
 * Reads the tab's localized strings.
 *
 * @returns {MemberHomeLocationMessages} the strings; a missing one is reported and shows its key
 */
function readMemberMessages() {
    const holder = document.getElementById('mhl-i18n');
    const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
    return {
        confirmText: krtI18nText(data.confirmText, 'data-confirm-text'),
        confirmSubmit: krtI18nText(data.confirmSubmit, 'data-confirm-submit'),
        relocation: readRelocationMessages(data),
        error: krtI18nText(data.error, 'data-error'),
        refreshError: krtI18nText(data.refreshError, 'data-refresh-error'),
    };
}

/**
 * Reads the location picked in the content's select.
 *
 * @returns {PickedLocation | null} the picked location, or null before a pick or without a choice
 */
function pickedLocation() {
    const select = document.getElementById('mhl-location');
    if (!(select instanceof HTMLSelectElement) || !select.value) {
        return null;
    }
    const option = select.selectedOptions[0];
    return { id: select.value, name: option ? option.dataset.locationName || '' : '' };
}

/**
 * Enables the call to action once a location is picked and marks the preview row that stays.
 */
function syncPick() {
    const open = document.getElementById('mhl-open');
    const content = document.getElementById('mhl-content');
    const picked = pickedLocation();
    if (open instanceof HTMLButtonElement) {
        open.disabled = picked === null;
    }
    if (content) {
        markStaying(content, picked ? picked.id : null);
    }
}

/**
 * Wires the admin's member home-location tab (REQ-PATCH-022): the pick of a curated location, the
 * confirmation naming it, the choice on the member's behalf after which the content re-renders in
 * place, and the `patch-change` room the backend announces every relocation and phase end in.
 */
function initMemberHomeLocation() {
    const page = document.getElementById('mhl-page');
    const formEl = document.getElementById('mhl-confirm-form');
    const textEl = document.getElementById('mhl-confirm-text');
    const submitEl = document.getElementById('mhl-confirm-submit');
    if (
        !page ||
        !(formEl instanceof HTMLFormElement) ||
        !textEl ||
        !(submitEl instanceof HTMLButtonElement)
    ) {
        return;
    }
    /** @type {HTMLElement} */
    const confirmText = textEl;
    /** @type {HTMLButtonElement} */
    const confirmSubmit = submitEl;
    const pageUrl = page.dataset.pageUrl || '';
    const i18n = readMemberMessages();

    const sections = krtPageSections.create({
        dict() {
            return { 'mhl.refresh.error': i18n.refreshError };
        },
        keys: { refreshErrorKey: 'mhl.refresh.error' },
        topicClass: 'PATCH_CHANGE',
        sections: {
            // The backend announces every relocation and phase end as the room's history section.
            history: { container: '#mhl-content', fragmentValue: 'content' },
        },
        pageUrl() {
            return pageUrl;
        },
        topic: 'patch-change',
        receiver: { coalesceMs: 1000 },
    });

    /** @type {PickedLocation | null} */
    let choosing = null;

    /** Closes the confirmation and forgets the pick under confirmation. */
    function close() {
        choosing = null;
        krtModal.close(MODAL_ID);
    }

    /**
     * Reports a refused choice and re-renders the content, whose state has moved on.
     *
     * @param {ApiProblem | null} problem the backend's refusal
     * @returns {boolean} true, the refusal is handled
     */
    function refuse(problem) {
        close();
        showFrontendErrorToast(problem && problem.detail ? problem.detail : i18n.error);
        sections.refresh('history', { broadcast: false });
        return true;
    }

    document.addEventListener('change', (event) => {
        if (event.target instanceof HTMLSelectElement && event.target.id === 'mhl-location') {
            syncPick();
        }
    });
    document.addEventListener('submit', (event) => {
        const target = event.target;
        if (!(target instanceof HTMLFormElement) || target.id !== 'mhl-form') {
            return;
        }
        event.preventDefault();
        const picked = pickedLocation();
        if (!picked) {
            return;
        }
        choosing = picked;
        confirmText.textContent = format(i18n.confirmText, [
            target.dataset.label || '',
            picked.name,
        ]);
        confirmSubmit.textContent = format(i18n.confirmSubmit, [picked.name]);
        krtModal.open(MODAL_ID, { focus: confirmSubmit });
    });
    formEl.addEventListener('submit', (event) => {
        event.preventDefault();
        const current = choosing;
        if (!current) {
            return;
        }
        sections.write({
            method: 'POST',
            url: pageUrl,
            payload: { locationId: current.id },
            toast: false,
            errorMessage: i18n.error,
            submitter: confirmSubmit,
            onSuccess(/** @type {ApiDto<'RelocationResultDto'> | null} */ body) {
                close();
                showFrontendSuccessToast(relocationSummary(i18n.relocation, body, current.name));
                return sections.refresh('history', { broadcast: false });
            },
            onConflict(/** @type {ApiProblem | null} */ problem) {
                return refuse(problem);
            },
            onError(_status, /** @type {ApiProblem | null} */ problem) {
                return refuse(problem);
            },
        });
    });
    document.addEventListener('krt:swapped', (event) => {
        const container = event.detail && event.detail.container ? event.detail.container : null;
        if (container) {
            localizeTimes(container);
        }
        syncPick();
    });

    localizeTimes(document);
    syncPick();
}

initMemberHomeLocation();
