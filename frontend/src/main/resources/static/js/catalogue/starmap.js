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
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { localisedName } from '/js/kernel/krt-localised-name.js';
import {
    bindPager,
    pagerLabels,
    reloadedBeyondEnd,
    renderPager,
} from '/js/catalogue/catalogue-pager.js';
import {
    bindLinkRowActions,
    bindTypedFilter,
    byId as $,
    changeLink as sendLinkChange,
    createLinkPicker,
    dictionaryLabels,
    filterValue,
    idOf,
    jsonReader,
    showCandidates,
    swapRow,
} from '/js/catalogue/catalogue-link-admin.js';

/** @typedef {ApiDto<'StarmapObjectAdminDto'>} StarmapObject */
/** @typedef {ApiDto<'StarmapPlaceLinkDto'>} StarmapPlace */
/** @typedef {'SET' | 'CLEAR' | 'RELEASE'} LinkAction */

/** The page and proxy base path. */
const PAGE_URL = '/admin/starmap';

/**
 * The objects the object list shows, by id, so a row can be re-rendered after a write.
 *
 * @type {Map<string, StarmapObject>}
 */
const shownObjects = new Map();

/**
 * The rows the link list shows, by id.
 *
 * @type {Map<string, StarmapPlace>}
 */
const shownPlaces = new Map();

let objectsPage = 0;
let placesPage = 0;

/** @type {StarmapObject | null} */
let storageTarget = null;

/**
 * The page's section seam: writes go through it, and a peer admin's change or an APPLY, announced in
 * the admin room's `starmap` section, reloads both lists on their current pages.
 */
const sections = krtPageSections.create({
    topicClass: 'CATALOGUE_ADMIN',
    topic: 'catalogue-admin',
    sections: { starmap: { container: '#starmap-objects' } },
    receiver: {
        coalesceMs: 1500,
        refresh() {
            loadObjects(objectsPage);
            loadPlaces(placesPage);
        },
    },
});

/** Reads one label of the page's dictionary. */
const t = dictionaryLabels(() => window.krtStarmapI18n, 'krtStarmapI18n');

/** Reads a JSON answer for one render target, or null after the load-error toast or a newer read. */
const readJson = jsonReader(() => t('loadError'));

/** The object picker of a link row. */
const picker = createLinkPicker({
    modalId: 'starmap-link-modal',
    subjectId: 'starmap-link-row',
    searchId: 'starmap-link-search',
    candidatesId: 'starmap-link-candidates',
    subject: (/** @type {StarmapPlace} */ p) => p.name || '',
    search: (/** @type {StarmapPlace} */ p) => p.name || '',
    loadCandidates,
    link: (/** @type {StarmapPlace} */ p, objectId, btn) => changeLink(p, 'SET', objectId, btn),
});

/**
 * The name a list shows for an object: its P4K name in the page language, else its class name.
 *
 * @param {StarmapObject} o the object
 * @returns {string} the name
 */
function objectName(o) {
    return localisedName(o.name, o.nameDe) || o.className || o.guid || '';
}

/**
 * A yes / no / unknown label.
 *
 * @param {boolean | null | undefined} value the flag
 * @returns {string} the label
 */
function yesNo(value) {
    if (value == null) return t('unknown');
    return value ? t('yes') : t('no');
}

/**
 * Renders one object row.
 *
 * @param {StarmapObject} o the object
 * @returns {KrtHtml} the row markup
 */
function objectRow(o) {
    const visibility = o.visible
        ? krtHtml`<span class="chip chip--success">${t('visible')}</span>`
        : krtHtml`<span class="chip chip--muted">${t('hidden')}</span>`;
    const retired = o.retired
        ? krtHtml` <span class="chip chip--warning">${t('retired')}</span>`
        : '';
    /** @type {KrtHtml | string} */
    let storage = '';
    if (o.storagePlaceId) {
        storage = krtHtml`<span class="chip chip--muted">${t('storageExists')}</span>`;
    } else if (o.visible) {
        storage = krtHtml`<button type="button" class="btn btn-ghost btn-xs" data-storage-create>${t(
            'storageCreate',
        )}</button>`;
    }
    return krtHtml`<tr data-testid="starmap-object-row" data-object-id="${idOf(o)}"><td><span class="cell-title">${objectName(
        o,
    )}</span><span class="cell-sub data-value--mono">${o.className || ''}</span></td><td>${
        o.type || ''
    }</td><td>${o.starSystem || ''}</td><td>${yesNo(o.hasPosition)}</td><td>${yesNo(
        o.hideInStarmap,
    )}</td><td class="cell-status">${visibility}${retired}</td><td><select data-override data-current="${
        o.visibilityOverride || ''
    }" aria-label="${t('overrideLabel')}"><option value="">${t(
        'override.rule',
    )}</option><option value="VISIBLE">${t('override.VISIBLE')}</option><option value="HIDDEN">${t(
        'override.HIDDEN',
    )}</option></select></td><td class="cell-actions">${storage}</td></tr>`;
}

