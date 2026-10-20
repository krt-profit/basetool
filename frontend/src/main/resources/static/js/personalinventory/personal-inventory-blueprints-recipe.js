import { safeSameOriginUrl } from '/js/kernel/safe-url.js';
import { formatNumber } from '/js/kernel/krt-number-format.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

/** @type {HTMLElement | null} */
let mdEl = null;
/** @type {HTMLElement | null} */
let rowsEl = null;
/** @type {HTMLInputElement | null} */
let filterInput = null;
/** @type {HTMLElement | null} */
let detailEmpty = null;
/** @type {HTMLElement | null} */
let detailContent = null;
/** @type {HTMLElement | null} */
let nameEl = null;
/** @type {HTMLElement | null} */
let acquiredEl = null;
/** @type {HTMLElement | null} */
let sourceEl = null;
/** @type {HTMLElement | null} */
let recipeEl = null;
/** @type {HTMLElement | null} */
let noteSection = null;
/** @type {HTMLElement | null} */
let noteEl = null;
/** @type {HTMLElement | null} */
let editBtn = null;
/** @type {HTMLElement | null} */
let deleteBtn = null;
/** @type {HTMLElement | null} */
let backBtn = null;
/** @type {HTMLElement | null} */
let activeRow = null;

/**
 * @typedef {ApiDto<'PersonalBlueprintRecipeResponse'>} BpRecipe
 */

/**
 * @typedef {ApiDto<'BlueprintRequirementGroupDto'>} BpRecipeGroup
 */

/**
 * @typedef {ApiDto<'BlueprintRequirementIngredientDto'>} BpRecipeIngredient
 */

/**
 * @typedef {ApiDto<'BlueprintRequirementModifierDto'>} BpRecipeModifier
 */

const recipeCache = new Map();

const craftabilityById = new Map();
/** @type {HTMLElement | null} */
let detailCraftEl = null;
/** @type {HTMLInputElement | null} */
let refineryToggle = null;
let refineryOn = false;
/** @type {any} */
let activeCraftability = null;

let craftableOnly = false;
/** @type {HTMLElement | null} */
let filterEmptyEl = null;

function i18n() {
    return window.krtBlueprintsRecipeI18n || {};
}

function endpoints() {
    return window.krtBlueprintsEndpoints || {};
}

/**
 * Creates a detached element with an optional class and text content.
 *
 * @param {string} tag element tag name
 * @param {string | null} [cls] class attribute to set when truthy
 * @param {string | null} [text] text content to set when not null/undefined
 * @returns {HTMLElement} the new, unattached element
 */
function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) {
        node.className = cls;
    }
    if (text != null) {
        node.textContent = text;
    }
    return node;
}

/**
 * Removes every child of `node`, leaving the node itself in place.
 *
 * @param {Node} node the node to empty
 * @returns {void}
 */
function clear(node) {
    while (node.firstChild) {
        node.removeChild(node.firstChild);
    }
}

/**
 * Substitutes the blueprint id into an endpoint template and keeps the result same-origin.
 *
 * @param {string | undefined} template the endpoint with an `ID_PLACEHOLDER`
 * @param {string} id the blueprint id
 * @returns {string} the same-origin URL
 */
function resolveUrl(template, id) {
    const raw = (template || '').replace('ID_PLACEHOLDER', encodeURIComponent(id));
    return safeSameOriginUrl(raw, raw);
}

/**
 * Reads a row attribute, mapping a missing or `null` literal value to an empty string.
 *
 * @param {Element} row the master row
 * @param {string} name the attribute name
 * @returns {string} the value, or an empty string
 */
function attr(row, name) {
    const v = row.getAttribute(name);
    return v == null || v === 'null' ? '' : v;
}

/**
 * Formats an acquisition instant in Europe/Berlin.
 *
 * @param {string} iso the ISO-8601 instant, or an empty string
 * @param {boolean} withTime whether to include the time of day
 * @returns {string} the formatted date, the raw value when unparseable, or an empty string
 */
function formatAcquired(iso, withTime) {
    if (!iso) {
        return '';
    }
    let s = iso;
    if (!s.endsWith('Z') && s.includes('T')) {
        s += 'Z';
    }
    const date = new Date(s);
    if (isNaN(date.getTime())) {
        return iso;
    }
    /** @type {Intl.DateTimeFormatOptions} */
    const options = {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        timeZone: 'Europe/Berlin',
    };
    if (withTime) {
        options.hour = '2-digit';
        options.minute = '2-digit';
    }
    return new Intl.DateTimeFormat(undefined, options).format(date);
}

/**
 * The master-list rows, or an empty list before the collection is wired.
 *
 * @returns {HTMLElement[]} the row elements in document order
 */
function rows() {
    return rowsEl
        ? Array.from(
              /** @type {NodeListOf<HTMLElement>} */ (rowsEl.querySelectorAll('.master-row')),
          )
        : [];
}

/**
 * The master-list rows that the filter leaves visible.
 *
 * @returns {HTMLElement[]} the visible row elements
 */
function visibleRows() {
    return rows().filter((r) => {
        return !r.hidden;
    });
}

/**
 * Marks a master row as the selected one and loads its detail.
 *
 * @param {HTMLElement | null | undefined} row the row to select
 * @param {{ focus?: boolean, showDetail?: boolean }} [opts] focus the row; reveal the detail pane
 */
