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

/* global supplyI18n */

import { krtEvents } from '/js/kernel/event-delegation.js';
import { showKrtConfirm } from '/js/kernel/toast.js';
import { bindResults, initPledgeModal, numberOrNull, sendWrite } from '/js/supply/supply-common.js';

document.addEventListener('DOMContentLoaded', () => {
    const afterWrite = bindResults({
        containerSelector: '#supply-mine-results',
        listUrl: '/supply/donations/mine',
        section: 'mine',
    });
    initPledgeModal(afterWrite);

    krtEvents.on('click', 'supply-donation-withdraw', async (button) => {
        const confirmed = await showKrtConfirm(
            String(supplyI18n.withdrawDonationTitle),
            String(supplyI18n.withdrawDonationText),
            String(supplyI18n.withdrawDonationAction),
        );
        if (!confirmed) return;
        sendWrite({
            method: 'POST',
            url: `/api/proxy/supply/donations/${button.getAttribute('data-donation-id')}/withdraw`,
            payload: { version: numberOrNull(button.getAttribute('data-version') || '') },
            successMessage: supplyI18n.withdrawn,
            errorMessage: supplyI18n.donationError,
            submitter: button,
            afterWrite,
        });
    });
});
