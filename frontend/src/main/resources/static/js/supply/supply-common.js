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

/* global supplyI18n, supplyConflict */

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';

/**
 * Looks up an element by id and returns it as the given control type; the supply templates always
 * render the ids the modules ask for.
 *
 * @param {string} id the element id
 * @returns {HTMLInputElement} the element, typed as an input for its value and checked state
 */
export function field(id) {
    return /** @type {HTMLInputElement} */ (document.getElementById(id));
}

/**
 * Looks up a select by id.
 *
 * @param {string} id the element id
 * @returns {HTMLSelectElement} the select
 */
export function select(id) {
    return /** @type {HTMLSelectElement} */ (document.getElementById(id));
}

/**
 * Parses a number field; an empty or invalid value is `null`.
 *
 * @param {string} value the field value
 * @returns {number | null} the number, or null
 */
export function numberOrNull(value) {
    if (value === '') return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Returns the trimmed value of a field, or null when it is empty.
 *
 * @param {string} value the field value
 * @returns {string | null} the trimmed value, or null
 */
export function textOrNull(value) {
    const trimmed = value.trim();
    return trimmed === '' ? null : trimmed;
}

/**
 * Sends a JSON write to a supply proxy and runs `afterWrite` once it succeeded.
 *
 * @param {object} opts the request
 * @param {string} opts.method the HTTP method
 * @param {string} opts.url the proxy path
 * @param {object | undefined} opts.payload the JSON body
 * @param {string} opts.successMessage the localized success toast
 * @param {string} opts.errorMessage the localized fallback error text
 * @param {HTMLElement | null} [opts.submitter] the control to disable while the request runs
 * @param {() => void} opts.afterWrite called after a successful write
 * @returns {Promise<unknown>} resolves when the write has settled
 */
export function sendWrite(opts) {
    return krtFetch.write({
        method: opts.method,
        url: opts.url,
        payload: opts.payload,
        successMessage: opts.successMessage,
        errorMessage: opts.errorMessage,
        conflict: supplyConflict,
        submitter: opts.submitter || undefined,
        onSuccess() {
            opts.afterWrite();
        },
    });
}

/** The global supply room; the statistics page listens to it with the section `stats`. */
const STATS_TOPIC = 'supply-needs';

/** @type {WeakMap<Element, KrtReceiver>} */
const receivers = new WeakMap();

/**
 * Wires the live refresh of a results container: reloads it when a peer reports a change and tells
 * the peers after the own write.
 *
 * @param {object} cfg the wiring
 * @param {string} cfg.containerSelector the selector of the results container
 * @param {string} cfg.listUrl the URL of the current list, fragment parameter included
 * @param {string} cfg.section the section key of the container
 * @returns {() => void} the function to call after an own write
 */
export function bindResults(cfg) {
    const container = document.querySelector(cfg.containerSelector);
    const seam = krtPageSections.create({
        sections: { [cfg.section]: { container: cfg.containerSelector } },
        pageUrl() {
            return `${cfg.listUrl}${window.location.search}`;
        },
        topic() {
            return container ? container.getAttribute('data-live-sync-topic') || null : null;
        },
        receiver: container ? { coalesceMs: 1500, retargetable: true } : undefined,
    });
    if (container && seam.receiver) {
        receivers.set(container, seam.receiver);
    }
    krtFetch.bindSwap({ container: cfg.containerSelector, history: true });

    return function afterWrite() {
        seam.refresh(cfg.section);
    };
}

/**
 * Points a bound results container at another topic room, for a page whose list switches between
 * rooms in place.
 *
 * @param {string} containerSelector the selector of the results container
 * @param {string} topic the new topic room, empty for none
 */
export function retargetResults(containerSelector, topic) {
    const container = document.querySelector(containerSelector);
    if (!container) return;
    container.setAttribute('data-live-sync-topic', topic);
    const receiver = receivers.get(container);
    if (receiver) receiver.retarget(topic);
}

/** Tells the statistics page of the peers that a donation changed. */
export function announceStats() {
    krtPageSections.publish(STATS_TOPIC, ['stats']);
}

/**
 * Wires a filter form to a results container: a change of a field or a submit swaps the results
 * for the form's current values in place and keeps the address bar in step.
 *
 * @param {object} cfg the wiring
 * @param {string} cfg.formId the id of the filter form
 * @param {string} cfg.containerSelector the selector of the results container
 * @param {Record<string, string>} [cfg.fragmentOn] fragment values for the fields whose change
 *     needs more than the plain results, keyed by field name
 */
export function bindFilterForm(cfg) {
    const found = document.getElementById(cfg.formId);
    if (!(found instanceof HTMLFormElement) || !document.querySelector(cfg.containerSelector)) {
        return;
    }
    const form = found;
    const fragmentOn = cfg.fragmentOn || {};

    /**
     * Swaps the results for the form's current values.
     *
     * @param {string} fragmentValue the fragment to request
     */
    function apply(fragmentValue) {
        const params = new URLSearchParams();
        for (const [key, value] of new FormData(form).entries()) {
            if (value !== '') params.append(key, String(value));
        }
        const query = params.toString();
        krtFetch.swap({
            url: `${form.getAttribute('action') || window.location.pathname}${query ? `?${query}` : ''}`,
            container: cfg.containerSelector,
            fragmentValue,
            history: true,
        });
    }

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        apply('results');
    });
    form.addEventListener('change', (event) => {
        const target = event.target;
        const name =
            target instanceof HTMLSelectElement || target instanceof HTMLInputElement
                ? target.name
                : '';
        apply(fragmentOn[name] || 'results');
    });
}