function select(row, opts) {
    if (!row) {
        return;
    }
    const options = opts || {};
    if (activeRow) {
        activeRow.classList.remove('is-active');
        activeRow.setAttribute('aria-selected', 'false');
        activeRow.tabIndex = -1;
    }
    activeRow = row;
    row.classList.add('is-active');
    row.setAttribute('aria-selected', 'true');
    row.tabIndex = 0;
    if (options.focus) {
        row.focus();
    }

    try {
        const url = new URL(window.location.href);
        url.searchParams.set('bp', attr(row, 'data-id'));
        history.replaceState(null, '', url);
    } catch (_e) {}

    renderDetailHead(row);
    activeCraftability = craftabilityById.get(attr(row, 'data-id')) || null;
    loadRecipe(attr(row, 'data-id'));
    renderCraftDetail(attr(row, 'data-id'));

    if (options.showDetail && mdEl && window.matchMedia('(width <= 1024px)').matches) {
        mdEl.classList.add('is-detail');
    }
}

/**
 * Describes where a blueprint came from, naming the exchange client that added it by its
 * registry display name, or by its id when the client is no longer registered.
 *
 * @param {string} source the recorded source, or an empty string
 * @param {string} clientId the exchange client's id, or an empty string
 * @param {string} clientName the client's display name, or an empty string
 * @returns {string} the text, empty when no source was recorded
 */
function describeSource(source, clientId, clientName) {
    if (!source) {
        return '';
    }
    const client = clientName || clientId;
    const dict = i18n();
    const label = krtI18nText(dict.sourceLabel, 'krtBlueprintsRecipeI18n.sourceLabel');
    const name = krtI18nText(dict[`source${source}`], `krtBlueprintsRecipeI18n.source${source}`);
    const via = client
        ? ` ${krtI18nText(dict.sourceVia, 'krtBlueprintsRecipeI18n.sourceVia')} ${client}`
        : '';
    return `${label}: ${name}${via}`;
}

/**
 * Shortens the source line for the detail chip: the source name and the client after a dot.
 *
 * @param {string} source the recorded source, or an empty string
 * @param {string} clientId the exchange client's id, or an empty string
 * @param {string} clientName the client's display name, or an empty string
 * @returns {string} the chip text, empty when no source was recorded
 */
function sourceChipText(source, clientId, clientName) {
    if (!source) {
        return '';
    }
    const client = clientName || clientId;
    const name = krtI18nText(i18n()[`source${source}`], `krtBlueprintsRecipeI18n.source${source}`);
    return client ? `${name} · ${client}` : name;
}

/**
 * Fills the detail header from the selected row's data attributes.
 *
 * @param {Element} row the selected master row
 */
function renderDetailHead(row) {
    if (!detailContent || !detailEmpty || !nameEl || !acquiredEl || !noteEl || !noteSection) {
        return;
    }
    detailEmpty.hidden = true;
    detailContent.hidden = false;

    const id = attr(row, 'data-id');
    const name = attr(row, 'data-name');
    const note = attr(row, 'data-note');
    const version = attr(row, 'data-version');
    const acquired = attr(row, 'data-acquired-at');
    const source = attr(row, 'data-source');
    const sourceClient = attr(row, 'data-source-client');
    const sourceClientName = attr(row, 'data-source-client-name');

    nameEl.textContent = name;
    const formatted = formatAcquired(acquired, false);
    acquiredEl.textContent = formatted
        ? `${krtI18nText(
              i18n().acquiredLabel,
              'krtBlueprintsRecipeI18n.acquiredLabel',
          )} ${formatted}`
        : '';
    acquiredEl.title = formatAcquired(acquired, true);
    acquiredEl.hidden = !formatted;
    if (sourceEl) {
        const sourceText = describeSource(source, sourceClient, sourceClientName);
        sourceEl.textContent = sourceChipText(source, sourceClient, sourceClientName);
        sourceEl.title = sourceText;
        sourceEl.hidden = !sourceText;
    }

    [editBtn, deleteBtn].forEach((btn) => {
        if (!btn) {
            return;
        }
        btn.setAttribute('data-id', id);
        btn.setAttribute('data-name', name);
    });
    if (editBtn) {
        editBtn.setAttribute('data-note', note);
        editBtn.setAttribute('data-version', version);
        editBtn.setAttribute('data-acquired-at', acquired);
    }
    if (deleteBtn) {
        const removable = attr(row, 'data-removable') !== 'false';
        deleteBtn.hidden = !removable;
        deleteBtn.style.display = removable ? '' : 'none';
    }

    if (note) {
        noteEl.textContent = note;
        noteSection.hidden = false;
    } else {
        noteSection.hidden = true;
    }
}

/**
 * Shows a blueprint's recipe from the cache or fetches it.
 *
 * @param {string} id the blueprint id
 */
function loadRecipe(id) {
    if (!recipeEl || !id) {
        return;
    }
    if (recipeCache.has(id)) {
        renderRecipe(recipeCache.get(id));
        return;
    }
    clear(recipeEl);
    recipeEl.appendChild(
        el(
            'div',
            'krt-bp-recipe-loading',
            krtI18nText(i18n().loading, 'krtBlueprintsRecipeI18n.loading'),
        ),
    );
    krtFetch
        .getJson(resolveUrl(endpoints().recipe, id))
        .then((recipe) => {
            if (!recipe) {
                showError();
                return;
            }
            recipeCache.set(id, recipe);
            if (activeRow && attr(activeRow, 'data-id') === id) {
                renderRecipe(recipe);
            }
        })
        .catch(() => {
            showError();
        });
}

