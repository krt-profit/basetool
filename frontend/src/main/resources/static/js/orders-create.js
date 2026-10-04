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

/* global materialIndex: writable, MSG_UNIT_SCU, MSG_UNIT_PIECE, MSG_MATERIAL_LABEL, MSG_AMOUNT_LABEL, MSG_MINQUALITY_LABEL, SCU_HINT_TEXT, MSG_SCMDB_SUCCESS, MSG_SCMDB_SOME_UNKNOWN, MSG_SCMDB_NO_MATCH, MSG_SCMDB_NOT_FOUND, ITEM_I18N, EDIT_ITEMS, MSG_MATERIAL_INVALID, MSG_ITEM_INVALID, MSG_CREATE_FAILED, MSG_UPDATE_FAILED, showFrontendErrorToast */

function buildScuHint() {
    const hint = document.createElement('span');
    hint.className = 'scu-hint';
    hint.hidden = true;
    hint.setAttribute('data-role', 'scu-hint');
    hint.setAttribute('tabindex', '0');
    hint.setAttribute('role', 'img');
    hint.setAttribute('aria-label', SCU_HINT_TEXT);
    const mark = document.createElement('span');
    mark.setAttribute('aria-hidden', 'true');
    mark.textContent = '?';
    const bubble = document.createElement('span');
    bubble.className = 'scu-hint__bubble';
    bubble.setAttribute('aria-hidden', 'true');
    bubble.textContent = SCU_HINT_TEXT;
    hint.appendChild(mark);
    hint.appendChild(bubble);
    return hint;
}

function copyTemplateOptions(templateId, target) {
    const tpl = document.getElementById(templateId);
    if (!tpl || !target) return;
    Array.from(tpl.children).forEach(function (opt) {
        target.appendChild(opt.cloneNode(true));
    });
}

/**
 * Fills an item material's quality select from the tier template; an inactive tier is offered
 * only when it is the stored choice (REQ-ORDERS-036).
 *
 * @param {HTMLSelectElement} select the select to fill
 * @param {string | null} selectedCode the code to preselect
 */
function fillQualityTierOptions(select, selectedCode) {
    const tpl = document.getElementById('item-quality-options-template');
    if (!tpl) return;
    Array.from(tpl.querySelectorAll('option')).forEach(function (opt) {
        const code = opt.value;
        const selected = code === selectedCode;
        if (opt.getAttribute('data-inactive') === 'true' && !selected) return;
        const option = document.createElement('option');
        option.value = code;
        option.textContent = opt.textContent || code;
        option.selected = selected;
        select.appendChild(option);
    });
}

/**
 * Shows or hides an SCU hint through the `hidden` attribute.
 *
 * @param {Element | null} hint the `.scu-hint` element
 * @param {boolean} visible whether the hint is shown
 */
function setHintVisible(hint, visible) {
    if (!hint) return;
    hint.classList.remove('is-hidden');
    /** @type {HTMLElement} */ (hint).hidden = !visible;
}

/**
 * Reads the quantity type of a material picker: the combobox mirrors it onto the hidden input,
 * a plain select carries it on the chosen option.
 *
 * @param {Element | null} sel the material picker
 * @returns {string} `SCU`, `PIECE` or an empty string
 */
function quantityTypeOf(sel) {
    if (!sel) return '';
    let qt = /** @type {HTMLElement} */ (sel).dataset.quantityType || '';
    if (!qt && sel.tagName === 'SELECT') {
        const opt = /** @type {HTMLSelectElement} */ (sel).selectedOptions[0];
        qt = (opt && opt.getAttribute('data-quantity-type')) || '';
    }
    return qt;
}

function refreshMaterialUnit(row) {
    if (!row) {
        return;
    }
    const sel = row.querySelector('[data-role="material-select"]');
    const amountInput = row.querySelector('[data-role="material-amount"]');
    const unitSpan = row.querySelector('[data-role="amount-unit"]');
    const hint = row.querySelector('.scu-hint');
    if (!sel || !amountInput) {
        return;
    }
    const qt = quantityTypeOf(sel);
    if (qt === 'PIECE') {
        amountInput.setAttribute('step', '1');
        if (unitSpan) unitSpan.textContent = MSG_UNIT_PIECE;
        setHintVisible(hint, false);
    } else if (qt === 'SCU') {
        amountInput.setAttribute('step', '0.001');
        if (unitSpan) unitSpan.textContent = MSG_UNIT_SCU;
        setHintVisible(hint, true);
    } else {
        amountInput.setAttribute('step', '0.001');
        if (unitSpan) unitSpan.textContent = '';
        setHintVisible(hint, false);
    }
    updateMaterialSummary();
}