/**
 * Selects each override select's current value after a render.
 *
 * @param {ParentNode} root the rendered rows
 */
function selectCurrentOverrides(root) {
    root.querySelectorAll('select[data-override]').forEach((select) => {
        if (select instanceof HTMLSelectElement) {
            select.value = select.dataset.current || '';
        }
    });
}

/**
 * Loads one page of the objects with the current filters.
 *
 * @param {number} page the zero-based page
 */
function loadObjects(page) {
    const params = new URLSearchParams({ page: String(page) });
    const query = filterValue('starmap-objects-query');
    const system = filterValue('starmap-objects-system');
    const type = filterValue('starmap-objects-type');
    if (query) params.set('query', query);
    if (system) params.set('starSystem', system);
    if (type) params.set('type', type);
    readJson(`${PAGE_URL}/objects?${params}`, 'starmap-objects').then(
        (/** @type {ApiPage<'StarmapObjectAdminDto'> | null} */ result) => {
            if (!result) return;
            if (reloadedBeyondEnd(page, result, loadObjects)) return;
            objectsPage = page;
            const rows = result.content || [];
            shownObjects.clear();
            rows.forEach((o) => shownObjects.set(idOf(o), o));
            const body = $('starmap-objects');
            const empty = $('starmap-objects-empty');
            if (empty) empty.hidden = rows.length > 0;
            if (body) {
                krtHtml.set(body, rows.map(objectRow));
                selectCurrentOverrides(body);
            }
            renderPager($('starmap-objects-pager'), result, pagerLabels(t));
        },
    );
}

/**
 * Shows an object as saved, in its row.
 *
 * @param {StarmapObject} o the object
 */
function showObject(o) {
    shownObjects.set(idOf(o), o);
    const row = document.querySelector(`tr[data-object-id="${CSS.escape(idOf(o))}"]`);
    if (!row) return;
    const fresh = swapRow(row, objectRow(o));
    if (fresh) selectCurrentOverrides(fresh);
}

/**
 * Sends a changed override select and swaps its row in place; a stale version reloads the list.
 *
 * @param {HTMLSelectElement} select the changed select
 */
function onOverrideChange(select) {
    const row = select.closest('tr[data-object-id]');
    const o = row instanceof HTMLElement ? shownObjects.get(row.dataset.objectId || '') : null;
    if (!o) return;
    sections
        .write({
            method: 'POST',
            url: `${PAGE_URL}/objects/${encodeURIComponent(idOf(o))}/visibility`,
            payload: { override: select.value || null, version: o.version },
            submitter: select,
            toast: false,
            errorMessage: t('error'),
            onConflict() {
                showFrontendErrorToast(t('conflict'));
                loadObjects(objectsPage);
                return true;
            },
        })
        .then((result) => {
            if (!result.ok) {
                select.value = o.visibilityOverride || '';
                return;
            }
            showFrontendSuccessToast(t('toastVisibility'));
            showObject(/** @type {StarmapObject} */ (result.body));
        });
}

/**
 * Opens the storage-place dialog for an object with an empty name: the P4K name is game text
 * (REQ-P4K-033) and `location.name` reaches the exchange, so the admin types the curated name.
 *
 * @param {StarmapObject} o the object
 */
function openStorageDialog(o) {
    const form = $('starmap-storage-form');
    if (form instanceof HTMLFormElement) form.reset();
    storageTarget = o;
    const objectLabel = $('starmap-storage-object');
    if (objectLabel) objectLabel.textContent = objectName(o);
    const name = $('starmap-storage-name');
    if (name instanceof HTMLInputElement) name.value = '';
    krtModal.open('starmap-storage-modal', { focus: name });
}

/**
 * Creates the storage place the dialog describes and marks its object's row in place.
 *
 * @param {SubmitEvent} e the submit
 */