/** Replaces the recipe pane with the load error. */
function showError() {
    if (!recipeEl) {
        return;
    }
    clear(recipeEl);
    recipeEl.appendChild(
        el(
            'div',
            'krt-bp-recipe-error',
            krtI18nText(i18n().error, 'krtBlueprintsRecipeI18n.error'),
        ),
    );
}

/**
 * Renders a recipe into the recipe pane.
 *
 * @param {BpRecipe} recipe the recipe
 */
function renderRecipe(recipe) {
    if (!recipeEl) {
        return;
    }
    clear(recipeEl);
    const groups = recipe.requirementGroups || [];
    const flat = recipe.ingredients || [];
    if (groups.length === 0 && flat.length === 0) {
        recipeEl.appendChild(
            el(
                'div',
                'krt-bp-recipe-empty',
                krtI18nText(i18n().empty, 'krtBlueprintsRecipeI18n.empty'),
            ),
        );
        return;
    }

    if ((recipe.variantCount ?? 0) > 1) {
        const hint = `${recipe.variantCount} ${krtI18nText(
            i18n().variants,
            'krtBlueprintsRecipeI18n.variants',
        )} · ${krtI18nText(i18n().exampleRecipe, 'krtBlueprintsRecipeI18n.exampleRecipe')}`;
        recipeEl.appendChild(el('p', 'krt-bp-recipe-variants', hint));
    }

    if (groups.length > 0) {
        const pane = recipeEl;
        groups.forEach((g, idx) => {
            pane.appendChild(renderQualityBlock(g, idx));
        });
    } else {
        const block = el('div', 'quality-block');
        flat.forEach((ing) => {
            block.appendChild(renderIngredientLine(ing));
        });
        const affects = el('div', 'quality-affects');
        affects.appendChild(el('span', 'krt-bp-recipe-dash', '–'));
        block.appendChild(affects);
        recipeEl.appendChild(block);
    }
}

/**
 * Renders one requirement group with its ingredients and its quality slider.
 *
 * @param {BpRecipeGroup} group the requirement group
 * @param {number} groupIndex the group's position, matching the craftability groups
 * @returns {HTMLElement} the quality block
 */
function renderQualityBlock(group, groupIndex) {
    const block = el('div', 'quality-block');
    const slot = group.name || group.groupKey;
    if (slot) {
        block.appendChild(el('span', 'quality-source', slot));
    }

    const ings = group.ingredients || [];
    let firstIngredientName = '';
    if (ings.length > 0) {
        ings.forEach((ing, idx) => {
            if (idx === 0) {
                firstIngredientName = ing.name || '';
            }
            block.appendChild(renderIngredientLine(ing));
        });
    } else {
        block.appendChild(el('div', 'quality-name', '–'));
    }

    const mods = group.modifiers || [];
    const banded = mods.filter((m) => {
        return (
            m.effectiveQualityMin != null &&
            m.effectiveQualityMax != null &&
            m.effectiveQualityMax > m.effectiveQualityMin
        );
    });

    const affects = el('div', 'quality-affects');
    affects.appendChild(el('span', null, '→'));

    if (mods.length === 0) {
        affects.appendChild(el('span', 'krt-bp-recipe-dash', '–'));
        block.appendChild(affects);
        return block;
    }

    /** @type {{ modifier: BpRecipeModifier, out: HTMLElement }[]} */
    const chips = [];
    mods.forEach((m) => {
        const chip = el('span', 'chip');
        chip.appendChild(el('span', null, `${m.label || m.propertyKey || ''} `));
        const valueOut = el('output', null, '×?');
        chip.appendChild(valueOut);
        const bw = betterWhenText(m.betterWhen);
        if (bw) {
            chip.title = bw;
        }
        chips.push({ modifier: m, out: valueOut });
        affects.appendChild(chip);
    });

    if (banded.length > 0) {
        const qmin = Math.min.apply(
            null,
            /** @type {number[]} */ (
                banded.map((m) => {
                    return m.effectiveQualityMin;
                })
            ),
        );
        const qmax = Math.max.apply(
            null,
            /** @type {number[]} */ (
                banded.map((m) => {
                    return m.effectiveQualityMax;
                })
            ),
        );

        let defaultQ = qmax;
        const craftGroup =
            activeCraftability &&
            activeCraftability.groups &&
            activeCraftability.groups[groupIndex];
        if (craftGroup) {
            const eff = refineryOn
                ? craftGroup.effectiveQualityWithRefinery
                : craftGroup.effectiveQuality;
            if (eff != null) {
                defaultQ = Math.max(qmin, Math.min(qmax, eff));
            }
        }

        const qrow = el('div', 'quality-row');
        const range = document.createElement('input');
        range.type = 'range';
        range.step = '1';
        range.min = String(qmin);
        range.max = String(qmax);
        range.value = String(defaultQ);
        range.setAttribute(
            'aria-label',
            krtI18nText(i18n().qualityAria, 'krtBlueprintsRecipeI18n.qualityAria') +
                (firstIngredientName ? ` ${firstIngredientName}` : ''),
        );
        qrow.appendChild(range);
        const qval = el('span', 'quality-value');
        const qOut = el('output', null, String(Math.round(defaultQ)));
        qval.appendChild(qOut);
        const qMaxSmall = document.createElement('small');
        qMaxSmall.textContent = ` / ${Math.round(qmax)}`;
        qval.appendChild(qMaxSmall);
        qrow.appendChild(qval);
        block.appendChild(qrow);

        const hintParts = [];
        const firstBw = betterWhenText(mods[0] && mods[0].betterWhen);
        if (firstBw) {
            hintParts.push(firstBw);
        }
        if (banded[0] && banded[0].modifierAtMaxQuality != null) {
            hintParts.push(
                `${formatNumber(Math.round(qmax), 0, document.documentElement.lang)} → ×${formatNumber(banded[0].modifierAtMaxQuality, 2, document.documentElement.lang)}`,
            );
        }
        if (hintParts.length > 0) {
            const hint = document.createElement('small');
            hint.textContent = `(${hintParts.join(' · ')})`;
            affects.appendChild(hint);
        }

        function compute() {
            const q = parseFloat(range.value);
            qOut.textContent = String(Math.round(q));
            range.setAttribute('aria-valuetext', `${Math.round(q)} / ${Math.round(qmax)}`);
            chips.forEach((c) => {
                const value = computeModifierValue(c.modifier, q);
                c.out.textContent = `×${value == null ? '?' : formatNumber(value, 2, document.documentElement.lang)}`;
            });
        }
        range.addEventListener('input', compute);
        compute();
    } else {
        chips.forEach((c) => {
            const v = c.modifier.modifierAtMaxQuality;
            c.out.textContent =
                v == null ? '–' : `×${formatNumber(v, 2, document.documentElement.lang)}`;
        });
    }

    block.appendChild(affects);
    return block;
}