/**
 * Parses a typed amount with either decimal separator.
 *
 * @param {string} raw the field value
 * @returns {number} the amount, or NaN when it is not a number
 */
function parseAmount(raw) {
    if (window.krtScuInput) return window.krtScuInput.parse(raw);
    return parseFloat(String(raw).replace(',', '.'));
}

/**
 * Formats a number for the summary in the page language.
 *
 * @param {number} value the number
 * @returns {string} the formatted number with at most three decimals
 */
function formatSummaryNumber(value) {
    return value.toLocaleString(document.documentElement.lang || undefined, {
        maximumFractionDigits: 3,
    });
}

/**
 * Returns the name of the unit chosen in an org-unit select, or an empty string.
 *
 * @param {string} selectId the select's id
 * @returns {string} the selected option's text
 */
function selectedUnitName(selectId) {
    const sel = /** @type {HTMLSelectElement | null} */ (document.getElementById(selectId));
    if (!sel || !sel.value) return '';
    const opt = sel.selectedOptions[0];
    return opt ? (opt.textContent || '').trim() : '';
}

/**
 * Writes a live summary line: the count phrase, an optional emphasised quantity, and the
 * requesting unit; the phrases come from the element's `data-one`, `data-other` and `data-for`.
 *
 * @param {HTMLElement} el the `.form-actions__summary` element
 * @param {number} count the number of filled lines
 * @param {string} strong the emphasised quantity, or an empty string
 * @param {string} unitName the requesting unit's name, or an empty string
 */
function renderSummary(el, count, strong, unitName) {
    const pattern = (count === 1 ? el.dataset.one : el.dataset.other) || '{0}';
    const parts = [];
    parts.push(document.createTextNode(pattern.replace('{0}', formatSummaryNumber(count))));
    if (strong) {
        const s = document.createElement('strong');
        s.textContent = strong;
        parts.push(s);
    }
    if (unitName) {
        parts.push(document.createTextNode((el.dataset.for || '{0}').replace('{0}', unitName)));
    }
    el.replaceChildren();
    parts.forEach(function (part, i) {
        if (i > 0) el.appendChild(document.createTextNode(' · '));
        el.appendChild(part);
    });
}

/** Recomputes the material form's summary: filled lines, their SCU total and the requester. */
function updateMaterialSummary() {
    const el = document.getElementById('orders-material-summary');
    const container = document.getElementById('materials-container');
    if (!el || !container) return;
    let count = 0;
    let scu = 0;
    container.querySelectorAll('.material-row').forEach(function (row) {
        const sel = row.querySelector('[data-role="material-select"]');
        if (!sel || !(/** @type {HTMLInputElement} */ (sel).value)) return;
        count++;
        if (quantityTypeOf(sel) !== 'SCU') return;
        const amount = row.querySelector('[data-role="material-amount"]');
        const value = amount ? parseAmount(/** @type {HTMLInputElement} */ (amount).value) : NaN;
        if (!isNaN(value)) scu += value;
    });
    const scuText = scu > 0 ? formatSummaryNumber(scu) + ' ' + (el.dataset.unit || '') : '';
    renderSummary(el, count, scuText.trim(), selectedUnitName('requestingOrgUnitId'));
}

/** Recomputes the item form's summary: lines with a chosen item and the requester. */
function updateItemSummary() {
    const el = document.getElementById('orders-item-summary');
    const container = document.getElementById('item-lines');
    if (!el || !container) return;
    let count = 0;
    container.querySelectorAll('.item-line').forEach(function (row) {
        const sel = row.querySelector('[data-role="item-select"]');
        if (sel && /** @type {HTMLInputElement} */ (sel).value) count++;
    });
    renderSummary(el, count, '', selectedUnitName('item-requestingOrgUnitId'));
}

/**
 * Enables a material row's remove button only while more than one row exists.
 */
