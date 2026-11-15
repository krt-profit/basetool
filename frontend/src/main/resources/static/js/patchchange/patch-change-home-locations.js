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

/** @import { PatchChangeMessages } from '/js/patchchange/patch-change-messages.js' */

import { format } from '/js/patchchange/patch-change-messages.js';

/**
 * What the home-location hint under the prompt switch offers the form.
 *
 * @typedef {object} HomeLocationsSeam
 * @property {() => void} sync shows the warning while the prompt is on and none is curated
 * @property {(locations: ApiDto<'HomeLocationOptionDto'>[] | null | undefined) => void} show
 *     names the curated home locations of a fresh form read
 */

/**
 * Installs the hint under the home-location prompt switch: the curated home locations by name and
 * a warning while the prompt is on and none is curated (REQ-PATCH-021).
 *
 * @param {HTMLFormElement} form the patch-change form holding the prompt switch
 * @param {PatchChangeMessages} i18n the page's localized strings
 * @returns {HomeLocationsSeam} the hint's seam; it does nothing when the page renders no hint
 */
export function initHomeLocations(form, i18n) {
    const namesEl = document.getElementById('pc-home-locations');
    const alertEl = document.getElementById('pc-home-alert');
    const promptEl = form.querySelector('input[name="homeLocationPrompt"]');
    if (!namesEl || !alertEl || !(promptEl instanceof HTMLInputElement)) {
        return { sync() {}, show() {} };
    }
    /** @type {HTMLElement} */
    const names = namesEl;
    /** @type {HTMLElement} */
    const warning = alertEl;
    /** @type {HTMLInputElement} */
    const promptSwitch = promptEl;
    let curated = Number(names.dataset.count || '0');

    /** Shows the warning while the prompt is on and no home location is curated. */
    function sync() {
        warning.hidden = !promptSwitch.checked || curated > 0;
    }

    /**
     * Names the curated home locations of a fresh form read.
     *
     * @param {ApiDto<'HomeLocationOptionDto'>[] | null | undefined} locations the curated ones
     */
    function show(locations) {
        const curatedNames = (locations || []).map((location) => location.name || '');
        curated = curatedNames.length;
        names.dataset.count = String(curated);
        names.textContent =
            curated > 0
                ? format(i18n.homeLocations, [curatedNames.join(', ')])
                : i18n.homeLocationsNone;
        sync();
    }

    return { sync, show };
}
