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

import { krtHtml } from '/js/kernel/krt-html.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { krtModal } from '/js/kernel/krt-modal.js';

/** The id of the one maintenance banner of the page. */
const BANNER_ID = 'krt-maintenance-banner';

/** The header the edge sets on its maintenance answer (`docker/edge/include/maintenance.conf`). */
const MAINTENANCE_HEADER = 'X-Basetool-Maintenance';

/** The `type` suffix of the edge's maintenance problem body. */
const MAINTENANCE_PROBLEM_SUFFIX = '/problems/maintenance';

/**
 * A public static file the frontend serves itself. The probe sends no cookie, so it never touches
 * or creates a session.
 */
const PROBE_URL = '/favicon.ico';

/** Milliseconds between two probes, before the jitter. */
const PROBE_INTERVAL_MS = 5000;

/** Up to this many milliseconds are added to each probe, so the open tabs do not probe in step. */
const PROBE_JITTER_MS = 2500;

/** How long the notice that the tool answers again stays before it hides itself. */
const BACK_NOTICE_MS = 8000;

/** Whether the tool is held to be unreachable, which also silences the error toasts. */
let active = false;

/** @type {number | null} */
let probeTimer = null;

/** @type {number | null} */
let hideTimer = null;

/**
 * Tells whether a parsed body is the edge's maintenance problem.
 *
 * @param {unknown} problem the parsed response body
 * @returns {boolean} true when its `type` names the maintenance problem
 */
function isMaintenanceProblem(problem) {
    return (
        typeof problem === 'object' &&
        problem !== null &&
        'type' in problem &&
        typeof problem.type === 'string' &&
        problem.type.endsWith(MAINTENANCE_PROBLEM_SUFFIX)
    );
}

/**
 * Tells whether a response is the answer for an application that is down: a 502 or a 504, or a
 * 503 carrying the edge's maintenance header or maintenance problem.
 *
 * @param {Response} response the response to inspect
 * @param {unknown} [problem] the parsed body, when the caller read it
 * @returns {boolean} true when the application is down for a restart
 */
function isMaintenanceResponse(response, problem) {
    if (response.status === 502 || response.status === 504) {
        return true;
    }
    if (response.status !== 503) {
        return false;
    }
    return response.headers.get(MAINTENANCE_HEADER) === '1' || isMaintenanceProblem(problem);
}

/** Removes the banner and cancels its pending hide. */
function hide() {
    if (hideTimer !== null) {
        window.clearTimeout(hideTimer);
        hideTimer = null;
    }
    const banner = document.getElementById(BANNER_ID);
    if (banner) {
        banner.remove();
    }
}

/**
 * Shows the banner on the topmost layer, so it stays visible above an open dialog.
 *
 * @param {'down' | 'back'} state whether the tool is unreachable or answers again
 */
function show(state) {
    const i18n = window.krtMaintenanceI18n || {};
    const dismiss = krtI18nText(i18n.dismiss, 'krtMaintenanceI18n.dismiss');
    const message =
        state === 'down'
            ? krtI18nText(i18n.unreachable, 'krtMaintenanceI18n.unreachable')
            : krtI18nText(i18n.back, 'krtMaintenanceI18n.back');
    let banner = document.getElementById(BANNER_ID);
    if (!banner) {
        banner = document.createElement('div');
        banner.id = BANNER_ID;
        banner.setAttribute('role', 'status');
        banner.setAttribute('aria-live', 'polite');
    }
    banner.className = 'alert krt-maintenance-banner';
    banner.dataset.state = state;
    const icon =
        state === 'down'
            ? krtHtml`<svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-warning"/></svg>`
            : '';
    krtHtml.set(
        banner,
        krtHtml`${icon}<span class="krt-maintenance-banner__text">${message}</span><button type="button" class="btn btn-ghost btn-icon krt-maintenance-banner__close" aria-label="${dismiss}" title="${dismiss}"><svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-close"/></svg></button>`,
    );
    const close = banner.querySelector('button');
    if (close) {
        close.addEventListener('click', hide);
    }
    krtModal.layerRoot().appendChild(banner);
}

/** Leaves the maintenance mode: the banner says the tool answers again, then hides itself. */
function leave() {
    active = false;
    if (probeTimer !== null) {
        window.clearTimeout(probeTimer);
        probeTimer = null;
    }
    show('back');
    hideTimer = window.setTimeout(hide, BACK_NOTICE_MS);
}

/**
 * Asks the frontend whether it answers again.
 *
 * @returns {Promise<boolean>} true when the probe got a regular answer from the frontend
 */
async function answersAgain() {
    try {
        const response = await fetch(PROBE_URL, {
            method: 'HEAD',
            cache: 'no-store',
            credentials: 'omit',
            redirect: 'manual',
        });
        return response.ok && !isMaintenanceResponse(response);
    } catch (_unreachable) {
        return false;
    }
}

/** Probes once; leaves the maintenance mode on an answer, else schedules the next probe. */
async function probe() {
    probeTimer = null;
    if (!active) {
        return;
    }
    if (await answersAgain()) {
        if (active) {
            leave();
        }
        return;
    }
    scheduleProbe();
}

/** Schedules the next probe unless one is pending. */
function scheduleProbe() {
    if (probeTimer !== null) {
        return;
    }
    probeTimer = window.setTimeout(
        probe,
        PROBE_INTERVAL_MS + Math.floor(Math.random() * PROBE_JITTER_MS),
    );
}

/**
 * Enters the maintenance mode, or renews it: shows the banner and probes until the frontend
 * answers again. Writes are never resent; the member saves again once the banner says so.
 */
function enter() {
    if (hideTimer !== null) {
        window.clearTimeout(hideTimer);
        hideTimer = null;
    }
    active = true;
    show('down');
    scheduleProbe();
}

/**
 * Takes note of an answer: a maintenance answer enters the mode, any other answer while in it
 * proves the frontend reachable and leaves it.
 *
 * @param {Response} response the answer
 * @param {unknown} [problem] the parsed body, when the caller read it
 * @returns {boolean} true when the answer is a maintenance answer
 */
function observe(response, problem) {
    if (isMaintenanceResponse(response, problem)) {
        enter();
        return true;
    }
    if (active) {
        leave();
    }
    return false;
}

/**
 * Tells whether the tool is held to be unreachable right now.
 *
 * @returns {boolean} true between entering the mode and the first regular answer
 */
function isActive() {
    return active;
}

/**
 * The client side of the restart window (REQ-FE-040): it recognises the maintenance answer, tells
 * the member that the input stays on the page, and says when saving works again.
 */
export const krtMaintenance = { enter, observe, isActive, isMaintenanceResponse };