/**
 * Renders one ingredient line with its quantity and minimum quality.
 *
 * @param {BpRecipeIngredient} ing the ingredient
 * @returns {HTMLElement} the line
 */
function renderIngredientLine(ing) {
    const line = el('div', 'quality-name');
    const strong = document.createElement('strong');
    strong.textContent = ing.name || '?';
    line.appendChild(strong);
    const metaParts = [];
    if (ing.quantityScu != null) {
        if (ing.quantityType === 'PIECE') {
            metaParts.push(`${Math.round(Number(ing.quantityScu))} ${unitLabel('PIECE')}`);
        } else {
            metaParts.push(
                `${formatNumber(ing.quantityScu, 2, document.documentElement.lang)} ${unitLabel('SCU')}`,
            );
        }
    }
    if (ing.quantityUnits != null) {
        metaParts.push(`${ing.quantityUnits}x`);
    }
    if (ing.minQuality != null) {
        metaParts.push(
            `${krtI18nText(i18n().minQuality, 'krtBlueprintsRecipeI18n.minQuality')} ${
                ing.minQuality
            }`,
        );
    }
    if (metaParts.length > 0) {
        const small = document.createElement('small');
        small.textContent = ` · ${metaParts.join(' · ')}`;
        line.appendChild(small);
    }
    return line;
}

/**
 * The localized hint for which direction of a modifier is better.
 *
 * @param {string | null | undefined} bw `higher`, `lower` or `neutral`
 * @returns {string | null} the hint, or null for anything else
 */
function betterWhenText(bw) {
    if (bw === 'higher') {
        return krtI18nText(i18n().betterHigher, 'krtBlueprintsRecipeI18n.betterHigher');
    }
    if (bw === 'lower') {
        return krtI18nText(i18n().betterLower, 'krtBlueprintsRecipeI18n.betterLower');
    }
    if (bw === 'neutral') {
        return krtI18nText(i18n().betterNeutral, 'krtBlueprintsRecipeI18n.betterNeutral');
    }
    return null;
}

/**
 * Clamps a value to the unit interval.
 *
 * @param {number} t the value
 * @returns {number} the value between 0 and 1
 */
function clamp01(t) {
    return t < 0 ? 0 : t > 1 ? 1 : t;
}

/**
 * Linear interpolation.
 *
 * @param {number} a the value at 0
 * @param {number} b the value at 1
 * @param {number} t the position between 0 and 1
 * @returns {number} the interpolated value
 */
function lerp(a, b, t) {
    return a + (b - a) * t;
}

/**
 * Evaluates a quality modifier at a quality, over its segments or its min/max range.
 *
 * @param {BpRecipeModifier} m the modifier
 * @param {number} q the quality
 * @returns {number | null | undefined} the modifier value, null when the quality is out of range
 */
function computeModifierValue(m, q) {
    const segs = m.segments || [];
    if (segs.length > 0) {
        const stepped = (m.valueRangeType || 'linear').toLowerCase() !== 'linear';
        for (let i = 0; i < segs.length; i++) {
            const a = segs[i].qualityMin;
            const b = segs[i].qualityMax;
            const vs = segs[i].modifierAtStart;
            const ve = segs[i].modifierAtEnd;
            if (a == null || b == null) {
                continue;
            }
            if (q <= b || i === segs.length - 1) {
                if (stepped) {
                    return vs == null ? ve : vs;
                }
                const t = b === a ? 0 : clamp01((q - a) / (b - a));
                return vs == null || ve == null ? vs : lerp(vs, ve, t);
            }
        }
        return null;
    }
    const qmin = m.effectiveQualityMin;
    const qmax = m.effectiveQualityMax;
    const vmin = m.modifierAtMinQuality;
    const vmax = m.modifierAtMaxQuality;
    if (qmin != null && qmax != null && vmin != null && vmax != null) {
        const tt = qmax === qmin ? 0 : clamp01((q - qmin) / (qmax - qmin));
        return lerp(vmin, vmax, tt);
    }
    return null;
}

