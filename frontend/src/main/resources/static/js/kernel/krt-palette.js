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
 * The quick access (REQ-UI-026, REQ-UI-033): the dialog behind the search field and Ctrl+K. This
 * script wires the dialog's events to the modules that hold the pages, the actions, the groups,
 * the backend entity search and the rows on screen.
 */

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtChrome } from '/js/kernel/krt-chrome.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { NAV_RENDERED_EVENT } from '/js/kernel/nav-preferences.js';
import { showFrontendSuccessToast } from '/js/kernel/toast.js';
import { createActions } from '/js/kernel/krt-palette-actions.js';
import { createEntitySearch } from '/js/kernel/krt-palette-entity-search.js';
import { createGroups } from '/js/kernel/krt-palette-groups.js';
import { remember } from '/js/kernel/krt-palette-history.js';
import { createPageIndex } from '/js/kernel/krt-palette-index.js';
import { toggleFavourite } from '/js/kernel/krt-palette-personalise.js';
import { createView } from '/js/kernel/krt-palette-view.js';
import { krtSearchHitHref, krtSearchParse } from '/js/kernel/krt-search.js';

/** @import { PaletteItem } from '/js/kernel/krt-palette-items.js' */

document.addEventListener('DOMContentLoaded', () => {
    const dialog = document.getElementById('nav-palette');
    const root = dialog ? dialog.querySelector('.palette') : null;
    const input = /** @type {HTMLInputElement | null} */ (document.getElementById('palette-input'));
    const results = document.getElementById('palette-results');
    const emptyEl = dialog ? dialog.querySelector('.palette-empty') : null;
    const liveEl = dialog ? dialog.querySelector('[data-palette-live]') : null;
    if (!dialog || !(root instanceof HTMLElement) || !input || !results) return;
    const paletteRoot = root;
    const paletteInput = input;
    const data = paletteRoot.dataset;

    /**
     * Says that a page became or stopped being a favourite: in the dialog's status line while it
     * is open, otherwise as a toast.
     *
     * @param {string} name the page's name
     * @param {boolean} favourite the page's new state
     */
    const announceFavourite = function (name, favourite) {
        const pattern = favourite
            ? krtI18nText(data.liveFavouriteAdded, 'data-live-favourite-added')
            : krtI18nText(data.liveFavouriteRemoved, 'data-live-favourite-removed');
        const message = pattern.replace('{name}', name);
        if (krtModal.isOpen(dialog) && liveEl) {
            liveEl.textContent = '';
            liveEl.textContent = message;
        } else {
            showFrontendSuccessToast(message);
        }
    };

    const pageIndex = createPageIndex(paletteRoot);
    const search = createEntitySearch(data.entitySearchUrl, () => {
        if (krtModal.isOpen(dialog)) view.render(true);
    });
    const groupsFor = createGroups({
        paletteRoot,
        pages: pageIndex.items,
        emptyStatePages: pageIndex.emptyStatePages,
        currentActions: createActions(paletteRoot, announceFavourite),
        entityHitsFor: search.hitsFor,
    });
    const view = createView({
        input: paletteInput,
        results,
        empty: emptyEl,
        groupsFor,
        searching: search.pending,
        onChoose: (item) => {
            choose(item);
        },
    });

    /**
     * The path a row opens in a new browser tab.
     *
     * @param {PaletteItem} item the row's item
     * @returns {string | null} the path, or null for a row that opens nothing
     */
    const newTabTarget = function (item) {
        if (item.fill) return null;
        if (item.fromServer) {
            const adminMode = !!krtChrome.nav && krtChrome.nav.isAdminMode();
            return krtSearchHitHref(/** @type {any} */ (item), adminMode);
        }
        return item.href || item.tabHref || null;
    };

    /**
     * Opens a row in a new browser tab, closing the palette.
     *
     * @param {PaletteItem} item the highlighted row's item
     */
    const chooseInNewTab = function (item) {
        const target = newTabTarget(item);
        if (!target) return;
        krtModal.close(dialog);
        if (item.href && !item.fromServer) remember(item);
        window.open(target, '_blank', 'noopener');
    };

    /**
     * Opens a page or runs an action, closing the palette first.
     *
     * @param {PaletteItem} item the chosen row
     */
    const choose = function (item) {
        if (item.fill) {
            paletteInput.value = item.fill;
            paletteInput.focus();
            update();
            return;
        }
        krtModal.close(dialog);
        if (item.run) {
            item.run();
        } else if (item.href) {
            if (!item.fromServer) remember(item);
            const adminMode = !!krtChrome.nav && krtChrome.nav.isAdminMode();
            window.location.assign(
                item.fromServer
                    ? krtSearchHitHref(/** @type {any} */ (item), adminMode)
                    : item.href,
            );
        }
    };

    /** Shows the rows for the edited query and searches the backend for it. */
    const update = function () {
        search.schedule(krtSearchParse(paletteInput.value));
        view.render();
    };

    document.addEventListener(NAV_RENDERED_EVENT, () => {
        pageIndex.rebuild();
        if (krtModal.isOpen(dialog)) {
            view.render(true);
        }
    });

    /**
     * Opens the palette.
     *
     * @param {string} [prefill] text to start the search with
     */
    const open = function (prefill) {
        paletteInput.value = prefill || '';
        pageIndex.refreshFrequencies();
        update();
        krtModal.open(dialog, { focus: paletteInput });
        const end = paletteInput.value.length;
        paletteInput.setSelectionRange(end, end);
    };

    krtChrome.palette = { open };

    paletteInput.addEventListener('input', () => {
        update();
    });
    paletteInput.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            view.select(view.selectedIndex() + 1);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            view.select(view.selectedIndex() - 1);
        } else if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
            e.preventDefault();
            const current = view.current();
            if (current) choose(current);
        } else if (e.key === 'Tab' && !e.shiftKey && !e.ctrlKey && !e.metaKey && !e.altKey) {
            const current = view.current();
            if (current && current.fill) {
                e.preventDefault();
                choose(current);
            }
        }
    });

    dialog.addEventListener('keydown', (e) => {
        if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
        const current = view.current();
        if (e.key === 'Enter') {
            e.preventDefault();
            if (current) chooseInNewTab(current);
        } else if ((e.key === 'd' || e.key === 'D') && !e.shiftKey) {
            e.preventDefault();
            const key = current && current.starrable ? current.key : undefined;
            if (current && key) {
                const name = current.label;
                toggleFavourite(key, (favourite) => {
                    announceFavourite(name, favourite);
                });
            }
        }
    });

    dialog.addEventListener('click', (e) => {
        const target = e.target instanceof Element ? e.target : null;
        if (target === dialog || (target && target.closest('.palette-cancel'))) {
            krtModal.close(dialog);
        }
    });

    dialog.addEventListener('close', () => {
        paletteInput.value = '';
        search.cancel();
    });

    document.querySelectorAll('[data-palette-open]').forEach((trigger) => {
        trigger.addEventListener('click', () => {
            if (krtChrome.nav) krtChrome.nav.close();
            open('');
        });
    });

    document.addEventListener('keydown', (e) => {
        if (!(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey) return;
        if (e.key !== 'k' && e.key !== 'K') return;
        const top = krtModal.topmost();
        if (top && top !== dialog) return;
        e.preventDefault();
        if (krtModal.isOpen(dialog)) {
            paletteInput.focus();
            paletteInput.select();
            return;
        }
        if (krtChrome.nav) krtChrome.nav.close();
        open('');
    });

    if (/mac|iphone|ipad/i.test(navigator.platform || navigator.userAgent)) {
        document.querySelectorAll('[data-palette-kbd]').forEach((kbd) => {
            kbd.textContent = '⌘ K';
        });
    }
});