/**
 * Switches the material and item rows of a catalogue picker with its kind select.
 *
 * @param {string} kindId the id of the kind select
 * @param {string} materialRowId the id of the material row
 * @param {string} itemRowId the id of the item row
 * @param {string[]} materialOnlyRowIds the ids of the rows only a material has, such as the quality
 */
export function bindKindToggle(kindId, materialRowId, itemRowId, materialOnlyRowIds) {
    const kind = document.getElementById(kindId);
    if (!kind) return;
    /** @returns {void} */
    function apply() {
        const isMaterial = select(kindId).value === 'material';
        const materialRow = document.getElementById(materialRowId);
        const itemRow = document.getElementById(itemRowId);
        if (materialRow) materialRow.hidden = !isMaterial;
        if (itemRow) itemRow.hidden = isMaterial;
        materialOnlyRowIds.forEach((rowId) => {
            const row = document.getElementById(rowId);
            if (row) row.hidden = !isMaterial;
        });
    }
    kind.addEventListener('change', apply);
    apply();
}

/**
 * Wires the pledge modal shared by the needs page and „Meine Spenden": the openers, the catalogue
 * switch and the submit.
 *
 * @param {() => void} afterWrite called after a successful pledge
 */
export function initPledgeModal(afterWrite) {
    const modal = document.getElementById('supply-pledge-modal');
    const form = document.getElementById('supply-pledge-form');
    if (!modal || !form) return;
    bindKindToggle('supply-pledge-kind', 'supply-pledge-material-row', 'supply-pledge-item-row', [
        'supply-pledge-quality-row',
    ]);

    /**
     * Resets the form to a free pledge and opens it.
     *
     * @param {{ needId: string, unitId: string, entry: string, source: string }} preset the preset
     */
    function open(preset) {
        /** @type {HTMLFormElement} */ (form).reset();
        field('supply-pledge-need-id').value = preset.needId;
        field('supply-pledge-source-id').value = preset.source;
        const label = document.getElementById('supply-pledge-need-label');
        const catalogue = document.getElementById('supply-pledge-catalogue');
        const unitRow = document.getElementById('supply-pledge-unit-row');
        const forNeed = preset.needId !== '';
        if (label) {
            label.hidden = !forNeed;
            label.textContent = forNeed
                ? String(supplyI18n.pledgeForNeed).replace('{0}', preset.entry)
                : '';
        }
        if (catalogue) catalogue.hidden = forNeed;
        if (unitRow) unitRow.hidden = forNeed;
        if (preset.unitId !== '') select('supply-pledge-unit').value = preset.unitId;
        krtModal.open(modal);
    }

    krtEvents.on('click', 'supply-open-pledge', () => {
        open({ needId: '', unitId: '', entry: '', source: '' });
    });
    krtEvents.on('click', 'supply-open-pledge-for-need', (button) => {
        open({
            needId: button.getAttribute('data-need-id') || '',
            unitId: button.getAttribute('data-unit-id') || '',
            entry: button.getAttribute('data-entry') || '',
            source: '',
        });
    });

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const needId = textOrNull(field('supply-pledge-need-id').value);
        const isItem = select('supply-pledge-kind').value === 'item';
        const submitter = /** @type {HTMLButtonElement | null} */ (
            form.querySelector('button[type="submit"]')
        );
        sendWrite({
            method: 'POST',
            url: '/api/proxy/supply/donations',
            payload: {
                needId,
                receivingOrgUnitId: needId ? null : textOrNull(select('supply-pledge-unit').value),
                materialId:
                    needId || isItem ? null : textOrNull(select('supply-pledge-material').value),
                gameItemId:
                    needId || !isItem ? null : textOrNull(select('supply-pledge-item').value),
                quality:
                    needId || isItem ? null : numberOrNull(field('supply-pledge-quality').value),
                amount: numberOrNull(field('supply-pledge-amount').value),
                sourceInventoryItemId: textOrNull(field('supply-pledge-source-id').value),
            },
            successMessage: supplyI18n.pledgeSaved,
            errorMessage: supplyI18n.pledgeError,
            submitter,
            afterWrite() {
                krtModal.close(modal);
                afterWrite();
                announceStats();
            },
        });
    });
}
