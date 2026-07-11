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

/*
 * Landing/index page module, extracted verbatim from the former inline script of index.html
 * (ADR-0069, follow-up to #924).
 *
 * Toggles the collapsible info box (delegated via the shared krtEvents 'index-toggle-info'
 * action) and marks the current announcement read in place through krtFetch (epic #571,
 * REQ-FE-005) instead of reloading, removing the control on success; the classic POST form
 * stays the no-JS fallback.
 */

function toggleInfo() {
    const body = document.getElementById('info-body');
    const icon = document.getElementById('info-toggle-icon');
    if (window.getComputedStyle(body).display === 'none') {
        body.style.display = 'block';
        icon.innerText = '▼';
    } else {
        body.style.display = 'none';
        icon.innerText = '▶';
    }
}

// CSP-safe delegated binding (replaces the historical onclick="toggleInfo()" inline handler
// on #info-header — the form-inside-the-header keeps event-propagation isolated via the
// shared `stop-propagation` action declared on its `data-trigger` attribute).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'index-toggle-info', toggleInfo);
}

// Mark-announcement-read in place (epic #571, REQ-FE-005): POST through krtFetch and remove the
// control on success instead of reloading. The id is passed as a query param so the existing
// @RequestParam twin binds it; the form keeps its action/method=post as the no-JS fallback.
const announcementForm = document.getElementById('announcement-read-form');
if (announcementForm && window.krtFetch) {
    announcementForm.addEventListener('submit', function (event) {
        event.preventDefault();
        const idInput = announcementForm.querySelector('input[name="id"]');
        const announcementId = idInput ? idInput.value : '';
        window.krtFetch.write({
            method: 'POST',
            url: '/announcement/read?id=' + encodeURIComponent(announcementId),
            toast: false,
            onSuccess: function () {
                announcementForm.remove();
            },
        });
    });
}
