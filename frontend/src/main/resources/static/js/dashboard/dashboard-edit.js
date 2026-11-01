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
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtReorder } from '/js/kernel/krt-reorder.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { loadAll, stopPolling } from '/js/dashboard/dashboard-widgets.js';

const BOARD_ID = 'dashboard-board';
const WRITE_LOCK = 'dashboard-layout';
const COLUMNS = ['WIDE', 'NARROW'];
const CATALOGUE_MODAL = 'dashboard-catalogue-modal';
const OPTIONS_MODAL = 'dashboard-options-modal';
const RESET_MODAL = 'dashboard-reset-modal';

/**
 * One instance of a layout write.
 *
 * @typedef {object} LayoutItem
 * @property {string | null} id the stored instance, null to add one
 * @property {string} type the widget type
 * @property {string} column the column
 */

/** @type {Array<{ syncBounds: () => void }>} the reorder handles of the current board */
let reorderHandles = [];

/** @type {HTMLElement | null} the instance whose options dialog is open */
let optionsItem = null;

/**
 * The board element.
 *
 * @returns {HTMLElement | null} the board, or null on a page without a dashboard
 */
function board() {
    return document.getElementById(BOARD_ID);
}

/**
 * The mode the board shows, as the write endpoints name it.
 *
 * @returns {string} the mode segment
 */
function mode() {
    return board()?.dataset.mode ?? 'member';
}

/**
 * The column containers in layout order.
 *
 * @returns {HTMLElement[]} the wide column, then the narrow one
 */
function columns() {
    return COLUMNS.map(
        (name) =>
            /** @type {HTMLElement | null} */ (
                document.querySelector(`[data-dashboard-column="${name}"]`)
            ),
    ).filter((column) => column !== null);
}

/**
 * The instances in layout order: the wide column's, then the narrow column's.
 *
 * @returns {HTMLElement[]} the instance elements
 */
function orderedItems() {
    return columns().flatMap(
        (column) =>
            /** @type {HTMLElement[]} */ (
                Array.from(column.querySelectorAll(':scope > [data-dashboard-item]'))
            ),
    );
}

/**
 * The layout the page shows, as a layout write sends it.
 *
 * @returns {LayoutItem[]} the instances in order
 */
function layoutItems() {
    return columns().flatMap((column) =>
        Array.from(column.querySelectorAll(':scope > [data-dashboard-item]')).map((element) => {
            const item = /** @type {HTMLElement} */ (element);
            return {
                id: item.dataset.widgetId || null,
                type: item.dataset.widgetType ?? '',
                column: column.dataset.dashboardColumn ?? 'WIDE',
            };
        }),
    );
}

/** Keeps the empty state, the instance count and the reorder bounds in line with the columns. */
function syncState() {
    const element = board();
    if (!element) return;
    const count = orderedItems().length;
    element.dataset.count = String(count);
    element.querySelector('[data-dashboard-empty]')?.classList.toggle('is-hidden', count > 0);
    reorderHandles.forEach((handle) => handle.syncBounds());
}

/** Removes the one-time hint once the dashboard was changed. */
function dropHint() {
    document.querySelector('[data-dashboard-hint]')?.remove();
}

/**
 * Takes the stored layout the backend answered: the new version and the ids of the instances,
 * which come back in the order they were sent.
 *
 * @param {{ version: number, widgets: Array<{ id: string, optionsVersion: number }> }} stored
 *     the backend's answer
 */
function applyStored(stored) {
    const element = board();
    if (!element || !stored) return;
    element.dataset.version = String(stored.version);
    element.dataset.customised = 'true';
    orderedItems().forEach((item, index) => {
        const widget = stored.widgets[index];
        if (!widget) return;
        item.dataset.widgetId = widget.id;
        item.dataset.optionsVersion = String(widget.optionsVersion);
    });
    dropHint();
}

/**
 * Re-reads the board from the server and swaps it in place; every instance loads again. Used
 * after a conflict and after a change the DOM cannot mirror itself.
 *
 * @returns {Promise<void>} settles when the board was swapped
 */
export async function reloadBoard() {
    const element = board();
    if (!element) return;
    const response = await krtFetch.get(`/dashboard/${mode()}/board`, {
        accept: 'text/html',
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
    });
    if (!response || !response.ok) return;
    const fresh = krtFetch.parseTrustedDocument(await response.text()).getElementById(BOARD_ID);
    if (!fresh) return;
    stopPolling();
    Object.keys(fresh.dataset).forEach((key) => {
        element.dataset[key] = fresh.dataset[key];
    });
    krtFetch.setTrustedHtml(element, fresh.innerHTML);
    attachReorder();
    loadAll();
}

