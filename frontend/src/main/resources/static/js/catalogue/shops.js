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
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { bindPager, renderPager } from '/js/catalogue/catalogue-pager.js';
import {
    bindCandidateClick,
    bindTypedFilter,
    byId as $,
    changeLink as sendLinkChange,
    dictionaryLabels,
    filterValue,
    forgetRead,
    idOf,
    readJson as readJsonOr,
    showCandidates,
    swapRow,
} from '/js/catalogue/catalogue-link-admin.js';

/** @typedef {ApiDto<'ShopAdminDto'>} Shop */
/** @typedef {'SET' | 'CLEAR' | 'RELEASE'} LinkAction */

/** The page and proxy base path. */
const PAGE_URL = '/admin/shops';

/**
 * The shops the list shows, by id, so a row can be re-rendered after a write.
 *
 * @type {Map<string, Shop>}
 */
const shownShops = new Map();

let shopsPage = 0;

/** @type {Shop | null} */
let linkTarget = null;

/** Reads one label of the page's dictionary. */
const t = dictionaryLabels(() => window.krtShopsI18n, 'krtShopsI18n');

/**
 * The page's section seam: writes go through it, and a peer admin's change or an APPLY, announced in
 * the admin room's `shops` section, reloads the list on its current page.
 */
const sections = krtPageSections.create({
    topicClass: 'CATALOGUE_ADMIN',
    topic: 'catalogue-admin',
    sections: { shops: { container: '#shops-rows' } },
    receiver: {
        coalesceMs: 1500,
        refresh() {
            loadShops(shopsPage);
        },
    },
});

/**
 * Reads a JSON answer for one render target, or null after the page's load-error toast or when a
 * later read of the target superseded it.
 *
 * @param {string} url the proxy URL
 * @param {string} target the render target
 * @returns {Promise<any>} the parsed body, or null
 */
function readJson(url, target) {
    return readJsonOr(url, t('loadError'), target);
}

/**
 * The name the list shows for a shop: its registry name, else its entity name.
 *
 * @param {Shop} s the shop
 * @returns {string} the name
 */
function shopName(s) {
    return s.registryName || s.entityName || '';
}

/**
 * Renders one shop row with the actions its link state allows.
 *
 * @param {Shop} s the shop
 * @returns {KrtHtml} the row markup
 */
function shopRow(s) {
    const kind = s.kind ? t(`kind.${s.kind}`) : '';
    const exchange = s.commodityExchange
        ? krtHtml` <span class="chip chip--info">${t('exchange')}</span>`
        : '';
    const retired = s.retired
        ? krtHtml` <span class="chip chip--warning">${t('retired')}</span>`
        : '';
    const items = s.itemListKnown
        ? krtHtml`<span class="chip chip--success">${t('itemsKnown')}</span>`
        : krtHtml`<span class="chip chip--muted">${t('itemsUnknown')}</span>`;
    const terminal = s.terminalId
        ? krtHtml`<span class="cell-title">${s.terminalName || s.terminalId}</span>`
        : krtHtml`<span class="text-muted">${t('unlinked')}</span>`;
    const manual = s.manual ? krtHtml` <span class="chip chip--info">${t('manual')}</span>` : '';
    const clear = s.terminalId
        ? krtHtml` <button type="button" class="btn btn-quiet-danger btn-xs" data-link-action="CLEAR">${t(
              'actionClear',
          )}</button>`
        : '';
    const release = s.manual
        ? krtHtml` <button type="button" class="btn btn-ghost btn-xs" data-link-action="RELEASE">${t(
              'actionRelease',
          )}</button>`
        : '';
    return krtHtml`<tr data-testid="shops-row" data-shop-id="${idOf(s)}"><td><span class="cell-title">${shopName(
        s,
    )}</span><span class="cell-sub data-value--mono">${s.entityName || ''}</span>${retired}</td><td>${
        s.starSystem || ''
    }</td><td>${s.placeName || ''}</td><td>${kind}${exchange}</td><td>${items}</td><td>${terminal}${manual}</td><td class="cell-actions"><button type="button" class="btn btn-ghost btn-xs" data-link-open>${t(
        'actionLink',
    )}</button>${clear}${release}</td></tr>`;
}

/**
 * Loads one page of the shops with the current filters.
 *
 * @param {number} page the zero-based page
 */
function loadShops(page) {
    const params = new URLSearchParams({ page: String(page) });
    const query = filterValue('shops-query');
    const system = filterValue('shops-system');
    const unlinked = $('shops-unlinked');
    if (query) params.set('query', query);
    if (system) params.set('starSystem', system);
    if (unlinked instanceof HTMLInputElement && unlinked.checked)
        params.set('unlinkedOnly', 'true');
    readJson(`${PAGE_URL}/entries?${params}`, 'shops-entries').then(
        (/** @type {ApiPage<'ShopAdminDto'> | null} */ result) => {
            if (!result) return;
            shopsPage = page;
            const rows = result.content || [];
            shownShops.clear();
            rows.forEach((s) => shownShops.set(idOf(s), s));
            const body = $('shops-rows');
            const empty = $('shops-empty');
            if (empty) empty.hidden = rows.length > 0;
            if (body) krtHtml.set(body, rows.map(shopRow));
            renderPager($('shops-pager'), result, {
                previous: t('pagePrevious'),
                next: t('pageNext'),
            });
        },
    );
}