/**
 * Rounds an SCU amount to two decimals.
 *
 * @param {number | string | null | undefined} value the amount
 * @returns {string} the rounded amount, `0` when empty
 */
function fmtScu(value) {
    if (value == null) {
        return '0';
    }
    const n = Number(value);
    return (Math.round(n * 100) / 100).toString();
}

/**
 * The localized unit label of a quantity type.
 *
 * @param {string | null | undefined} quantityType `SCU` or `PIECE`
 * @returns {string} the unit label
 */
function unitLabel(quantityType) {
    return quantityType === 'PIECE'
        ? krtI18nText(i18n().unitPiece, 'krtBlueprintsRecipeI18n.unitPiece')
        : i18n().unitScu || 'SCU';
}

/**
 * Formats an amount in the unit of its quantity type.
 *
 * @param {number | string | null | undefined} value the amount
 * @param {string | null | undefined} quantityType `SCU` or `PIECE`
 * @returns {string} the formatted amount
 */
function fmtAmount(value, quantityType) {
    if (quantityType === 'PIECE') {
        return value == null ? '0' : String(Math.round(Number(value)));
    }
    return fmtScu(value);
}

function loadCraftability() {
    const url = endpoints().craftability;
    if (!url) {
        return;
    }
    const sep = url.indexOf('?') === -1 ? '?' : '&';
    const target = safeSameOriginUrl(`${url + sep}includeRefinery=true`, url);
    krtFetch
        .getJson(target)
        .catch(() => null)
        .then((list) => {
            craftabilityById.clear();
            if (Array.isArray(list)) {
                list.forEach((c) => {
                    if (c && c.blueprintId) {
                        craftabilityById.set(c.blueprintId, c);
                    }
                });
            }
            decorateRows();
            applyClientFilter();
            if (activeRow) {
                const id = attr(activeRow, 'data-id');
                activeCraftability = craftabilityById.get(id) || null;
                renderCraftDetail(id);
                if (recipeCache.has(id)) {
                    renderRecipe(recipeCache.get(id));
                }
            } else if (visibleRows().length > 0) {
                select(visibleRows()[0], {
                    showDetail: !window.matchMedia('(width <= 1024px)').matches,
                });
            }
        })
        .catch(() => {});
}

/**
 * Classifies one blueprint for the list dot, the count colour and the key-figure edge.
 *
 * @param {any} data the blueprint's craftability, or undefined before it loaded
 * @returns {'ok' | 'limited' | 'none' | 'na'} craftable, craftable with a caveat (only thanks
 *     to the refinery, or with unevaluated item ingredients), not craftable, or not evaluable
 */
function craftState(data) {
    if (!data || !data.recipeResolved || !data.hasResourceIngredients) {
        return 'na';
    }
    const count = refineryOn ? data.craftableWithRefinery : data.craftable;
    if (count <= 0) {
        return 'none';
    }
    if ((refineryOn && data.craftableWithRefinery > data.craftable) || data.hasItemIngredients) {
        return 'limited';
    }
    return 'ok';
}

/**
 * The craftable count under the current refinery setting.
 *
 * @param {any} data the blueprint's craftability
 * @returns {number} how many crafts the stock allows
 */
function craftCount(data) {
    return refineryOn ? data.craftableWithRefinery : data.craftable;
}

/**
 * Formats a craftable count as „3×".
 *
 * @param {number} count the craftable count
 * @returns {string} the formatted count
 */
function countText(count) {
    const tpl = krtI18nText(i18n().countTemplate, 'krtBlueprintsRecipeI18n.countTemplate');
    return tpl.replace('{0}', String(count));
}

/**
 * The state's spoken label, for the badge title.
 *
 * @param {'ok' | 'limited' | 'none' | 'na'} state the craft state
 * @returns {string} the label
 */
function stateLabel(state) {
    const dict = i18n();
    if (state === 'ok') {
        return krtI18nText(dict.craftableLabel, 'krtBlueprintsRecipeI18n.craftableLabel');
    }
    if (state === 'limited') {
        return krtI18nText(dict.limitedState, 'krtBlueprintsRecipeI18n.limitedState');
    }
    if (state === 'none') {
        return krtI18nText(dict.notCraftable, 'krtBlueprintsRecipeI18n.notCraftable');
    }
    return krtI18nText(dict.notEvaluated, 'krtBlueprintsRecipeI18n.notEvaluated');
}