function syncMaterialRemoveButtons() {
    const container = document.getElementById('materials-container');
    if (!container) return;
    const buttons = container.querySelectorAll('[data-trigger="orders-remove-material"]');
    buttons.forEach(function (btn) {
        /** @type {HTMLButtonElement} */ (btn).disabled = buttons.length <= 1;
    });
}

/**
 * Renames every row's fields to consecutive `materials[i]` indexes, so the bound list has no gap,
 * and continues new rows after the last one.
 */
function renumberMaterialRows() {
    const container = document.getElementById('materials-container');
    if (!container) return;
    const rows = container.querySelectorAll('.material-row');
    rows.forEach(function (row, i) {
        row.querySelectorAll('[name^="materials["]').forEach(function (field) {
            const name = field.getAttribute('name') || '';
            field.setAttribute('name', name.replace(/^materials\[\d+\]/, 'materials[' + i + ']'));
        });
    });
    materialIndex = rows.length;
}

/**
 * Removes the material row of the clicked remove button while another row remains.
 *
 * @param {Element} btn the clicked remove button
 */
function removeMaterialRow(btn) {
    const container = document.getElementById('materials-container');
    const row = btn.closest('.material-row');
    if (!container || !row || container.querySelectorAll('.material-row').length <= 1) return;
    row.remove();
    renumberMaterialRows();
    syncMaterialRemoveButtons();
    updateMaterialSummary();
}

/**
 * Updates the `n / max` counter named by a textarea's `data-counter`.
 *
 * @param {HTMLTextAreaElement} area the textarea
 */
function updateCounter(area) {
    const counter = document.getElementById(area.dataset.counter || '');
    if (!counter) return;
    const max = area.maxLength > 0 ? area.maxLength : 1000;
    counter.textContent = area.value.length + ' / ' + max;
}

async function findJobOrderMaterialByName(normalizedName) {
    try {
        const res = await fetch(
            '/catalog/material-search?jobOrder=true&q=' + encodeURIComponent(normalizedName),
            { headers: { Accept: 'application/json' } },
        );
        if (!res.ok) {
            return null;
        }
        const list = (await res.json()) || [];
        return list.find((m) => (m.name || '').trim().toLowerCase() === normalizedName) || null;
    } catch (_e) {
        return null;
    }
}

async function importFromScmdb() {
    const text = document.getElementById('scmdb-import-text').value;
    const lines = text.split('\n');

    let foundAny = false;
    const unknownMaterials = [];

    const scuRegex = /⛏\s*(.+?)\s*[—–-]\s*([\d,.]+)\s*SCU/i;
    const pieceRegex = /💎\s*(.+?)\s*[×x*]\s*([\d,.]+)/i;

    for (let line of lines) {
        line = line.trim();
        if (!line) continue;

        const match = line.match(scuRegex) || line.match(pieceRegex);

        if (match) {
            const materialName = match[1].trim().toLowerCase();
            const amount = parseFloat(match[2].replace(',', '.'));
            if (isNaN(amount)) continue;

            const material = await findJobOrderMaterialByName(materialName);

            if (material) {
                const container = document.getElementById('materials-container');
                let targetRow = null;

                if (!foundAny) {
                    const rows = container.getElementsByClassName('material-row');
                    if (rows.length > 0) {
                        const firstSelect = rows[0].querySelector('[data-role="material-select"]');
                        if (firstSelect && !firstSelect.value) {
                            targetRow = rows[0];
                        }
                    }
                }

                if (!targetRow) {
                    addMaterialRow();
                    targetRow = container.lastElementChild;
                }

                const matField = targetRow.querySelector('[data-role="material-select"]');
                const amountInput = targetRow.querySelector('[data-role="material-amount"]');

                if (matField.krtCombobox) {
                    matField.krtCombobox.setValue(material.id, material.name, {
                        quantityType: material.quantityType || '',
                    });
                } else {
                    matField.value = material.id;
                }
                amountInput.value = amount;
                refreshMaterialUnit(targetRow);

                foundAny = true;
            } else {
                unknownMaterials.push(match[1].trim());
            }
        }
    }

    if (foundAny) {
        document.getElementById('scmdb-import-text').value = '';
        syncMaterialRemoveButtons();
        updateMaterialSummary();
        if (window.krtModal) {
            window.krtModal.close('orders-scmdb-modal');
        }
        let successMsg = MSG_SCMDB_SUCCESS;
        if (unknownMaterials.length > 0) {
            successMsg += ' (' + MSG_SCMDB_SOME_UNKNOWN + ': ' + unknownMaterials.join(', ') + ')';
        }

        if (window.showFrontendSuccessToast) {
            window.showFrontendSuccessToast(successMsg);
        }
    } else {
        let errorMsg = MSG_SCMDB_NO_MATCH;
        if (unknownMaterials.length > 0) {
            errorMsg = MSG_SCMDB_NOT_FOUND + ': ' + unknownMaterials.join(', ');
        }

        if (window.showFrontendErrorToast) {
            window.showFrontendErrorToast(errorMsg);
        }
    }
}

