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

import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { localisedName } from '/js/kernel/krt-localised-name.js';
import {
    bindPager,
    pagerLabels,
    reloadedBeyondEnd,
    renderPager,
} from '/js/catalogue/catalogue-pager.js';
import {
    bindTypedFilter,
    byId as $,
    dictionaryLabels,
    filterValue,
    idOf,
    jsonReader,
    swapRow,
} from '/js/catalogue/catalogue-link-admin.js';
import { formatTimestamp } from '/js/catalogue/catalogue-time.js';

/** @typedef {ApiDto<'GameItemAdminDto'>} AdminItem */

/** The page and proxy base path. */
const PAGE_URL = '/admin/items';

/**
 * The items the list shows, by id, so a row can be re-rendered after a change.
 *
 * @type {Map<string, AdminItem>}
 */
const shownItems = new Map();

let itemsPage = 0;

/**
 * The page's section seam: changes go through it, and a peer admin's change, announced in the admin
 * room's `items` section, reloads the list on its current page (REQ-FE-015).
 */
const sections = krtPageSections.create({
    topicClass: 'CATALOGUE_ADMIN',
    topic: 'catalogue-admin',
    sections: { items: { container: '#admin-items-rows' } },
    receiver: {
        coalesceMs: 1500,
        refresh() {
            loadItems(itemsPage);
        },
    },
});

/** Reads one label of the page's dictionary. */
const t = dictionaryLabels(() => window.krtAdminItemsI18n, 'krtAdminItemsI18n');

/** Reads a JSON answer for one render target, or null after the load-error toast or a newer read. */
const readJson = jsonReader(() => t('loadError'));

/**
 * The type column of an item: its type and sub-type.
 *
 * @param {AdminItem} item the item
 * @returns {string} the label, empty when the item has no type
 */
function typeLabel(item) {
    return [item.type, item.subType].filter((part) => part).join(' · ');
}

/**
 * Renders one item row with the action its state allows.
 *
 * @param {AdminItem} item the item
 * @returns {KrtHtml} the row markup
 */
function itemRow(item) {
    const since = item.unobtainableSince || '';
    const status = since
        ? krtHtml`<span class="chip chip--muted">${t('unobtainable')}</span> <span class="cell-sub">${t(
              'since',
          )} ${formatTimestamp(since)}</span>`
        : krtHtml`<span class="chip chip--success">${t('offered')}</span>`;
    const retired = item.retired
        ? krtHtml` <span class="chip chip--warning">${t('retired')}</span>`
        : '';
    const action = since
        ? krtHtml`<button type="button" class="btn btn-ghost btn-xs" data-obtainable="true">${t(
              'actionShow',
          )}</button>`
        : krtHtml`<button type="button" class="btn btn-ghost btn-xs" data-obtainable="false">${t(
              'actionHide',
          )}</button>`;
    return krtHtml`<tr data-testid="admin-item-row" data-item-id="${idOf(item)}"><td><span class="cell-title">${localisedName(
        item.name,
        item.nameDe,
    )}</span><span class="cell-sub">${item.manufacturerName || ''}</span></td><td>${typeLabel(
        item,
    )}</td><td class="cell-status">${status}${retired}</td><td class="cell-actions">${action}</td></tr>`;
}

/** @returns {boolean} whether the list shows only the items hidden as not obtainable */
function unobtainableOnly() {
    const toggle = $('admin-items-unobtainable');
    return toggle instanceof HTMLInputElement && toggle.checked;
}

/**
 * Loads one page of the items with the current filters.
 *
 * @param {number} page the zero-based page
 */
function loadItems(page) {
    const params = new URLSearchParams({ page: String(page) });
    const query = filterValue('admin-items-query');
    if (query) params.set('query', query);
    if (unobtainableOnly()) params.set('unobtainableOnly', 'true');
    readJson(`${PAGE_URL}/list?${params}`, 'admin-items').then(
        (/** @type {ApiPage<'GameItemAdminDto'> | null} */ result) => {
            if (!result) return;
            if (reloadedBeyondEnd(page, result, loadItems)) return;
            itemsPage = page;
            const rows = result.content || [];
            shownItems.clear();
            rows.forEach((item) => shownItems.set(idOf(item), item));
            const body = $('admin-items-rows');
            const empty = $('admin-items-empty');
            if (empty) empty.hidden = rows.length > 0;
            if (body) krtHtml.set(body, rows.map(itemRow));
            renderPager($('admin-items-pager'), result, pagerLabels(t));
        },
    );
}

/**
 * Shows an item as saved: its row in place, or the reloaded list when the item left the filter.
 *
 * @param {AdminItem} item the item after the change
 */
function showItem(item) {
    if (unobtainableOnly() && !item.unobtainableSince) {
        loadItems(itemsPage);
        return;
    }
    shownItems.set(idOf(item), item);
    const row = document.querySelector(`tr[data-item-id="${CSS.escape(idOf(item))}"]`);
    if (row) swapRow(row, itemRow(item));
}

/**
 * Hides an item as not obtainable, or offers it again. The request names the wanted state, so a
 * peer's change in between leaves nothing to conflict with; the backend's 409 means an import or a
 * sync holds the catalogue.
 *
 * @param {AdminItem} item the item as shown
 * @param {boolean} obtainable whether the pickers offer the item after the change
 * @param {Element | null} submitter the control that sent it
 */
function changeObtainability(item, obtainable, submitter) {
    sections
        .write({
            method: 'POST',
            url: `${PAGE_URL}/${encodeURIComponent(idOf(item))}/obtainability`,
            payload: { obtainable },
            submitter,
            toast: false,
            errorMessage: t('error'),
            onConflict() {
                showFrontendErrorToast(t('busy'));
                return true;
            },
        })
        .then((result) => {
            if (!result.ok) return;
            showFrontendSuccessToast(obtainable ? t('toastShown') : t('toastHidden'));
            showItem(/** @type {AdminItem} */ (result.body));
        });
}

/**
 * Handles the list's clicks: the hide and the offer-again buttons.
 *
 * @param {MouseEvent} e the click
 */
function onItemsClick(e) {
    const target = e.target instanceof Element ? e.target : null;
    const button = target ? target.closest('button[data-obtainable]') : null;
    const row = button ? button.closest('tr[data-item-id]') : null;
    if (!(button instanceof HTMLElement) || !(row instanceof HTMLElement)) return;
    const item = shownItems.get(row.dataset.itemId || '');
    if (item) changeObtainability(item, button.dataset.obtainable === 'true', button);
}

/** Wires the filters, the pager and the row actions, then loads the list. */
function initAdminItems() {
    const form = $('admin-items-filter');
    if (form) form.addEventListener('submit', (e) => e.preventDefault());
    bindTypedFilter('admin-items-query', () => loadItems(0));
    const toggle = $('admin-items-unobtainable');
    if (toggle) toggle.addEventListener('change', () => loadItems(0));
    bindPager($('admin-items-pager'), loadItems);
    const rows = $('admin-items-rows');
    if (rows) rows.addEventListener('click', onItemsClick);
    loadItems(0);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAdminItems);
} else {
    initAdminItems();
}
