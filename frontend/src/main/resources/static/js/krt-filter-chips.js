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
     * One active filter, ready to render as a chip.
     *
     * @typedef {object} ActiveFilter
     * @property {HTMLInputElement | HTMLSelectElement} control the form control behind it
     * @property {HTMLOptionElement | null} option the selected option of a multiple select
     * @property {string} text the chip text
     */

    /**
     * The filter container a chip bar reflects: the element named by `data-filter-form`.
     *
     * @param {HTMLElement} bar the `[data-filter-chips]` element
     * @returns {HTMLElement | null} the form or container holding the filter controls
     */
    function sourceOf(bar) {
        const id = bar.getAttribute('data-filter-form');
        return id ? document.getElementById(id) : null;
    }

    /**
     * Whether a control takes no part in the chips: a hidden or button input, a search field, a
     * segmented control, or anything marked `data-filter-chip-ignore` / `data-filter-ignore`.
     *
     * @param {HTMLInputElement | HTMLSelectElement} control the control
     * @returns {boolean} true when it is skipped
     */
    function ignored(control) {
        if (control.disabled || !control.name) return true;
        if (control.closest('[data-filter-chip-ignore], [data-filter-ignore]')) return true;
        const type = (control.getAttribute('type') || '').toLowerCase();
        return ['hidden', 'submit', 'button', 'reset', 'search'].indexOf(type) >= 0;
    }

    /**
     * The visible name of a control: `data-chip-label`, its `<label>`, or its `aria-label`.
     *
     * @param {HTMLInputElement | HTMLSelectElement} control the control
     * @returns {string} the trimmed name, or the control's form name as the last resort
     */
    function labelOf(control) {
        const own = control.getAttribute('data-chip-label');
        if (own) return own;
        const labels = control.labels;
        if (labels && labels.length > 0) {
            const text = (labels[0].textContent || '').replace(/\s+/g, ' ').trim();
            if (text) return text;
        }
        return control.getAttribute('aria-label') || control.name;
    }

    /**
     * Collects the active filters of a container, one entry per chip.
     *
     * @param {HTMLElement} source the filter container
     * @returns {ActiveFilter[]} the active filters in document order
     */
    function collect(source) {
        /** @type {ActiveFilter[]} */
        const active = [];
        const controls = source.querySelectorAll('input, select');
        for (let i = 0; i < controls.length; i++) {
            const control = /** @type {HTMLInputElement | HTMLSelectElement} */ (controls[i]);
            if (ignored(control)) continue;
            if (control instanceof HTMLSelectElement) {
                for (let j = 0; j < control.selectedOptions.length; j++) {
                    const option = control.selectedOptions[j];
                    if (option.value === '') continue;
                    active.push({
                        control,
                        option: control.multiple ? option : null,
                        text: labelOf(control) + ': ' + (option.textContent || '').trim(),
                    });
                }
                continue;
            }
            const type = (control.getAttribute('type') || 'text').toLowerCase();
            if (type === 'checkbox') {
                if (control.checked) active.push({ control, option: null, text: labelOf(control) });
                continue;
            }
            if (type === 'radio') {
                if (control.checked && control.value !== '') {
                    active.push({ control, option: null, text: labelOf(control) });
                }
                continue;
            }
            if (control.value.trim() !== '') {
                active.push({
                    control,
                    option: null,
                    text: labelOf(control) + ': ' + control.value.trim(),
                });
            }
        }
        return active;
    }

    /**
     * Clears one filter and lets the page's own listeners react, as if the member had changed it.
     *
     * @param {ActiveFilter} filter the filter to clear
     */
    function clear(filter) {
        const control = filter.control;
        if (control instanceof HTMLSelectElement) {
            if (filter.option) filter.option.selected = false;
            else control.value = '';
        } else if (control.type === 'checkbox') {
            control.checked = false;
        } else if (control.type === 'radio') {
            const group = control.form
                ? control.form.querySelectorAll('input[type="radio"]')
                : document.querySelectorAll('input[type="radio"]');
            let reset = false;
            for (let i = 0; i < group.length; i++) {
                const radio = /** @type {HTMLInputElement} */ (group[i]);
                if (radio.name === control.name && radio.value === '') {
                    radio.checked = true;
                    reset = true;
                }
            }
            if (!reset) control.checked = false;
        } else {
            control.value = '';
        }
        control.dispatchEvent(new Event('input', { bubbles: true }));
        control.dispatchEvent(new Event('change', { bubbles: true }));
    }

    /**
     * The pending re-render frame of each bar.
     *
     * @type {WeakMap<HTMLElement, number>}
     */
    const frames = new WeakMap();

    /**
     * Re-renders a bar on the next animation frame, once however many events arrive before it.
     *
     * @param {HTMLElement} bar the `[data-filter-chips]` element
     */
    function schedule(bar) {
        if (frames.get(bar)) return;
        frames.set(
            bar,
            window.requestAnimationFrame(function () {
                frames.delete(bar);
                render(bar);
            }),
        );
    }

    /**
     * Drops a bar's pending re-render, so a render done right now keeps the focus it moves.
     *
     * @param {HTMLElement} bar the `[data-filter-chips]` element
     */
    function cancelScheduled(bar) {
        const frame = frames.get(bar);
        if (frame) window.cancelAnimationFrame(frame);
        frames.delete(bar);
    }

    /**
     * Renders the chips of one bar from the current state of its container.
     *
     * @param {HTMLElement} bar the `[data-filter-chips]` element
     */
    function render(bar) {
        const source = sourceOf(bar);
        const reset = bar.querySelector('[data-filter-chips-reset]');
        const old = bar.querySelectorAll('.filter-chip');
        for (let i = 0; i < old.length; i++) old[i].remove();
        if (!source) {
            bar.hidden = true;
            return;
        }
        const active = collect(source);
        const removeLabel = bar.getAttribute('data-remove-label') || '';
        active.forEach(function (filter) {
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'chip chip--primary filter-chip';
            chip.setAttribute('data-testid', 'filter-chip-' + filter.control.name);
            chip.setAttribute(
                'aria-label',
                removeLabel ? removeLabel + ': ' + filter.text : filter.text,
            );
            const text = document.createElement('span');
            text.textContent = filter.text;
            chip.appendChild(text);
            const svgNs = 'http://www.w3.org/2000/svg';
            const icon = document.createElementNS(svgNs, 'svg');
            icon.setAttribute('class', 'krt-icon');
            icon.setAttribute('aria-hidden', 'true');
            const use = document.createElementNS(svgNs, 'use');
            use.setAttribute('href', '#krt-icon-close');
            icon.appendChild(use);
            chip.appendChild(icon);
            chip.addEventListener('click', function () {
                const chips = Array.prototype.slice.call(bar.querySelectorAll('.filter-chip'));
                const index = chips.indexOf(chip);
                clear(filter);
                cancelScheduled(bar);
                render(bar);
                const rest = bar.querySelectorAll('.filter-chip');
                const next = /** @type {HTMLElement | null} */ (
                    rest[Math.min(index, rest.length - 1)] || source.querySelector('input, select')
                );
                if (next) next.focus();
            });
            bar.insertBefore(chip, reset);
        });
        bar.hidden = active.length === 0;
    }

    /**
     * Wires one bar: renders it, re-renders on every change in its container, and clears all
     * filters from the reset button.
     *
     * @param {HTMLElement} bar the `[data-filter-chips]` element
     */
    function init(bar) {
        if (bar.hasAttribute('data-filter-chips-ready')) {
            render(bar);
            return;
        }
        bar.setAttribute('data-filter-chips-ready', '');
        document.addEventListener('input', function (event) {
            const source = sourceOf(bar);
            if (source && event.target instanceof Node && source.contains(event.target))
                schedule(bar);
        });
        document.addEventListener('change', function (event) {
            const source = sourceOf(bar);
            if (source && event.target instanceof Node && source.contains(event.target))
                schedule(bar);
        });
        const reset = bar.querySelector('[data-filter-chips-reset]');
        if (reset) {
            reset.addEventListener('click', function () {
                const source = sourceOf(bar);
                if (!source) return;
                collect(source).forEach(clear);
                cancelScheduled(bar);
                render(bar);
                const first = /** @type {HTMLElement | null} */ (
                    source.querySelector('input, select')
                );
                if (first) first.focus();
            });
        }
        render(bar);
    }

    /**
     * Wires every chip bar below a root.
     *
     * @param {ParentNode} root the subtree to scan
     */
    function initAll(root) {
        const bars = root.querySelectorAll('[data-filter-chips]');
        for (let i = 0; i < bars.length; i++) init(/** @type {HTMLElement} */ (bars[i]));
    }

    window.krtFilterChips = {
        /**
         * Re-renders the chip bars; a page that swaps its filter form calls this after the swap.
         *
         * @param {ParentNode} [root] the subtree to scan; the whole document when omitted
         */
        refresh(root) {
            initAll(root || document);
        },
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            initAll(document);
        });
    } else {
        initAll(document);
    }
})();
