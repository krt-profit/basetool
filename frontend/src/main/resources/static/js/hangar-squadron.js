// @ts-check
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

/**
 * Opens or closes the owner rows of one ship type in the org-unit overview.
 *
 * @param {HTMLElement} toggle the type row's chevron button
 */
function toggleSquadronOwners(toggle) {
    const groupId = toggle.getAttribute('aria-controls');
    const group = groupId ? document.getElementById(groupId) : null;
    if (!group) {
        return;
    }
    const open = toggle.getAttribute('aria-expanded') !== 'true';
    toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    group.hidden = !open;
    const row = toggle.closest('tr');
    if (row) {
        row.classList.toggle('is-open', open);
    }
}

document.addEventListener('click', (event) => {
    const target = event.target instanceof Element ? event.target : null;
    const toggle = target ? target.closest('.hangar-tree__toggle') : null;
    if (toggle instanceof HTMLElement) {
        toggleSquadronOwners(toggle);
    }
});

document.addEventListener('DOMContentLoaded', () => {
    const filterForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('squadron-filter-form')
    );
    const resultsContainer = document.getElementById('squadron-results');
    const searchInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('squadron-ship-filter')
    );
    /** @type {number | null} */
    let squadronFilterTimer = null;

    /**
     * Swaps the overview for the given URL, or for the filter form's current state.
     *
     * @param {string | null} url the target URL, or null to build it from the filter form
     */
    function applySquadronFilter(url) {
        let target = url;
        if (!target) {
            const params = new URLSearchParams();
            if (filterForm) {
                const data = new FormData(filterForm);
                for (const [key, value] of data.entries()) {
                    if (value !== '') {
                        params.append(key, String(value));
                    }
                }
            }
            const query = params.toString();
            target = `/hangar/squadron${query ? `?${query}` : ''}`;
        }
        if (!resultsContainer || !window.krtFetch) {
            window.location.assign(target);
            return;
        }
        window.krtFetch.swap({ url: target, container: resultsContainer, history: true });
    }

    if (filterForm) {
        filterForm.addEventListener('submit', (event) => {
            event.preventDefault();
            clearTimeout(squadronFilterTimer ?? undefined);
            applySquadronFilter(null);
        });
    }
    if (searchInput) {
        searchInput.addEventListener('input', () => {
            clearTimeout(squadronFilterTimer ?? undefined);
            squadronFilterTimer = window.setTimeout(() => {
                applySquadronFilter(null);
            }, 300);
        });
    }
    if (resultsContainer) {
        resultsContainer.addEventListener('click', (event) => {
            const target = event.target instanceof Element ? event.target : null;
            const clear = target ? target.closest('[data-testid="empty-state-action"]') : null;
            if (!clear || !resultsContainer.contains(clear)) {
                return;
            }
            event.preventDefault();
            if (searchInput) {
                searchInput.value = '';
            }
            clearTimeout(squadronFilterTimer ?? undefined);
            applySquadronFilter(clear.getAttribute('href'));
        });
    }
    if (window.krtFetch && typeof window.krtFetch.bindSwap === 'function') {
        window.krtFetch.bindSwap({ container: '#squadron-results', history: true });
    }
});
