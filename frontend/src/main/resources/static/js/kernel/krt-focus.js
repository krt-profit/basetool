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

const FOCUS_PARAM = 'focus';
const FOCUS_CLASS = 'krt-focus-target';

/**
 * Brings the list item a link names into view (REQ-NOTIF-032): a page opened with `?focus=<id>`
 * scrolls to the element carrying `data-focus-id="<id>"` and marks it with `krt-focus-target`; an
 * id no element carries leaves the page as it is.
 */
function focusRequestedItem() {
    const id = new URLSearchParams(window.location.search).get(FOCUS_PARAM);
    if (!id) {
        return;
    }
    const target = document.querySelector(`[data-focus-id="${CSS.escape(id)}"]`);
    if (!(target instanceof HTMLElement)) {
        return;
    }
    target.classList.add(FOCUS_CLASS);
    target.scrollIntoView({ block: 'center' });
}

window.addEventListener('load', focusRequestedItem);
