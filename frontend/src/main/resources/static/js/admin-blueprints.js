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
 * Admin blueprint-catalog page module (/admin/blueprints), extracted verbatim from the former
 * inline script of admin/blueprints.html (ADR-0069, follow-up to #924). One IIFE.
 *
 * Computes each blueprint modifier slider's live output from its segment / linear-band data
 * attributes (segmented vs stepped value-range types), and wires the in-place search that
 * swaps the #admin-bp-results fragment (REQ-FE-002); document-delegated listeners survive the
 * swap, and computeAll re-runs on krt:swapped so swapped-in sliders show their initial value.
 */

(function () {
    function clamp01(t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }
    function lerp(a, b, t) {
        return a + (b - a) * t;
    }
    function num(el, attr) {
        const v = el.getAttribute(attr);
        return v === null || v === '' ? null : parseFloat(v);
    }
    function compute(slider) {
        const range = slider.querySelector('.bp-range');
        if (!range) {
            return;
        }
        const q = parseFloat(range.value);
        let value = null;
        const segs = slider.querySelectorAll('.bp-seg');
        if (segs.length) {
            // Segmented modifier: locate the segment containing q. How the value behaves *within* a
            // segment depends on value_range_type: a 'linear' curve interpolates from start to end
            // across the segment, whereas a stepped form (e.g. 'linear_integer_additive') holds a
            // constant value inside the segment and only changes when q crosses into the next one.
            const stepped =
                (slider.getAttribute('data-vrt') || 'linear').toLowerCase() !== 'linear';
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
            // Simple linear band between the two endpoints.
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
            vOut.textContent = '×' + (value === null ? '?' : value.toFixed(2));
        }
    }

    const RESULTS_ID = 'admin-bp-results';

    // Initial display for every slider in root: the range starts at max, so its output
    // must be computed once even before the user drags it.
    function computeAll(root) {
        const sliders = (root || document).querySelectorAll('.bp-slider');
        for (let i = 0; i < sliders.length; i++) {
            compute(sliders[i]);
        }
    }

    // Delegated slider input: ONE listener on document survives the AJAX table swap, so
    // sliders in swapped-in rows stay live without per-element re-binding.
    document.addEventListener('input', function (e) {
        const t = e.target;
        if (t && t.classList && t.classList.contains('bp-range')) {
            const slider = t.closest('.bp-slider');
            if (slider) {
                compute(slider);
            }
        }
    });

    // Search submit -> in-place swap of the results block (REQ-FE-002). Delegated on
    // document so it survives the form being re-rendered inside the swapped fragment.
    // The page index is dropped so a new search lands on page 0. Reset and the prev/next
    // pager are plain anchors marked data-swap; krtFetch intercepts them after bindSwap.
    document.addEventListener('submit', function (e) {
        const form = e.target;
        if (!form || form.id !== 'admin-bp-filter') {
            return;
        }
        e.preventDefault();
        const input = form.querySelector('input[name="search"]');
        const search = input ? input.value.trim() : '';
        const url =
            form.getAttribute('action') + (search ? '?search=' + encodeURIComponent(search) : '');
        if (window.krtFetch) {
            window.krtFetch.swap({ url: url, container: '#' + RESULTS_ID, history: true });
        } else {
            window.location.assign(url);
        }
    });

    computeAll(document);
    if (window.krtFetch) {
        window.krtFetch.bindSwap({ container: '#' + RESULTS_ID, history: true });
    }
    document.addEventListener('krt:swapped', function (e) {
        const c = e.detail && e.detail.container;
        if (c && c.id === RESULTS_ID) {
            computeAll(c);
        }
    });
})();
