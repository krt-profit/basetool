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

import { formatNumber } from '/js/kernel/krt-number-format.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';

/**
 * Clamps a number to the unit interval.
 *
 * @param {number} t the value
 * @returns {number} the value limited to 0..1
 */
function clamp01(t) {
    return t < 0 ? 0 : t > 1 ? 1 : t;
}

/**
 * Linearly interpolates between two numbers.
 *
 * @param {number} a the start value
 * @param {number} b the end value
 * @param {number} t the position between a and b (0..1)
 * @returns {number} the interpolated value
 */
function lerp(a, b, t) {
    return a + (b - a) * t;
}

/**
 * Reads a numeric attribute.
 *
 * @param {Element} el the element
 * @param {string} attr the attribute name
 * @returns {number | null} the parsed number, or null when the attribute is absent or empty
 */
function num(el, attr) {
    const v = el.getAttribute(attr);
    return v === null || v === '' ? null : parseFloat(v);
}

/**
 * Updates a quality slider's quality and multiplier read-outs from its range input.
 *
 * @param {Element} slider the `.bp-slider` element
 */
function compute(slider) {
    const range = /** @type {HTMLInputElement | null} */ (slider.querySelector('.bp-range'));
    if (!range) {
        return;
    }
    const q = parseFloat(range.value);
    /** @type {number | null} */
    let value = null;
    const segs = slider.querySelectorAll('.bp-seg');
    if (segs.length) {
        const stepped = (slider.getAttribute('data-vrt') || 'linear').toLowerCase() !== 'linear';
        for (let i = 0; i < segs.length; i++) {
            const a = num(segs[i], 'data-qmin');
            const b = num(segs[i], 'data-qmax');
            const vs = num(segs[i], 'data-vstart');
            const ve = num(segs[i], 'data-vend');
            if (a === null || b === null) {
                continue;
            }
            if (q <= b || i === segs.length - 1) {
                if (stepped) {
                    value = vs === null ? ve : vs;
                } else {
                    const t = b === a ? 0 : clamp01((q - a) / (b - a));
                    value = vs === null || ve === null ? vs : lerp(vs, ve, t);
                }
                break;
            }
        }
    } else {
        const qmin = num(slider, 'data-qmin');
        const qmax = num(slider, 'data-qmax');
        const vmin = num(slider, 'data-vmin');
        const vmax = num(slider, 'data-vmax');
        if (qmin !== null && qmax !== null && vmin !== null && vmax !== null) {
            const tt = qmax === qmin ? 0 : clamp01((q - qmin) / (qmax - qmin));
            value = lerp(vmin, vmax, tt);
        }
    }
    const qOut = slider.querySelector('.bp-q');
    const vOut = slider.querySelector('.bp-out');
    if (qOut) {
        qOut.textContent = String(Math.round(q));
    }
    if (vOut) {
        vOut.textContent = `×${value === null ? '?' : formatNumber(value, 2, document.documentElement.lang)}`;
    }
}

const RESULTS_ID = 'admin-bp-results';

/**
 * Updates every quality slider below the root.
 *
 * @param {ParentNode | null | undefined} root the container, defaulting to the document
 */
function computeAll(root) {
    const sliders = (root || document).querySelectorAll('.bp-slider');
    for (let i = 0; i < sliders.length; i++) {
        compute(sliders[i]);
    }
}

document.addEventListener('input', (e) => {
    const t = /** @type {Element | null} */ (e.target);
    if (t && t.classList && t.classList.contains('bp-range')) {
        const slider = t.closest('.bp-slider');
        if (slider) {
            compute(slider);
        }
    }
});

/**
 * Swaps the results with the search term of the filter form.
 *
 * @param {HTMLFormElement} form the filter form
 */
function search(form) {
    const input = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="search"]')
    );
    const term = input ? input.value.trim() : '';
    const url = form.getAttribute('action') + (term ? `?search=${encodeURIComponent(term)}` : '');
    krtFetch.swap({ url, container: `#${RESULTS_ID}`, history: true });
}

/** @type {number | null} */
let searchTimer = null;

document.addEventListener('submit', (e) => {
    const form = /** @type {HTMLFormElement | null} */ (e.target);
    if (!form || form.id !== 'admin-bp-filter') {
        return;
    }
    e.preventDefault();
    clearTimeout(searchTimer ?? undefined);
    search(form);
});

const filterForm = /** @type {HTMLFormElement | null} */ (
    document.getElementById('admin-bp-filter')
);
if (filterForm) {
    filterForm.addEventListener('input', () => {
        clearTimeout(searchTimer ?? undefined);
        searchTimer = setTimeout(() => {
            search(filterForm);
        }, 300);
    });
}

computeAll(document);
krtFetch.bindSwap({ container: `#${RESULTS_ID}`, history: true });
document.addEventListener('krt:swapped', (e) => {
    const c = e.detail && e.detail.container;
    if (c && c.id === RESULTS_ID) {
        computeAll(c);
    }
});