/**
 * Sends a link change as a compare-and-set on the link the row shows and swaps the row in place; a
 * link that changed meanwhile reloads the list.
 *
 * @param {Shop} s the shop as shown
 * @param {LinkAction} action the change
 * @param {string | null} terminalId the terminal to link for SET
 * @param {Element | null} submitter the control that sent it
 * @returns {Promise<boolean>} whether the change was saved
 */
function changeLink(s, action, terminalId, submitter) {
    return sendLinkChange({
        sections,
        url: `${PAGE_URL}/${encodeURIComponent(idOf(s))}/terminal-link`,
        payload: {
            action,
            terminalId,
            expectedTerminalId: s.terminalId || null,
            expectedManual: s.manual === true,
        },
        action,
        submitter,
        t,
        reload: () => loadShops(shopsPage),
        show(/** @type {Shop} */ updated) {
            shownShops.set(idOf(updated), updated);
            const row = document.querySelector(`tr[data-shop-id="${CSS.escape(idOf(updated))}"]`);
            if (row) swapRow(row, shopRow(updated));
        },
    });
}

/**
 * Handles the list's clicks: the picker opener and the clear and release actions.
 *
 * @param {MouseEvent} e the click
 */
function onShopsClick(e) {
    const target = e.target instanceof Element ? e.target : null;
    const btn = target ? target.closest('button[data-link-open], button[data-link-action]') : null;
    const row = btn ? btn.closest('tr[data-shop-id]') : null;
    if (!(btn instanceof HTMLButtonElement) || !(row instanceof HTMLElement)) return;
    const s = shownShops.get(row.dataset.shopId || '');
    if (!s) return;
    if (btn.dataset.linkOpen !== undefined) {
        openLinkDialog(s);
        return;
    }
    const action = btn.dataset.linkAction;
    if (action === 'CLEAR' || action === 'RELEASE') changeLink(s, action, null, btn);
}

/**
 * Opens the terminal picker for a shop, searching by the shop's place.
 *
 * @param {Shop} s the shop
 */
function openLinkDialog(s) {
    linkTarget = s;
    const label = $('shops-link-shop');
    if (label) label.textContent = shopName(s);
    const search = $('shops-link-search');
    if (search instanceof HTMLInputElement) search.value = s.placeName || '';
    krtModal.open('shops-link-modal', { focus: search });
    loadCandidates();
}

/** Loads the terminals matching the picker's search as link candidates. */
function loadCandidates() {
    const query = filterValue('shops-link-search');
    const show = (/** @type {ApiDto<'ShopTerminalOptionDto'>[]} */ rows) =>
        showCandidates(
            'shops-link-candidates',
            'shops-link-empty',
            rows.map(
                (o) =>
                    krtHtml`<li data-testid="shops-link-candidate"><span><span class="cell-title">${
                        o.name || ''
                    }</span> <span class="cell-sub">${o.starSystem || ''}</span></span><button type="button" class="btn btn-ghost btn-xs" data-candidate-id="${
                        o.id
                    }">${t('pick')}</button></li>`,
            ),
        );
    if (!query) {
        forgetRead('shops-terminals');
        show([]);
        return;
    }
    readJson(`${PAGE_URL}/terminals?${new URLSearchParams({ query })}`, 'shops-terminals').then(
        (result) => {
            if (Array.isArray(result)) show(result);
        },
    );
}

/**
 * Links the picker's shop to the picked terminal.
 *
 * @param {string | null} terminalId the terminal
 * @param {HTMLButtonElement} btn the pick button
 */
function pickCandidate(terminalId, btn) {
    const s = linkTarget;
    if (!s) return;
    changeLink(s, 'SET', terminalId, btn).then((saved) => {
        if (!saved) return;
        linkTarget = null;
        krtModal.close('shops-link-modal');
    });
}

/** Wires the filters, the pager, the row actions and the picker, then loads the list. */
function initShops() {
    const form = $('shops-filter');
    if (form) form.addEventListener('submit', (e) => e.preventDefault());
    ['shops-query', 'shops-system'].forEach((id) => bindTypedFilter(id, () => loadShops(0)));
    bindTypedFilter('shops-link-search', loadCandidates);
    const unlinked = $('shops-unlinked');
    if (unlinked) unlinked.addEventListener('change', () => loadShops(0));
    bindPager($('shops-pager'), loadShops);
    const rows = $('shops-rows');
    if (rows) rows.addEventListener('click', onShopsClick);
    bindCandidateClick('shops-link-candidates', pickCandidate);
    loadShops(0);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initShops);
} else {
    initShops();
}
