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
 * Variante C "Herkunft" (deduct-from) picker (REQ-INV-027) shared by the personal
 * ("Mein Lager", inventory-my.js) and global ("Lager", inventory-admin.js) pages.
 *
 * A book-out (Ausbuchen) or transfer (Umbuchen -> Ort/Nutzer) removes a quantity X from an
 * entry that is split independently across job-order and mission earmark tags. This module
 * lets the user choose how much of X comes from each tag; whatever is left over is taken from
 * that dimension's not-yet-assigned rest. It mirrors the backend contract in
 * InventoryCheckoutService.resolveReductionPlan: per dimension the sum of the tag reductions
 * must not exceed X (else 400), each reduction must fit its slice (else 400), and the rest must
 * be able to absorb whatever the tags did not cover, i.e. X - sum <= rest (else the R5 422). An
 * omitted plan (all inputs 0) makes the backend take the deduction from the rest first -- the
 * locked default "Rest zuerst, Rest leer lassen".
 *
 * The picker reads the entry's tags straight from the source leaf row's Variante-C chips
 * (.assoc-split[data-assoc-field] -> .assoc-chip[data-target-id][data-amount]), so no extra
 * server round-trip is needed. State lives per modal in `registry`, keyed by a short prefix
 * ('bookout' / 'umbuchen') that also names the section element via data-herkunft="<prefix>".
 *
 * The page code calls populate() when a modal opens, collect() + isValid() on submit, and
 * reset() on close; every value change (amount, tag inputs, sell amount, type/mode radios)
 * re-runs recompute() through the delegated input/change listeners this module installs, which
 * refresh the rest chips, the "assign at least" warning and the coupled-sale proceeds hint, and
 * enable/disable the modal's submit button.
 */