function addMaterialRow() {
    const container = document.getElementById('materials-container');

    const row = document.createElement('div');
    row.className = 'material-row material-grid';
    const removeLabel = container.dataset.removeLabel || '';

    row.innerHTML = `
        <div class="form-group">
            <label class="visually-hidden">${escapeHtml(MSG_MATERIAL_LABEL)}</label>
            <select name="materials[${escapeAttr(materialIndex)}].materialId" data-role="material-select" data-krt-combobox="remote-materials-joborder" required></select>
        </div>
        <div class="form-group material-row__amount">
            <input type="text" inputmode="decimal" data-scu-decimal name="materials[${escapeAttr(materialIndex)}].amount" value="" data-role="material-amount" step="0.001" min="0" required aria-label="${escapeAttr(MSG_AMOUNT_LABEL)}">
            <span class="material-row__unit" data-role="amount-unit"></span>
        </div>
        <div class="form-group">
            <select name="materials[${escapeAttr(materialIndex)}].minQuality" aria-label="${escapeAttr(MSG_MINQUALITY_LABEL)}"></select>
        </div>
        <button type="button" class="btn btn-ghost btn-icon material-row__remove" data-trigger="orders-remove-material" data-testid="order-material-remove" aria-label="${escapeAttr(removeLabel)}" title="${escapeAttr(removeLabel)}"><svg class="krt-icon" aria-hidden="true"><use href="#krt-icon-trash"/></svg></button>
    `;
    const amountCell = row.querySelector('.material-row__amount');
    if (amountCell) {
        amountCell.appendChild(buildScuHint());
    }
    copyTemplateOptions(
        'minquality-options-template',
        row.querySelector('select[name$=".minQuality"]'),
    );

    container.appendChild(row);
    if (window.krtEnhanceComboboxes) {
        window.krtEnhanceComboboxes(row);
    }
    materialIndex++;
    syncMaterialRemoveButtons();
    refreshMaterialUnit(row);
}

let itemLineIndex = 0;

function fetchItemOptions(query) {
    return fetch('/orders/item-search?q=' + encodeURIComponent(query || ''), {
        headers: { Accept: 'application/json' },
    })
        .then(function (r) {
            return r.ok ? r.json() : [];
        })
        .then(function (list) {
            return (list || []).map(function (gi) {
                return { value: gi.id, label: gi.name };
            });
        })
        .catch(function () {
            return [];
        });
}

function setFormDisabled(containerId, disabled) {
    const c = document.getElementById(containerId);
    if (!c) return;
    c.querySelectorAll('input, select, textarea, button').forEach((el) => {
        el.disabled = disabled;
    });
}

function toggleOrderMode() {
    const checked = document.querySelector('input[name="orderModeToggle"]:checked');
    const mode = checked ? checked.value : 'material';
    document.getElementById('mode-material').hidden = mode !== 'material';
    document.getElementById('mode-item').hidden = mode !== 'item';
    setFormDisabled('mode-material', mode !== 'material');
    setFormDisabled('mode-item', mode !== 'item');
    if (mode === 'material') {
        syncMaterialRemoveButtons();
    }
}

