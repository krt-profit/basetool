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

/**
 * The localized strings of the patch-change page, read once from the `#pc-i18n` holder.
 *
 * @typedef {object} PatchChangeMessages
 * @property {string} triggered the success toast of a trigger, `{0}` the label
 * @property {string} triggerError the fallback error of a trigger
 * @property {string} triggerLabel the trigger button, `{0}` the label
 * @property {string} confirmLabel the confirmation's label, `{0}` the label
 * @property {string} previous the previous patch change, `{0}` its label, `{1}` its live time
 * @property {string} previewError the fallback error of the preview
 * @property {string} previewEmpty the preview when no enabled effect reports a count
 * @property {string} corrected the success toast of a label correction
 * @property {string} correctError the fallback error of a label correction
 * @property {string} conflict the toast of a label correction on a stale version
 * @property {string} refreshError the toast of a failed history refresh
 * @property {string} phaseEndText the confirmation of a phase end, `{0}` the patch label
 * @property {string} phaseEnded the success toast of a phase end
 * @property {string} phaseEndError the fallback error of a phase end
 * @property {string} homeLocations the curated home locations, `{0}` their names
 * @property {string} homeLocationsNone the hint while no home location is curated
 */

/**
 * Reads the page's localized strings.
 *
 * @returns {PatchChangeMessages} the strings; a missing one is reported and shows its key
 */
export function readMessages() {
    const holder = document.getElementById('pc-i18n');
    const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
    return {
        triggered: krtI18nText(data.triggered, 'data-triggered'),
        triggerError: krtI18nText(data.triggerError, 'data-trigger-error'),
        triggerLabel: krtI18nText(data.triggerLabel, 'data-trigger-label'),
        confirmLabel: krtI18nText(data.confirmLabel, 'data-confirm-label'),
        previous: krtI18nText(data.previous, 'data-previous'),
        previewError: krtI18nText(data.previewError, 'data-preview-error'),
        previewEmpty: krtI18nText(data.previewEmpty, 'data-preview-empty'),
        corrected: krtI18nText(data.corrected, 'data-corrected'),
        correctError: krtI18nText(data.correctError, 'data-correct-error'),
        conflict: krtI18nText(data.conflict, 'data-conflict'),
        refreshError: krtI18nText(data.refreshError, 'data-refresh-error'),
        phaseEndText: krtI18nText(data.phaseEndText, 'data-phase-end-text'),
        phaseEnded: krtI18nText(data.phaseEnded, 'data-phase-ended'),
        phaseEndError: krtI18nText(data.phaseEndError, 'data-phase-end-error'),
        homeLocations: krtI18nText(data.homeLocations, 'data-home-locations'),
        homeLocationsNone: krtI18nText(data.homeLocationsNone, 'data-home-locations-none'),
    };
}

/**
 * Reads the label of every count kind the preview can show, from the `#pc-count-labels` list.
 *
 * @returns {Map<string, string>} the label by count kind
 */
export function readCountLabels() {
    /** @type {Map<string, string>} */
    const labels = new Map();
    document.querySelectorAll('#pc-count-labels [data-count-kind]').forEach((item) => {
        labels.set(item.getAttribute('data-count-kind') || '', item.textContent || '');
    });
    return labels;
}

/**
 * Fills the `{0}`, `{1}`, … placeholders of a message.
 *
 * @param {string} template the message with placeholders
 * @param {Array<string | number>} values the values by placeholder index
 * @returns {string} the message; a placeholder without a value stays as it is
 */
export function format(template, values) {
    return template.replace(/\{(\d+)\}/g, (match, index) => {
        const value = values[Number(index)];
        return value === undefined ? match : String(value);
    });
}

/**
 * Formats an instant in the viewer's local time, to the minute, in the page language.
 *
 * @param {string | null | undefined} iso the instant as an ISO-8601 string
 * @returns {string} the local date and time, the input when it is no date, or '' without one
 */
export function localTime(iso) {
    if (!iso) {
        return '';
    }
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
        return iso;
    }
    return date.toLocaleString(document.documentElement.lang || undefined, {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
    });
}

/**
 * Rewrites every server-rendered UTC time of the page below a root into the viewer's local time.
 *
 * @param {ParentNode} root the subtree to localize
 */
export function localizeTimes(root) {
    root.querySelectorAll('time.pc-local-time[datetime]').forEach((time) => {
        time.textContent = localTime(time.getAttribute('datetime'));
    });
}
