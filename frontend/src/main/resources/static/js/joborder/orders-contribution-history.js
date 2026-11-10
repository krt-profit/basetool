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
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtScuInput } from '/js/kernel/scu-decimal-input.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';
import { showFrontendErrorToast } from '/js/kernel/toast.js';

/** @param {HTMLFormElement} form the retained correction form
 * @param {string} selector the control selector
 * @returns {HTMLInputElement | HTMLSelectElement} the required control
 */
function control(form, selector) {
    const element = form.querySelector(selector);
    if (!(element instanceof HTMLInputElement || element instanceof HTMLSelectElement)) {
        throw new Error(`Missing correction control: ${selector}`);
    }
    return element;
}

/** @param {HTMLFormElement} form the editor
 * @returns {string} its provision endpoint prefix
 */
function provisionUrl(form) {
    return `/orders/${encodeURIComponent(form.dataset.orderId || '')}/claims/provisions/${encodeURIComponent(form.dataset.creditId || '')}`;
}

/** @param {HTMLFormElement} form the retained editor
 * @param {string} text localized result or error
 */
function message(form, text) {
    const status = form.querySelector('[data-correction-message]');
    if (status) status.textContent = text;
}

/** @param {string} url the authorized server fragment
 * @param {string} fallback localized failure
 * @returns {Promise<string>} its markup
 */
async function fragment(url, fallback) {
    const response = await krtFetch.get(url, { accept: 'text/html' });
    if (!response) throw new Error(fallback);
    if (!response.ok) {
        const problem = await response.json();
        throw new Error(problem.message || problem.detail || fallback);
    }
    return response.text();
}

/** @param {HTMLFormElement} form the correction editor
 */
function validateControls(form) {
    const quantity = control(form, '[data-correction-kind]').value === 'QUANTITY';
    const attribution = form.querySelector('[data-correction-attribution]');
    const stock = form.querySelector('[data-correction-stock]');
    if (attribution instanceof HTMLElement) attribution.hidden = quantity;
    if (stock instanceof HTMLElement) stock.hidden = !quantity;
    const amount = control(form, '[data-correction-amount]');
    const target = control(form, '[data-correction-target]');
    if (amount instanceof HTMLInputElement) {
        amount.readOnly = !quantity && !target.value;
        const chosen = target instanceof HTMLSelectElement ? target.selectedOptions[0] : null;
        if (!quantity && chosen?.dataset.max) amount.max = chosen.dataset.max;
        else amount.removeAttribute('max');
    }
    const stockTarget = form.querySelector('[data-correction-stock-target]');
    if (stockTarget instanceof HTMLSelectElement) {
        stockTarget.required =
            quantity && krtScuInput.parse(amount.value) > Number(form.dataset.quantityAmount);
    }
}

/** @param {HTMLFormElement} form the retained form
 * @returns {Promise<void>} resolves after current physical versions are loaded
 */
async function loadQuantityOptions(form) {
    const stock = form.querySelector('[data-correction-stock]');
    if (!(stock instanceof HTMLElement)) return;
    const previous = stock.querySelector('select');
    const selected = previous?.value || '';
    const previousLabel = previous?.selectedOptions[0]?.textContent || '';
    stock.dataset.loaded = 'false';
    const html = await fragment(`${provisionUrl(form)}/quantity-options`, form.dataset.error || '');
    krtFetch.setTrustedHtml(stock, html);
    const select = stock.querySelector('select');
    if (select) {
        select.value = selected;
        if (selected && select.value !== selected) {
            const stale = new Option(previousLabel, selected, true, true);
            stale.disabled = true;
            select.appendChild(stale);
            select.setCustomValidity(form.dataset.error || '');
        }
    }
    stock.dataset.loaded = 'true';
    validateControls(form);
}

/** @param {HTMLFormElement} form the dirty form whose values must survive a conflict
 * @returns {Promise<void>} resolves after a deliberate retry has current scoped versions
 */
