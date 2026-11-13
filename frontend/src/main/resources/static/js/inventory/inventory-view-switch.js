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

const viewLinks = '[data-lager-view-switch] a[href]';

/**
 * Updates the links that carry the current view to another Lager page.
 *
 * @param {boolean} items whether the current view displays items
 */
function syncViewLinks(items) {
    /** @type {NodeListOf<HTMLAnchorElement>} */
    const links = document.querySelectorAll(
        'a[data-lager-view-link], main.lager-page .page-head a.page-eyebrow',
    );
    links.forEach((link) => {
        const url = new URL(link.href);
        if (items) {
            url.searchParams.set('view', 'items');
        } else {
            url.searchParams.delete('view');
        }
        link.href = url.href;
    });
}

/**
 * Binds pagination and the in-place view switch, then lets peer refreshes wait for view changes.
 *
 * @param {{container: string, fragmentValue: string, onViewChanged?: () => void}} options
 *     the stable container, fragment name and optional view-specific initializer
 * @returns {(refresh: () => void | Promise<void>) => Promise<void>} a peer refresh after view swaps
 */
export function bindLagerViewSwitch({ container, fragmentValue, onViewChanged }) {
    const workspace = document.querySelector(container);
    const swapOptions = { container, fragmentValue, history: true };
    /** @type {Promise<void> | null} */
    let pendingView = null;

    krtFetch.bindSwap(swapOptions);
    workspace?.addEventListener('click', (event) => {
        const target = event.target;
        if (
            !(event instanceof MouseEvent) ||
            !(target instanceof Element) ||
            event.defaultPrevented ||
            event.button !== 0 ||
            event.ctrlKey ||
            event.metaKey ||
            event.altKey ||
            event.shiftKey
        ) {
            return;
        }
        const link = target.closest(viewLinks);
        if (!(link instanceof HTMLAnchorElement)) return;
        event.preventDefault();
        if (link.getAttribute('aria-current') === 'page') return;
        const hadFocus = document.activeElement === link;
        const errorMessage = link
            .closest('[data-lager-view-switch]')
            ?.getAttribute('data-lager-view-error');
        const switchView = krtFetch
            .swap({ ...swapOptions, url: link.href, errorMessage: errorMessage || undefined })
            .then((swapped) => {
                if (!swapped) return;
                const selected = workspace.querySelector(`${viewLinks}[aria-current="page"]`);
                if (selected instanceof HTMLAnchorElement) {
                    syncViewLinks(new URL(selected.href).searchParams.get('view') === 'items');
                    if (hadFocus && document.activeElement === document.body) {
                        selected.focus({ preventScroll: true });
                    }
                }
                onViewChanged?.();
            });
        pendingView = switchView;
        void switchView.finally(() => {
            if (pendingView === switchView) pendingView = null;
        });
    });

    return async function afterViewSwitch(refresh) {
        while (pendingView) await pendingView;
        await refresh();
    };
}