function decorateRows() {
    rows().forEach((r) => {
        const id = attr(r, 'data-id');
        let aside = r.querySelector('.krt-bp-row-aside');
        if (!aside) {
            aside = el('span', 'krt-bp-row-aside');
            r.appendChild(aside);
        }
        let badge = /** @type {HTMLElement | null} */ (aside.querySelector('.krt-bp-craft-badge'));
        if (!badge) {
            badge = el('span', 'krt-bp-craft-badge');
            aside.appendChild(badge);
        }
        const data = craftabilityById.get(id);
        const state = craftState(data);
        r.setAttribute('data-craft', state);
        badge.textContent =
            state === 'ok' || state === 'limited' ? countText(craftCount(data)) : '–';
        badge.title = stateLabel(state);
        badge.setAttribute('aria-label', stateLabel(state));
    });
    updateCraftableCount();
}

/**
 * Writes the number of craftable blueprints into the „Craftbar" segment.
 *
 * @returns {void}
 */
function updateCraftableCount() {
    const countEl = document.querySelector(
        '[data-testid="segment-bpScope-craftable"] .krt-segmented__count',
    );
    if (!countEl) {
        return;
    }
    const craftable = rows().filter((r) => {
        return isRowCraftable(attr(r, 'data-id'));
    }).length;
    countEl.textContent = String(craftable);
}

/**
 * Builds the key-figure block of the detail pane: the craftable count, its state and what
 * limits it.
 *
 * @param {any} data the blueprint's craftability
 * @returns {HTMLElement} the block
 */
function renderKpi(data) {
    const dict = i18n();
    const state = craftState(data);
    const kpi = el('div', `krt-bp-kpi krt-bp-kpi--${state}`);
    kpi.setAttribute('data-testid', 'bp-craft-kpi');
    const body = el('span', 'krt-bp-kpi__body');
    if (state === 'na') {
        kpi.appendChild(el('span', 'krt-bp-kpi__value', '–'));
        body.appendChild(el('span', 'krt-bp-kpi__label', stateLabel(state)));
        const reason = !data.recipeResolved
            ? krtI18nText(dict.noRecipe, 'krtBlueprintsRecipeI18n.noRecipe')
            : krtI18nText(dict.itemNotEvaluated, 'krtBlueprintsRecipeI18n.itemNotEvaluated');
        body.appendChild(el('span', 'krt-bp-kpi__sub', reason));
        kpi.appendChild(body);
        return kpi;
    }
    const count = craftCount(data);
    kpi.appendChild(el('span', 'krt-bp-kpi__value', countText(count)));
    body.appendChild(
        el(
            'span',
            'krt-bp-kpi__label',
            count > 0
                ? krtI18nText(dict.craftableLabel, 'krtBlueprintsRecipeI18n.craftableLabel')
                : krtI18nText(dict.notCraftable, 'krtBlueprintsRecipeI18n.notCraftable'),
        ),
    );
    const parts = [];
    const limit = refineryOn ? data.limitingMaterialNameWithRefinery : data.limitingMaterialName;
    if (limit) {
        parts.push(`${krtI18nText(dict.limitedBy, 'krtBlueprintsRecipeI18n.limitedBy')} ${limit}`);
    }
    if (refineryOn) {
        parts.push(
            data.craftableWithRefinery > data.craftable && data.craftable === 0
                ? krtI18nText(dict.viaRefinery, 'krtBlueprintsRecipeI18n.viaRefinery')
                : krtI18nText(dict.refineryIncluded, 'krtBlueprintsRecipeI18n.refineryIncluded'),
        );
    }
    if (data.hasItemIngredients) {
        parts.push(krtI18nText(dict.itemHint, 'krtBlueprintsRecipeI18n.itemHint'));
    }
    if (parts.length > 0) {
        body.appendChild(el('span', 'krt-bp-kpi__sub', parts.join(' · ')));
    }
    kpi.appendChild(body);
    return kpi;
}

/**
 * Builds the ingredients table: per material the need of one craft, the qualifying stock and
 * its quality on a 0–1000 bar.
 *
 * @param {any} data the blueprint's craftability
 * @returns {HTMLElement | null} the section, or null when no material was evaluated
 */