function onStorageSubmit(e) {
    e.preventDefault();
    const o = storageTarget;
    if (!o) return;
    const name = $('starmap-storage-name');
    const description = $('starmap-storage-description');
    const hidden = $('starmap-storage-hidden');
    const home = $('starmap-storage-home');
    sections
        .write({
            method: 'POST',
            url: `${PAGE_URL}/objects/${encodeURIComponent(idOf(o))}/storage-place`,
            payload: {
                name: name instanceof HTMLInputElement ? name.value.trim() : '',
                description: description instanceof HTMLTextAreaElement ? description.value : '',
                hidden: hidden instanceof HTMLInputElement && hidden.checked,
                homeLocation: home instanceof HTMLInputElement && home.checked,
            },
            submitter: $('starmap-storage-submit'),
            toast: false,
            errorMessage: t('error'),
            onConflict() {
                showFrontendErrorToast(t('conflict'));
                krtModal.close('starmap-storage-modal');
                loadObjects(objectsPage);
                return true;
            },
            onError(status) {
                if (status !== 400) return false;
                showFrontendErrorToast(t('storageRefused'));
                return true;
            },
        })
        .then((result) => {
            if (!result.ok) return;
            const created = /** @type {ApiDto<'LocationDto'>} */ (result.body);
            krtModal.close('starmap-storage-modal');
            storageTarget = null;
            showFrontendSuccessToast(t('toastStorage'));
            showObject(Object.assign({}, o, { storagePlaceId: created.id }));
        });
}

/**
 * Handles the object list's clicks: the storage-place opener.
 *
 * @param {MouseEvent} e the click
 */
function onObjectsClick(e) {
    const target = e.target instanceof Element ? e.target : null;
    const btn = target ? target.closest('button[data-storage-create]') : null;
    const row = btn ? btn.closest('tr[data-object-id]') : null;
    if (!(row instanceof HTMLElement)) return;
    const o = shownObjects.get(row.dataset.objectId || '');
    if (o) openStorageDialog(o);
}

/**
 * Renders one link row with the actions its state allows.
 *
 * @param {StarmapPlace} p the row
 * @returns {KrtHtml} the row markup
 */
function placeRow(p) {
    const linked = p.starmapObjectId
        ? krtHtml`<span class="data-value--mono">${p.starmapObjectClassName || p.starmapObjectGuid || ''}</span>`
        : krtHtml`<span class="text-muted">${t('unlinked')}</span>`;
    const manual = p.manual ? krtHtml` <span class="chip chip--info">${t('manual')}</span>` : '';
    const clear = p.starmapObjectId
        ? krtHtml` <button type="button" class="btn btn-quiet-danger btn-xs" data-link-action="CLEAR">${t(
              'actionClear',
          )}</button>`
        : '';
    const release = p.manual
        ? krtHtml` <button type="button" class="btn btn-ghost btn-xs" data-link-action="RELEASE">${t(
              'actionRelease',
          )}</button>`
        : '';
    return krtHtml`<tr data-testid="starmap-place-row" data-place-id="${idOf(p)}"><td><span class="cell-title">${
        p.name || ''
    }</span></td><td>${p.starSystem || ''}</td><td>${linked}${manual}</td><td class="cell-actions"><button type="button" class="btn btn-ghost btn-xs" data-link-open>${t(
        'actionLink',
    )}</button>${clear}${release}</td></tr>`;
}

/** @returns {string} the UEX table the link list shows */
function currentKind() {
    return filterValue('starmap-links-kind') || 'CITY';
}

/**
 * Loads one page of the link rows with the current filters.
 *
 * @param {number} page the zero-based page
 */
function loadPlaces(page) {
    const params = new URLSearchParams({ page: String(page) });
    const query = filterValue('starmap-links-query');
    const unlinked = $('starmap-links-unlinked');
    if (query) params.set('query', query);
    if (unlinked instanceof HTMLInputElement && unlinked.checked)
        params.set('unlinkedOnly', 'true');
    readJson(
        `${PAGE_URL}/places/${encodeURIComponent(currentKind())}?${params}`,
        'starmap-places',
    ).then((/** @type {ApiPage<'StarmapPlaceLinkDto'> | null} */ result) => {
        if (!result) return;
        if (reloadedBeyondEnd(page, result, loadPlaces)) return;
        placesPage = page;
        const rows = result.content || [];
        shownPlaces.clear();
        rows.forEach((p) => shownPlaces.set(idOf(p), p));
        const body = $('starmap-links');
        const empty = $('starmap-links-empty');
        if (empty) empty.hidden = rows.length > 0;
        if (body) krtHtml.set(body, rows.map(placeRow));
        renderPager($('starmap-links-pager'), result, pagerLabels(t));
    });
}

