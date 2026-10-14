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

/* global purchasingI18n */

import { krtModal } from '/js/kernel/krt-modal.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { showKrtConfirm } from '/js/kernel/toast.js';
import {
    bindResults,
    field,
    numberOrNull,
    select,
    sendWrite,
    submitterOf,
    textOrNull,
} from '/js/purchasing/purchasing-common.js';

document.addEventListener('DOMContentLoaded', () => {
    const afterWrite = bindResults({
        containerSelector: '#purchasing-partners-results',
        listUrl: '/purchasing/partners',
        section: 'deals',
    });

    const modal = document.getElementById('purchasing-partner-modal');
    const form = /** @type {HTMLFormElement | null} */ (
        document.getElementById('purchasing-partner-form')
    );
    if (!modal || !form) return;
    const text = /** @type {HTMLTextAreaElement} */ (
        document.getElementById('purchasing-partner-conditions')
    );
    const reputation = /** @type {HTMLTextAreaElement} */ (
        document.getElementById('purchasing-partner-reputation')
    );

    krtEvents.on('click', 'purchasing-open-partner', () => {
        form.reset();
        field('purchasing-partner-id').value = '';
        field('purchasing-partner-version').value = '';
        const kindRow = document.getElementById('purchasing-partner-kind-row');
        if (kindRow) kindRow.hidden = false;
        krtModal.open(modal);
    });

    krtEvents.on('click', 'purchasing-edit-partner', (button) => {
        form.reset();
        field('purchasing-partner-id').value = button.getAttribute('data-partner-id') || '';
        field('purchasing-partner-version').value = button.getAttribute('data-version') || '';
        field('purchasing-partner-name').value = button.getAttribute('data-name') || '';
        field('purchasing-partner-tag').value = button.getAttribute('data-org-tag') || '';
        select('purchasing-partner-channel').value = button.getAttribute('data-channel') || 'OTHER';
        select('purchasing-partner-contact').value = button.getAttribute('data-contact') || 'OTHER';
        text.value = button.getAttribute('data-conditions') || '';
        reputation.value = button.getAttribute('data-reputation') || '';
        const kindRow = document.getElementById('purchasing-partner-kind-row');
        if (kindRow) kindRow.hidden = true;
        krtModal.open(modal);
    });

    form.addEventListener('submit', (event) => {
        event.preventDefault();
        const id = textOrNull(field('purchasing-partner-id').value);
        const common = {
            name: field('purchasing-partner-name').value.trim(),
            orgTag: textOrNull(field('purchasing-partner-tag').value),
            channel: select('purchasing-partner-channel').value,
            conditionsNote: textOrNull(text.value),
            reputationNote: textOrNull(reputation.value),
            contactVia: select('purchasing-partner-contact').value,
        };
        sendWrite({
            method: id ? 'PUT' : 'POST',
            url: id ? `/api/proxy/purchasing/partners/${id}` : '/api/proxy/purchasing/partners',
            payload: id
                ? { ...common, version: numberOrNull(field('purchasing-partner-version').value) }
                : { ...common, kind: select('purchasing-partner-kind').value },
            successMessage: purchasingI18n.partnerSaved,
            errorMessage: purchasingI18n.partnerError,
            submitter: submitterOf(form),
            afterWrite() {
                krtModal.close(modal);
                afterWrite();
            },
        });
    });

    /**
     * Sends a status change of a partner that carries only the echoed version.
     *
     * @param {HTMLElement} button the action button carrying the partner id and version
     * @param {string} action `block`, `unblock` or `erase`
     */
    function changePartner(button, action) {
        sendWrite({
            method: 'POST',
            url: `/api/proxy/purchasing/partners/${button.getAttribute('data-partner-id')}/${action}`,
            payload: { version: numberOrNull(button.getAttribute('data-version') || '') },
            successMessage: purchasingI18n.partnerChanged,
            errorMessage: purchasingI18n.partnerError,
            submitter: button,
            afterWrite,
        });
    }

    krtEvents.on('click', 'purchasing-partner-block', (button) => {
        changePartner(button, 'block');
    });
    krtEvents.on('click', 'purchasing-partner-unblock', (button) => {
        changePartner(button, 'unblock');
    });
    krtEvents.on('click', 'purchasing-partner-erase', async (button) => {
        const confirmed = await showKrtConfirm(
            String(purchasingI18n.eraseTitle),
            String(purchasingI18n.eraseConfirm),
            String(purchasingI18n.eraseAction),
        );
        if (confirmed) changePartner(button, 'erase');
    });
});