async function refreshCorrection(form) {
    form.dataset.refreshing = 'true';
    try {
        const html = await fragment(`${provisionUrl(form)}/editor`, form.dataset.error || '');
        const document = krtFetch.parseTrustedDocument(html);
        const fresh = document.querySelector('form');
        if (!fresh) throw new Error(form.dataset.error || '');
        const target = control(form, '[data-correction-target]');
        const value = target.value;
        const label =
            target instanceof HTMLSelectElement ? target.selectedOptions[0]?.textContent : '';
        const replacement = fresh.querySelector('[data-correction-target]');
        if (!(target instanceof HTMLSelectElement) || !(replacement instanceof HTMLSelectElement))
            return;
        target.replaceChildren(...Array.from(replacement.options));
        target.value = value;
        if (value && target.value !== value) {
            const stale = new Option(label || value, value, true, true);
            stale.disabled = true;
            target.appendChild(stale);
            target.setCustomValidity(form.dataset.error || '');
        } else target.setCustomValidity('');
        form.dataset.creditVersion = fresh.dataset.creditVersion;
        form.dataset.claimVersion = fresh.dataset.claimVersion;
        if (fresh.dataset.quantityAmount === undefined) delete form.dataset.quantityAmount;
        else form.dataset.quantityAmount = fresh.dataset.quantityAmount;
        form.dataset.creditAmount = fresh.dataset.creditAmount;
        if (control(form, '[data-correction-kind]').value === 'QUANTITY')
            await loadQuantityOptions(form);
        validateControls(form);
        message(form, form.dataset.conflict || '');
    } catch (error) {
        form.dataset.unavailable = 'true';
        message(form, error instanceof Error ? error.message : form.dataset.error || '');
    } finally {
        delete form.dataset.refreshing;
    }
}

/** @param {HTMLFormElement} form the deliberately submitted correction
 * @returns {Promise<void>} settles after the public write
 */
async function submitCorrection(form) {
    if (form.dataset.refreshing || form.dataset.unavailable || !joborderHooks.writeOrder) return;
    const kind = control(form, '[data-correction-kind]').value;
    const target = control(form, '[data-correction-target]');
    const amountInput = control(form, '[data-correction-amount]');
    const amount =
        kind === 'ATTRIBUTION' && !target.value ? 0 : krtScuInput.parse(amountInput.value);
    if (
        !Number.isFinite(amount) ||
        amount < 0 ||
        (form.dataset.piece === 'true' && !Number.isInteger(amount))
    ) {
        amountInput.setCustomValidity(form.dataset.error || '');
    } else amountInput.setCustomValidity('');
    validateControls(form);
    if (!form.reportValidity()) return;
    const stock = form.querySelector('[data-correction-stock]');
    if (kind === 'QUANTITY' && (!(stock instanceof HTMLElement) || stock.dataset.loaded !== 'true'))
        return;
    const selected = target instanceof HTMLSelectElement ? target.selectedOptions[0] : null;
    const stockTarget = form.querySelector('[data-correction-stock-target]');
    const stockVersions =
        kind === 'QUANTITY'
            ? Array.from(form.querySelectorAll('[data-stock-version]')).map((row) => ({
                  id: row.getAttribute('data-id'),
                  version: Number(row.getAttribute('data-version')),
              }))
            : [];
    await joborderHooks.writeOrder({
        method: 'POST',
        url: `${provisionUrl(form)}/correction`,
        payload: {
            kind,
            version: Number(form.dataset.creditVersion),
            claimVersion: Number(form.dataset.claimVersion),
            amount,
            targetClaimId: kind === 'ATTRIBUTION' ? target.value || null : null,
            targetClaimVersion:
                kind === 'ATTRIBUTION' && target.value ? Number(selected?.dataset.version) : null,
            stockVersions,
            targetStockRowId:
                kind === 'QUANTITY' &&
                amount > Number(form.dataset.quantityAmount) &&
                stockTarget instanceof HTMLSelectElement
                    ? stockTarget.value || null
                    : null,
        },
        submitter: form.querySelector('button[type="submit"]'),
        successMessage: form.dataset.success,
        errorMessage: form.dataset.error,
        onConflict() {
            refreshCorrection(form);
            return true;
        },
        onError(_status, problem) {
            message(form, problem?.message || problem?.detail || form.dataset.error || '');
            return true;
        },
        onSuccess() {
            delete form.dataset.dirty;
            joborderHooks.refreshOrderSection?.(['materials', 'aggregated', 'kpi']);
            krtPageSections.publish('inventory', ['stock']);
            krtPageSections.publish('orders', ['demand']);
        },
    });
}

