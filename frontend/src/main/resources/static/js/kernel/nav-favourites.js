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

import { krtEvents } from '/js/kernel/event-delegation.js';
import {
    favouriteKeys,
    NAV_RENDERED_EVENT,
    starChange,
    stateElement,
    writePreferences,
} from '/js/kernel/nav-preferences.js';

/**
 * Puts every star in line with the stored favourites: pressed for a favourite, disabled for an
 * entry that cannot be added at the cap, and the label that says what a press does.
 */
function renderStars() {
    const state = stateElement();
    if (!state) {
        return;
    }
    const keys = favouriteKeys(state);
    const loaded = state.dataset.loaded === 'true';
    const full = keys.length >= Number(state.dataset.max || '0');
    document.querySelectorAll('[data-favourite-toggle]').forEach((star) => {
        if (!(star instanceof HTMLButtonElement)) {
            return;
        }
        const pressed = keys.includes(star.dataset.entryKey || '');
        const label = (pressed ? star.dataset.labelRemove : star.dataset.labelAdd) || '';
        star.setAttribute('aria-pressed', String(pressed));
        star.setAttribute('aria-label', label);
        star.title = !pressed && full ? state.dataset.full || label : label;
        star.disabled = !loaded || (!pressed && full);
    });
}

/**
 * Marks the favourite that opens the page being shown.
 */
function markCurrent() {
    const star = Array.from(document.querySelectorAll('[data-favourite-toggle]')).find(
        (candidate) => !candidate.closest('#sidebar'),
    );
    const key = star instanceof HTMLElement ? star.dataset.entryKey : null;
    document.querySelectorAll('.nav-favourites a[data-entry-key]').forEach((link) => {
        const current = key !== null && link.getAttribute('data-entry-key') === key;
        link.classList.toggle('is-active', current);
        if (current) {
            link.setAttribute('aria-current', 'page');
        } else {
            link.removeAttribute('aria-current');
        }
    });
}

/**
 * Stars or unstars the star's entry; starring a hidden page shows it again in the same write.
 *
 * @param {HTMLElement} star the pressed star
 */
function toggle(star) {
    const key = star.dataset.entryKey;
    if (!stateElement() || !key) {
        return;
    }
    const wanted = star.getAttribute('aria-pressed') !== 'true';
    writePreferences((favourites, hidden) => starChange(favourites, hidden, key, wanted), {
        submitter: star,
    });
}

krtEvents.on('click', 'nav-favourite-toggle', toggle);
document.addEventListener(NAV_RENDERED_EVENT, () => {
    renderStars();
    markCurrent();
});
renderStars();
markCurrent();