function addItemLine(prefill) {
    prefill = prefill || {};
    const idx = itemLineIndex++;
    const container = document.getElementById('item-lines');
    if (!container) return null;
    const row = document.createElement('div');
    row.className = 'item-line';
    row.dataset.lineIndex = idx;
    const manufactured = Number(prefill.manufactured) || 0;
    const minAmount = manufactured > 0 ? manufactured : 1;
    let idInput = '';
    if (prefill.id) {
        idInput = `<input type="hidden" name="items[${escapeAttr(idx)}].id" value="${escapeAttr(prefill.id)}">`;
    }
    let removeButton = '';
    let producedNote = '';
    if (manufactured > 0) {
        producedNote = `<p class="oc-note-block text-muted mb-0" data-role="produced-note">${escapeHtml(ITEM_I18N.producedLocked.replace('{0}', String(manufactured)))}</p>`;
    } else {
        removeButton = `<button type="button" class="btn btn-quiet-danger mb-0 nowrap" data-trigger="orders-remove-item">${escapeHtml(ITEM_I18N.remove)}</button>`;
    }
    row.innerHTML = `
        ${idInput}
        <input type="hidden" name="items[${escapeAttr(idx)}].clientLineId" value="${escapeAttr(idx)}">
        <input type="hidden" name="items[${escapeAttr(idx)}].parentClientLineId" value="${escapeAttr(prefill.parentId != null ? prefill.parentId : '')}">
        <div class="oc-line-fields">
            <div class="form-group flex-2 mb-0">
                <label>${escapeHtml(ITEM_I18N.item)}</label>
                <select name="items[${escapeAttr(idx)}].gameItemId" data-role="item-select" data-testid="order-item-combobox" required></select>
            </div>
            <div class="form-group flex-1 mb-0" data-role="blueprint-wrap" hidden>
                <label>${escapeHtml(ITEM_I18N.blueprint)}</label>
                <select name="items[${escapeAttr(idx)}].blueprintId" data-role="blueprint-select"></select>
            </div>
            <div class="form-group flex-1 mb-0">
                <label>${escapeHtml(ITEM_I18N.amount)}</label>
                <input type="number" step="1" name="items[${escapeAttr(idx)}].amount" data-role="amount" min="${escapeAttr(minAmount)}" value="${escapeAttr(prefill.amount || 1)}" required>
            </div>
            ${removeButton}
        </div>
        ${producedNote}
        <div data-role="derived" class="oc-derived-block"></div>
        <div data-role="unresolved" class="alert alert-danger oc-note-block" hidden></div>
        <div data-role="subassemblies" class="oc-note-block"></div>
    `;
    copyTemplateOptions('item-options-template', row.querySelector('[data-role="item-select"]'));
    container.appendChild(row);
    const itemSelect = row.querySelector('select[data-role="item-select"]');
    if (itemSelect && prefill.gameItemId) {
        const seeded = document.createElement('option');
        seeded.value = prefill.gameItemId;
        seeded.textContent = prefill.gameItemName || prefill.gameItemId;
        seeded.selected = true;
        itemSelect.appendChild(seeded);
        itemSelect.value = prefill.gameItemId;
    }
    if (itemSelect && window.krtSearchableSelect) {
        window.krtSearchableSelect(itemSelect, {
            placeholder: ITEM_I18N.searchPlaceholder,
            noResultsText: ITEM_I18N.searchNoResults,
            hintText: ITEM_I18N.searchHint,
            invalidText: ITEM_I18N.searchInvalid,
            loadingText: ITEM_I18N.searchLoading,
            remoteSource: fetchItemOptions,
        });
    }
    if (prefill.gameItemId) {
        loadBlueprints(row, prefill.blueprintId, prefill.qualities);
    }
    updateItemSummary();
    return row;
}

function clearDerived(row) {
    row.querySelector('[data-role="derived"]').innerHTML = '';
    const u = row.querySelector('[data-role="unresolved"]');
    u.hidden = true;
    u.innerHTML = '';
    row.querySelector('[data-role="subassemblies"]').innerHTML = '';
}

function loadBlueprints(row, preselectBpId, qualities) {
    const gameItemId = row.querySelector('[data-role="item-select"]').value;
    const wrap = row.querySelector('[data-role="blueprint-wrap"]');
    const bpSelect = row.querySelector('[data-role="blueprint-select"]');
    clearDerived(row);
    if (!gameItemId) {
        wrap.hidden = true;
        bpSelect.innerHTML = '';
        return;
    }
    fetch('/orders/item-blueprints/' + encodeURIComponent(gameItemId), {
        headers: { Accept: 'application/json' },
    })
        .then((r) => (r.ok ? r.json() : []))
        .then((list) => {
            list = list || [];
            bpSelect.replaceChildren(
                ...list.map((b) => {
                    const opt = document.createElement('option');
                    opt.value = b.id;
                    opt.textContent = String(b.outputName || b.scwikiKey || b.id);
                    return opt;
                }),
            );
            wrap.hidden = list.length <= 1;
            if (list.length > 0) {
                const pick =
                    preselectBpId && list.some((b) => b.id === preselectBpId)
                        ? preselectBpId
                        : list[0].id;
                bpSelect.value = pick;
                loadDerivation(row, qualities);
            }
        })
        .catch(() => {
            wrap.hidden = true;
        });
}

