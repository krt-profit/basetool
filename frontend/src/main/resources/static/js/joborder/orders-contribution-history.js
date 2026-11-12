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

/** Returns a required input or select from the retained correction form.
 * @param {HTMLFormElement} form the retained correction form
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

/** Builds the provision's authorized frontend endpoint prefix.
 * @param {HTMLFormElement} form the editor
 * @returns {string} its provision endpoint prefix
 */
function provisionUrl(form) {
    return `/orders/${encodeURIComponent(form.dataset.orderId || '')}/claims/provisions/${encodeURIComponent(form.dataset.creditId || '')}`;
}

/** Shows a localized result in the retained correction form.
 * @param {HTMLFormElement} form the retained editor
 * @param {string} text localized result or error
 */
function message(form, text) {
    const status = form.querySelector('[data-correction-message]');
    if (status) status.textContent = text;
}

/** Loads an authorized same-origin correction fragment.
 * @param {string} url the authorized server fragment
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

/** Activates only the controls belonging to the selected correction kind.
 * @param {HTMLFormElement} form the correction editor
 */
function validateControls(form) {
    const kind = control(form, '[data-correction-kind]').value;
    const quantity = kind === 'QUANTITY';
    const quality = kind === 'QUALITY';
    const qualityFields = form.querySelector('[data-correction-quality]');
    const qualityStock = form.querySelector('[data-correction-quality-stock]');
    const suppliedQuality = form.querySelector('[data-correction-supplied-quality]');
    if (qualityFields instanceof HTMLElement) qualityFields.hidden = !quality;
    if (qualityStock instanceof HTMLElement) {
        qualityStock.hidden = !quality;
        qualityStock.querySelectorAll('[data-quality-stock-row]').forEach((row) => {
            if (row instanceof HTMLInputElement) row.disabled = !quality;
        });
    }
    if (suppliedQuality instanceof HTMLInputElement) {
        suppliedQuality.required = quality;
        suppliedQuality.disabled = !quality;
    }
    const attribution = form.querySelector('[data-correction-attribution]');
    const stock = form.querySelector('[data-correction-stock]');
    if (attribution instanceof HTMLElement) attribution.hidden = quantity;
    if (stock instanceof HTMLElement) {
        stock.hidden = !quantity;
        stock.querySelectorAll('input, select').forEach((input) => {
            if (input instanceof HTMLInputElement || input instanceof HTMLSelectElement)
                input.disabled = !quantity;
        });
    }
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

/** Refreshes quantity choices without silently replacing a retained selection.
 * @param {HTMLFormElement} form the retained form
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

/** Loads current attribution choices while preserving deliberate edits.
 * @param {HTMLFormElement} form the retained correction editor
 * @param {boolean} keepCurrent whether to offer the existing attribution as the initial choice
 * @returns {Promise<boolean>} whether choices for the current declared quality were applied
 */
async function loadAttributionOptions(form, keepCurrent) {
    const target = control(form, '[data-correction-target]');
    if (!(target instanceof HTMLSelectElement)) return false;
    const kind = control(form, '[data-correction-kind]').value;
    const qualityInput = form.querySelector('[data-correction-supplied-quality]');
    const quality =
        kind === 'QUALITY' && qualityInput instanceof HTMLInputElement
            ? qualityInput.valueAsNumber
            : NaN;
    if (
        kind === 'QUALITY' &&
        (!(qualityInput instanceof HTMLInputElement) ||
            !Number.isFinite(quality) ||
            !qualityInput.checkValidity())
    )
        return false;
    const observedTarget = target.value;
    const value = keepCurrent ? form.dataset.currentClaimId || '' : observedTarget;
    const label =
        (target.value && target.selectedOptions[0]?.textContent) || form.dataset.stale || '';
    const generation = String(Number(form.dataset.optionsGeneration || '0') + 1);
    form.dataset.optionsGeneration = generation;
    form.dataset.attributionLoading = 'true';
    const current = () =>
        form.isConnected &&
        form.dataset.optionsGeneration === generation &&
        control(form, '[data-correction-kind]').value === kind &&
        (kind !== 'QUALITY' ||
            (qualityInput instanceof HTMLInputElement && qualityInput.valueAsNumber === quality));
    try {
        const query =
            kind === 'QUALITY' ? `?suppliedQuality=${encodeURIComponent(String(quality))}` : '';
        const html = await fragment(
            `${provisionUrl(form)}/attribution-options${query}`,
            form.dataset.error || '',
        );
        if (!current()) return false;
        const document = krtFetch.parseTrustedDocument(html);
        const replacement = document.querySelector('[data-correction-target]');
        if (!(replacement instanceof HTMLSelectElement)) throw new Error(form.dataset.error || '');
        const retained = target.value !== observedTarget ? target.value : value;
        const retainedLabel = (target.value && target.selectedOptions[0]?.textContent) || label;
        target.replaceChildren(...Array.from(replacement.options));
        target.value = retained;
        if (retained && target.value !== retained) {
            const stale = new Option(retainedLabel, retained, true, true);
            stale.disabled = true;
            target.appendChild(stale);
            target.setCustomValidity(form.dataset.stale || form.dataset.error || '');
        } else target.setCustomValidity('');
        if (keepCurrent) {
            if (!form.dataset.amountDirty && retained === value)
                control(form, '[data-correction-amount]').value = String(
                    Number(form.dataset.creditAmount || '0'),
                );
            form.dataset.qualityTargetInitialized = 'true';
        }
        validateControls(form);
        return true;
    } catch (error) {
        if (!current()) return false;
        form.dataset.unavailable = 'true';
        message(form, error instanceof Error ? error.message : form.dataset.error || '');
        return false;
    } finally {
        if (current()) delete form.dataset.attributionLoading;
    }
}
/** Lists the explicitly selected current stock portions.
 * @param {HTMLFormElement} form the correction editor
 * @returns {HTMLInputElement[]} its explicitly selected current stock portions
 */
function selectedQualityRows(form) {
    return Array.from(form.querySelectorAll('[data-quality-stock-row]:checked')).filter(
        (row) => row instanceof HTMLInputElement,
    );
}

/** Loads authorized current stock choices when requested in the editor.
 * @param {HTMLFormElement} form the retained editor
 * @param {boolean} refresh whether to reload displayed stock versions
 * @returns {Promise<boolean>} whether current authorized stock choices were applied
 */
async function loadQualityOptions(form, refresh = false) {
    const options = form.querySelector('[data-correction-quality-stock-options]');
    if (
        !(options instanceof HTMLElement) ||
        (options.dataset.loaded === 'true' && !refresh) ||
        form.dataset.qualityStockLoading ||
        control(form, '[data-correction-kind]').value !== 'QUALITY'
    )
        return false;
    const current = () =>
        form.isConnected && control(form, '[data-correction-kind]').value === 'QUALITY';
    form.dataset.qualityStockLoading = 'true';
    try {
        const html = await fragment(
            `${provisionUrl(form)}/quality-options`,
            form.dataset.error || '',
        );
        if (!current()) return false;
        const retained = selectedQualityRows(form).map((row) => ({
            row,
            panel: row.closest('.list-panel') || row,
        }));
        krtFetch.setTrustedHtml(options, html);
        const currentRows = Array.from(options.querySelectorAll('[data-quality-stock-row]')).filter(
            (row) => row instanceof HTMLInputElement,
        );
        for (const { row, panel } of retained) {
            const current = currentRows.find((candidate) => candidate.value === row.value);
            if (current) current.checked = true;
            else {
                row.dataset.unavailable = 'true';
                row.setCustomValidity(form.dataset.stale || form.dataset.error || '');
                options.appendChild(panel);
            }
        }
        options.dataset.loaded = 'true';
        validateControls(form);
        return true;
    } catch (error) {
        if (!current()) return false;
        if (refresh) form.dataset.unavailable = 'true';
        message(form, error instanceof Error ? error.message : form.dataset.error || '');
        return false;
    } finally {
        delete form.dataset.qualityStockLoading;
    }
}

/** Refreshes required correction context while retaining deliberate edits.
 * @param {HTMLFormElement} form the dirty form whose values must survive a refresh
 * @returns {Promise<void>} settles after required current context reads
 */
async function refreshCorrection(form) {
    const kind = control(form, '[data-correction-kind]').value;
    const qualityInput = form.querySelector('[data-correction-supplied-quality]');
    const quality = qualityInput instanceof HTMLInputElement ? qualityInput.valueAsNumber : NaN;
    let generation = form.dataset.optionsGeneration;
    const current = () =>
        form.isConnected &&
        form.dataset.optionsGeneration === generation &&
        control(form, '[data-correction-kind]').value === kind &&
        (kind !== 'QUALITY' ||
            (qualityInput instanceof HTMLInputElement && qualityInput.valueAsNumber === quality));
    form.dataset.refreshing = 'true';
    form.dataset.unavailable = 'true';
    try {
        const html = await fragment(`${provisionUrl(form)}/editor`, form.dataset.error || '');
        if (!current()) return;
        const document = krtFetch.parseTrustedDocument(html);
        const fresh = document.querySelector('form');
        if (!fresh) throw new Error(form.dataset.error || '');
        const target = control(form, '[data-correction-target]');
        const value = target.value;
        const label =
            target instanceof HTMLSelectElement ? target.selectedOptions[0]?.textContent : '';
        const replacement = fresh.querySelector('[data-correction-target]');
        if (!(target instanceof HTMLSelectElement) || !(replacement instanceof HTMLSelectElement))
            throw new Error(form.dataset.error || '');
        target.replaceChildren(...Array.from(replacement.options));
        target.value = value;
        if (value && target.value !== value) {
            const stale = new Option(label || value, value, true, true);
            stale.disabled = true;
            target.appendChild(stale);
            target.setCustomValidity(form.dataset.error || '');
        } else target.setCustomValidity('');
        form.dataset.creditVersion = fresh.dataset.creditVersion;
        form.dataset.currentClaimId = fresh.dataset.currentClaimId;
        form.dataset.claimVersion = fresh.dataset.claimVersion;
        if (fresh.dataset.quantityAmount === undefined) delete form.dataset.quantityAmount;
        else form.dataset.quantityAmount = fresh.dataset.quantityAmount;
        form.dataset.creditAmount = fresh.dataset.creditAmount;
        if (kind === 'QUANTITY') await loadQuantityOptions(form);
        else if (kind === 'QUALITY') {
            const attribution = loadAttributionOptions(form, false);
            generation = form.dataset.optionsGeneration;
            if (!(await attribution) || !current()) return;
            const details = form.querySelector('[data-correction-quality-stock]');
            if (
                ((details instanceof HTMLDetailsElement && details.open) ||
                    selectedQualityRows(form).length) &&
                !(await loadQualityOptions(form, true))
            )
                return;
        }
        if (!current()) return;
        validateControls(form);
        delete form.dataset.unavailable;
        message(form, form.dataset.conflict || '');
    } catch (error) {
        if (!current()) return;
        form.dataset.unavailable = 'true';
        message(form, error instanceof Error ? error.message : form.dataset.error || '');
    } finally {
        delete form.dataset.refreshing;
    }
}

/** Submits a deliberately checked correction through the shared order writer.
 * @param {HTMLFormElement} form the deliberately submitted correction
 * @returns {Promise<void>} settles after the public write
 */
async function submitCorrection(form) {
    if (
        form.dataset.refreshing ||
        form.dataset.attributionLoading ||
        form.dataset.qualityStockLoading ||
        !joborderHooks.writeOrder
    )
        return;
    if (form.dataset.unavailable) {
        await refreshCorrection(form);
        return;
    }
    const kind = control(form, '[data-correction-kind]').value;
    const qualityInput = form.querySelector('[data-correction-supplied-quality]');
    const target = control(form, '[data-correction-target]');
    const amountInput = control(form, '[data-correction-amount]');
    const amount = kind !== 'QUANTITY' && !target.value ? 0 : krtScuInput.parse(amountInput.value);
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
    const qualityRows = kind === 'QUALITY' ? selectedQualityRows(form) : [];
    const stockVersions =
        kind === 'QUANTITY'
            ? Array.from(form.querySelectorAll('[data-stock-version]')).map((row) => ({
                  id: row.getAttribute('data-id'),
                  version: Number(row.getAttribute('data-version')),
              }))
            : qualityRows.map((row) => ({
                  id: row.value,
                  version: Number(row.dataset.version),
              }));
    await joborderHooks.writeOrder({
        method: 'POST',
        url: `${provisionUrl(form)}/correction`,
        payload: {
            kind,
            version: Number(form.dataset.creditVersion),
            claimVersion: Number(form.dataset.claimVersion),
            amount,
            targetClaimId: kind !== 'QUANTITY' ? target.value || null : null,
            targetClaimVersion:
                kind !== 'QUANTITY' && target.value ? Number(selected?.dataset.version) : null,
            stockVersions,
            suppliedQuality:
                kind === 'QUALITY' && qualityInput instanceof HTMLInputElement
                    ? qualityInput.valueAsNumber
                    : null,
            qualityStockRowIds: qualityRows.map((row) => row.value),
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

/** Loads retained contribution histories into their authorized slots.
 * @param {ParentNode} root the page or refreshed order section
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
    document.addEventListener(
        'toggle',
        (event) => {
            const details = event.target;
            if (
                !(details instanceof HTMLDetailsElement) ||
                !details.matches('[data-correction-quality-stock]') ||
                !details.open
            )
                return;
            const form = details.closest('form[data-contribution-correction]');
            if (form instanceof HTMLFormElement) loadQualityOptions(form);
        },
        true,
    );
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
    document.addEventListener('input', async (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const form = target?.closest('form[data-contribution-correction]');
        if (!(form instanceof HTMLFormElement)) return;
        form.dataset.dirty = 'true';
        if (target?.matches('[data-correction-amount]')) form.dataset.amountDirty = 'true';
        validateControls(form);
        if (target?.matches('[data-correction-supplied-quality]')) {
            await loadAttributionOptions(form, !form.dataset.qualityTargetInitialized);
        }
    });
    document.addEventListener('change', async (event) => {
        const target = event.target instanceof Element ? event.target : null;
        const form = target?.closest('form[data-contribution-correction]');
        if (!(form instanceof HTMLFormElement)) return;
        form.dataset.dirty = 'true';
        const amount = control(form, '[data-correction-amount]');
        if (target?.matches('[data-correction-kind]')) {
            const kind = control(form, '[data-correction-kind]').value;
            const quantity = kind === 'QUANTITY';
            form.dataset.optionsGeneration = String(
                Number(form.dataset.optionsGeneration || '0') + 1,
            );
            delete form.dataset.attributionLoading;
            delete form.dataset.qualityTargetInitialized;
            amount.value = quantity ? form.dataset.quantityAmount || '0' : '0';
            delete form.dataset.amountDirty;
            validateControls(form);
            if (kind === 'QUALITY') {
                await loadAttributionOptions(form, true);
                const details = form.querySelector('[data-correction-quality-stock]');
                if (details instanceof HTMLDetailsElement && details.open)
                    await loadQualityOptions(form);
            } else if (quantity) {
                try {
                    await loadQuantityOptions(form);
                } catch (error) {
                    form.dataset.unavailable = 'true';
                    message(
                        form,
                        error instanceof Error ? error.message : form.dataset.error || '',
                    );
                }
            } else await loadAttributionOptions(form, false);
        }
        if (target instanceof HTMLSelectElement && target.matches('[data-correction-stock-target]'))
            target.setCustomValidity('');
        if (target instanceof HTMLInputElement && target.matches('[data-quality-stock-row]'))
            target.setCustomValidity(
                target.checked && target.dataset.unavailable
                    ? form.dataset.stale || form.dataset.error || ''
                    : '',
            );
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
