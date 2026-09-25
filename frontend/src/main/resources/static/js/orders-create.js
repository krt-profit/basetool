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
    hint.className = 'scu-hint krtm-hidden';
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
    let qt = sel.dataset.quantityType || '';
    if (!qt && sel.tagName === 'SELECT') {
        const opt = sel.selectedOptions && sel.selectedOptions[0];
        qt = (opt && opt.getAttribute('data-quantity-type')) || '';
    }
    if (qt === 'PIECE') {
        amountInput.setAttribute('step', '1');
        if (unitSpan) unitSpan.textContent = '(' + MSG_UNIT_PIECE + ')';
        if (hint) hint.classList.add('krtm-hidden');
    } else if (qt === 'SCU') {
        amountInput.setAttribute('step', '0.001');
        if (unitSpan) unitSpan.textContent = '(' + MSG_UNIT_SCU + ')';
        if (hint) hint.classList.remove('krtm-hidden');
    } else {
        amountInput.setAttribute('step', '0.001');
        if (unitSpan) unitSpan.textContent = '';
        if (hint) hint.classList.add('krtm-hidden');
    }
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
    row.className = 'material-row';

    row.innerHTML = `
        <div class="form-group flex-2 mb-0">
            <label>${escapeHtml(MSG_MATERIAL_LABEL)}</label>
            <select name="materials[${escapeAttr(materialIndex)}].materialId" data-role="material-select" data-krt-combobox="remote-materials-joborder" required></select>
        </div>
        <div class="form-group flex-1 mb-0">
            <label data-role="amount-label">${escapeHtml(MSG_AMOUNT_LABEL)} <span data-role="amount-unit"></span></label>
            <input type="text" inputmode="decimal" data-scu-decimal name="materials[${escapeAttr(materialIndex)}].amount" value="" data-role="material-amount" step="0.001" min="0" required>
        </div>
        <div class="form-group flex-1 mb-0">
            <label>${escapeHtml(MSG_MINQUALITY_LABEL)}</label>
            <select name="materials[${escapeAttr(materialIndex)}].minQuality"></select>
        </div>
    `;
    const amountLabel = row.querySelector('[data-role="amount-label"]');
    if (amountLabel) {
        amountLabel.removeAttribute('data-role');
        amountLabel.appendChild(buildScuHint());
    }
    copyTemplateOptions(
        'minquality-options-template',
        row.querySelector('select[name$=".minQuality"]'),
    );

    container.appendChild(row);
    if (window.krtEnhanceComboboxes) {
        window.krtEnhanceComboboxes(row);
    }
    refreshMaterialUnit(row);
    materialIndex++;
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
}

function addItemLine(prefill) {
    prefill = prefill || {};
    const idx = itemLineIndex++;
    const container = document.getElementById('item-lines');
    if (!container) return null;
    const row = document.createElement('div');
    row.className = 'item-line';
    row.dataset.lineIndex = idx;
    row.style.cssText = 'border:1px solid var(--color-gray-3); padding:1rem; margin-bottom:1rem;';
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
        <div data-role="unresolved" class="hud-box hud-box-error oc-note-block krtm-hidden"></div>
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
    return row;
}

function clearDerived(row) {
    row.querySelector('[data-role="derived"]').innerHTML = '';
    const u = row.querySelector('[data-role="unresolved"]');
    u.classList.add('krtm-hidden');
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
                let goodSel = '';
                let noneSel = ' selected';
                if (storedQ === 'GOOD') {
                    goodSel = ' selected';
                    noneSel = '';
                }
                html += `
                    <div class="oc-material-line">
                        <input type="hidden" name="items[${escapeAttr(idx)}].materials[${escapeAttr(mi)}].materialId" value="${escapeAttr(mat.id)}">
                        <span class="flex-2">${escapeHtml(mat.name || '')}</span>
                        <span class="flex-1">${escapeHtml(qty)} ${escapeHtml(unit)}</span>
                        <select name="items[${escapeAttr(idx)}].materials[${escapeAttr(mi)}].quality" class="flex-1">
                            <option value="GOOD"${goodSel}>${escapeHtml(ITEM_I18N.qualityGood)}</option>
                            <option value="NONE"${noneSel}>${escapeHtml(ITEM_I18N.qualityNone)}</option>
                        </select>
                    </div>`;
            });
            derived.innerHTML = '';
            if ((d.materials || []).length) {
                derived.innerHTML = html;
            }
            if ((d.unresolvedIngredients || []).length) {
                unresolved.classList.remove('krtm-hidden');
                unresolved.innerHTML = `<p>${escapeHtml(ITEM_I18N.unresolved)} ${escapeHtml(d.unresolvedIngredients.map(String).join(', '))}</p>`;
            } else {
                unresolved.classList.add('krtm-hidden');
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

if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'orders-import-scmdb', importFromScmdb);
    window.krtEvents.on('click', 'orders-add-material', addMaterialRow);
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