function loadDerivation(row, qualities) {
    const blueprintId = row.querySelector('[data-role="blueprint-select"]').value;
    const amount = parseInt(row.querySelector('[data-role="amount"]').value, 10) || 1;
    if (!blueprintId) {
        clearDerived(row);
        return;
    }
    const idx = row.dataset.lineIndex;
    const derived = row.querySelector('[data-role="derived"]');
    const unresolved = row.querySelector('[data-role="unresolved"]');
    const subs = row.querySelector('[data-role="subassemblies"]');
    fetch('/orders/item-derivation/' + encodeURIComponent(blueprintId) + '?amount=' + amount, {
        headers: { Accept: 'application/json' },
    })
        .then((r) => (r.ok ? r.json() : null))
        .then((d) => {
            if (!d) {
                clearDerived(row);
                return;
            }
            let html = `<strong class="oc-label-strong">${escapeHtml(ITEM_I18N.materialsTitle)}</strong>`;
            (d.materials || []).forEach((m, mi) => {
                const mat = m.material || {};
                const unit = mat.quantityType === 'PIECE' ? MSG_UNIT_PIECE : MSG_UNIT_SCU;
                const qty =
                    mat.quantityType === 'PIECE'
                        ? Math.round(m.requiredQuantity || 0)
                        : Number((m.requiredQuantity || 0).toFixed(3));
                const storedQ =
                    qualities && mat.id && qualities[mat.id] ? qualities[mat.id] : m.defaultQuality;
                html += `
                    <div class="oc-material-line">
                        <input type="hidden" name="items[${escapeAttr(idx)}].materials[${escapeAttr(mi)}].materialId" value="${escapeAttr(mat.id)}">
                        <span class="flex-2">${escapeHtml(mat.name || '')}</span>
                        <span class="flex-1">${escapeHtml(qty)} ${escapeHtml(unit)}</span>
                        <select name="items[${escapeAttr(idx)}].materials[${escapeAttr(mi)}].quality" class="flex-1" data-quality-code="${escapeAttr(storedQ || '')}"></select>
                    </div>`;
            });
            derived.innerHTML = '';
            if ((d.materials || []).length) {
                derived.innerHTML = html;
                derived.querySelectorAll('select[data-quality-code]').forEach(function (sel) {
                    fillQualityTierOptions(
                        /** @type {HTMLSelectElement} */ (sel),
                        sel.getAttribute('data-quality-code'),
                    );
                });
            }
            if ((d.unresolvedIngredients || []).length) {
                unresolved.hidden = false;
                unresolved.innerHTML = `<p>${escapeHtml(ITEM_I18N.unresolved)} ${escapeHtml(d.unresolvedIngredients.map(String).join(', '))}</p>`;
            } else {
                unresolved.hidden = true;
                unresolved.innerHTML = '';
            }
            let s = '';
            if ((d.subAssemblies || []).length) {
                s = `<strong class="oc-label-strong">${escapeHtml(ITEM_I18N.subTitle)}</strong>`;
                d.subAssemblies.forEach((sa) => {
                    const gi = sa.gameItem || {};
                    s += `<div class="oc-material-line">
                        <span class="flex-2">${escapeHtml(gi.name || '')} &times; ${escapeHtml(sa.quantity)}</span>
                        <button type="button" class="btn btn-ghost mb-0" data-trigger="orders-adopt-sub" data-game-item-id="${escapeAttr(gi.id)}" data-game-item-name="${escapeAttr(gi.name || '')}" data-amount="${escapeAttr(sa.quantity)}" data-parent="${escapeAttr(idx)}">${escapeHtml(ITEM_I18N.subAdopt)}</button>
                    </div>`;
                });
                subs.innerHTML = s;
            } else {
                subs.innerHTML = '';
            }
        })
        .catch(() => clearDerived(row));
}

