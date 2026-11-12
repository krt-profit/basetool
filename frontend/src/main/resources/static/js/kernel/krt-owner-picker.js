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

import { krtFetch } from '/js/kernel/krt-fetch.js';

const KIND_ORDER = ['ORGANISATIONSLEITUNG', 'BEREICH', 'SQUADRON', 'SPECIAL_COMMAND'];

/** @typedef {ApiDto<'OrgUnitMembershipOptionDto'> & {orgUnitId: string}} OwnerPickerOption */
/** @typedef {{controller: AbortController, status: 'loading' | 'failed'}} OwnerPickerRead */

/** @type {WeakMap<HTMLSelectElement, OwnerPickerRead>} */
const pendingOwnerReads = new WeakMap();

/**
 * Chooses the effective active unit, the sole option, or the empty choice.
 *
 * @param {readonly OwnerPickerOption[]} options the eligible units
 * @param {string | null} activeId the effective active org unit
 * @returns {string} the unit to preselect
 */
export function ownerPickerPreset(options, activeId) {
    if (activeId && options.some((option) => option.orgUnitId === activeId)) {
        return activeId;
    }
    return options.length === 1 ? options[0].orgUnitId : '';
}

/**
 * Renders eligible units in the same kind groups as the server-side owner picker.
 *
 * @param {HTMLSelectElement} select the select to replace
 * @param {unknown} memberships the available units
 * @param {string | null} activeId the effective active org unit
 * @returns {number} the number of eligible options
 */
export function renderOwnerPicker(select, memberships, activeId) {
    /** @type {OwnerPickerOption[]} */
    const options = Array.isArray(memberships)
        ? memberships.filter((option) => typeof option?.orgUnitId === 'string' && option.orgUnitId)
        : [];
    const preset = ownerPickerPreset(options, activeId);
    const labels = window.krtOwnerPickerI18n;
    select.replaceChildren();
    if (!preset) {
        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = labels?.placeholder ?? '';
        select.appendChild(placeholder);
    }

    const groups = KIND_ORDER.filter((kind) => options.some((option) => option.kind === kind));
    /** @type {Map<string, HTMLOptGroupElement>} */
    const groupElements = new Map();
    if (groups.length > 1) {
        for (const kind of groups) {
            const group = document.createElement('optgroup');
            group.label = labels?.[kind] ?? kind;
            groupElements.set(kind, group);
            select.appendChild(group);
        }
    }
    for (const option of options) {
        const element = document.createElement('option');
        element.value = option.orgUnitId;
        element.textContent = option.orgUnitName || option.orgUnitId;
        const parent = option.kind ? groupElements.get(option.kind) : undefined;
        (parent ?? select).appendChild(element);
    }
    select.value = preset;
    return options.length;
}

/**
 * Refreshes the destination picker, canceling any previous read for the same select.
 *
 * @param {HTMLSelectElement} select the destination picker
 * @param {HTMLElement | null} wrapper the wrapper to hide if no unit is available
 * @param {string | null} url the options endpoint, or null to clear the picker
 */
export async function refreshOwnerPicker(select, wrapper, url) {
    pendingOwnerReads.get(select)?.controller.abort();
    if (wrapper) wrapper.hidden = true;
    select.replaceChildren();
    if (!url) {
        pendingOwnerReads.delete(select);
        return;
    }

    const controller = new AbortController();
    /** @type {OwnerPickerRead} */
    const read = { controller, status: 'loading' };
    pendingOwnerReads.set(select, read);
    try {
        const options = await krtFetch.getJson(url, { signal: controller.signal });
        if (controller.signal.aborted) return;
        const activeId = (wrapper ?? select).getAttribute('data-owner-picker-active');
        const count = renderOwnerPicker(select, options, activeId);
        if (wrapper) wrapper.hidden = count === 0;
        pendingOwnerReads.delete(select);
    } catch {
        if (!controller.signal.aborted) read.status = 'failed';
    }
}

/**
 * Returns a localized reason why the destination cannot be submitted yet.
 *
 * @param {HTMLSelectElement | null} select the active destination picker
 * @returns {string | null} a reason, or null when the destination can be submitted
 */
export function ownerPickerError(select) {
    if (!select) return null;
    const status = pendingOwnerReads.get(select)?.status;
    const labels = window.krtOwnerPickerI18n;
    if (status === 'loading') return labels?.loading ?? '';
    if (status === 'failed') return labels?.unavailable ?? '';
    if (!select.value && select.options.length > 1) return labels?.required ?? '';
    return null;
}
