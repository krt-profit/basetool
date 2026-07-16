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
 * Job-order create/edit page module (/orders/create, /orders/items/edit), extracted verbatim from
 * the former inline script of orders-create.html (ADR-0069, follow-up to #924).
 *
 * Drives both order modes: the material-order editor (quantity-type-aware rows, SCU hint, SCMDB
 * screenshot import) and the item-order editor (searchable item combobox with on-demand backend
 * search, blueprint + derivation loading, sub-assembly adoption, edit-mode prefill), the order-mode
 * toggle, and the #575 in-place create/edit submit through window.krtFetch.submitForm.
 *
 * The localized MSG_* and ITEM_I18N strings, the server materialIndex seed and EDIT_ITEMS prefill are
 * defined by the inline Thymeleaf bootstrap block of orders-create.html, which executes immediately
 * before this classic script.
 */

/* global materialIndex: writable, MSG_UNIT_SCU, MSG_UNIT_PIECE, MSG_MATERIAL_LABEL, MSG_AMOUNT_LABEL, MSG_MINQUALITY_LABEL, SCU_HINT_TEXT, MSG_SCMDB_SUCCESS, MSG_SCMDB_SOME_UNKNOWN, MSG_SCMDB_NO_MATCH, MSG_SCMDB_NOT_FOUND, ITEM_I18N, EDIT_ITEMS, MSG_MATERIAL_INVALID, MSG_ITEM_INVALID, MSG_CREATE_FAILED, MSG_UPDATE_FAILED, showFrontendErrorToast */

// Inline "?" SCU-hint marker for JS-built rows (the Thymeleaf fragment cannot be used here).
// The hint text is a trusted message constant with no HTML-special characters, so it is safe
// to inline without escaping. Starts hidden via the runtime `krtm-hidden` class (mirroring the
// scu-hint fragment's `th:classappend`); refreshMaterialUnit() toggles it for SCU materials. The
// former inline `style="display:none;"` is blocked by the CSP style-src-attr 'none' pin (ADR-0093),
// and a `style.display = ''` reveal cannot override a class, so visibility is class-based throughout.
function scuHintMarkup() {
    return (
        '<span class="scu-hint krtm-hidden" data-role="scu-hint" tabindex="0" role="img" aria-label="' +
        SCU_HINT_TEXT +
        '"><span aria-hidden="true">?</span>' +
        '<span class="scu-hint__bubble" aria-hidden="true">' +
        SCU_HINT_TEXT +
        '</span></span>'
    );
}

// Mirrors a material row's amount field to the selected material's quantity type: PIECE -> integer
// step + "(Stueck)" unit and no SCU hint; SCU -> 0.001 step + "(SCU)" unit + hint. Called on
// material change, after adding/importing a row, and once per existing row on load.
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
    // The material picker is a searchable combobox (REQ-FE-016): after enhancement, data-role
    // lives on the hidden input and the selected option's data-quantity-type is mirrored onto
    // it. This module's on-load row sync runs at parse time, BEFORE the DOMContentLoaded
    // enhancer — at that point the raw <select> still answers the data-role query, so fall
    // back to its selected option (edit mode / validation redisplay).
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

// Resolves a parsed SCMDB material name to its catalog entry via the server-side job-order
// material search (the page no longer preloads the catalog as <option>s, REQ-FE-016).
// Exact-match on the trimmed, lowercased name — the same normalization the former
// preloaded-option scan applied. Returns the matching MaterialDto or null on a miss or any
// fetch failure, so the import degrades to "material unknown" instead of throwing.
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
    let unknownMaterials = [];

    // Regex für SCU (Spitzhacke): ⛏ Name — Menge SCU (oder ähnliche Trennzeichen)
    const scuRegex = /⛏\s*(.+?)\s*[—–-]\s*([\d,.]+)\s*SCU/i;
    // Regex für Stück (Diamant): 💎 Name × Menge (oder ähnliche Trennzeichen)
    const pieceRegex = /💎\s*(.+?)\s*[×x*]\s*([\d,.]+)/i;

    // Lines are processed sequentially (one awaited lookup per line) so the row targeting stays
    // deterministic: each hit fills the next fresh row in export order.
    for (let line of lines) {
        line = line.trim();
        if (!line) continue;

        let match = line.match(scuRegex) || line.match(pieceRegex);

        if (match) {
            const materialName = match[1].trim().toLowerCase();
            // Komma zu Punkt + numerische Normalisierung, damit Werte wie
            // "2.0000" (Laranite/Titanium) oder "0.0200" (Aluminum) ohne
            // überflüssige Nachkomma-Nullen im number-Input landen.
            const amount = parseFloat(match[2].replace(',', '.'));
            if (isNaN(amount)) continue;

            // Resolve the name against the job-order material catalog on the server.
            const material = await findJobOrderMaterialByName(materialName);

            if (material) {
                const container = document.getElementById('materials-container');
                let targetRow = null;

                // Beim ersten gefundenen Material prüfen, ob die erste Zeile leer ist. The
                // material picker is an enhanced combobox, so it is addressed by data-role (the
                // hidden input) — a bare querySelector('select') would hit the minQuality select.
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

                // setValue() syncs the hidden value, the visible label and the mirrored
                // option metadata in one step (REQ-FE-016). The picker is remote-mode, so
                // the label + data map MUST be passed — the id cannot be resolved from a
                // preloaded option list, and a value-only call would clear the selection.
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

    const minQualityOptions = document.getElementById('minquality-options-template').innerHTML;

    // The material select is built EMPTY: the marker value opts it into the server-side-search
    // combobox (remote-materials-joborder, REQ-FE-016), which fetches its options on demand —
    // no preloaded catalog options exist on this page anymore.
    row.innerHTML = `
        <div class="form-group flex-2 mb-0">
            <label>${MSG_MATERIAL_LABEL}</label>
            <select name="materials[${materialIndex}].materialId" data-role="material-select" data-krt-combobox="remote-materials-joborder" required></select>
        </div>
        <div class="form-group flex-1 mb-0">
            <label>${MSG_AMOUNT_LABEL} <span data-role="amount-unit"></span>${scuHintMarkup()}</label>
            <input type="text" inputmode="decimal" data-scu-decimal name="materials[${materialIndex}].amount" value="" data-role="material-amount" step="0.001" min="0" required>
        </div>
        <div class="form-group flex-1 mb-0">
            <label>${MSG_MINQUALITY_LABEL}</label>
            <select name="materials[${materialIndex}].minQuality">${minQualityOptions}</select>
        </div>
    `;

    container.appendChild(row);
    // Manually built DOM: the global DOMContentLoaded/krt:swapped enhancer does not see it, so
    // upgrade the fresh material select in place (REQ-FE-016).
    if (window.krtEnhanceComboboxes) {
        window.krtEnhanceComboboxes(row);
    }
    refreshMaterialUnit(row);
    materialIndex++;
}

// ── Item-order editor ────────────────────────────────────────────────
let itemLineIndex = 0;

// Live-searches the orderable-item catalog on the backend (the combobox debounces the calls) and
// maps the result to the combobox's {value, label} option shape. Returns [] on any failure so the
// picker degrades to "no matches" instead of throwing.
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

function escapeName(s) {
    if (window.escapeHtml) {
        return window.escapeHtml(String(s));
    }
    return String(s).replace(
        /[&<>"']/g,
        (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
    );
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
    // Disable the hidden form's controls so their `required` fields never block the visible
    // form's submit (disabled controls are also omitted from the POST body).
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
    const options = document.getElementById('item-options-template').innerHTML;
    row.innerHTML = `
        <input type="hidden" name="items[${idx}].clientLineId" value="${idx}">
        <input type="hidden" name="items[${idx}].parentClientLineId" value="${prefill.parentId != null ? prefill.parentId : ''}">
        <div class="oc-line-fields">
            <div class="form-group flex-2 mb-0">
                <label>${ITEM_I18N.item}</label>
                <select name="items[${idx}].gameItemId" data-role="item-select" data-testid="order-item-combobox" required>${options}</select>
            </div>
            <div class="form-group flex-1 mb-0" data-role="blueprint-wrap" hidden>
                <label>${ITEM_I18N.blueprint}</label>
                <select name="items[${idx}].blueprintId" data-role="blueprint-select"></select>
            </div>
            <div class="form-group flex-1 mb-0">
                <label>${ITEM_I18N.amount}</label>
                <input type="number" step="1" name="items[${idx}].amount" data-role="amount" min="1" value="${prefill.amount || 1}" required>
            </div>
            <button type="button" class="btn btn-quiet-danger mb-0 nowrap" data-trigger="orders-remove-item">${ITEM_I18N.remove}</button>
        </div>
        <div data-role="derived" class="oc-derived-block"></div>
        <div data-role="unresolved" class="hud-box hud-box-error oc-note-block krtm-hidden"></div>
        <div data-role="subassemblies" class="oc-note-block"></div>
    `;
    container.appendChild(row);
    // Seed the chosen item as a selected <option> BEFORE enhancing, so the combobox shows its
    // label even though the option list is now fetched on demand rather than preloaded. Built via
    // the DOM so the backend-supplied name is never parsed as HTML. Then upgrade into a
    // searchable, backend-backed dropdown; if the enhancer failed to load, the plain <select>
    // with the seeded option keeps working as a fallback.
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
        // In edit mode, thread the stored blueprint + per-material qualities through the async
        // load so the line restores exactly as ordered. After enhancement the value lives on
        // the combobox's hidden input, which still carries data-role="item-select".
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
            bpSelect.innerHTML = list
                .map(
                    (b) =>
                        `<option value="${b.id}">${escapeName(b.outputName || b.scwikiKey || b.id)}</option>`,
                )
                .join('');
            wrap.hidden = list.length <= 1;
            if (list.length > 0) {
                // Restore the previously-chosen blueprint in edit mode if it is still offered.
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
            let html = `<strong class="oc-label-strong">${ITEM_I18N.materialsTitle}</strong>`;
            (d.materials || []).forEach((m, mi) => {
                const mat = m.material || {};
                const unit = mat.quantityType === 'PIECE' ? 'Stk' : 'SCU';
                const qty =
                    mat.quantityType === 'PIECE'
                        ? Math.round(m.requiredQuantity || 0)
                        : Number((m.requiredQuantity || 0).toFixed(3));
                // Edit mode: restore the stored quality for this material; else blueprint default.
                const storedQ =
                    qualities && mat.id && qualities[mat.id] ? qualities[mat.id] : m.defaultQuality;
                const goodSel = storedQ === 'GOOD' ? ' selected' : '';
                const noneSel = storedQ !== 'GOOD' ? ' selected' : '';
                html += `
                    <div class="oc-material-line">
                        <input type="hidden" name="items[${idx}].materials[${mi}].materialId" value="${mat.id}">
                        <span class="flex-2">${escapeName(mat.name || '')}</span>
                        <span class="flex-1">${qty} ${unit}</span>
                        <select name="items[${idx}].materials[${mi}].quality" class="flex-1">
                            <option value="GOOD"${goodSel}>${ITEM_I18N.qualityGood}</option>
                            <option value="NONE"${noneSel}>${ITEM_I18N.qualityNone}</option>
                        </select>
                    </div>`;
            });
            derived.innerHTML = (d.materials || []).length ? html : '';
            if ((d.unresolvedIngredients || []).length) {
                unresolved.classList.remove('krtm-hidden');
                unresolved.innerHTML = `<p>${ITEM_I18N.unresolved} ${d.unresolvedIngredients.map(escapeName).join(', ')}</p>`;
            } else {
                unresolved.classList.add('krtm-hidden');
                unresolved.innerHTML = '';
            }
            if ((d.subAssemblies || []).length) {
                let s = `<strong class="oc-label-strong">${ITEM_I18N.subTitle}</strong>`;
                d.subAssemblies.forEach((sa) => {
                    const gi = sa.gameItem || {};
                    s += `<div class="oc-material-line">
                        <span class="flex-2">${escapeName(gi.name || '')} &times; ${sa.quantity}</span>
                        <button type="button" class="btn btn-ghost mb-0" data-trigger="orders-adopt-sub" data-game-item-id="${gi.id}" data-game-item-name="${escapeName(gi.name || '')}" data-amount="${sa.quantity}" data-parent="${idx}">${ITEM_I18N.subAdopt}</button>
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
    // Edit mode: rebuild the existing lines from the injected prefill; otherwise start with one
    // empty line.
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

// Quantity-type-aware material rows: react to material changes and sync the rows present on load.
const materialsContainerEl = document.getElementById('materials-container');
if (materialsContainerEl) {
    materialsContainerEl.addEventListener('change', (e) => {
        if (e.target && e.target.matches && e.target.matches('[data-role="material-select"]')) {
            refreshMaterialUnit(e.target.closest('.material-row'));
        }
    });
    materialsContainerEl.querySelectorAll('.material-row').forEach(refreshMaterialUnit);
}

// CSP-safe delegated bindings (replaces onclick="importFromScmdb()" and
// onclick="addMaterialRow()" inline handlers).
if (window.krtEvents && typeof window.krtEvents.on === 'function') {
    window.krtEvents.on('click', 'orders-import-scmdb', importFromScmdb);
    window.krtEvents.on('click', 'orders-add-material', addMaterialRow);
    window.krtEvents.on('click', 'orders-add-item', () => addItemLine());
}

// In-place create submit (#575): a create/edit navigates away on success, so intercept the form,
// POST it via FormData (the browser serializes the dynamic material/item editor AND omits the
// disabled inactive-form controls, exactly like the native POST) and navigate to the JSON
// targetUrl. On a validation/backend failure the page STAYS with an inline toast instead of the
// POST->redirect reflash. The classic form-POST is the no-JS fallback (krtCsrf absent).
// Migrated to krtFetch.submitForm (S10, REQ-FE-009): the shared foundation owns the CSRF header
// (no Content-Type so the browser sets the multipart boundary), the bare-403 refresh-and-retry,
// X-Reauthenticate and the double-submit guard (submitter). This helper keeps only its page
// behaviour: navigate away to the JSON targetUrl on success (toast:false; a create/edit leaves
// the page) and the 400-invalid vs generic inline error toast (onError). The classic form-POST
// stays the no-JS fallback (krtFetch absent).
function _submitOrderCreate(form, invalidMessage, failedMessage, submitter) {
    if (!window.krtFetch) {
        form.submit();
        return;
    }
    window.krtFetch.submitForm({
        form: form,
        submitter: submitter,
        toast: false,
        errorMessage: failedMessage,
        onError: function (status) {
            showFrontendErrorToast(status === 400 ? invalidMessage : failedMessage);
            return true;
        },
        onSuccess: function (body) {
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