/**
 * Drops the change the page made, says so and shows the stored state again.
 *
 * @returns {Promise<void>} settles when the board was swapped
 */
function conflict() {
    showFrontendErrorToast(board()?.dataset.conflict ?? '');
    return reloadBoard();
}

/**
 * Sends the layout the page shows.
 *
 * @param {LayoutItem[]} [extra] instances to append, e.g. the one a catalogue pick adds
 * @returns {Promise<boolean>} whether the backend stored the layout; a failure re-reads the board
 */
async function writeLayout(extra = []) {
    const element = board();
    if (!element) return false;
    const result = await krtFetch.write({
        method: 'PUT',
        url: `/dashboard/${mode()}/layout`,
        payload: () => ({
            widgets: [...layoutItems(), ...extra],
            version: Number(element.dataset.version || '0'),
        }),
        serialize: WRITE_LOCK,
        toast: false,
        errorMessage: element.dataset.saveError,
        onError: (status) => {
            if (status !== 409) return false;
            conflict();
            return true;
        },
        onSuccess: (stored) => {
            if (extra.length === 0) applyStored(stored);
        },
    });
    if (!result.ok && result.status !== 409) {
        await reloadBoard();
    }
    return result.ok;
}

/**
 * Makes both columns' instances movable with the up/down controls and Alt+ArrowUp/ArrowDown.
 */
function attachReorder() {
    reorderHandles = columns().map((container) =>
        krtReorder.attach({
            container,
            itemSelector: '[data-dashboard-item]',
            persist: () => writeLayout(),
        }),
    );
    syncState();
}

/**
 * Moves an instance to the other column and stores the layout.
 *
 * @param {HTMLElement} trigger the column switch of the instance
 */
async function moveColumn(trigger) {
    const item = trigger.closest('[data-dashboard-item]');
    if (!(item instanceof HTMLElement)) return;
    const target = item.dataset.column === 'WIDE' ? 'NARROW' : 'WIDE';
    const container = columns().find((column) => column.dataset.dashboardColumn === target);
    if (!container) return;
    container.append(item);
    item.dataset.column = target;
    const wide = target === 'WIDE';
    const label = trigger.dataset[wide ? 'labelNarrow' : 'labelWide'] ?? '';
    trigger.setAttribute('aria-label', label);
    trigger.title = label;
    syncState();
    trigger.focus();
    await writeLayout();
}

/**
 * Removes an instance and stores the layout.
 *
 * @param {HTMLElement} trigger the remove control of the instance
 */
async function removeItem(trigger) {
    const item = trigger.closest('[data-dashboard-item]');
    if (!(item instanceof HTMLElement)) return;
    item.remove();
    syncState();
    await writeLayout();
}

/**
 * Switches the edit mode of the board and its toolbar.
 *
 * @param {HTMLElement} toggle the "Anpassen" button
 */
function toggleEditing(toggle) {
    const element = board();
    if (!element) return;
    const editing = !element.classList.contains('is-editing');
    element.classList.toggle('is-editing', editing);
    document.querySelector('[data-dashboard-toolbar]')?.classList.toggle('is-hidden', !editing);
    toggle.setAttribute('aria-pressed', String(editing));
    toggle.textContent =
        (editing ? toggle.dataset.labelDone : toggle.dataset.labelCustomise) ?? toggle.textContent;
}

/** Opens the catalogue; at the cap it only explains why nothing can be added. */
function openCatalogue() {
    const element = board();
    const full = element ? Number(element.dataset.count) >= Number(element.dataset.max) : false;
    const dialog = document.getElementById(CATALOGUE_MODAL);
    dialog?.querySelector('[data-dashboard-full]')?.classList.toggle('is-hidden', !full);
    dialog?.querySelectorAll('[data-trigger="dashboard-catalogue-add"]').forEach((button) => {
        /** @type {HTMLButtonElement} */ (button).disabled = full;
    });
    krtModal.open(CATALOGUE_MODAL);
}

/**
 * Adds the picked widget at the end of its default column and re-reads the board.
 *
 * @param {HTMLElement} button the catalogue entry's add button
 */
