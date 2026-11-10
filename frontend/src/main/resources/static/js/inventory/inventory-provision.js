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

/** @type {WeakSet<Element>} */
const bound = new WeakSet();
/** @type {WeakMap<Element, number>} */
const generations = new WeakMap();

/** @param {HTMLElement} picker the optional provision picker
 */
function syncSelection(picker) {
    const select = picker.querySelector('select');
    const claimId = picker.querySelector('[data-provision-claim-id]');
    const version = picker.querySelector('[data-provision-claim-version]');
    if (!select || !(claimId instanceof HTMLInputElement) || !(version instanceof HTMLInputElement))
        return;
    claimId.value = select.value;
    version.value = select.value ? select.selectedOptions[0]?.dataset.provisionVersion || '' : '';
}

/** @param {Element} root the ordinary allocation row or popover
 * @returns {ApiDto<'MaterialProvisionSelection'> | null} the explicit observed choice
 */
export function selectedProvision(root) {
    const picker = root.querySelector('[data-provision-picker]');
    if (!(picker instanceof HTMLElement) || picker.hidden) return null;
    const select = picker.querySelector('select');
    if (!select?.value || !select.checkValidity()) return null;
    return {
        claimId: select.value,
        claimVersion: Number(select.selectedOptions[0]?.dataset.provisionVersion),
    };
}

/** @param {Element} root the allocation row or popover
 * @returns {boolean} whether a selected contribution can be deliberately submitted
 */
export function provisionReady(root) {
    const picker = root.querySelector('[data-provision-picker]');
    if (!(picker instanceof HTMLElement) || picker.hidden) return true;
    const select = picker.querySelector('select');
    if (!select) return true;
    if (picker.dataset.loading === 'true' && select.value) return false;
    return select.reportValidity();
}

/**
 * Refreshes only authoritative choices and their observed versions while retaining dirty values.
 * @param {Element} root the ordinary allocation row or popover
 * @param {string} orderId the selected order
 * @param {string} materialId physical material identity, absent for a game item
 * @param {string} quality physical material quality
 * @param {boolean} [refreshObserved] permits a deliberate post-conflict version refresh
 * @returns {Promise<void>} settles after this picker has current choices
 */
export async function refreshProvisionPicker(
    root,
    orderId,
    materialId,
    quality,
    refreshObserved = false,
) {
    const picker = root.querySelector('[data-provision-picker]');
    if (!(picker instanceof HTMLElement)) return;
    const select = picker.querySelector('select');
    if (!select) return;
    const status = picker.querySelector('[data-provision-status]');
    if (!bound.has(picker)) {
        bound.add(picker);
        select.addEventListener('change', () => {
            select.setCustomValidity('');
            syncSelection(picker);
            if (status) status.textContent = '';
        });
    }
    const context = `${orderId}|${materialId}`;
    if (picker.dataset.context !== context) {
        select.value = '';
        while (select.options.length > 1) select.remove(1);
        select.setCustomValidity('');
        syncSelection(picker);
    }
    picker.dataset.context = context;
    picker.hidden = !orderId || !materialId || quality === '' || !Number.isFinite(Number(quality));
    if (picker.hidden) {
        select.value = '';
        syncSelection(picker);
        return;
    }
    let selected = select.value;
    const generation = (generations.get(picker) || 0) + 1;
    generations.set(picker, generation);
    picker.dataset.loading = 'true';
    try {
        const query = new URLSearchParams({ materialId, quality });
        const response = await krtFetch.get(
            `/orders/${encodeURIComponent(orderId)}/claims/provision-options?${query}`,
            { accept: 'text/html' },
        );
        if (!response?.ok) throw new Error(picker.dataset.error || '');
        const html = await response.text();
        if (generations.get(picker) !== generation) return;
        selected = select.value;
        const label = select.selectedOptions[0]?.textContent || '';
        const observedVersion = select.selectedOptions[0]?.dataset.provisionVersion || '';
        krtFetch.setTrustedHtml(select, html);
        select.value = selected;
        const changed =
            selected &&
            observedVersion &&
            select.selectedOptions[0]?.dataset.provisionVersion !== observedVersion &&
            !refreshObserved;
        if (selected && (select.value !== selected || changed)) {
            const stale = new Option(label, selected, true, true);
            stale.disabled = true;
            stale.dataset.provisionVersion = observedVersion;
            select.appendChild(stale);
            select.selectedIndex = select.options.length - 1;
            select.setCustomValidity(picker.dataset.stale || '');
            if (status) status.textContent = picker.dataset.stale || '';
        } else {
            select.setCustomValidity('');
            if (status) status.textContent = '';
        }
        syncSelection(picker);
    } catch (error) {
        if (generations.get(picker) !== generation) return;
        if (selected) select.setCustomValidity(picker.dataset.error || '');
        if (status)
            status.textContent =
                error instanceof Error ? error.message : picker.dataset.error || '';
    } finally {
        if (generations.get(picker) === generation) picker.dataset.loading = 'false';
    }
}