/**
 * Sends a link change as a compare-and-set on the link the row shows and swaps the row in place;
 * a link that changed meanwhile closes the picker and reloads the list.
 *
 * @param {StarmapPlace} p the row as shown
 * @param {LinkAction} action the change
 * @param {string | null} objectId the object to link for SET
 * @param {Element | null} submitter the control that sent it
 * @returns {Promise<boolean>} whether the change was saved
 */
function changeLink(p, action, objectId, submitter) {
    return sendLinkChange({
        sections,
        url: `${PAGE_URL}/places/${encodeURIComponent(p.kind || currentKind())}/${encodeURIComponent(idOf(p))}/link`,
        payload: {
            action,
            starmapObjectId: objectId,
            expectedStarmapObjectId: p.starmapObjectId || null,
            expectedManual: p.manual === true,
        },
        action,
        submitter,
        t,
        reload() {
            picker.dismiss();
            loadPlaces(placesPage);
        },
        show(/** @type {StarmapPlace} */ updated) {
            shownPlaces.set(idOf(updated), updated);
            const row = document.querySelector(`tr[data-place-id="${CSS.escape(idOf(updated))}"]`);
            if (row) swapRow(row, placeRow(updated));
        },
    });
}

/** Loads the first page of the objects matching the picker's search as link candidates. */
function loadCandidates() {
    const query = filterValue('starmap-link-search');
    const params = new URLSearchParams({ page: '0' });
    if (query) params.set('query', query);
    readJson(`${PAGE_URL}/objects?${params}`, 'starmap-candidates').then(
        (/** @type {ApiPage<'StarmapObjectAdminDto'> | null} */ result) => {
            if (!result) return;
            showCandidates(
                'starmap-link-candidates',
                'starmap-link-empty',
                (result.content || []).map(
                    (o) =>
                        krtHtml`<li data-testid="starmap-link-candidate"><span><span class="cell-title">${objectName(
                            o,
                        )}</span> <span class="cell-sub data-value--mono">${o.className || ''} · ${
                            o.type || ''
                        } · ${o.starSystem || ''}</span></span><button type="button" class="btn btn-ghost btn-xs" data-candidate-id="${
                            o.id
                        }">${t('pick')}</button></li>`,
                ),
            );
        },
    );
}

/**
 * Keeps a filter form from submitting; its fields reload the list in place.
 *
 * @param {string} id the form id
 */
function holdSubmit(id) {
    const form = $(id);
    if (form) form.addEventListener('submit', (e) => e.preventDefault());
}

/** Wires the filters, pagers, row actions and dialogs, then loads both lists. */
function initStarmap() {
    holdSubmit('starmap-objects-filter');
    holdSubmit('starmap-links-filter');
    ['starmap-objects-query', 'starmap-objects-system', 'starmap-objects-type'].forEach((id) =>
        bindTypedFilter(id, () => loadObjects(0)),
    );
    bindTypedFilter('starmap-links-query', () => loadPlaces(0));
    const kind = $('starmap-links-kind');
    if (kind) kind.addEventListener('change', () => loadPlaces(0));
    const unlinked = $('starmap-links-unlinked');
    if (unlinked) unlinked.addEventListener('change', () => loadPlaces(0));
    bindPager($('starmap-objects-pager'), loadObjects);
    bindPager($('starmap-links-pager'), loadPlaces);

    const objects = $('starmap-objects');
    if (objects) {
        objects.addEventListener('click', onObjectsClick);
        objects.addEventListener('change', (e) => {
            if (
                e.target instanceof HTMLSelectElement &&
                e.target.matches('select[data-override]')
            ) {
                onOverrideChange(e.target);
            }
        });
    }
    bindLinkRowActions({
        rowsId: 'starmap-links',
        rowSelector: 'tr[data-place-id]',
        find: (row) => shownPlaces.get(row.dataset.placeId || ''),
        open: picker.open,
        change: (p, action, btn) => changeLink(p, action, null, btn),
    });
    const storageForm = $('starmap-storage-form');
    if (storageForm) storageForm.addEventListener('submit', onStorageSubmit);

    loadObjects(0);
    loadPlaces(0);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initStarmap);
} else {
    initStarmap();
}
