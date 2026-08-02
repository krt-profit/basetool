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

/*
 * Shared toast + confirmation-modal utilities, extracted verbatim from the former inline script of
 * fragments/toast.html (ADR-0069, follow-up to #924). Loaded on every page via the toast fragment.
 *
 * Defines the auto-dismiss toast initialiser (initToasts) plus the three window globals the rest of
 * the app calls: window.showFrontendErrorToast / window.showFrontendSuccessToast build and show a
 * transient toast, and window.showKrtConfirm is the KRT-styled Promise<boolean> replacement for the
 * native window.confirm().
 *
 * The Thymeleaf-interpolated toast/confirm labels (window.krtToastI18n) stay inline in the fragment
 * bootstrap this module reads.
 */

function initToasts() {
    const toasts = document.querySelectorAll('.notification-toast:not(.initialized)');
    toasts.forEach((toast) => {
        toast.classList.add('initialized');
        // Show shortly after load
        setTimeout(() => {
            toast.classList.add('visible');
        }, 100);

        // Hide after duration
        const duration = parseInt(toast.getAttribute('data-duration') || '', 10) || 5000;
        setTimeout(() => {
            toast.classList.remove('visible');
            setTimeout(() => toast.remove(), 300);
        }, duration);
    });
}

window.showFrontendErrorToast = function (message) {
    const toast = document.createElement('div');
    toast.className = 'notification-toast error-toast';
    toast.setAttribute('data-duration', '7000');

    const title = document.createElement('h4');
    title.textContent = window.krtToastI18n.errorTitle;

    const p = document.createElement('p');
    p.textContent = message;

    toast.appendChild(title);
    toast.appendChild(p);
    document.body.appendChild(toast);

    initToasts();
};

window.showFrontendSuccessToast = function (message) {
    const toast = document.createElement('div');
    toast.className = 'notification-toast';
    toast.setAttribute('data-duration', '5000');

    const title = document.createElement('h4');
    title.textContent = window.krtToastI18n.successTitle;

    const p = document.createElement('p');
    p.textContent = message;

    toast.appendChild(title);
    toast.appendChild(p);
    document.body.appendChild(toast);

    initToasts();
};

document.addEventListener('DOMContentLoaded', initToasts);

/*
 * KRT-styled confirmation modal (replaces native window.confirm()).
 * Returns a Promise<boolean> that resolves to true if the user
 * confirmed, false otherwise. Safe to call when the optional KRT
 * confirm fragment is not rendered on the page: falls back to a
 * minimal on-the-fly modal so no native confirm() is required.
 */
window.showKrtConfirm = function (title, message, confirmLabel, cancelLabel) {
    return new Promise((resolve) => {
        const overlay = document.createElement('div');
        overlay.className = 'krt-confirm-overlay';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');

        const dialog = document.createElement('div');
        dialog.className = 'krt-confirm-dialog';

        const h = document.createElement('h4');
        h.className = 'krt-confirm-title';
        h.textContent = title || window.krtToastI18n.confirmTitle;

        const p = document.createElement('p');
        p.className = 'krt-confirm-message';
        p.textContent = message || '';

        const actions = document.createElement('div');
        actions.className = 'krt-confirm-actions';

        const btnCancel = document.createElement('button');
        btnCancel.type = 'button';
        btnCancel.className = 'btn btn-secondary krt-confirm-cancel';
        btnCancel.textContent = cancelLabel || window.krtToastI18n.cancel;

        const btnOk = document.createElement('button');
        btnOk.type = 'button';
        btnOk.className = 'btn btn-danger krt-confirm-ok';
        btnOk.textContent = confirmLabel || window.krtToastI18n.confirm;

        function close(result) {
            document.removeEventListener('keydown', onKey);
            overlay.remove();
            resolve(result);
        }
        function onKey(e) {
            if (e.key === 'Escape') close(false);
            else if (e.key === 'Enter') close(true);
        }

        btnCancel.addEventListener('click', () => close(false));
        btnOk.addEventListener('click', () => close(true));
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) close(false);
        });
        document.addEventListener('keydown', onKey);

        actions.appendChild(btnCancel);
        actions.appendChild(btnOk);
        dialog.appendChild(h);
        dialog.appendChild(p);
        dialog.appendChild(actions);
        overlay.appendChild(dialog);
        document.body.appendChild(overlay);

        setTimeout(() => btnOk.focus(), 50);
    });
};