function renderIngredients(data) {
    /** @type {ApiDto<'CraftabilityMaterialDto'>[]} */
    const materials = data.materials || [];
    if (materials.length === 0) {
        return null;
    }
    const dict = i18n();
    const limit = refineryOn ? data.limitingMaterialNameWithRefinery : data.limitingMaterialName;
    const section = el('section', 'krt-bp-detail-section');
    section.appendChild(
        el(
            'h3',
            'krt-bp-section-head',
            krtI18nText(dict.ingredients, 'krtBlueprintsRecipeI18n.ingredients'),
        ),
    );
    const table = el('table', 'data-table data-table--stack krt-bp-ing-table');
    table.setAttribute('data-testid', 'bp-ingredients');
    const head = el('thead');
    const headRow = el('tr');
    headRow.appendChild(
        el('th', null, krtI18nText(dict.colMaterial, 'krtBlueprintsRecipeI18n.colMaterial')),
    );
    headRow.appendChild(
        el('th', 'num', krtI18nText(dict.colNeed, 'krtBlueprintsRecipeI18n.colNeed')),
    );
    headRow.appendChild(
        el('th', 'num', krtI18nText(dict.colStock, 'krtBlueprintsRecipeI18n.colStock')),
    );
    const qualityHead = el(
        'th',
        'krt-bp-ing-quality',
        krtI18nText(dict.colQuality, 'krtBlueprintsRecipeI18n.colQuality'),
    );
    qualityHead.title = dict.qualityHint || '';
    headRow.appendChild(qualityHead);
    head.appendChild(headRow);
    table.appendChild(head);
    const body = el('tbody');
    materials.forEach((m) => {
        const avail = refineryOn ? m.availableScuWithRefinery : m.availableScu;
        const missing = (refineryOn ? m.missingScuWithRefinery : m.missingScu) ?? 0;
        const eff = refineryOn ? m.effectiveQualityWithRefinery : m.effectiveQuality;
        const qt = m.quantityType;
        const unit = ` ${unitLabel(qt)}`;
        const row = el('tr');
        row.appendChild(el('td', 'cell-title', m.materialName || '?'));
        const need = el('td', 'num', fmtAmount(m.requiredScu, qt) + unit);
        need.setAttribute('data-label', dict.colNeed || '');
        row.appendChild(need);
        let stockState = 'is-ok';
        if (missing > 0) {
            stockState = 'is-short';
        } else if (limit && m.materialName === limit) {
            stockState = 'is-limit';
        }
        const stock = el('td', `num krt-bp-ing-stock ${stockState}`, fmtAmount(avail, qt) + unit);
        stock.setAttribute('data-label', dict.colStock || '');
        if (missing > 0) {
            const missingText = krtI18nText(
                dict.missing,
                'krtBlueprintsRecipeI18n.missing',
            ).replace('{0}', fmtAmount(missing, qt) + unit);
            stock.appendChild(el('span', 'krt-bp-ing-missing', missingText));
        }
        row.appendChild(stock);
        const quality = el('td', 'krt-bp-ing-quality');
        quality.setAttribute('data-label', dict.colQuality || '');
        const wrap = el('span', 'krt-bp-quality');
        const meter = el('span', 'meter krt-bp-quality-meter');
        const fill = el('i', 'krt-bp-quality-fill');
        const percent = eff == null ? 0 : Math.max(0, Math.min(100, Number(eff) / 10));
        fill.setAttribute('data-krtm-width', String(percent));
        fill.style.width = `${percent}%`;
        meter.appendChild(fill);
        wrap.appendChild(meter);
        wrap.appendChild(
            el('span', 'krt-bp-quality-value', eff == null ? '–' : String(Math.round(eff))),
        );
        quality.appendChild(wrap);
        row.appendChild(quality);
        body.appendChild(row);
    });
    table.appendChild(body);
    section.appendChild(table);
    return section;
}

/**
 * Renders the craftability detail of a blueprint.
 *
 * @param {string} id the blueprint id
 */
function renderCraftDetail(id) {
    if (!detailCraftEl) {
        return;
    }
    clear(detailCraftEl);
    const data = craftabilityById.get(id);
    if (!data) {
        detailCraftEl.hidden = true;
        return;
    }
    detailCraftEl.hidden = false;
    detailCraftEl.appendChild(renderKpi(data));
    const ingredients = renderIngredients(data);
    if (ingredients) {
        detailCraftEl.appendChild(ingredients);
    }
}

const TOGGLE_PREF_KEY = 'personal_blueprints_toggles';
let togglesRestored = false;

function readTogglePref() {
    try {
        const raw = localStorage.getItem(TOGGLE_PREF_KEY);
        const parsed = raw === null ? null : JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (_e) {
        return null;
    }
}

function writeTogglePref() {
    try {
        localStorage.setItem(
            TOGGLE_PREF_KEY,
            JSON.stringify({ refinery: refineryOn, craftable: craftableOnly }),
        );
    } catch (_e) {}
}

/**
 * The „Craftbar" radio of the scope segment.
 *
 * @returns {HTMLInputElement | null} the radio, or null outside the member page
 */
function craftableRadio() {
    return /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="bpScope"][value="craftable"]')
    );
}

/**
 * The „Alle" radio of the scope segment.
 *
 * @returns {HTMLInputElement | null} the radio, or null outside the member page
 */
function allRadio() {
    return /** @type {HTMLInputElement | null} */ (
        document.querySelector('input[name="bpScope"][value=""]')
    );
}

function restoreToggles() {
    if (togglesRestored) {
        return;
    }
    togglesRestored = true;
    const saved = readTogglePref();
    if (!saved) {
        return;
    }
    if (refineryToggle && typeof saved.refinery === 'boolean') {
        refineryToggle.checked = saved.refinery;
    }
    if (typeof saved.craftable === 'boolean') {
        const target = saved.craftable ? craftableRadio() : allRadio();
        if (target) {
            target.checked = true;
        }
    }
}

function onRefineryToggle() {
    refineryOn = !!(refineryToggle && refineryToggle.checked);
    writeTogglePref();
    decorateRows();
    applyClientFilter();
    if (activeRow) {
        const id = attr(activeRow, 'data-id');
        renderCraftDetail(id);
        if (recipeCache.has(id)) {
            renderRecipe(recipeCache.get(id));
        }
    }
}

function onScopeChange() {
    const radio = craftableRadio();
    craftableOnly = !!(radio && radio.checked);
    writeTogglePref();
    applyClientFilter();
}

/**
 * Whether a blueprint is craftable, possibly with a caveat.
 *
 * @param {string} id the blueprint id
 * @returns {boolean} true for `ok` and `limited`
 */
function isRowCraftable(id) {
    const state = craftState(craftabilityById.get(id));
    return state === 'ok' || state === 'limited';
}