async function addWidget(button) {
    const ok = await writeLayout([
        {
            id: null,
            type: button.dataset.widgetType ?? '',
            column: button.dataset.column ?? 'WIDE',
        },
    ]);
    if (!ok) return;
    krtModal.close(CATALOGUE_MODAL);
    dropHint();
    await reloadBoard();
}

/**
 * Fills the options dialog from an instance and opens it; only the options the widget offers show.
 *
 * @param {HTMLElement} trigger the options control of the instance
 */
function openOptions(trigger) {
    const item = trigger.closest('[data-dashboard-item]');
    const dialog = document.getElementById(OPTIONS_MODAL);
    if (!(item instanceof HTMLElement) || !dialog) return;
    optionsItem = item;
    const offered = (item.dataset.options ?? '').split(' ');
    dialog.querySelectorAll('[data-option]').forEach((group) => {
        const option = /** @type {HTMLElement} */ (group).dataset.option ?? '';
        group.classList.toggle('is-hidden', !offered.includes(option));
        const select = group.querySelector('select');
        if (select) select.value = item.dataset[option] ?? '';
    });
    krtModal.open(OPTIONS_MODAL);
}

/**
 * Reads one option of the dialog, empty when the widget does not offer it.
 *
 * @param {HTMLFormElement} form the options form
 * @param {string} name the option's field name
 * @returns {string | null} the chosen value, or null for none
 */
function optionValue(form, name) {
    const group = form.querySelector(`[data-option="${name}"]`);
    const select = group?.querySelector('select');
    if (!select || group?.classList.contains('is-hidden')) return null;
    return select.value || null;
}

/**
 * Stores the options of the open instance; an instance of the unchanged default is stored first.
 * A conflict or a vanished instance drops the change and re-reads the board.
 *
 * @param {HTMLFormElement} form the options form
 */
async function saveOptions(form) {
    const item = optionsItem;
    if (!item) return;
    if (!item.dataset.widgetId && !(await writeLayout())) return;
    const days = optionValue(form, 'days');
    const rows = optionValue(form, 'rows');
    const result = await krtFetch.write({
        method: 'PUT',
        url: `/dashboard/${mode()}/widgets/${item.dataset.widgetId}/options`,
        payload: () => ({
            orgUnitId: optionValue(form, 'unit'),
            days: days === null ? null : Number(days),
            rows: rows === null ? null : Number(rows),
            status: optionValue(form, 'status'),
            version: Number(item.dataset.optionsVersion || '0'),
        }),
        toast: false,
        errorMessage: board()?.dataset.saveError,
        onError: (status) => {
            if (status !== 409 && status !== 404) return false;
            krtModal.close(OPTIONS_MODAL);
            conflict();
            return true;
        },
    });
    if (!result.ok) return;
    krtModal.close(OPTIONS_MODAL);
    await reloadBoard();
}

/** Restores the mode's default and re-reads the board. */
async function resetToDefault() {
    const element = board();
    if (!element) return;
    const result = await krtFetch.write({
        method: 'DELETE',
        url: () => `/dashboard/${mode()}?version=${element.dataset.version || '0'}`,
        toast: false,
        serialize: WRITE_LOCK,
        errorMessage: element.dataset.saveError,
        onError: (status) => {
            if (status !== 409) return false;
            krtModal.close(RESET_MODAL);
            conflict();
            return true;
        },
    });
    if (!result.ok) return;
    krtModal.close(RESET_MODAL);
    await reloadBoard();
}

/**
 * Registers the edit controls of the dashboard and attaches the reorder helper to its columns.
 */
export function initDashboardEdit() {
    if (!board()) return;
    attachReorder();
    krtEvents.on('click', 'dashboard-customise', toggleEditing);
    krtEvents.on('click', 'dashboard-move-column', moveColumn);
    krtEvents.on('click', 'dashboard-remove', removeItem);
    krtEvents.on('click', 'dashboard-add', openCatalogue);
    krtEvents.on('click', 'dashboard-catalogue-add', addWidget);
    krtEvents.on('click', 'dashboard-options', openOptions);
    krtEvents.on('submit', 'dashboard-options-form', (form, event) => {
        event.preventDefault();
        saveOptions(/** @type {HTMLFormElement} */ (form));
    });
    krtEvents.on('click', 'dashboard-reset', () => krtModal.open(RESET_MODAL));
    krtEvents.on('click', 'dashboard-reset-confirm', resetToDefault);
    krtEvents.on('click', 'dashboard-reload', () => {
        reloadBoard();
    });
}
