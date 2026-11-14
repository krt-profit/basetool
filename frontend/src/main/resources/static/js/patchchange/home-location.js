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
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { markStaying, relocationSummary } from '/js/patchchange/home-location-common.js';
import { format } from '/js/patchchange/patch-change-messages.js';

/** The page's path, also the relay of the choice. */
const PAGE_URL = '/home-location';

/** Where the member continues once relocated. */
const START_URL = '/';

/**
 * The localized strings of the home-location page, read once from the `#hl-i18n` holder.
 *
 * @typedef {object} HomeLocationMessages
 * @property {string} cta the call to action once a location is chosen, `{0}` the location
 * @property {string} ctaChoose the call to action before a location is chosen
 * @property {string} success what moved, `{0}` the location, `{1}` the items, `{2}` the ships
 * @property {string} error the fallback error of the choice
 */

/**
 * Reads the page's localized strings.
 *
 * @returns {HomeLocationMessages} the strings; a missing one is reported and shows its key
 */
function readHomeLocationMessages() {
    const holder = document.getElementById('hl-i18n');
    const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
    return {
        cta: krtI18nText(data.cta, 'data-cta'),
        ctaChoose: krtI18nText(data.ctaChoose, 'data-cta-choose'),
        success: krtI18nText(data.success, 'data-success'),
        error: krtI18nText(data.error, 'data-error'),
    };
}

/**
 * Wires the member's home-location page (REQ-PATCH-022): choosing a card names the location in
 * the call to action and marks the preview row that stays; the confirmation relays the final
 * choice, shows what moved and continues into the tool, and a refusal shows the backend's reason
 * in place.
 */
function initHomeLocation() {
    const formEl = document.getElementById('hl-form');
    const submitEl = document.getElementById('hl-submit');
    const errorEl = document.getElementById('hl-error');
    const successEl = document.getElementById('hl-success');
    if (
        !(formEl instanceof HTMLFormElement) ||
        !(submitEl instanceof HTMLButtonElement) ||
        !errorEl ||
        !successEl
    ) {
        return;
    }
    /** @type {HTMLFormElement} */
    const form = formEl;
    /** @type {HTMLButtonElement} */
    const submit = submitEl;
    /** @type {HTMLElement} */
    const error = errorEl;
    /** @type {HTMLElement} */
    const success = successEl;
    const i18n = readHomeLocationMessages();

    /** @returns {HTMLInputElement | null} the chosen card's radio, or null before a choice */
    function chosen() {
        const radio = form.querySelector('input[name="locationId"]:checked');
        return radio instanceof HTMLInputElement ? radio : null;
    }

    /** Names the chosen location in the call to action and marks the preview row that stays. */
    function sync() {
        const radio = chosen();
        submit.disabled = radio === null;
        submit.textContent = radio
            ? format(i18n.cta, [radio.dataset.locationName || ''])
            : i18n.ctaChoose;
        markStaying(form, radio ? radio.value : null);
    }

    /**
     * Shows why the choice was refused.
     *
     * @param {string} message the localized reason
     */
    function showError(message) {
        error.textContent = message;
        error.hidden = false;
    }

    form.addEventListener('change', sync);
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const radio = chosen();
        if (!radio) {
            return;
        }
        const name = radio.dataset.locationName || '';
        error.hidden = true;
        krtFetch.write({
            method: 'POST',
            url: PAGE_URL,
            payload: { locationId: radio.value },
            toast: false,
            submitter: submit,
            onSuccess(/** @type {ApiDto<'RelocationResultDto'> | null} */ body) {
                success.textContent = relocationSummary(i18n.success, body, name);
                success.hidden = false;
                window.location.assign(START_URL);
            },
            onError(_status, /** @type {ApiProblem | null} */ problem) {
                showError(problem && problem.detail ? problem.detail : i18n.error);
                return true;
            },
            onNetworkError() {
                showError(i18n.error);
                return true;
            },
        });
    });

    sync();
}

initHomeLocation();
