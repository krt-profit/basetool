// @ts-check
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

(function () {
    'use strict';

    /**
     * Sizes the reserved and private segments of the balance bar from the entered in-game balance
     * (REQ-BANK-032): the reserved share is the holder's custody total, capped at the balance.
     *
     * @param {HTMLInputElement} input the balance input
     */
    function renderBar(input) {
        const panel = input.closest('[data-reserved]');
        if (!panel) return;
        const reservedSeg = /** @type {HTMLElement | null} */ (
            panel.querySelector('[data-balance-seg="reserved"]')
        );
        const ownSeg = /** @type {HTMLElement | null} */ (
            panel.querySelector('[data-balance-seg="own"]')
        );
        if (!reservedSeg || !ownSeg) return;
        const balance = Number(input.value);
        const reservedRaw = Number(panel.getAttribute('data-reserved'));
        const reserved = Number.isFinite(reservedRaw) ? Math.max(reservedRaw, 0) : 0;
        let reservedPercent = 0;
        if (Number.isFinite(balance) && balance > 0) {
            reservedPercent = Math.min(100, (reserved / balance) * 100);
        } else if (reserved > 0) {
            reservedPercent = 100;
        }
        reservedSeg.style.width = `${reservedPercent}%`;
        ownSeg.style.width = `${100 - reservedPercent}%`;
    }

    document.addEventListener('input', (event) => {
        const target = event.target;
        if (target instanceof HTMLInputElement && target.hasAttribute('data-balance-input')) {
            renderBar(target);
        }
    });

    /** Binds the bookings pager of the holder detail to an in-place fragment swap. */
    function bindBankHolderBookingsPager() {
        if (window.krtFetch) {
            window.krtFetch.bindSwap({
                container: '#bank-holder-bookings-results',
                fragmentValue: 'holderBookings',
                history: true,
            });
        }
    }
    document.addEventListener('DOMContentLoaded', bindBankHolderBookingsPager);
    document.addEventListener('krt:swapped', bindBankHolderBookingsPager);
})();