/** @param {ParentNode} root the page or refreshed order section
 */
function loadHistory(root) {
    root.querySelectorAll('[data-contribution-history-slot]').forEach((slot) => {
        if (!(slot instanceof HTMLElement) || slot.dataset.loaded) return;
        slot.dataset.loaded = 'true';
        krtFetch.swap({
            url: `/orders/${encodeURIComponent(slot.dataset.orderId || '')}/claims/history`,
            container: slot,
            history: false,
            preserveScroll: true,
            errorMessage: slot.dataset.error,
        });
    });
}

/** Installs history and correction controls without replacing dirty forms on peer updates. */
export function initContributionHistory() {
    loadHistory(document);
    document.addEventListener('krt:swapped', (event) => {
        const container = event.detail.container;
        if (container && !container.matches('[data-contribution-history-slot]'))
            loadHistory(container);
    });
    document.addEventListener('click', async (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const cancel = target?.closest('[data-correction-cancel]');
        if (cancel) {
            cancel.closest('[data-correction-editor]')?.replaceChildren();
            return;
        }
        const button = target?.closest('[data-open-contribution-correction]');
        if (!(button instanceof HTMLButtonElement)) return;
        const history = button.closest('[data-contribution-history]');
        const editor = button.parentElement?.querySelector('[data-correction-editor]');
        if (
            !(history instanceof HTMLElement) ||
            !(editor instanceof HTMLElement) ||
            editor.querySelector('form')
        )
            return;
        button.disabled = true;
        try {
            const html = await fragment(
                `/orders/${encodeURIComponent(history.dataset.orderId || '')}/claims/provisions/${encodeURIComponent(button.dataset.creditId || '')}/editor`,
                history.dataset.error || '',
            );
            krtFetch.setTrustedHtml(editor, html);
            editor.querySelector('select')?.focus();
        } catch (error) {
            showFrontendErrorToast(
                error instanceof Error ? error.message : history.dataset.error || '',
            );
        } finally {
            button.disabled = false;
        }
    });
    document.addEventListener('input', (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const form = target?.closest('form[data-contribution-correction]');
        if (!(form instanceof HTMLFormElement)) return;
        form.dataset.dirty = 'true';
        if (target?.matches('[data-correction-amount]')) form.dataset.amountDirty = 'true';
        validateControls(form);
    });
    document.addEventListener('change', async (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const form = target?.closest('form[data-contribution-correction]');
        if (!(form instanceof HTMLFormElement)) return;
        form.dataset.dirty = 'true';
        delete form.dataset.unavailable;
        const amount = control(form, '[data-correction-amount]');
        if (target?.matches('[data-correction-kind]')) {
            const quantity = control(form, '[data-correction-kind]').value === 'QUANTITY';
            amount.value = quantity ? form.dataset.quantityAmount || '0' : '0';
            delete form.dataset.amountDirty;
            if (quantity) {
                try {
                    await loadQuantityOptions(form);
                } catch (error) {
                    form.dataset.unavailable = 'true';
                    message(
                        form,
                        error instanceof Error ? error.message : form.dataset.error || '',
                    );
                }
            }
        }
        if (target instanceof HTMLSelectElement && target.matches('[data-correction-stock-target]'))
            target.setCustomValidity('');
        if (target instanceof HTMLSelectElement && target.matches('[data-correction-target]')) {
            target.setCustomValidity('');
            if (!target.value) amount.value = '0';
            else if (!form.dataset.amountDirty)
                amount.value = String(
                    Math.min(
                        Number(target.selectedOptions[0]?.dataset.max),
                        Number(form.dataset.creditAmount),
                    ),
                );
        }
        validateControls(form);
    });
    document.addEventListener('submit', (event) => {
        const form = event.target;
        if (!(form instanceof HTMLFormElement) || !form.matches('[data-contribution-correction]'))
            return;
        event.preventDefault();
        submitCorrection(form);
    });
}
