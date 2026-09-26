// @ts-check
(function () {
    'use strict';

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

    const recipeCache = new Map();

    const craftabilityById = new Map();
    /** @type {HTMLElement | null} */
    let detailCraftEl = null;
    /** @type {HTMLInputElement | null} */
    let refineryToggle = null;
    let refineryOn = false;
    /** @type {any} */
    let activeCraftability = null;

    /** @type {HTMLInputElement | null} */
    let craftableToggle = null;
    let craftableOnly = false;

    const REFINERY_GLYPH = '⟢';

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

    function resolveUrl(template, id) {
        const raw = (template || '').replace('ID_PLACEHOLDER', encodeURIComponent(id));
        return window.safeSameOriginUrl ? window.safeSameOriginUrl(raw, raw) : raw;
    }

    function attr(row, name) {
        const v = row.getAttribute(name);
        return v == null || v === 'null' ? '' : v;
    }

    function formatAcquired(iso) {
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
        return new Intl.DateTimeFormat(undefined, {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            timeZone: 'Europe/Berlin',
        }).format(date);
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

    function visibleRows() {
        return rows().filter(function (r) {
            return !r.hidden;
        });
    }

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

        if (options.showDetail && mdEl && window.matchMedia('(max-width: 900px)').matches) {
            mdEl.classList.add('is-detail');
        }
    }

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

        nameEl.textContent = name;
        const formatted = formatAcquired(acquired);
        acquiredEl.textContent = formatted
            ? window.krtI18nText(i18n().acquiredLabel, 'krtBlueprintsRecipeI18n.acquiredLabel') +
              ' ' +
              formatted
            : '';

        [editBtn, deleteBtn].forEach(function (btn) {
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
                window.krtI18nText(i18n().loading, 'krtBlueprintsRecipeI18n.loading'),
            ),
        );
        fetch(resolveUrl(endpoints().recipe, id), {
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        })
            .then(function (resp) {
                return resp.ok ? resp.json() : null;
            })
            .then(function (recipe) {
                if (!recipe) {
                    showError();
                    return;
                }
                recipeCache.set(id, recipe);
                if (activeRow && attr(activeRow, 'data-id') === id) {
                    renderRecipe(recipe);
                }
            })
            .catch(function () {
                showError();
            });
    }

    function showError() {
        if (!recipeEl) {
            return;
        }
        clear(recipeEl);
        recipeEl.appendChild(
            el(
                'div',
                'krt-bp-recipe-error',
                window.krtI18nText(i18n().error, 'krtBlueprintsRecipeI18n.error'),
            ),
        );
    }

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
                    window.krtI18nText(i18n().empty, 'krtBlueprintsRecipeI18n.empty'),
                ),
            );
            return;
        }

        if (recipe.variantCount > 1) {
            const hint =
                recipe.variantCount +
                ' ' +
                window.krtI18nText(i18n().variants, 'krtBlueprintsRecipeI18n.variants') +
                ' · ' +
                window.krtI18nText(i18n().exampleRecipe, 'krtBlueprintsRecipeI18n.exampleRecipe');
            recipeEl.appendChild(el('p', 'krt-bp-recipe-variants', hint));
        }

        if (groups.length > 0) {
            const pane = recipeEl;
            groups.forEach(function (g, idx) {
                pane.appendChild(renderQualityBlock(g, idx));
            });
        } else {
            const block = el('div', 'quality-block');
            flat.forEach(function (ing) {
                block.appendChild(renderIngredientLine(ing));
            });
            const affects = el('div', 'quality-affects');
            affects.appendChild(el('span', 'krt-bp-recipe-dash', '–'));
            block.appendChild(affects);
            recipeEl.appendChild(block);
        }
    }

    function renderQualityBlock(group, groupIndex) {
        const block = el('div', 'quality-block');
        const slot = group.name || group.groupKey;
        if (slot) {
            block.appendChild(el('span', 'quality-source', slot));
        }

        const ings = group.ingredients || [];
        let firstIngredientName = '';
        if (ings.length > 0) {
            ings.forEach(function (ing, idx) {
                if (idx === 0) {
                    firstIngredientName = ing.name || '';
                }
                block.appendChild(renderIngredientLine(ing));
            });
        } else {
            block.appendChild(el('div', 'quality-name', '–'));
        }

        const mods = group.modifiers || [];
        const banded = mods.filter(function (m) {
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

        const chips = [];
        mods.forEach(function (m) {
            const chip = el('span', 'chip');
            chip.appendChild(el('span', null, (m.label || m.propertyKey || '') + ' '));
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
                banded.map(function (m) {
                    return m.effectiveQualityMin;
                }),
            );
            const qmax = Math.max.apply(
                null,
                banded.map(function (m) {
                    return m.effectiveQualityMax;
                }),
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
                window.krtI18nText(i18n().qualityAria, 'krtBlueprintsRecipeI18n.qualityAria') +
                    (firstIngredientName ? ' ' + firstIngredientName : ''),
            );
            qrow.appendChild(range);
            const qval = el('span', 'quality-value');
            const qOut = el('output', null, String(Math.round(defaultQ)));
            qval.appendChild(qOut);
            const qMaxSmall = document.createElement('small');
            qMaxSmall.textContent = ' / ' + Math.round(qmax);
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
                    Math.round(qmax) + ' → ×' + Number(banded[0].modifierAtMaxQuality).toFixed(2),
                );
            }
            if (hintParts.length > 0) {
                const hint = document.createElement('small');
                hint.textContent = '(' + hintParts.join(' · ') + ')';
                affects.appendChild(hint);
            }

            function compute() {
                const q = parseFloat(range.value);
                qOut.textContent = String(Math.round(q));
                range.setAttribute('aria-valuetext', Math.round(q) + ' / ' + Math.round(qmax));
                chips.forEach(function (c) {
                    const value = computeModifierValue(c.modifier, q);
                    c.out.textContent = '×' + (value == null ? '?' : value.toFixed(2));
                });
            }
            range.addEventListener('input', compute);
            compute();
        } else {
            chips.forEach(function (c) {
                const v = c.modifier.modifierAtMaxQuality;
                c.out.textContent = v == null ? '–' : '×' + Number(v).toFixed(2);
            });
        }

        block.appendChild(affects);
        return block;
    }

    function renderIngredientLine(ing) {
        const line = el('div', 'quality-name');
        const strong = document.createElement('strong');
        strong.textContent = ing.name || '?';
        line.appendChild(strong);
        const metaParts = [];
        if (ing.quantityScu != null) {
            if (ing.quantityType === 'PIECE') {
                metaParts.push(Math.round(Number(ing.quantityScu)) + ' ' + unitLabel('PIECE'));
            } else {
                metaParts.push(Number(ing.quantityScu).toFixed(2) + ' ' + unitLabel('SCU'));
            }
        }
        if (ing.quantityUnits != null) {
            metaParts.push(ing.quantityUnits + 'x');
        }
        if (ing.minQuality != null) {
            metaParts.push(
                window.krtI18nText(i18n().minQuality, 'krtBlueprintsRecipeI18n.minQuality') +
                    ' ' +
                    ing.minQuality,
            );
        }
        if (metaParts.length > 0) {
            const small = document.createElement('small');
            small.textContent = ' · ' + metaParts.join(' · ');
            line.appendChild(small);
        }
        return line;
    }

    function betterWhenText(bw) {
        if (bw === 'higher') {
            return window.krtI18nText(i18n().betterHigher, 'krtBlueprintsRecipeI18n.betterHigher');
        }
        if (bw === 'lower') {
            return window.krtI18nText(i18n().betterLower, 'krtBlueprintsRecipeI18n.betterLower');
        }
        if (bw === 'neutral') {
            return window.krtI18nText(
                i18n().betterNeutral,
                'krtBlueprintsRecipeI18n.betterNeutral',
            );
        }
        return null;
    }

    function clamp01(t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    function lerp(a, b, t) {
        return a + (b - a) * t;
    }

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

    function fmtScu(value) {
        if (value == null) {
            return '0';
        }
        const n = Number(value);
        return (Math.round(n * 100) / 100).toString();
    }

    function unitLabel(quantityType) {
        return quantityType === 'PIECE'
            ? window.krtI18nText(i18n().unitPiece, 'krtBlueprintsRecipeI18n.unitPiece')
            : i18n().unitScu || 'SCU';
    }

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
        const target = window.safeSameOriginUrl
            ? window.safeSameOriginUrl(url + sep + 'includeRefinery=true', url)
            : url + sep + 'includeRefinery=true';
        fetch(target, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
            .then(function (resp) {
                return resp.ok ? resp.json() : null;
            })
            .then(function (list) {
                craftabilityById.clear();
                if (Array.isArray(list)) {
                    list.forEach(function (c) {
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
                }
            })
            .catch(function () {});
    }

    function decorateRows() {
        rows().forEach(function (r) {
            const id = attr(r, 'data-id');
            let aside = r.querySelector('.krt-bp-row-aside');
            if (!aside) {
                aside = el('span', 'krt-bp-row-aside');
                r.appendChild(aside);
            }
            let badge = /** @type {HTMLElement | null} */ (
                aside.querySelector('.krt-bp-craft-badge')
            );
            if (!badge) {
                badge = el('span', 'krt-bp-craft-badge');
                aside.appendChild(badge);
            }
            clear(badge);
            badge.className = 'krt-bp-craft-badge';
            badge.removeAttribute('title');

            const data = craftabilityById.get(id);
            if (!data || !data.recipeResolved || !data.hasResourceIngredients) {
                badge.classList.add('is-muted');
                badge.textContent = '–';
                if (data && data.hasItemIngredients) {
                    badge.title = i18n().itemHint || '';
                }
                return;
            }
            const count = refineryOn ? data.craftableWithRefinery : data.craftable;
            if (count > 0) {
                badge.classList.add('is-ok');
                badge.textContent = '×' + count;
                if (refineryOn && data.craftableWithRefinery > data.craftable) {
                    badge.classList.add('is-ref');
                    const mark = document.createElement('span');
                    mark.textContent = ' ' + REFINERY_GLYPH;
                    badge.appendChild(mark);
                    badge.title = i18n().viaRefinery || '';
                }
            } else {
                badge.classList.add('is-missing');
                badge.textContent = window.krtI18nText(
                    i18n().badgeMissing,
                    'krtBlueprintsRecipeI18n.badgeMissing',
                );
            }
        });
    }

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
        detailCraftEl.appendChild(
            el(
                'h2',
                'section-title',
                window.krtI18nText(i18n().craftTitle, 'krtBlueprintsRecipeI18n.craftTitle'),
            ),
        );

        if (!data.recipeResolved) {
            detailCraftEl.appendChild(
                el(
                    'p',
                    'krt-bp-craft-note',
                    window.krtI18nText(i18n().noRecipe, 'krtBlueprintsRecipeI18n.noRecipe'),
                ),
            );
            return;
        }
        if (!data.hasResourceIngredients) {
            detailCraftEl.appendChild(
                el(
                    'p',
                    'krt-bp-craft-note',
                    window.krtI18nText(
                        i18n().itemNotEvaluated,
                        'krtBlueprintsRecipeI18n.itemNotEvaluated',
                    ),
                ),
            );
            return;
        }

        const count = refineryOn ? data.craftableWithRefinery : data.craftable;
        const summary = el('div', 'krt-bp-craft-summary');
        summary.appendChild(
            el(
                'span',
                'krt-bp-craft-label',
                window.krtI18nText(i18n().craftableLabel, 'krtBlueprintsRecipeI18n.craftableLabel'),
            ),
        );
        const countEl = el(
            'span',
            'krt-bp-craft-count chip ' + (count > 0 ? 'chip--success' : 'chip--warning'),
        );
        if (count > 0) {
            countEl.textContent = '×' + count;
            if (refineryOn && data.craftableWithRefinery > data.craftable) {
                const mark = el('span', 'krt-bp-craft-refmark', ' ' + REFINERY_GLYPH);
                mark.title = i18n().viaRefinery || '';
                countEl.appendChild(mark);
            }
        } else {
            countEl.textContent = window.krtI18nText(
                i18n().notCraftable,
                'krtBlueprintsRecipeI18n.notCraftable',
            );
        }
        summary.appendChild(countEl);
        const limit = refineryOn
            ? data.limitingMaterialNameWithRefinery
            : data.limitingMaterialName;
        if (count > 0 && limit) {
            summary.appendChild(
                el(
                    'span',
                    'krt-bp-craft-limit',
                    window.krtI18nText(i18n().limitedBy, 'krtBlueprintsRecipeI18n.limitedBy') +
                        ' ' +
                        limit,
                ),
            );
        }
        detailCraftEl.appendChild(summary);

        const materials = data.materials || [];
        if (materials.length > 0) {
            const list = el('div', 'krt-bp-craft-mats');
            materials.forEach(function (m) {
                const avail = refineryOn ? m.availableScuWithRefinery : m.availableScu;
                const missing = refineryOn ? m.missingScuWithRefinery : m.missingScu;
                const eff = refineryOn ? m.effectiveQualityWithRefinery : m.effectiveQuality;
                const qt = m.quantityType;
                const unit = unitLabel(qt);
                const row = el('div', 'krt-bp-craft-mat' + (missing > 0 ? ' is-short' : ''));
                row.appendChild(el('span', 'krt-bp-craft-mat-name', m.materialName || '?'));
                const figs = el('span', 'krt-bp-craft-mat-figs');
                figs.appendChild(
                    el(
                        'span',
                        'krt-bp-craft-mat-fig',
                        fmtAmount(avail, qt) + ' / ' + fmtAmount(m.requiredScu, qt) + ' ' + unit,
                    ),
                );
                figs.appendChild(
                    el(
                        'span',
                        'krt-bp-craft-mat-q',
                        eff != null
                            ? window.krtI18nText(
                                  i18n().qualityShort,
                                  'krtBlueprintsRecipeI18n.qualityShort',
                              ) +
                                  ' ' +
                                  Math.round(eff)
                            : '–',
                    ),
                );
                if (missing > 0) {
                    figs.appendChild(
                        el(
                            'span',
                            'krt-bp-craft-mat-missing',
                            '−' + fmtAmount(missing, qt) + ' ' + unit,
                        ),
                    );
                }
                row.appendChild(figs);
                list.appendChild(row);
            });
            detailCraftEl.appendChild(list);
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
        if (craftableToggle && typeof saved.craftable === 'boolean') {
            craftableToggle.checked = saved.craftable;
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

    function onCraftableToggle() {
        craftableOnly = !!(craftableToggle && craftableToggle.checked);
        writeTogglePref();
        applyClientFilter();
    }

    function isRowCraftable(id) {
        const data = craftabilityById.get(id);
        if (!data || !data.recipeResolved || !data.hasResourceIngredients) {
            return false;
        }
        const count = refineryOn ? data.craftableWithRefinery : data.craftable;
        return count > 0;
    }

    function applyClientFilter() {
        if (!rowsEl) {
            return;
        }
        const q = filterInput ? (filterInput.value || '').trim().toLowerCase() : '';
        rows().forEach(function (r) {
            const matchesSearch = q === '' || attr(r, 'data-name').toLowerCase().indexOf(q) !== -1;
            const matchesCraft = !craftableOnly || isRowCraftable(attr(r, 'data-id'));
            r.hidden = !(matchesSearch && matchesCraft);
        });
    }

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

    function init() {
        mdEl = document.getElementById('krt-bp-md');
        const craftToolbar = document.getElementById('krt-bp-craft-toolbar');
        if (craftToolbar) {
            craftToolbar.classList.toggle('is-empty', !mdEl);
        }
        if (!mdEl) {
            return;
        }
        rowsEl = document.getElementById('krt-bp-master-rows');
        filterInput = /** @type {HTMLInputElement | null} */ (document.getElementById('krt-bp-q'));
        detailEmpty = document.getElementById('krt-bp-detail-empty');
        detailContent = document.getElementById('krt-bp-detail-content');
        nameEl = document.getElementById('krt-bp-detail-name');
        acquiredEl = document.getElementById('krt-bp-detail-acquired');
        recipeEl = document.getElementById('krt-bp-detail-recipe');
        noteSection = document.getElementById('krt-bp-detail-note-section');
        noteEl = document.getElementById('krt-bp-detail-note');
        editBtn = document.getElementById('krt-bp-detail-edit');
        deleteBtn = document.getElementById('krt-bp-detail-delete');
        backBtn = document.getElementById('krt-bp-detail-back');
        detailCraftEl = document.getElementById('krt-bp-detail-craft');

        refineryToggle = /** @type {HTMLInputElement | null} */ (
            document.getElementById('krt-bp-refinery-toggle')
        );
        craftableToggle = /** @type {HTMLInputElement | null} */ (
            document.getElementById('krt-bp-craftable-toggle')
        );
        restoreToggles();
        if (refineryToggle) {
            refineryOn = /** @type {HTMLInputElement} */ (refineryToggle).checked;
            if (!refineryToggle.dataset.wired) {
                refineryToggle.addEventListener('change', onRefineryToggle);
                refineryToggle.dataset.wired = '1';
            }
        }
        if (craftableToggle) {
            craftableOnly = /** @type {HTMLInputElement} */ (craftableToggle).checked;
            if (!craftableToggle.dataset.wired) {
                craftableToggle.addEventListener('change', onCraftableToggle);
                craftableToggle.dataset.wired = '1';
            }
        }

        rows().forEach(function (r) {
            r.tabIndex = -1;
            r.addEventListener('click', function () {
                select(r, { showDetail: true });
            });
        });
        if (rowsEl) {
            rowsEl.addEventListener('keydown', onListKeydown);
        }
        if (filterInput) {
            filterInput.addEventListener('input', applyClientFilter);
            filterInput.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    applyClientFilter();
                }
            });
            const filterForm = filterInput.closest('form');
            if (filterForm) {
                filterForm.addEventListener('submit', function (e) {
                    e.preventDefault();
                    applyClientFilter();
                });
            }
        }
        if (backBtn) {
            backBtn.addEventListener('click', function () {
                if (!mdEl) {
                    return;
                }
                mdEl.classList.remove('is-detail');
                if (activeRow) {
                    activeRow.focus();
                }
            });
        }

        /** @type {Element | null | undefined} */
        let initial = null;
        let fromDeeplink = false;
        try {
            const wanted = new URLSearchParams(window.location.search).get('bp');
            if (wanted && rowsEl) {
                initial = rows().find(function (r) {
                    return attr(r, 'data-id') === wanted;
                });
                fromDeeplink = initial != null;
            }
        } catch (_e) {}
        if (!initial) {
            initial = rows()[0] || null;
        }
        if (initial) {
            const mobile = window.matchMedia('(max-width: 900px)').matches;
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

    document.addEventListener('krt:swapped', function (e) {
        const c = e.detail && e.detail.container;
        if (c && (c.id === 'krt-bp-list' || c.querySelector('#krt-bp-md'))) {
            activeRow = null;
            init();
        }
    });
})();