const itemLinesContainer = document.getElementById('item-lines');
if (itemLinesContainer) {
    itemLinesContainer.addEventListener('change', (e) => {
        const row = e.target.closest('.item-line');
        if (!row) return;
        if (e.target.matches('[data-role="item-select"]')) {
            loadBlueprints(row);
        } else if (e.target.matches('[data-role="blueprint-select"]')) {
            loadDerivation(row);
        } else if (e.target.matches('[data-role="amount"]')) {
            loadDerivation(row);
        }
    });
    itemLinesContainer.addEventListener('click', (e) => {
        const removeBtn = e.target.closest('[data-trigger="orders-remove-item"]');
        if (removeBtn) {
            const row = removeBtn.closest('.item-line');
            if (row) {
                row.remove();
                updateItemSummary();
            }
            return;
        }
        const adoptBtn = e.target.closest('[data-trigger="orders-adopt-sub"]');
        if (adoptBtn) {
            addItemLine({
                gameItemId: adoptBtn.dataset.gameItemId,
                gameItemName: adoptBtn.dataset.gameItemName,
                amount: parseInt(adoptBtn.dataset.amount, 10) || 1,
                parentId: adoptBtn.dataset.parent,
            });
        }
    });
    if (Array.isArray(EDIT_ITEMS) && EDIT_ITEMS.length) {
        EDIT_ITEMS.forEach((line) => addItemLine(line));
    } else {
        addItemLine();
    }
}
document
    .querySelectorAll('input[name="orderModeToggle"]')
    .forEach((r) => r.addEventListener('change', toggleOrderMode));
toggleOrderMode();

const materialsContainerEl = document.getElementById('materials-container');
if (materialsContainerEl) {
    materialsContainerEl.addEventListener('change', (e) => {
        if (e.target && e.target.matches && e.target.matches('[data-role="material-select"]')) {
            refreshMaterialUnit(e.target.closest('.material-row'));
        }
    });
    materialsContainerEl.querySelectorAll('.material-row').forEach(refreshMaterialUnit);
}

const materialModeEl = document.getElementById('mode-material');
if (materialModeEl) {
    materialModeEl.addEventListener('input', updateMaterialSummary);
    materialModeEl.addEventListener('change', updateMaterialSummary);
}
const itemModeEl = document.getElementById('mode-item');
if (itemModeEl) {
    itemModeEl.addEventListener('change', updateItemSummary);
}
document.querySelectorAll('textarea[data-counter]').forEach(function (area) {
    const textarea = /** @type {HTMLTextAreaElement} */ (area);
    textarea.addEventListener('input', function () {
        updateCounter(textarea);
    });
    updateCounter(textarea);
});
updateMaterialSummary();
updateItemSummary();

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'orders-import-scmdb', importFromScmdb);
    window.krtEvents.on('click', 'orders-add-material', addMaterialRow);
    window.krtEvents.on('click', 'orders-remove-material', removeMaterialRow);
    window.krtEvents.on('click', 'orders-add-item', () => addItemLine());
}

function _submitOrderCreate(form, invalidMessage, failedMessage, submitter) {
    if (!window.krtFetch) {
        form.submit();
        return;
    }
    window.krtFetch.submitForm({
        form,
        submitter,
        toast: false,
        errorMessage: failedMessage,
        onError(status) {
            showFrontendErrorToast(status === 400 ? invalidMessage : failedMessage);
            return true;
        },
        onSuccess(body) {
            if (body && body.targetUrl) {
                window.location.assign(body.targetUrl);
            } else {
                window.location.reload();
            }
        },
    });
}
const _materialCreateForm = document.querySelector('#mode-material form');
if (_materialCreateForm) {
    _materialCreateForm.addEventListener('submit', (e) => {
        e.preventDefault();
        _submitOrderCreate(
            _materialCreateForm,
            MSG_MATERIAL_INVALID,
            MSG_CREATE_FAILED,
            e.submitter,
        );
    });
}
const _itemCreateForm = document.querySelector('#mode-item form');
if (_itemCreateForm) {
    _itemCreateForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const failed =
            _itemCreateForm.getAttribute('action').indexOf('/items/update') >= 0
                ? MSG_UPDATE_FAILED
                : MSG_CREATE_FAILED;
        _submitOrderCreate(_itemCreateForm, MSG_ITEM_INVALID, failed, e.submitter);
    });
}
