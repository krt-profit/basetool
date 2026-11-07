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

const PHONE_QUERY = '(width <= 768px)';
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';
const CONDENSED_CLASS = 'is-condensed';

/**
 * @typedef {object} MissionHeadParts
 * @property {HTMLElement} sticky the head wrapper that carries `--mission-head-top`
 * @property {HTMLElement} dock the tab dock that pins on the phone class
 * @property {HTMLElement} title the page's `h1` with the mission name
 * @property {HTMLElement} status the head's status badge
 * @property {HTMLElement} miniTitle the mini bar's name button
 * @property {HTMLElement} miniStatus the mini bar's status badge
 * @property {HTMLElement} miniCta the mini bar's sign-up button
 */

/**
 * Looks up every element the head behaviour needs.
 *
 * @returns {MissionHeadParts | null} the parts, or null when the page has no mission head
 */
function findParts() {
    const sticky = document.getElementById('mission-head-sticky');
    const dock = document.getElementById('mission-head-dock');
    const title = document.getElementById('mission-title');
    const status = document.getElementById('mission-status-badge');
    const miniTitle = document.getElementById('mission-head-mini-title');
    const miniStatus = document.getElementById('mission-head-mini-status');
    const miniCta = document.getElementById('mission-head-mini-cta');
    if (!sticky || !dock || !title || !status || !miniTitle || !miniStatus || !miniCta) {
        return null;
    }
    return { sticky, dock, title, status, miniTitle, miniStatus, miniCta };
}

/**
 * Publishes the site header's height as the offset the head and the dock pin below.
 *
 * @param {MissionHeadParts} parts the head elements
 */
function syncOffset(parts) {
    const pageHeader = /** @type {HTMLElement | null} */ (document.querySelector('body > header'));
    if (pageHeader) {
        parts.sticky.style.setProperty('--mission-head-top', `${pageHeader.offsetHeight}px`);
    }
}

/**
 * Marks the dock condensed while it is pinned on the phone class, which shows the mini bar.
 *
 * @param {MissionHeadParts} parts the head elements
 * @param {MediaQueryList} phone the phone-class media query
 */
function syncCondensed(parts, phone) {
    const pinnedTop = parseFloat(getComputedStyle(parts.dock).top);
    const stuck =
        phone.matches &&
        Number.isFinite(pinnedTop) &&
        parts.dock.getBoundingClientRect().top <= pinnedTop + 1;
    parts.dock.classList.toggle(CONDENSED_CLASS, stuck);
}

/**
 * Copies the mission name and status from the head into the mini bar.
 *
 * @param {MissionHeadParts} parts the head elements
 */
function syncMini(parts) {
    parts.miniTitle.textContent = parts.title.textContent;
    parts.miniStatus.className = `${parts.status.className} mission-head-mini__status`;
    parts.miniStatus.textContent = parts.status.textContent;
}

/**
 * Pins the mission head below the site header and, on the phone class, shows the condensed mini
 * bar (title, status, sign-up) once the tab dock has stuck (REQ-MISSION-033).
 */
function initMissionDetailHead() {
    const found = findParts();
    if (!found) {
        return;
    }
    const parts = found;
    const phone = window.matchMedia(PHONE_QUERY);
    let framePending = false;

    function scheduleCondensed() {
        if (framePending) {
            return;
        }
        framePending = true;
        window.requestAnimationFrame(() => {
            framePending = false;
            syncCondensed(parts, phone);
        });
    }

    parts.miniTitle.addEventListener('click', () => {
        const still = window.matchMedia(REDUCED_MOTION_QUERY).matches;
        window.scrollTo({ top: 0, behavior: still ? 'auto' : 'smooth' });
    });
    parts.miniCta.addEventListener('click', () => {
        const signUp = document.getElementById('add-participant-btn');
        if (signUp) {
            signUp.click();
        }
    });

    const mirror = new MutationObserver(() => syncMini(parts));
    mirror.observe(parts.title, { childList: true, characterData: true, subtree: true });
    mirror.observe(parts.status, {
        attributes: true,
        attributeFilter: ['class'],
        childList: true,
        characterData: true,
        subtree: true,
    });

    window.addEventListener('scroll', scheduleCondensed, { passive: true });
    window.addEventListener('resize', () => {
        syncOffset(parts);
        scheduleCondensed();
    });
    phone.addEventListener('change', scheduleCondensed);

    syncOffset(parts);
    syncMini(parts);
    scheduleCondensed();
}

initMissionDetailHead();
