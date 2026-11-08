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

/** @typedef {{ previous: string, next: string }} PagerLabels */

/**
 * The pager button names from a page's dictionary.
 *
 * @param {(key: string) => string} t the page's label reader
 * @returns {PagerLabels} the accessible names of the two buttons
 */
export function pagerLabels(t) {
    return { previous: t('pagePrevious'), next: t('pageNext') };
}

/**
 * Reloads a list's last page when the page it asked for lies beyond it, as after the last row of
 * the last page was resolved or a peer shrank the list.
 *
 * @param {number} asked the zero-based page the list asked for
 * @param {{ totalPages?: number }} page the page the backend answered
 * @param {(page: number) => void} load loads a page of the list
 * @returns {boolean} true when the last page is being reloaded and the answer must not be shown
 */
export function reloadedBeyondEnd(asked, page, load) {
    const last = Math.max((page.totalPages || 0) - 1, 0);
    if (asked <= last) return false;
    load(last);
    return true;
}

/**
 * Renders a previous/next pager below a script-rendered list, or hides it on a single page.
 *
 * @param {HTMLElement | null} nav the pager element
 * @param {{ page?: number, totalPages?: number } | null} page the page the list shows
 * @param {PagerLabels} labels the accessible names of the two buttons
 */
export function renderPager(nav, page, labels) {
    if (!nav) return;
    const total = page && page.totalPages ? page.totalPages : 0;
    const current = page && page.page ? page.page : 0;
    nav.hidden = total <= 1;
    krtHtml.set(
        nav,
        krtHtml`<button type="button" class="page-btn" data-page="${current - 1}" aria-label="${
            labels.previous
        }">‹</button><span class="page-status"><b>${current + 1}</b> / ${Math.max(
            total,
            1,
        )}</span><button type="button" class="page-btn" data-page="${current + 1}" aria-label="${
            labels.next
        }">›</button>`,
    );
    nav.querySelectorAll('button[data-page]').forEach((btn) => {
        if (btn instanceof HTMLButtonElement) {
            const target = Number(btn.dataset.page);
            btn.disabled = target < 0 || target >= total;
        }
    });
}

/**
 * Makes a pager load the page its enabled buttons ask for.
 *
 * @param {HTMLElement | null} nav the pager element
 * @param {(page: number) => void} load loads a page of its list
 */
export function bindPager(nav, load) {
    if (!nav) return;
    nav.addEventListener('click', (e) => {
        const target = e.target instanceof Element ? e.target : null;
        const btn = target ? target.closest('button[data-page]') : null;
        if (btn instanceof HTMLButtonElement && !btn.disabled) {
            load(Number(btn.dataset.page));
        }
    });
}
