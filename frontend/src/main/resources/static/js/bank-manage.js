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
     * Formats a whole aUEC amount with German thousands separators.
     *
     * @param {number} value the amount
     * @returns {string} the formatted amount
     */
    function format(value) {
        return Math.round(value).toLocaleString('de-DE');
    }

    /**
     * Reads one threshold field as a non-negative whole number.
     *
     * @param {HTMLFormElement} form the KRT tier form
     * @param {string} tier `t1` or `t2`
     * @returns {number | null} the amount, or null when the field is empty or invalid
     */
    function valueOf(form, tier) {
        const input = /** @type {HTMLInputElement | null} */ (
            form.querySelector(`[data-tier-input="${tier}"]`)
        );
        if (!input || input.value.trim() === '') return null;
        const n = Number(input.value);
        return Number.isFinite(n) && n >= 0 ? n : null;
    }

    /**
     * Sets the text of the first element matching a selector inside the form.
     *
     * @param {HTMLFormElement} form the KRT tier form
     * @param {string} selector the element selector
     * @param {string} text the new text
     */
    function setText(form, selector, text) {
        const el = form.querySelector(selector);
        if (el) el.textContent = text;
    }

    /**
     * Re-renders the tier bar, its scale and the example from the two threshold fields, so the
     * bar shows what saving would store (REQ-BANK-047).
     *
     * @param {HTMLFormElement} form the KRT tier form
     */
    function render(form) {
        const t1 = valueOf(form, 't1');
        const t2 = valueOf(form, 't2');
        const upTo = form.getAttribute('data-label-up-to') || '{0}';
        const none = form.getAttribute('data-label-none') || '';
        const above = form.getAttribute('data-label-above') || '';
        const noOl = form.getAttribute('data-label-no-ol') || '';
        setText(
            form,
            '[data-tier-range="employee"]',
            t1 === null ? none : upTo.replace('{0}', format(t1)),
        );
        setText(
            form,
            '[data-tier-range="management"]',
            t2 === null ? above : upTo.replace('{0}', format(t2)),
        );
        setText(form, '[data-tier-range="ol"]', t2 === null ? noOl : above);
        setText(form, '[data-tier-mark="t1"]', format(t1 === null ? 0 : t1));
        setText(form, '[data-tier-mark="t2"]', t2 === null ? '∞' : format(t2));
        const example = form.querySelector('[data-testid="bank-krt-example"]');
        if (!example) return;
        const template = example.getAttribute('data-template') || '';
        const templateOl = example.getAttribute('data-template-ol') || '';
        setText(form, '[data-tier-example]', template.replace('{0}', format(t1 === null ? 0 : t1)));
        setText(
            form,
            '[data-tier-example-ol]',
            t2 === null ? '' : templateOl.replace('{0}', format(t2)),
        );
    }

    document.addEventListener('input', (event) => {
        const target = event.target;
        if (!(target instanceof HTMLInputElement) || !target.hasAttribute('data-tier-input'))
            return;
        const form = target.closest('form[data-bank-tiers]');
        if (form instanceof HTMLFormElement) render(form);
    });

    /** Binds the manage view's filters and pager to an in-place fragment swap. */
    function bindBankManageSwaps() {
        if (window.krtFetch) {
            window.krtFetch.bindSwap({
                container: '#bank-manage-results',
                fragmentValue: 'manageBody',
                history: true,
            });
        }
    }
    document.addEventListener('DOMContentLoaded', bindBankManageSwaps);
    document.addEventListener('krt:swapped', bindBankManageSwaps);
})();
