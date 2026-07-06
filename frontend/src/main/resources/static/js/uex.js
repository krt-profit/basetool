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
 * UEX admin page module (/admin/uex), extracted verbatim from the former inline script of
 * admin/uex.html (ADR-0069, follow-up to #924).
 *
 * In-place (#582) override + visibility toggles: the three-state loading-dock / auto-load override
 * buttons and the terminal hidden toggle save via the krtFetch AJAX twins and patch their own state
 * (and the adjacent UEX-source-chip mismatch marker) client-side, so the page never reloads; the
 * classic POST->redirect forms stay the no-JS fallback. Also localises the last-sync timestamps and
 * drives the global system/location/terminal filter.
 *
 * The two Thymeleaf-interpolated toast strings (MSG_SAVED, MSG_ERROR) stay inline in the page
 * bootstrap this module reads.
 */

/* global MSG_SAVED, MSG_ERROR */

// In-place toggle messages (#582). The override / visibility forms now save via the AJAX
// twins, so the scroll + disclosure snapshot the old POST->redirect needed is gone with it.

document.addEventListener('DOMContentLoaded', function () {
    // ---- In-place override + visibility toggles (#582) ---------------
    // The three-state override buttons (loading-dock / auto-load) and the terminal hidden
    // toggle save via the AJAX twins and patch their own state client-side, so the page never
    // reloads. The new state is deterministic from the clicked action, so no backend body is
    // needed.

    // Patches a three-state override group: the button matching the clicked action becomes
    // active, the other two secondary. For terminals the adjacent UEX source chip's "mismatch"
    // marker is recomputed from the action and the rendered UEX value (data-uex-value): yes ->
    // effective true, no -> false, uex -> the UEX value (so no mismatch).
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
            onSuccess: function () {
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
            onSuccess: function () {
                if (!btn) {
                    return;
                }
                // btn-secondary is present only when the terminal is visible (hidden=false).
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

    // Selecting by action keeps the markup edit-free. stopPropagation on the click keeps a
    // button press inside a <summary> (city/station controls) from toggling the <details>.
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

    // ---- Last-sync timestamp localisation ---------------------------
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

    // ---- Global filter ---------------------------------------------
    // Matches against star-system names *and* any nested location/terminal
    // name. A system is hidden only if it contains zero matching children;
    // otherwise it is force-opened so the admin can immediately see what
    // matched. Per-parent details retain their own open state — we only
    // open them transiently to surface a match, and reset all overrides
    // once the filter is cleared.
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
