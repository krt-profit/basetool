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

import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { applyAppearance } from '/js/kernel/nav-appearance.js';
import { reloadMenu } from '/js/kernel/nav-preferences.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';

const RESET_URL = '/navigation/reset';
const MODAL_ID = 'navigation-reset-modal';

/**
 * Closes the confirmation dialog.
 */
function closeDialog() {
    const dialog = document.getElementById(MODAL_ID);
    if (dialog && window.krtModal) {
        window.krtModal.close(dialog);
    }
}

/**
 * Resets every navigation preference, both dashboards and both start pages after the member
 * confirmed, then puts the page in line with the cleared store: the lists, the tab bar and the
 * start-page controls are re-rendered, and the desktop mode returns to the drawer.
 *
 * @param {HTMLElement} button the confirm button of the dialog
 */
function reset(button) {
    krtFetch.write({
        method: 'POST',
        url: RESET_URL,
        serialize: 'nav-appearance',
        submitter: button instanceof HTMLButtonElement ? button : undefined,
        toast: false,
        errorMessage: button.dataset.error,
        onSuccess: async (stored) => {
            closeDialog();
            applyAppearance(stored.appearance);
            await reloadMenu();
            showFrontendSuccessToast(button.dataset.done || '');
        },
        onError: () => {
            closeDialog();
            showFrontendErrorToast(button.dataset.error || '');
            return true;
        },
    });
}

krtEvents.on('click', 'nav-reset-confirm', reset);