(function () {
    'use strict';

    // Matches the backend REDUCTION_EPSILON; amounts are SCU-rounded to 3 decimals first so this
    // only absorbs floating-point noise, never a meaningful quantity.
    const EPS = 1e-6;

    // Chip-display epsilon (three-decimal SCU): a |rest| within this reads as an exact zero.
    const REST_EPS = 0.0005;

    // prefix -> { itemId, dims: [{ field, entryAmount, isPiece, sumAllocated, tags: [...] }] }.
    const registry = {};

    /**
     * The localized strings the picker renders, injected as the page global `herkunftI18n` by the
     * template's th:inline bootstrap block. The fallback keeps the module usable (English-ish) if a
     * page forgot to declare it.
     *
     * @returns {object} the active string bundle
     */
    function i18n() {
        return (
            window.herkunftI18n || {
                order: 'Order',
                mission: 'Mission',
                rest: 'From rest: {0}',
                min: 'Assign at least {0} to tags',
                proceedsTitle: 'Sale proceeds',
                personal: 'Personal: {0}',
                auec: 'aUEC',
            }
        );
    }

    /**
     * Resolves the picker section element for a modal prefix.
     *
     * @param {string} prefix the modal prefix ('bookout' / 'umbuchen')
     * @returns {HTMLElement|null} the section, or null when the page has no such picker
     */
    function sectionOf(prefix) {
        return document.querySelector('[data-herkunft="' + prefix + '"]');
    }

    /**
     * Reports whether a picker is live: shown (not krtm-hidden) and inside no display:none subtree.
     * The Umbuchen picker sits inside #umbuchenTransferFields (hidden in PERSONAL mode) and every
     * picker sits inside a modal that is display:none while closed, so an inactive picker neither
     * gates its submit button nor contributes a plan.
     *
     * @param {HTMLElement} section the picker section
     * @returns {boolean} true when the picker is visible and should be enforced
     */
    function isActive(section) {
        if (!section || section.classList.contains('krtm-hidden')) {
            return false;
        }
        let el = section;
        while (el) {
            if (el.style && el.style.display === 'none') {
                return false;
            }
            el = el.parentElement;
        }
        return true;
    }

    /**
     * Formats a quantity for display: whole for PIECE, three decimals for SCU.
     *
     * @param {number} n the amount
     * @param {boolean} isPiece whether the material is counted in whole pieces
     * @returns {string} the formatted amount
     */
    function fmtAmount(n, isPiece) {
        if (typeof n !== 'number' || isNaN(n)) {
            return '0';
        }
        return isPiece ? String(Math.round(n)) : n.toFixed(3);
    }

    /**
     * SCU-rounds a quantity to the entry's precision (whole for PIECE, three decimals for SCU),
     * matching InventoryItem.roundToScuScale so the client validation agrees with the backend.
     *
     * @param {number} n the raw amount
     * @param {boolean} isPiece whether the material is counted in whole pieces
     * @returns {number} the rounded amount (0 for a non-finite input)
     */
    function roundAmount(n, isPiece) {
        if (typeof n !== 'number' || isNaN(n)) {
            return 0;
        }
        return isPiece ? Math.round(n) : Math.round(n * 1000) / 1000;
    }

    /**
     * Parses an amount field, honouring the shared SCU decimal normaliser when present so "," and
     * "." both work regardless of browser locale.
     *
     * @param {string} value the raw field value
     * @returns {number} the parsed number (NaN when unparseable)
     */
    function parseAmount(value) {
        if (window.krtScuInput && typeof window.krtScuInput.parse === 'function') {
            return window.krtScuInput.parse(value);
        }
        return parseFloat(value);
    }

    /**
     * Formats an aUEC proceeds figure as a rounded whole credit amount.
     *
     * @param {number} n the credit
     * @returns {string} the rounded credit as a string
     */
    function formatAuec(n) {
        if (typeof n !== 'number' || isNaN(n)) {
            return '0';
        }
        return String(Math.round(n));
    }

    /**
     * Reads the currently deducted amount from the modal's amount input.
     *
     * @param {string} prefix the modal prefix
     * @returns {number} the deducted quantity (NaN when the field is empty / missing)
     */
    function deductedOf(prefix) {
        const section = sectionOf(prefix);
        if (!section) {
            return NaN;
        }
        const el = document.getElementById(section.getAttribute('data-herkunft-amount-id'));
        return el ? parseAmount(el.value) : NaN;
    }

    /**
     * The SELL context of a book-out picker: the positive sale amount when the SELL type is active,
     * else null (DISCARD, no sale amount yet, or a picker with no coupled-sale hint).
     *
     * @param {string} prefix the modal prefix
     * @returns {{sellAmount: number}|null} the sale context or null
     */
    function sellContext(prefix) {
        const section = sectionOf(prefix);
        if (!section || !section.hasAttribute('data-herkunft-sell-amount-id')) {
            return null;
        }
        const sellRadio = document.querySelector('input[name="type"][value="SELL"]');
        if (!sellRadio || !sellRadio.checked) {
            return null;
        }
        const sellEl = document.getElementById(
            section.getAttribute('data-herkunft-sell-amount-id'),
        );
        const sellAmount = sellEl ? parseFloat(sellEl.value) : NaN;
        if (!isFinite(sellAmount) || sellAmount <= 0) {
            return null;
        }
        return { sellAmount: sellAmount };
    }

    /**
     * Reads one dimension's earmark tags from the source leaf row's Variante-C chips.
     *
     * @param {HTMLElement} leaf the source entry's leaf row
     * @param {string} field the dimension ('JOB_ORDER' / 'MISSION')
     * @returns {object|null} the dimension descriptor, or null when the row has no such split
     */
    function readDimension(leaf, field) {
        const split = leaf.querySelector('.assoc-split[data-assoc-field="' + field + '"]');
        if (!split) {
            return null;
        }
        const entryAmount = parseFloat(split.getAttribute('data-amount')) || 0;
        const isPiece = split.getAttribute('data-piece') === 'true';
        const tags = [];
        let sumAllocated = 0;
        split.querySelectorAll('[data-assoc-chip]').forEach(function (chip) {
            const targetId = chip.getAttribute('data-target-id');
            if (!targetId) {
                return;
            }
            const allocated = parseFloat(chip.getAttribute('data-amount')) || 0;
            const nameEl = chip.querySelector('span:not(.assoc-chip__amt)');
            const name = nameEl ? nameEl.textContent.trim() : targetId;
            tags.push({ targetId: targetId, allocated: allocated, name: name });
            sumAllocated += allocated;
        });
        return {
            field: field,
            entryAmount: entryAmount,
            isPiece: isPiece,
            sumAllocated: roundAmount(sumAllocated, isPiece),
            tags: tags,
        };
    }

    /**
     * Finds a tag's amount input within a section or a single dimension block.
     *
     * @param {HTMLElement} scope the section or a [data-herkunft-dim] block
     * @param {string} field the dimension
     * @param {string} targetId the tag's job-order / mission id
     * @returns {HTMLInputElement|null} the input, or null when absent
     */
    function tagInput(scope, field, targetId) {
        const block =
            scope.matches && scope.matches('[data-herkunft-dim]')
                ? scope
                : scope.querySelector('[data-herkunft-dim="' + field + '"]');
        if (!block) {
            return null;
        }
        return block.querySelector(
            '[data-herkunft-input][data-herkunft-target="' + targetId + '"]',
        );
    }

    /**
     * Builds the DOM for one dimension: a caption, one amount input per tag, a rest chip, an
     * "assign at least" warning and (mission dimension of a SELL-capable picker only) a coupled
     * proceeds hint. Names are set via textContent so a mission name never injects markup.
     *
     * @param {string} prefix the modal prefix
     * @param {object} dim the dimension descriptor from readDimension
     * @returns {HTMLElement} the dimension block
     */
    function buildDimBlock(prefix, dim) {
        const strings = i18n();
        const block = document.createElement('div');
        block.className = 'herkunft-dim';
        block.setAttribute('data-herkunft-dim', dim.field);

        const cap = document.createElement('div');
        cap.className = 'herkunft-dim-cap';
        cap.textContent = dim.field === 'JOB_ORDER' ? strings.order : strings.mission;
        block.appendChild(cap);

        dim.tags.forEach(function (tag) {
            const row = document.createElement('label');
            row.className = 'herkunft-tag';

            const name = document.createElement('span');
            name.className = 'herkunft-tag-name';
            name.textContent = tag.name;

            const input = document.createElement('input');
            input.type = 'number';
            input.className = 'herkunft-input';
            input.setAttribute('data-herkunft-input', '');
            input.setAttribute('data-herkunft-target', tag.targetId);
            input.min = '0';
            input.max = String(tag.allocated);
            input.step = dim.isPiece ? '1' : '0.001';
            input.value = '0';
            input.setAttribute('inputmode', dim.isPiece ? 'numeric' : 'decimal');

            const max = document.createElement('span');
            max.className = 'herkunft-tag-max';
            max.textContent = '/ ' + fmtAmount(tag.allocated, dim.isPiece);

            row.appendChild(name);
            row.appendChild(input);
            row.appendChild(max);
            block.appendChild(row);
        });

        const restLine = document.createElement('div');
        restLine.className = 'herkunft-restline';
        const rest = document.createElement('span');
        rest.className = 'chip chip--muted';
        rest.setAttribute('data-herkunft-rest', '');
        restLine.appendChild(rest);
        const warn = document.createElement('span');
        warn.className = 'herkunft-warn krtm-hidden';
        warn.setAttribute('data-herkunft-warn', '');
        restLine.appendChild(warn);
        block.appendChild(restLine);

        if (
            dim.field === 'MISSION' &&
            sectionOf(prefix).hasAttribute('data-herkunft-sell-amount-id')
        ) {
            const proceeds = document.createElement('div');
            proceeds.className = 'herkunft-proceeds krtm-hidden';
            proceeds.setAttribute('data-herkunft-proceeds', '');
            block.appendChild(proceeds);
        }
        return block;
    }

    /**
     * Computes one dimension's live state from its inputs and the deducted amount, applying the
     * same rules the backend enforces.
     *
     * @param {HTMLElement} section the picker section
     * @param {object} dim the dimension descriptor
     * @param {number} deducted the current deducted amount
     * @returns {object} the dimension state (assigned, fromRest, minRequired, validity flags)
     */
    function dimState(section, dim, deducted) {
        const restDim = roundAmount(dim.entryAmount - dim.sumAllocated, dim.isPiece);
        const hasDeducted = isFinite(deducted) && deducted > 0;
        const target = hasDeducted ? roundAmount(deducted, dim.isPiece) : 0;

        let assigned = 0;
        let tagOver = false;
        dim.tags.forEach(function (tag) {
            const input = tagInput(section, dim.field, tag.targetId);
            let v = input ? parseFloat(input.value) : 0;
            if (isNaN(v) || v < 0) {
                v = 0;
            }
            v = roundAmount(v, dim.isPiece);
            if (v > tag.allocated + EPS) {
                tagOver = true;
            }
            assigned += v;
        });
        assigned = roundAmount(assigned, dim.isPiece);

        const fromRest = roundAmount(target - assigned, dim.isPiece);
        const minRequired = roundAmount(Math.max(0, target - restDim), dim.isPiece);
        const overAssigned = assigned > target + EPS;
        const underAssigned = fromRest > restDim + EPS;
        // A missing / zero deducted amount is the amount field's own error, not the picker's: don't
        // block on it here, the field's required+min guard already stops the submit.
        const valid = !hasDeducted || (!tagOver && !overAssigned && !underAssigned);

        return {
            restDim: restDim,
            assigned: assigned,
            fromRest: fromRest,
            minRequired: minRequired,
            tagOver: tagOver,
            overAssigned: overAssigned,
            underAssigned: underAssigned,
            valid: valid,
            hasDeducted: hasDeducted,
            target: target,
        };
    }

    /**
     * Refreshes one dimension's rest chip, warning and proceeds hint from its computed state.
     *
     * @param {HTMLElement} section the picker section
     * @param {object} dim the dimension descriptor
     * @param {object} state the dimension state from dimState
     * @param {object} strings the active i18n bundle
     * @param {{sellAmount: number}|null} sell the SELL context, or null
     */
    function updateDimUi(section, dim, state, strings, sell) {
        const block = section.querySelector('[data-herkunft-dim="' + dim.field + '"]');
        if (!block) {
            return;
        }
        const restEl = block.querySelector('[data-herkunft-rest]');
        if (restEl) {
            restEl.classList.remove('chip--muted', 'chip--danger', 'chip--success');
            if (state.tagOver || state.overAssigned || state.underAssigned) {
                restEl.classList.add('chip--danger');
            } else if (Math.abs(state.fromRest) <= REST_EPS) {
                restEl.classList.add('chip--success');
            } else {
                restEl.classList.add('chip--muted');
            }
            const shown = Math.max(0, state.fromRest);
            restEl.textContent = strings.rest.replace('{0}', fmtAmount(shown, dim.isPiece));
        }
        const warnEl = block.querySelector('[data-herkunft-warn]');
        if (warnEl) {
            if (
                state.hasDeducted &&
                state.minRequired > 0 &&
                state.assigned < state.minRequired - EPS
            ) {
                warnEl.textContent = strings.min.replace(
                    '{0}',
                    fmtAmount(state.minRequired, dim.isPiece),
                );
                warnEl.classList.remove('krtm-hidden');
            } else {
                warnEl.classList.add('krtm-hidden');
            }
        }
        updateProceeds(block, dim, state, sell, strings);
    }

    /**
     * Refreshes the coupled-sale proceeds hint under the mission dimension: each mission with a
     * positive reduction is credited sellAmount * scu / deducted, and the unassigned remainder
     * falls to the personal account. It is an estimate -- a mission the seller did not take part in
     * is credited personally by the backend, which the client cannot know.
     *
     * @param {HTMLElement} block the mission dimension block
     * @param {object} dim the dimension descriptor
     * @param {object} state the dimension state
     * @param {{sellAmount: number}|null} sell the SELL context, or null
     * @param {object} strings the active i18n bundle
     */
    function updateProceeds(block, dim, state, sell, strings) {
        const el = block.querySelector('[data-herkunft-proceeds]');
        if (!el) {
            return;
        }
        if (dim.field !== 'MISSION' || !sell || !state.hasDeducted) {
            el.classList.add('krtm-hidden');
            el.textContent = '';
            return;
        }
        el.textContent = '';
        const title = document.createElement('div');
        title.className = 'herkunft-proceeds-title';
        title.textContent = strings.proceedsTitle;
        el.appendChild(title);

        let assignedToMissions = 0;
        dim.tags.forEach(function (tag) {
            const input = tagInput(block, dim.field, tag.targetId);
            let r = input ? parseFloat(input.value) : 0;
            if (isNaN(r) || r <= 0) {
                return;
            }
            r = roundAmount(r, dim.isPiece);
            assignedToMissions += r;
            const credit = sell.sellAmount * (r / state.target);
            const line = document.createElement('div');
            line.className = 'herkunft-proceeds-line';
            line.textContent = tag.name + ': ' + formatAuec(credit) + ' ' + strings.auec;
            el.appendChild(line);
        });

        const personalScu = Math.max(0, state.target - assignedToMissions);
        const personalCredit = sell.sellAmount * (personalScu / state.target);
        const pLine = document.createElement('div');
        pLine.className = 'herkunft-proceeds-line herkunft-proceeds-personal';
        pLine.textContent = strings.personal.replace(
            '{0}',
            formatAuec(personalCredit) + ' ' + strings.auec,
        );
        el.appendChild(pLine);
        el.classList.remove('krtm-hidden');
    }

    /**
     * Recomputes the picker: refreshes every dimension's UI and enables/disables the modal's submit
     * button. An inactive or tag-less picker never gates the button.
     *
     * @param {string} prefix the modal prefix
     * @returns {boolean} true when the plan is valid (or the picker does not apply)
     */
    function recompute(prefix) {
        const section = sectionOf(prefix);
        const ctx = registry[prefix];
        const submitBtn = section
            ? document.getElementById(section.getAttribute('data-herkunft-submit-id'))
            : null;
        if (!section || !ctx || ctx.dims.length === 0 || !isActive(section)) {
            if (submitBtn) {
                submitBtn.disabled = false;
            }
            return true;
        }
        const strings = i18n();
        const deducted = deductedOf(prefix);
        const sell = sellContext(prefix);
        let allValid = true;
        ctx.dims.forEach(function (dim) {
            const state = dimState(section, dim, deducted);
            if (!state.valid) {
                allValid = false;
            }
            updateDimUi(section, dim, state, strings, sell);
        });
        if (submitBtn) {
            submitBtn.disabled = !allValid;
        }
        return allValid;
    }

    /**
     * Builds the picker for a modal from the source entry's leaf-row chips and shows it (hiding it
     * when the entry carries no tags at all). Call this when the modal opens, after it is shown.
     *
     * @param {string} prefix the modal prefix
     * @param {string} itemId the source entry id (matches the leaf row's data-item-id)
     */
    function populate(prefix, itemId) {
        const section = sectionOf(prefix);
        if (!section) {
            return;
        }
        const body = section.querySelector('[data-herkunft-body]');
        if (body) {
            body.textContent = '';
        }
        const leaf = itemId
            ? document.querySelector('.tree-row--leaf[data-item-id="' + itemId + '"]')
            : null;
        const dims = [];
        if (leaf) {
            ['JOB_ORDER', 'MISSION'].forEach(function (field) {
                const dim = readDimension(leaf, field);
                if (dim && dim.tags.length > 0) {
                    dims.push(dim);
                }
            });
        }
        registry[prefix] = { itemId: itemId, dims: dims };
        if (dims.length === 0) {
            section.classList.add('krtm-hidden');
            recompute(prefix);
            return;
        }
        section.classList.remove('krtm-hidden');
        dims.forEach(function (dim) {
            if (body) {
                body.appendChild(buildDimBlock(prefix, dim));
            }
        });
        recompute(prefix);
    }

    /**
     * Collects the picker's per-dimension "deduct from" plan for the submit payload. A dimension
     * with only zero inputs contributes null -- the backend then takes that dimension's deduction
     * from the rest first (the legacy default). An inactive / tag-less picker contributes null for
     * both dimensions.
     *
     * @param {string} prefix the modal prefix
     * @returns {{jobOrderReductions: Array|null, missionReductions: Array|null}} the plan
     */
    function collect(prefix) {
        const section = sectionOf(prefix);
        const ctx = registry[prefix];
        const out = { jobOrderReductions: null, missionReductions: null };
        if (!section || !ctx || ctx.dims.length === 0 || !isActive(section)) {
            return out;
        }
        ctx.dims.forEach(function (dim) {
            const list = [];
            dim.tags.forEach(function (tag) {
                const input = tagInput(section, dim.field, tag.targetId);
                let v = input ? parseFloat(input.value) : 0;
                if (isNaN(v) || v <= 0) {
                    return;
                }
                v = roundAmount(v, dim.isPiece);
                if (v > 0) {
                    list.push({ targetId: tag.targetId, amount: v });
                }
            });
            if (list.length > 0) {
                if (dim.field === 'JOB_ORDER') {
                    out.jobOrderReductions = list;
                } else {
                    out.missionReductions = list;
                }
            }
        });
        return out;
    }

    window.krtHerkunft = {
        populate: populate,
        recompute: recompute,
        collect: collect,
        /**
         * Recomputes and reports whether the picker's plan is currently submittable.
         *
         * @param {string} prefix the modal prefix
         * @returns {boolean} true when the plan is valid (or the picker does not apply)
         */
        isValid: function (prefix) {
            return recompute(prefix);
        },
        /**
         * Clears and hides a picker, e.g. when its modal closes.
         *
         * @param {string} prefix the modal prefix
         */
        reset: function (prefix) {
            delete registry[prefix];
            const section = sectionOf(prefix);
            if (section) {
                section.classList.add('krtm-hidden');
                const body = section.querySelector('[data-herkunft-body]');
                if (body) {
                    body.textContent = '';
                }
            }
        },
    };

    // Any value change inside an open picker's modal (tag inputs, the deducted amount, the sale
    // amount, the type/mode radios) re-runs the affected pickers. Only an active picker does any
    // work, so this stays cheap when no modal is open.
    function onAnyChange() {
        Object.keys(registry).forEach(function (prefix) {
            const section = sectionOf(prefix);
            if (section && isActive(section)) {
                recompute(prefix);
            }
        });
    }
    document.addEventListener('input', onAnyChange);
    document.addEventListener('change', onAnyChange);
})();
