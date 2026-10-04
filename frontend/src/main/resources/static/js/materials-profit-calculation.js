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

    /** @typedef {ApiDto<'ProfitCalculationDto'>} ProfitRow */

    const PROFIT_FILTER_KEY = 'profit_calculation_filters';
    const config = document.getElementById('profitConfig');

    /**
     * A label from the page's config element.
     *
     * @param {string} name the attribute suffix after `data-label-`
     * @returns {string} the label, or an empty string
     */
    function label(name) {
        return (config && config.getAttribute(`data-label-${name}`)) || '';
    }

    const WHOLE = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 0 });
    const PRICE = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 2 });
    const PER_SCU = new Intl.NumberFormat('de-DE', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    });
    const PERCENT = new Intl.NumberFormat('de-DE', {
        minimumFractionDigits: 1,
        maximumFractionDigits: 1,
    });

    /**
     * Formats a number, or a dash when it is absent.
     *
     * @param {Intl.NumberFormat} format the format
     * @param {number | null | undefined} value the number
     * @returns {string} the text
     */
    function num(format, value) {
        return value == null || !isFinite(Number(value)) ? '–' : format.format(Number(value));
    }

    /** @returns {HTMLSelectElement | null} the ship select */
    function shipSelect() {
        return /** @type {HTMLSelectElement | null} */ (document.getElementById('shipSelect'));
    }

    /** @returns {HTMLInputElement[]} the star-system checkboxes */
    function systemChecks() {
        return /** @type {HTMLInputElement[]} */ (
            Array.prototype.slice.call(document.getElementsByClassName('sysCheck'))
        );
    }

    /**
     * Shows the result state below the table: a message, or nothing once rows are shown.
     *
     * @param {'idle' | 'loading' | 'ready' | 'empty' | 'error'} state the state
     * @param {string} [message] the message for every state but `ready`
     */
    function setState(state, message) {
        const results = document.getElementById('profitResults');
        const box = document.getElementById('profitState');
        const text = document.getElementById('profitStateText');
        if (results) results.setAttribute('data-state', state);
        if (box) {
            box.hidden = state === 'ready';
            box.classList.toggle('profit-state--error', state === 'error');
        }
        if (text) text.textContent = message || '';
    }

    /** Writes the systems summary into the dropdown button: "Alle", one name, or a list. */
    function updateSystemSummary() {
        const header = document.getElementById('systemHeader');
        const textEl = header ? header.querySelector('.selected-text') : null;
        const checks = systemChecks();
        const picked = checks.filter((c) => {
            return c.checked;
        });
        const allBox = /** @type {HTMLInputElement | null} */ (document.getElementById('sysAll'));
        if (allBox) allBox.checked = picked.length === checks.length;
        if (!textEl) return;
        textEl.textContent =
            picked.length === checks.length || picked.length === 0
                ? label('all')
                : picked
                      .map((c) => {
                          return c.value;
                      })
                      .join(', ');
    }

    /** Shows the Hull C chip when the chosen ship loads at loading docks only. */
    function updateHullChip() {
        const select = shipSelect();
        const chip = document.getElementById('profitHullC');
        if (!select || !chip) return;
        const option = select.selectedOptions[0];
        chip.hidden = !(option && option.getAttribute('data-hull-c') === 'true');
    }

    /**
     * One route line: the side label, the terminal with its location in brackets, and the price.
     *
     * @param {string} cls the side class
     * @param {string} side the side label
     * @param {string | null | undefined} terminal the terminal's name
     * @param {string | null | undefined} place the terminal's location
     * @param {number | null | undefined} price the price per SCU
     * @returns {string} the line's HTML
     */
    function routeLine(cls, side, terminal, place, price) {
        const placeHtml = place
            ? ` <span class="profit-route__place" data-testid="profit-route-place">(${escapeHtml(
                  place,
              )})</span>`
            : '';
        const terminalHtml = terminal
            ? `<span class="profit-route__terminal" data-testid="profit-route-terminal">${escapeHtml(
                  terminal,
              )}</span>${placeHtml} · `
            : '';
        return `<span class="profit-route__line"><span class="${cls}">${escapeHtml(side)}</span> ${
            terminalHtml
        }${escapeHtml(num(PRICE, price))}</span>`;
    }

    /**
     * Renders the result rows, highest full-load profit first and numbered.
     *
     * @param {ProfitRow[]} data the rows
     */
    function renderRows(data) {
        const body = document.getElementById('profitBody');
        if (!body) return;
        const rows = data.slice().sort((a, b) => {
            const pa = Number(a.maxProfitFullLoad);
            const pb = Number(b.maxProfitFullLoad);
            const diff = (isFinite(pb) ? pb : -Infinity) - (isFinite(pa) ? pa : -Infinity);
            return diff !== 0
                ? diff
                : String(a.materialName).localeCompare(String(b.materialName), undefined, {
                      sensitivity: 'base',
                  });
        });
        let html = '';
        rows.forEach((item, index) => {
            const rank = index + 1;
            html += `<tr class="profit-row${
                rank === 1 ? ' profit-row--top' : ''
            }" data-testid="profit-row"><td><a class="row-link" data-testid="row-link" href="/materials/${escapeAttr(
                encodeURIComponent(item.materialId || ''),
            )}"><span class="profit-rank">${escapeHtml(
                rank,
            )}</span><span class="cell-title">${escapeHtml(
                item.materialName,
            )}</span></a></td><td class="profit-route">${routeLine(
                'profit-route__buy',
                label('buy'),
                item.buyTerminalName,
                item.buyTerminalLocation,
                item.minBuyPrice,
            )}${routeLine(
                'profit-route__sell',
                label('sell'),
                item.sellTerminalName,
                item.sellTerminalLocation,
                item.maxSellPrice,
            )}</td><td class="num">${escapeHtml(
                num(PER_SCU, item.profitPerScu),
            )}</td><td class="num">${escapeHtml(
                num(PERCENT, item.marginPercent),
            )}\u00a0%</td><td class="num">${escapeHtml(
                num(WHOLE, item.fullLoadCost),
            )}</td><td class="num profit-max">${escapeHtml(
                num(WHOLE, item.maxProfitFullLoad),
            )}</td></tr>`;
        });
        window.krtFetch.setTrustedHtml(body, html);
    }

    let requestToken = 0;

    /** Fetches and renders the calculation for the chosen ship and systems. */
    async function updateProfitCalculation() {
        const select = shipSelect();
        const body = document.getElementById('profitBody');
        const shipId = select ? select.value : '';
        const token = ++requestToken;
        if (!shipId) {
            if (body) body.innerHTML = '';
            setState('idle', label('select-ship'));
            return;
        }
        setState('loading', label('loading'));
        const params = new URLSearchParams();
        params.append('shipId', shipId);
        systemChecks().forEach((c) => {
            if (c.checked) params.append('starSystemNames', c.value);
        });
        try {
            const response = await fetch(
                `/api/proxy/materials/profit-calculation?${params.toString()}`,
                { headers: { 'X-Requested-With': 'XMLHttpRequest' } },
            );
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            if (token !== requestToken) return;
            if (!Array.isArray(data) || data.length === 0) {
                if (body) body.innerHTML = '';
                setState('empty', label('no-data'));
                return;
            }
            renderRows(data);
            setState('ready');
        } catch (_error) {
            if (token !== requestToken) return;
            if (body) body.innerHTML = '';
            setState('error', label('fetch-error'));
        }
    }

    /**
     * The saved ship and systems (REQ-UI-017).
     *
     * @returns {any} the parsed object, or `null`
     */
    function readProfitFilterPref() {
        try {
            const raw = localStorage.getItem(PROFIT_FILTER_KEY);
            const parsed = raw === null ? null : JSON.parse(raw);
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch (_e) {
            return null;
        }
    }

    /** Saves the ship and the systems, all systems as `null`. */
    function persistProfitFilters() {
        const select = shipSelect();
        const checks = systemChecks();
        const picked = checks
            .filter((c) => {
                return c.checked;
            })
            .map((c) => {
                return c.value;
            });
        try {
            localStorage.setItem(
                PROFIT_FILTER_KEY,
                JSON.stringify({
                    shipId: select && select.value !== '' ? select.value : null,
                    systems: picked.length === 0 || picked.length === checks.length ? null : picked,
                }),
            );
        } catch (_e) {}
    }

    /** Restores the last chosen ship and systems; stale values fall back to the defaults. */
    function restoreProfitFilters() {
        const saved = readProfitFilterPref();
        if (!saved) return;
        const select = shipSelect();
        if (select && typeof saved.shipId === 'string' && saved.shipId !== '') {
            for (let i = 0; i < select.options.length; i++) {
                if (select.options[i].value === saved.shipId) {
                    select.value = saved.shipId;
                    break;
                }
            }
        }
        if (Array.isArray(saved.systems) && saved.systems.length > 0) {
            const checks = systemChecks();
            const any = checks.some((c) => {
                return saved.systems.indexOf(c.value) >= 0;
            });
            checks.forEach((c) => {
                c.checked = !any || saved.systems.indexOf(c.value) >= 0;
            });
        }
    }

    /** Applies a changed input: summary, chip, persistence and a new calculation. */
    function onInputChanged() {
        updateSystemSummary();
        updateHullChip();
        persistProfitFilters();
        updateProfitCalculation();
    }

    document.addEventListener('DOMContentLoaded', () => {
        restoreProfitFilters();
        updateSystemSummary();
        updateHullChip();
        updateProfitCalculation();
    });

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('change', 'profit-update', onInputChanged);
        window.krtEvents.on('change', 'profit-toggle-all', (el) => {
            const all = /** @type {HTMLInputElement} */ (el);
            systemChecks().forEach((c) => {
                c.checked = all.checked;
            });
            onInputChanged();
        });
        window.krtEvents.on('change', 'profit-update-state', onInputChanged);
    }
})();
