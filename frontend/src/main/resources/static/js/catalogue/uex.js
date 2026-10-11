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

// @ts-check

/* global MSG_SAVED, MSG_ERROR */

document.addEventListener('DOMContentLoaded', () => {
    /**
     * Marks the active button of an override group and updates the mismatch flag of the chip.
     *
     * @param {HTMLFormElement} form the override form that was just saved
     * @param {string} action the saved override: 'yes', 'no' or the default
     */
    function patchOverrideGroup(form, action) {
        const group = form.parentElement;
        if (group) {
            group.querySelectorAll('form').forEach((f) => {
                const actionInput = /** @type {HTMLInputElement | null} */ (
                    f.querySelector('input[name="action"]')
                );
                const btn = f.querySelector('button');
                if (!actionInput || !btn) {
                    return;
                }
                const isActive = actionInput.value === action;
                btn.classList.toggle('btn-active', isActive);
                btn.classList.toggle('btn-secondary', !isActive);
            });
        }
        const td = form.closest('td');
        const chip = td ? td.querySelector('.uex-source-chip') : null;
        if (chip) {
            const raw = chip.getAttribute('data-uex-value');
            if (raw !== 'true' && raw !== 'false') {
                chip.classList.remove('mismatch');
            } else {
                const uexVal = raw === 'true';
                const effective = action === 'yes' ? true : action === 'no' ? false : uexVal;
                chip.classList.toggle('mismatch', uexVal !== effective);
            }
        }
    }

    /**
     * Saves one override choice and patches the group on success.
     *
     * @param {HTMLFormElement} form the override form carrying the chosen action
     */
    function submitOverride(form) {
        const actionInput = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="action"]')
        );
        const action = actionInput ? actionInput.value : '';
        window.krtFetch.write({
            method: 'POST',
            url: `${form.getAttribute('action')}?action=${encodeURIComponent(action)}`,
            successMessage: MSG_SAVED,
            errorMessage: MSG_ERROR,
            onSuccess() {
                patchOverrideGroup(form, action);
            },
        });
    }

    /**
     * Toggles the visibility flag through the form's endpoint and flips the button on success.
     *
     * @param {HTMLFormElement} form the visibility form
     */
    function submitVisibility(form) {
        const btn = form.querySelector('button');
        const hiddenInput = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="hidden"]')
        );
        window.krtFetch.write({
            method: 'POST',
            url: /** @type {string} */ (form.getAttribute('action')),
            successMessage: MSG_SAVED,
            errorMessage: MSG_ERROR,
            onSuccess() {
                if (!btn) {
                    return;
                }
                const currentlyHidden = !btn.classList.contains('btn-secondary');
                const newHidden = !currentlyHidden;
                btn.classList.toggle('btn-secondary', !newHidden);
                btn.textContent = newHidden
                    ? btn.getAttribute('data-label-show')
                    : btn.getAttribute('data-label-hide');
                if (hiddenInput) {
                    hiddenInput.value = String(!newHidden);
                }
            },
        });
    }

    /** @type {NodeListOf<HTMLFormElement>} */ (
        document.querySelectorAll('form[action*="/loading-dock"], form[action*="/auto-load"]')
    ).forEach((form) => {
        form.addEventListener('click', (ev) => {
            ev.stopPropagation();
        });
        form.addEventListener('submit', (ev) => {
            ev.preventDefault();
            ev.stopPropagation();
            if (!window.krtFetch) {
                form.submit();
                return;
            }
            submitOverride(form);
        });
    });
    /** @type {NodeListOf<HTMLFormElement>} */ (
        document.querySelectorAll('form[action*="/toggle-visibility"]')
    ).forEach((form) => {
        form.addEventListener('click', (ev) => {
            ev.stopPropagation();
        });
        form.addEventListener('submit', (ev) => {
            ev.preventDefault();
            ev.stopPropagation();
            if (!window.krtFetch) {
                form.submit();
                return;
            }
            submitVisibility(form);
        });
    });

    document.querySelectorAll('.local-datetime-display').forEach((el) => {
        const utcMsStr = el.getAttribute('data-utc');
        if (!utcMsStr) return;
        const date = new Date(parseInt(utcMsStr, 10));
        if (isNaN(date.getTime())) return;
        const day = String(date.getDate()).padStart(2, '0');
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const year = String(date.getFullYear()).slice(-2);
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        el.textContent = `${day}.${month}.${year} ${hours}:${minutes}`;
    });

    const filterInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('filterUex')
    );
    const systems = /** @type {NodeListOf<HTMLDetailsElement>} */ (
        document.querySelectorAll('details.uex-system')
    );
    if (filterInput) {
        filterInput.addEventListener('input', () => {
            const q = (filterInput.value || '').trim().toLowerCase();
            systems.forEach((sysEl) => {
                const sysName = (sysEl.getAttribute('data-system-name') || '').toLowerCase();
                let anyMatch = false;
                const nestedNames = /** @type {NodeListOf<HTMLElement>} */ (
                    sysEl.querySelectorAll('[data-name]')
                );
                const systemSelfMatches = q !== '' && sysName.indexOf(q) !== -1;
                nestedNames.forEach((el) => {
                    const n = (el.getAttribute('data-name') || '').toLowerCase();
                    const match = q === '' || n.indexOf(q) !== -1 || systemSelfMatches;
                    el.style.display = match ? '' : 'none';
                    if (match) anyMatch = true;
                });
                if (q === '') {
                    sysEl.style.display = '';
                } else if (systemSelfMatches || anyMatch) {
                    sysEl.style.display = '';
                    sysEl.open = true;
                } else {
                    sysEl.style.display = 'none';
                }
            });
        });
    }
});
