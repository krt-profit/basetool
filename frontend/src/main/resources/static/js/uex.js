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

/* global MSG_SAVED, MSG_ERROR */

document.addEventListener('DOMContentLoaded', function () {
    function patchOverrideGroup(form, action) {
        const group = form.parentElement;
        if (group) {
            group.querySelectorAll('form').forEach(function (f) {
                const actionInput = f.querySelector('input[name="action"]');
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

    function submitOverride(form) {
        const actionInput = form.querySelector('input[name="action"]');
        const action = actionInput ? actionInput.value : '';
        window.krtFetch.write({
            method: 'POST',
            url: form.getAttribute('action') + '?action=' + encodeURIComponent(action),
            successMessage: MSG_SAVED,
            errorMessage: MSG_ERROR,
            onSuccess() {
                patchOverrideGroup(form, action);
            },
        });
    }

    function submitVisibility(form) {
        const btn = form.querySelector('button');
        const hiddenInput = form.querySelector('input[name="hidden"]');
        window.krtFetch.write({
            method: 'POST',
            url: form.getAttribute('action'),
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

    document
        .querySelectorAll('form[action*="/loading-dock"], form[action*="/auto-load"]')
        .forEach(function (form) {
            form.addEventListener('click', function (ev) {
                ev.stopPropagation();
            });
            form.addEventListener('submit', function (ev) {
                ev.preventDefault();
                ev.stopPropagation();
                if (!window.krtFetch) {
                    form.submit();
                    return;
                }
                submitOverride(form);
            });
        });
    document.querySelectorAll('form[action*="/toggle-visibility"]').forEach(function (form) {
        form.addEventListener('click', function (ev) {
            ev.stopPropagation();
        });
        form.addEventListener('submit', function (ev) {
            ev.preventDefault();
            ev.stopPropagation();
            if (!window.krtFetch) {
                form.submit();
                return;
            }
            submitVisibility(form);
        });
    });

    document.querySelectorAll('.local-datetime-display').forEach(function (el) {
        const utcMsStr = el.getAttribute('data-utc');
        if (!utcMsStr) return;
        const date = new Date(parseInt(utcMsStr, 10));
        if (isNaN(date.getTime())) return;
        const day = String(date.getDate()).padStart(2, '0');
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const year = String(date.getFullYear()).slice(-2);
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        el.textContent = day + '.' + month + '.' + year + ' ' + hours + ':' + minutes;
    });

    const filterInput = document.getElementById('filterUex');
    const systems = document.querySelectorAll('details.uex-system');
    if (filterInput) {
        filterInput.addEventListener('input', function () {
            const q = (filterInput.value || '').trim().toLowerCase();
            systems.forEach(function (sysEl) {
                const sysName = (sysEl.getAttribute('data-system-name') || '').toLowerCase();
                let anyMatch = false;
                const nestedNames = sysEl.querySelectorAll('[data-name]');
                const systemSelfMatches = q !== '' && sysName.indexOf(q) !== -1;
                nestedNames.forEach(function (el) {
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