function applyClientFilter() {
    if (!rowsEl) {
        return;
    }
    const q = filterInput ? (filterInput.value || '').trim().toLowerCase() : '';
    let shown = 0;
    rows().forEach((r) => {
        const matchesSearch = q === '' || attr(r, 'data-name').toLowerCase().indexOf(q) !== -1;
        const matchesCraft = !craftableOnly || isRowCraftable(attr(r, 'data-id'));
        r.hidden = !(matchesSearch && matchesCraft);
        if (!r.hidden) {
            shown++;
        }
    });
    if (filterEmptyEl) {
        filterEmptyEl.hidden = shown > 0;
    }
}

/**
 * Moves the selection with the arrow, Home and End keys.
 *
 * @param {KeyboardEvent} e the key event
 */
function onListKeydown(e) {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') {
        return;
    }
    const vis = visibleRows();
    if (vis.length === 0) {
        return;
    }
    e.preventDefault();
    let idx = activeRow ? vis.indexOf(activeRow) : -1;
    if (e.key === 'ArrowDown') {
        idx = Math.min(vis.length - 1, idx + 1);
    } else if (e.key === 'ArrowUp') {
        idx = Math.max(0, idx - 1);
    } else if (e.key === 'Home') {
        idx = 0;
    } else {
        idx = vis.length - 1;
    }
    select(vis[idx], { focus: true, showDetail: false });
}

/**
 * Wires the toolbar outside the swapped list once: the search, the scope segment and the
 * refinery switch keep their listeners across list swaps.
 *
 * @returns {void}
 */
function wireToolbar() {
    filterInput = /** @type {HTMLInputElement | null} */ (document.getElementById('krt-bp-q'));
    refineryToggle = /** @type {HTMLInputElement | null} */ (
        document.getElementById('krt-bp-refinery-toggle')
    );
    restoreToggles();
    const radio = craftableRadio();
    craftableOnly = !!(radio && radio.checked);
    if (refineryToggle) {
        refineryOn = refineryToggle.checked;
        if (!refineryToggle.dataset.wired) {
            refineryToggle.addEventListener('change', onRefineryToggle);
            refineryToggle.dataset.wired = '1';
        }
    }
    document.querySelectorAll('input[name="bpScope"]').forEach((input) => {
        const scope = /** @type {HTMLInputElement} */ (input);
        if (!scope.dataset.wired) {
            scope.addEventListener('change', onScopeChange);
            scope.dataset.wired = '1';
        }
    });
    if (filterInput && !filterInput.dataset.wired) {
        filterInput.dataset.wired = '1';
        filterInput.addEventListener('input', applyClientFilter);
        const filterForm = filterInput.closest('form');
        if (filterForm) {
            filterForm.addEventListener('submit', (e) => {
                e.preventDefault();
                applyClientFilter();
            });
        }
    }
}

function init() {
    mdEl = document.getElementById('krt-bp-md');
    wireToolbar();
    if (!mdEl) {
        return;
    }
    rowsEl = document.getElementById('krt-bp-master-rows');
    filterEmptyEl = document.getElementById('krt-bp-filter-empty');
    detailEmpty = document.getElementById('krt-bp-detail-empty');
    detailContent = document.getElementById('krt-bp-detail-content');
    nameEl = document.getElementById('krt-bp-detail-name');
    acquiredEl = document.getElementById('krt-bp-detail-acquired');
    sourceEl = document.getElementById('krt-bp-detail-source');
    recipeEl = document.getElementById('krt-bp-detail-recipe');
    noteSection = document.getElementById('krt-bp-detail-note-section');
    noteEl = document.getElementById('krt-bp-detail-note');
    editBtn = document.getElementById('krt-bp-detail-edit');
    deleteBtn = document.getElementById('krt-bp-detail-delete');
    backBtn = document.getElementById('krt-bp-detail-back');
    detailCraftEl = document.getElementById('krt-bp-detail-craft');

    rows().forEach((r) => {
        r.tabIndex = -1;
        r.addEventListener('click', () => {
            select(r, { showDetail: true });
        });
    });
    if (rowsEl) {
        rowsEl.addEventListener('keydown', onListKeydown);
    }
    if (backBtn) {
        backBtn.addEventListener('click', () => {
            if (!mdEl) {
                return;
            }
            mdEl.classList.remove('is-detail');
            if (activeRow) {
                activeRow.focus();
            }
        });
    }
    applyClientFilter();

    /** @type {HTMLElement | null | undefined} */
    let initial = null;
    let fromDeeplink = false;
    try {
        const wanted = new URLSearchParams(window.location.search).get('bp');
        if (wanted && rowsEl) {
            initial = rows().find((r) => {
                return attr(r, 'data-id') === wanted;
            });
            fromDeeplink = initial != null;
        }
    } catch (_e) {}
    if (!initial) {
        initial = visibleRows()[0] || null;
    }
    if (initial) {
        const mobile = window.matchMedia('(width <= 1024px)').matches;
        select(initial, { showDetail: fromDeeplink || !mobile });
        if (!fromDeeplink && mobile) {
            mdEl.classList.remove('is-detail');
        }
    }

    loadCraftability();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}

document.addEventListener('krt:swapped', (e) => {
    const c = e.detail && e.detail.container;
    if (c && (c.id === 'krt-bp-list' || c.querySelector('#krt-bp-md'))) {
        activeRow = null;
        init();
    }
});
