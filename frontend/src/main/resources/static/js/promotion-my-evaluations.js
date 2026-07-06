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
 * My-evaluations page module (/promotion/my-evaluations), extracted verbatim from the former inline
 * script of promotion-my-evaluations.html (ADR-0069, follow-up to #924).
 *
 * Fills each eligibility card's progress bar client-side by scraping the already-rendered per-check
 * fraction texts (the aggregate achieved/required pair is not on the DTO), and drives the "only open
 * requirements" checkbox filter through the delegated window.krtEvents bus.
 *
 * The block carried no Thymeleaf interpolation, so there is no inline bootstrap: the whole script
 * moved here unchanged (its `th:unless="${isAllSquadronsMode}"` gate now rides on the th:src tag).
 */

/*
 * Progress-bar fill: client-side because the aggregate "achievedTotal /
 * requiredTotal" pair across all checks is not on the DTO and recomputing
 * it server-side just to render a CSS width would be wasted work. We
 * scrape the per-check fraction texts (already rendered in the table)
 * with the same logic the user reads visually.
 */
function meFillProgressBars() {
    const cards = document.querySelectorAll('.eligibility-card');
    cards.forEach(function (card) {
        // Use class selectors instead of [class^="..."]: the spans are rendered by
        // th:classappend on an element without an initial class attribute, which makes
        // Thymeleaf emit class=" check-status-ok" with a leading space. The attribute
        // prefix selector would then fail to match while the tokenised classList still
        // works — so the aggregate read 0/0 even when individual rows had real values.
        const fractions = card.querySelectorAll(
            '.checks-table tbody tr .check-status-ok, .checks-table tbody tr .check-status-missing',
        );
        let achieved = 0,
            required = 0;
        fractions.forEach(function (span) {
            const parts = (span.textContent || '').split('/');
            if (parts.length === 2) {
                const a = parseInt(parts[0].trim(), 10);
                const r = parseInt(parts[1].trim(), 10);
                if (Number.isFinite(a) && Number.isFinite(r)) {
                    achieved += a;
                    required += r;
                }
            }
        });
        const pct = required > 0 ? Math.min(100, Math.round((achieved / required) * 100)) : 0;
        const fill = card.querySelector('.eligibility-progress-fill');
        const text = card.querySelector('.me-progress-text');
        const bar = card.querySelector('.eligibility-progress');
        if (fill) fill.style.width = pct + '%';
        if (text) text.textContent = achieved + ' / ' + required + ' (' + pct + '%)';
        if (bar) bar.setAttribute('aria-valuenow', String(pct));
    });
}

/*
 * Filter "nur offene Voraussetzungen": hides satisfied check rows when
 * checked. Acts on .checks-table rows, not whole cards — even an "almost
 * eligible" card may still have one missing check the user wants to see.
 */
function meApplyOpenFilter() {
    const input = document.getElementById('me-filter-open');
    const onlyOpen = !!(input && input.checked);
    const rows = document.querySelectorAll('.checks-table tbody tr[data-me-satisfied]');
    rows.forEach(function (row) {
        const satisfied = row.getAttribute('data-me-satisfied') === 'true';
        row.classList.toggle('checks-row-hidden', onlyOpen && satisfied);
    });
}

document.addEventListener('DOMContentLoaded', function () {
    meFillProgressBars();
    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('change', 'me-filter-open', meApplyOpenFilter);
    }
});
