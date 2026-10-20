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

/* global MSG_STOLEN, MSG_QUALITY, MSG_INVENTORY_BELOW_FLOOR, MSG_PRODUCTION_COUNTED_HIGHER, PRODUCTION_I18N */

import { krtOrderWrite } from '/js/joborder/orders-detail-edit.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

function initOrdersDetailProduction() {
    const nav = /** @type {HTMLElement | null} */ (
        document.querySelector('.tab-nav[role="tablist"]')
    );
    if (!nav) {
        return;
    }
    const tabs = /** @type {HTMLElement[]} */ (
        Array.prototype.slice.call(nav.querySelectorAll('.tab[data-tab]'))
    );
    if (tabs.length === 0) {
        return;
    }
    const storeKey = 'orders-detail-tab';

    /**
     * Reads the key of a tab; every tab selected above carries one.
     *
     * @param {HTMLElement} tab the tab
     * @returns {string} the tab's key
     */
    function tabKey(tab) {
        return /** @type {string} */ (tab.getAttribute('data-tab'));
    }

    /**
     * Lists the tabs the order shows; a tab without content and without an action stays hidden.
     *
     * @returns {HTMLElement[]} the visible tabs in order
     */
    function visibleTabs() {
        return tabs.filter((t) => {
            return !t.hidden;
        });
    }

    /**
     * Lists the keys of the visible tabs.
     *
     * @returns {string[]} the tab keys
     */
    function validKeysNow() {
        return visibleTabs().map((t) => {
            return tabKey(t);
        });
    }

    /**
     * Activates a tab and its pane and remembers the choice.
     *
     * @param {string} key the tab key
     */
    function apply(key) {
        tabs.forEach((t) => {
            const on = t.getAttribute('data-tab') === key;
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', String(on));
            t.tabIndex = on ? 0 : -1;
        });
        document.querySelectorAll('.tab-panes .tab-pane').forEach((p) => {
            p.classList.toggle('on', p.id === `pane-${key}`);
        });
        try {
            localStorage.setItem(storeKey, key);
        } catch (_e) {}
    }

    /**
     * Picks the tab to show: the query, the hash, the remembered one, else the first.
     *
     * @param {boolean} useStore whether the remembered tab is consulted
     * @returns {string} the tab key
     */
    function resolveInitial(useStore) {
        const validKeys = validKeysNow();
        const q = new URLSearchParams(window.location.search).get('tab');
        if (q && validKeys.indexOf(q) >= 0) {
            return q;
        }
        const h = (window.location.hash.match(/tab=([\w-]+)/) || [])[1];
        if (h && validKeys.indexOf(h) >= 0) {
            return h;
        }
        if (useStore) {
            try {
                const s = localStorage.getItem(storeKey);
                if (s && validKeys.indexOf(s) >= 0) {
                    return s;
                }
            } catch (_e) {}
        }
        return validKeys[0];
    }

    /**
     * Shows a tab when it is visible, optionally recording it in the history.
     *
     * @param {string} key the tab key
     * @param {boolean} push whether to push a history entry
     */
    function show(key, push) {
        if (validKeysNow().indexOf(key) < 0) {
            return;
        }
        apply(key);
        if (push) {
            const url = new URL(window.location.href);
            url.searchParams.set('tab', key);
            url.hash = '';
            history.pushState({ tab: key }, '', url);
        }
    }

    tabs.forEach((t) => {
        t.addEventListener('click', () => {
            show(tabKey(t), true);
        });
    });
    nav.addEventListener('keydown', (e) => {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') {
            return;
        }
        const shown = visibleTabs();
        const i = shown.indexOf(/** @type {HTMLElement} */ (document.activeElement));
        if (i < 0) {
            return;
        }
        e.preventDefault();
        const next = shown[(i + (e.key === 'ArrowRight' ? 1 : shown.length - 1)) % shown.length];
        next.focus();
        show(tabKey(next), true);
    });
    document.addEventListener('krt:swapped', (ev) => {
        const container = ev && ev.detail && ev.detail.container;
        if (!container || typeof container.querySelectorAll !== 'function') {
            return;
        }
        const metas = /** @type {Element[]} */ (
            Array.prototype.slice.call(container.querySelectorAll('[data-od-tab-count]'))
        );
        metas.forEach((meta) => {
            const key = meta.getAttribute('data-od-tab-count');
            const count = parseInt((meta.textContent || '0').trim(), 10) || 0;
            const tab = document.getElementById(`tab-${key}`);
            if (!tab) {
                return;
            }
            const badge = tab.querySelector('.tab-count');
            if (badge) {
                badge.textContent = String(count);
            }
            if (count > 0 && tab.hidden) {
                tab.hidden = false;
            } else if (count === 0 && !tab.hidden && tab.hasAttribute('data-od-hide-empty')) {
                const wasActive = tab.classList.contains('active');
                tab.hidden = true;
                if (wasActive && visibleTabs().length > 0) {
                    show(tabKey(visibleTabs()[0]), false);
                }
            }
        });
    });
    window.addEventListener('popstate', () => {
        apply(resolveInitial(false));
    });
    apply(resolveInitial(true));
}

initOrdersDetailProduction();

document.addEventListener('krt:swapped', (ev) => {
    const container = ev && ev.detail && ev.detail.container;
    if (!container || container.id !== 'order-header-results') {
        return;
    }
    const meta = document.getElementById('order-head-meta');
    const badge = document.getElementById('order-status-badge');
    if (!meta || !badge) {
        return;
    }
    const statusClass = meta.getAttribute('data-status-class') || '';
    /** @type {string[]} */ (Array.prototype.slice.call(badge.classList)).forEach((cls) => {
        if (cls.indexOf('status-') === 0 && cls !== 'status-badge') {
            badge.classList.remove(cls);
        }
    });
    if (statusClass) {
        badge.classList.add(statusClass);
    }
    badge.textContent = meta.getAttribute('data-status-label') || badge.textContent;
});

/**
 * @typedef {object} OdProdEntry
 * @property {string} inventoryItemId the linked inventory entry
 * @property {string} ownerName the entry's owner
 * @property {string} location the entry's location
 * @property {boolean} stolen whether the entry is stolen stock
 * @property {number | string} quality the entry's quality, or a dash when unknown
 * @property {number} slice the part of the entry earmarked for this order
 * @property {number} stock the entry's total amount
 * @property {number} version the entry's version
 * @property {boolean} [belowFloor] whether the quality is below the material's floor
 */

/**
 * @typedef {object} OdProdInventoryItem
 * @property {string} id the inventory entry
 * @property {number} [amount] the entry's total amount
 * @property {number | null} [quality] the entry's quality
 * @property {boolean} [stolen] whether the entry is stolen stock
 * @property {number} version the entry's version
 * @property {{ effectiveName: string } | null} [user] the entry's owner
 * @property {{ name: string } | null} [location] the entry's location
 * @property {{ jobOrderId?: string, amount?: number | null }[]} [jobOrderAllocations] the earmarks
 */

/**
 * @typedef {object} OdProdMaterial
 * @property {string} materialId the material
 * @property {string} materialName the material's name
 * @property {string} quantityType the material's quantity type
 * @property {number[]} requiredTotals the required amounts of the order line, per quality bucket
 * @property {number} floor the highest quality floor among the buckets
 * @property {string} qualityLabel the label of that floor
 * @property {OdProdEntry[]} entries the linked entries, lowest eligible grade first
 * @property {Map<string, string>} [countedHigher] row id to the label of the higher tier it counts toward
 */

/**
 * @typedef {object} OdProdContext
 * @property {string | null} orderId the order
 * @property {string | null} itemId the order line
 * @property {number} lineAmount the ordered amount of the line
 * @property {number} manufactured the amount already made
 * @property {string | null} version the line's version
 * @property {number} remaining the amount still to make
 */

/** @type {OdProdMaterial[]} */
let _prodMaterials = [];
/** @type {OdProdContext | null} */
let _prodContext = null;

/**
 * Rounds to three decimals.
 *
 * @param {number} x the value
 * @returns {number} the rounded value
 */
function _prodRound3(x) {
    return Math.round(x * 1000) / 1000;
}

/**
 * Rounds to whole pieces or three SCU decimals.
 *
 * @param {number} x the value
 * @param {string} quantityType the material's quantity type
 * @returns {number} the rounded value
 */
function _prodRoundForType(x, quantityType) {
    return quantityType === 'PIECE' ? Math.round(x) : _prodRound3(x);
}

/**
 * Formats a number for the reconcile chip.
 *
 * @param {number} x the value
 * @param {string} quantityType the material's quantity type
 * @returns {string} the formatted value
 */
function _prodFmtNum(x, quantityType) {
    return quantityType === 'PIECE' ? String(Math.round(x)) : _prodRound3(x).toFixed(3);
}

/**
 * Formats a quantity with its unit.
 *
 * @param {number} x the value
 * @param {string} quantityType the material's quantity type
 * @returns {string} the formatted quantity
 */
function _prodFmtQty(x, quantityType) {
    return quantityType === 'PIECE'
        ? `${String(Math.round(x))} ${PRODUCTION_I18N.unitPiece}`
        : `${_prodRound3(x).toFixed(3)} ${PRODUCTION_I18N.unitScu}`;
}

/**
 * Computes how much of a material producing `k` pieces consumes.
 *
 * @param {OdProdMaterial} mat the material
 * @param {number} k the number of pieces to produce
 * @returns {number} the material demand
 */
function _prodDemand(mat, k) {
    const lineAmount = _prodContext && _prodContext.lineAmount ? _prodContext.lineAmount : 1;
    return mat.requiredTotals.reduce((sum, rt) => {
        return sum + _prodRoundForType((rt * k) / lineAmount, mat.quantityType);
    }, 0);
}

/**
 * Toggles the demand detail row a control points to.
 *
 * @param {HTMLElement | null} control the expand control
 */
export function _odToggleDemandRow(control) {
    if (!control) {
        return;
    }
    const expanded = control.getAttribute('aria-expanded') === 'true';
    control.setAttribute('aria-expanded', expanded ? 'false' : 'true');
    const id = control.getAttribute('aria-controls');
    const target = id ? document.getElementById(id) : null;
    if (target) {
        target.hidden = expanded;
    }
}

/**
 * Opens the production modal for an order line and loads the stock of its materials.
 *
 * @param {HTMLElement} button the record-production button carrying the line's data attributes
 */
export function openProductionModal(button) {
    const orderId = button.getAttribute('data-order-id');
    const itemId = button.getAttribute('data-item-id');
    const itemName = button.getAttribute('data-item-name') || '';
    const lineAmount = parseInt(button.getAttribute('data-amount') || '', 10) || 0;
    const manufactured = parseInt(button.getAttribute('data-manufactured') || '', 10) || 0;
    const version = button.getAttribute('data-version');
    const remaining = Math.max(0, lineAmount - manufactured);

    _prodContext = { orderId, itemId, lineAmount, manufactured, version, remaining };
    _prodMaterials = [];

    const title = document.getElementById('production-modal-title');
    if (title) {
        title.textContent = `${PRODUCTION_I18N.record} — ${itemName}`;
    }

    const amtInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('production-amount')
    );
    if (amtInput) {
        amtInput.max = String(remaining);
        amtInput.value = String(remaining > 0 ? 1 : 0);
        amtInput.oninput = _prodReconcile;
    }
    const hint = document.getElementById('production-remaining-hint');
    if (hint) {
        hint.textContent = `${PRODUCTION_I18N.remaining}: ${remaining} (${manufactured} / ${
            lineAmount
        } ${PRODUCTION_I18N.alreadyMade})`;
    }

    const container = document.getElementById('production-materials');
    if (container) {
        container.replaceChildren();
    }

    _prodResetBookIn();

    krtModal.open('production-modal');

    const row = button.closest('tr');
    const demandLis = row ? row.querySelectorAll('.od-production-demand li') : [];
    /** @type {Map<string, OdProdMaterial>} */
    const byMaterialId = new Map();
    demandLis.forEach((li) => {
        const materialId = li.getAttribute('data-material-id');
        if (!materialId) {
            return;
        }
        const requiredTotal = parseFloat(li.getAttribute('data-required-total') || '') || 0;
        const floor = parseInt(li.getAttribute('data-quality-floor') || '0', 10) || 0;
        const existing = byMaterialId.get(materialId);
        if (existing) {
            existing.requiredTotals.push(requiredTotal);
            if (floor > existing.floor) {
                existing.floor = floor;
                existing.qualityLabel = li.getAttribute('data-quality-label') || '';
            }
            return;
        }
        byMaterialId.set(materialId, {
            materialId,
            materialName: li.getAttribute('data-material-name') || '',
            quantityType: li.getAttribute('data-quantity-type') || 'SCU',
            requiredTotals: [requiredTotal],
            floor,
            qualityLabel: li.getAttribute('data-quality-label') || '',
            entries: [],
        });
    });
    byMaterialId.forEach((mat) => {
        _prodMaterials.push(mat);
        const card = document.createElement('div');
        card.className = 'card card--inset mb-1';
        card.setAttribute('data-prod-material', mat.materialId);
        krtHtml.set(
            card,
            krtHtml`<div class="card-head"><h3 class="card-title">${
                mat.materialName
            }</h3><span class="chip chip--muted" data-prod-chip></span></div><label class="od-prod-skip" title="${
                PRODUCTION_I18N.skipHint
            }"><input type="checkbox" data-prod-skip> ${
                PRODUCTION_I18N.skipLabel
            }</label><div data-prod-entries><p class="text-muted">${
                PRODUCTION_I18N.loadingStock
            }</p></div>`,
        );
        const title = card.querySelector('.card-title');
        if (mat.floor > 0 && title) {
            const badge = document.createElement('span');
            badge.className = 'quality-badge quality-good';
            badge.textContent = mat.qualityLabel;
            title.append(' ', badge);
        }
        if (container) {
            container.appendChild(card);
        }
        const skipCb = card.querySelector('[data-prod-skip]');
        if (skipCb) {
            skipCb.addEventListener('change', _prodReconcile);
        }
        Promise.all([
            krtFetch
                .getJson(`/orders/${orderId}/materials/${mat.materialId}/inventory`)
                .catch(() => []),
            _prodLoadHigherAttribution(orderId, mat),
        ])
            .then((results) => {
                /** @type {OdProdInventoryItem[] | null} */
                const items = results[0];
                mat.countedHigher = results[1];
                mat.entries = (items || [])
                    .map((it) => {
                        const alloc = (it.jobOrderAllocations || []).find((a) => {
                            return a.jobOrderId === orderId;
                        });
                        const slice = alloc && alloc.amount != null ? alloc.amount : 0;
                        return {
                            inventoryItemId: it.id,
                            ownerName: it.user ? it.user.effectiveName : '-',
                            location: it.location ? it.location.name : '-',
                            stolen: it.stolen === true,
                            quality: it.quality != null ? it.quality : '-',
                            slice,
                            stock: it.amount != null ? it.amount : 0,
                            version: it.version,
                        };
                    })
                    .filter((e) => {
                        return e.slice > 0;
                    })
                    .map((e) => {
                        const q = typeof e.quality === 'number' ? e.quality : 0;
                        return Object.assign(e, { belowFloor: q < mat.floor });
                    })
                    .sort((a, b) => {
                        if (a.belowFloor !== b.belowFloor) return a.belowFloor ? 1 : -1;
                        const qa = typeof a.quality === 'number' ? a.quality : 0;
                        const qb = typeof b.quality === 'number' ? b.quality : 0;
                        return qa - qb;
                    });
                _renderProdMaterialEntries(card, mat);
                _prodPrefill(card, mat);
                _prodReconcile();
            })
            .catch(() => {
                _renderProdMaterialEntries(card, mat);
                _prodReconcile();
            });
    });
    _prodReconcile();
}

/**
 * Renders the linked stock entries of a material card with an allocation input each.
 *
 * @param {Element} card the material card
 * @param {OdProdMaterial} mat the material with its entries
 */
function _renderProdMaterialEntries(card, mat) {
    const entriesEl = card.querySelector('[data-prod-entries]');
    if (!entriesEl) {
        return;
    }
    if (mat.entries.length === 0) {
        krtHtml.set(entriesEl, krtHtml`<p class="text-muted">${PRODUCTION_I18N.noStock}</p>`);
        return;
    }
    const html = mat.entries.map((e, idx) => {
        const cap = Math.min(e.slice, e.stock);
        /** @type {KrtHtml[]} */
        const chips = [];
        if (e.stolen) {
            chips.push(krtHtml` <span class="chip chip--danger chip-xs">${MSG_STOLEN}</span>`);
        }
        const higher = mat.countedHigher ? mat.countedHigher.get(e.inventoryItemId) : null;
        if (higher && !e.belowFloor) {
            chips.push(
                krtHtml` <span class="chip chip--warning chip-xs" data-testid="prod-counted-higher">${MSG_PRODUCTION_COUNTED_HIGHER.replace(
                    '{0}',
                    higher,
                )}</span>`,
            );
        }
        if (e.belowFloor) {
            chips.push(
                krtHtml` <span class="chip chip-xs" data-testid="prod-below-floor">${MSG_INVENTORY_BELOW_FLOOR}</span>`,
            );
        }
        return krtHtml`<div class="od-prod-entry"><div class="od-prod-src">${e.ownerName} · ${String(
            e.location,
        )}${chips} <small>${MSG_QUALITY} ${String(e.quality)} · ${PRODUCTION_I18N.available} ${_prodFmtQty(
            cap,
            mat.quantityType,
        )}</small></div><input type="text" data-number-input min="0" step="${
            mat.quantityType === 'PIECE' ? '1' : 'any'
        }" value="0" data-prod-alloc data-prod-idx="${idx}"></div>`;
    });
    krtHtml.set(entriesEl, html);
    entriesEl.querySelectorAll('[data-prod-alloc]').forEach((el) => {
        const inp = /** @type {HTMLInputElement} */ (el);
        const entry = mat.entries[Number(inp.getAttribute('data-prod-idx'))];
        if (entry && entry.belowFloor) {
            inp.disabled = true;
            inp.setAttribute('data-below-floor', 'true');
        }
        inp.addEventListener('input', _prodReconcile);
    });
}

/**
 * Finds the linked rows of a material the quality-bucket allocation currently counts toward a
 * tier with a higher floor than this line needs, so consuming them here is flagged (REQ-ORDERS-039).
 *
 * @param {string | null} orderId the order
 * @param {OdProdMaterial} mat the material with its floor
 * @returns {Promise<Map<string, string>>} row id to the label of the higher tier
 */
async function _prodLoadHigherAttribution(orderId, mat) {
    /** @type {Map<string, string>} */
    const higher = new Map();
    /** @type {Map<string, {floor: number, label: string}>} */
    const tiers = new Map();
    document.querySelectorAll('.aggregated-material-row').forEach((el) => {
        const row = /** @type {HTMLElement} */ (el);
        if (row.dataset.materialId !== mat.materialId || !row.dataset.qualityTierId) return;
        tiers.set(row.dataset.qualityTierId, {
            floor: parseInt(row.dataset.qualityFloor || '0', 10) || 0,
            label: row.dataset.qualityLabel || '',
        });
    });
    if (tiers.size < 2 || !orderId) return higher;
    try {
        const entries = await krtFetch.getJson(
            `/orders/${orderId}/materials/${mat.materialId}/attribution`,
        );
        for (const entry of entries) {
            const tier = tiers.get(entry.qualityTierId);
            if (tier && tier.floor > mat.floor && entry.amount > 0) {
                higher.set(entry.inventoryItemId, tier.label);
            }
        }
    } catch (_e) {
        return higher;
    }
    return higher;
}

/**
 * Pre-fills a material's consumption with the lowest qualifying grades first, up to the demand of
 * the current amount; the member may change every figure (REQ-ORDERS-039).
 *
 * @param {Element} card the material card
 * @param {OdProdMaterial} mat the material with its sorted entries
 */
function _prodPrefill(card, mat) {
    const amtInput = /** @type {HTMLInputElement | null} */ (
        document.getElementById('production-amount')
    );
    const k = parseInt(amtInput ? amtInput.value : '0', 10);
    if (!isFinite(k) || k < 1) return;
    let left = _prodDemand(mat, k);
    card.querySelectorAll('[data-prod-alloc]').forEach((el) => {
        const inp = /** @type {HTMLInputElement} */ (el);
        const e = mat.entries[parseInt(inp.getAttribute('data-prod-idx') || '0', 10)];
        if (!e || e.belowFloor || left <= 1e-4) return;
        const cap = Math.min(e.slice, e.stock);
        const take = Math.min(left, cap);
        inp.value = String(mat.quantityType === 'PIECE' ? Math.floor(take) : _prodRound3(take));
        left -= parseFloat(inp.value) || 0;
    });
}

/**
 * Looks up an input or checkbox of the production modal.
 *
 * @param {string} id the element id
 * @returns {HTMLInputElement | null} the input, or null when the page has none
 */
function _prodInput(id) {
    return /** @type {HTMLInputElement | null} */ (document.getElementById(id));
}

/**
 * Looks up a value-holding picker of the production modal, an input or an unenhanced select.
 *
 * @param {string} id the element id
 * @returns {HTMLInputElement | HTMLSelectElement | null} the picker, or null when the page has none
 */
function _prodPicker(id) {
    return /** @type {HTMLInputElement | HTMLSelectElement | null} */ (document.getElementById(id));
}

/**
 * Reads the acting user's id from the production form.
 *
 * @returns {string} the user id, empty when absent
 */
function _prodActingUserId() {
    const form = document.getElementById('production-form');
    return form ? form.getAttribute('data-acting-user-id') || '' : '';
}

/**
 * Reads the acting user's display name from the production form.
 *
 * @returns {string} the display name, empty when absent
 */
function _prodActingUserName() {
    const form = document.getElementById('production-form');
    return form ? form.getAttribute('data-acting-user-name') || '' : '';
}

/**
 * Sets a book-in picker, through its combobox when it is enhanced.
 *
 * @param {string} id the picker's element id
 * @param {string} value the value to select
 * @param {string} [label] the label of a value outside the loaded options
 */
function _prodSetPicker(id, value, label) {
    const el = _prodPicker(id);
    if (!el) {
        return;
    }
    if (el.krtCombobox) {
        el.krtCombobox.setValue(value || '', label);
    } else {
        el.value = value || '';
    }
}

/** Resets the book-in controls of the production modal to their defaults. */
function _prodResetBookIn() {
    _prodSetPicker('production-location', '');
    _prodSetPicker('production-owner', _prodActingUserId(), _prodActingUserName());
    const personalCb = _prodInput('production-personal');
    if (personalCb) {
        personalCb.checked = false;
        personalCb.disabled = false;
    }
    const allocateCb = _prodInput('production-allocate');
    if (allocateCb) {
        allocateCb.checked = true;
        allocateCb.disabled = false;
    }
    _prodRefreshOrgUnitPicker();
}

/** Keeps the allocate-to-order checkbox opposite to the personal checkbox. */
export function _prodSyncPersonalAllocate() {
    const personalCb = _prodInput('production-personal');
    const allocateCb = _prodInput('production-allocate');
    if (!allocateCb) {
        return;
    }
    const personal = !!(personalCb && personalCb.checked);
    allocateCb.disabled = personal;
    allocateCb.checked = !personal;
}

/** Disables the personal checkbox while the booking is made for another owner. */
export function _prodSyncPersonalForOwner() {
    const personalCb = _prodInput('production-personal');
    if (!personalCb) {
        return;
    }
    const ownerEl = _prodPicker('production-owner');
    const ownerId = (ownerEl && ownerEl.value) || '';
    const onBehalf = ownerId !== '' && ownerId !== _prodActingUserId();
    personalCb.disabled = onBehalf;
    if (onBehalf && personalCb.checked) {
        personalCb.checked = false;
        _prodSyncPersonalAllocate();
    }
}

/** Reloads the owning org-unit choices for the chosen owner and shows or hides the picker. */
export function _prodRefreshOrgUnitPicker() {
    const wrapper = document.getElementById('production-orgunit-wrapper');
    const select = /** @type {HTMLSelectElement | null} */ (
        document.getElementById('production-orgunit')
    );
    const ownerEl = _prodPicker('production-owner');
    if (!wrapper || !select) {
        return;
    }
    const ownerId = (ownerEl && ownerEl.value) || _prodActingUserId();
    select.replaceChildren();
    if (!ownerId) {
        wrapper.hidden = true;
        return;
    }
    krtFetch
        .getJson(`/users/${encodeURIComponent(ownerId)}/memberships?allKinds=true`)
        .then((memberships) => {
            if (!Array.isArray(memberships) || memberships.length < 1) {
                wrapper.hidden = true;
                return;
            }
            memberships.forEach((opt) => {
                const o = document.createElement('option');
                o.value = opt.orgUnitId;
                o.textContent = opt.orgUnitName;
                select.appendChild(o);
            });
            const form = document.getElementById('production-form');
            const responsibleId = form
                ? form.getAttribute('data-responsible-org-unit-id') || ''
                : '';
            if (
                responsibleId &&
                memberships.some((m) => {
                    return m.orgUnitId === responsibleId;
                })
            ) {
                select.value = responsibleId;
            }
            wrapper.hidden = false;
        })
        .catch(() => {
            wrapper.hidden = true;
        });
}

/**
 * Reads the book-in choices of the production modal.
 *
 * @returns {{locationId: string | null, ownerUserId: string | null, owningOrgUnitId: string | null, personal: boolean, allocateToOrder: boolean}} the book-in
 */
function _prodCollectBookIn() {
    const locEl = _prodPicker('production-location');
    const ownerEl = _prodPicker('production-owner');
    const orgUnitWrapper = document.getElementById('production-orgunit-wrapper');
    const orgUnitEl = _prodPicker('production-orgunit');
    const personalCb = _prodInput('production-personal');
    const allocateCb = _prodInput('production-allocate');
    const personal = !!(personalCb && personalCb.checked);
    return {
        locationId: (locEl && locEl.value) || null,
        ownerUserId: (ownerEl && ownerEl.value) || null,
        owningOrgUnitId:
            orgUnitWrapper && !orgUnitWrapper.hidden && orgUnitEl && orgUnitEl.value
                ? orgUnitEl.value
                : null,
        personal,
        allocateToOrder: personal ? false : !!(allocateCb && allocateCb.checked),
    };
}

/** Recomputes each material card's reconcile chip and the book button's enabled state. */
export function _prodReconcile() {
    if (!_prodContext) {
        return;
    }
    const amtInput = _prodInput('production-amount');
    let k = parseInt(amtInput ? amtInput.value : '0', 10);
    if (!isFinite(k) || k < 1) {
        k = 0;
    }
    let allCovered = k >= 1 && k <= _prodContext.remaining;
    _prodMaterials.forEach((mat) => {
        const card = document.querySelector(`[data-prod-material="${mat.materialId}"]`);
        if (!card) {
            return;
        }
        const skipCb = /** @type {HTMLInputElement | null} */ (
            card.querySelector('[data-prod-skip]')
        );
        const skipped = !!(skipCb && skipCb.checked);
        const chip = card.querySelector('[data-prod-chip]');
        card.classList.toggle('od-prod-skipped', skipped);
        card.querySelectorAll('[data-prod-alloc]').forEach((el) => {
            const inp = /** @type {HTMLInputElement} */ (el);
            inp.disabled = skipped || inp.getAttribute('data-below-floor') === 'true';
        });
        if (skipped) {
            if (chip) {
                chip.textContent = PRODUCTION_I18N.skipped;
                chip.className = 'chip chip--muted';
            }
            return;
        }
        const demand = _prodDemand(mat, k);
        let assigned = 0;
        card.querySelectorAll('[data-prod-alloc]').forEach((el) => {
            const inp = /** @type {HTMLInputElement} */ (el);
            assigned += _prodRoundForType(parseFloat(inp.value) || 0, mat.quantityType);
        });
        assigned = mat.quantityType === 'PIECE' ? Math.round(assigned) : _prodRound3(assigned);
        const rest = _prodRound3(demand - assigned);
        if (chip) {
            chip.textContent = PRODUCTION_I18N.reconcile
                .replace('{0}', _prodFmtNum(demand, mat.quantityType))
                .replace('{1}', _prodFmtNum(assigned, mat.quantityType))
                .replace('{2}', _prodFmtNum(rest, mat.quantityType));
            chip.className = `chip ${
                Math.abs(rest) < 1e-4 ? 'chip--success' : rest < 0 ? 'chip--danger' : 'chip--muted'
            }`;
        }
        if (Math.abs(rest) >= 1e-4) {
            allCovered = false;
        }
    });
    const locEl = _prodPicker('production-location');
    if (!locEl || !locEl.value) {
        allCovered = false;
    }
    const bookBtn = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('production-book-btn')
    );
    if (bookBtn) {
        bookBtn.disabled = !allCovered;
    }
}

/** Validates the allocations and books the production of the open order line. */
export function bookProduction() {
    if (!_prodContext) {
        return;
    }
    const amtInput = _prodInput('production-amount');
    const k = parseInt(amtInput ? amtInput.value : '0', 10);
    if (!isFinite(k) || k < 1 || k > _prodContext.remaining) {
        showFrontendErrorToast(PRODUCTION_I18N.allocError);
        return;
    }
    /** @type {Array<{inventoryItemId: string, materialId: string, amount: number, version: number}>} */
    const consumption = [];
    /** @type {string[]} */
    const skippedMaterialIds = [];
    let ok = true;
    _prodMaterials.forEach((mat) => {
        const card = document.querySelector(`[data-prod-material="${mat.materialId}"]`);
        const skipCb = /** @type {HTMLInputElement | null} */ (
            card ? card.querySelector('[data-prod-skip]') : null
        );
        if (skipCb && skipCb.checked) {
            skippedMaterialIds.push(mat.materialId);
            return;
        }
        const demand = _prodDemand(mat, k);
        let assigned = 0;
        if (card) {
            card.querySelectorAll('[data-prod-alloc]').forEach((el) => {
                const inp = /** @type {HTMLInputElement} */ (el);
                const v = _prodRoundForType(parseFloat(inp.value) || 0, mat.quantityType);
                if (v > 0) {
                    const idx = parseInt(inp.getAttribute('data-prod-idx') || '', 10);
                    const entry = mat.entries[idx];
                    if (entry) {
                        consumption.push({
                            inventoryItemId: entry.inventoryItemId,
                            materialId: mat.materialId,
                            amount: v,
                            version: entry.version,
                        });
                        assigned += v;
                    }
                }
            });
        }
        assigned = mat.quantityType === 'PIECE' ? Math.round(assigned) : _prodRound3(assigned);
        if (Math.abs(demand - assigned) >= 1e-4) {
            ok = false;
        }
    });
    if (!ok) {
        showFrontendErrorToast(PRODUCTION_I18N.allocError);
        return;
    }
    const bookIn = _prodCollectBookIn();
    if (!bookIn.locationId) {
        showFrontendErrorToast(PRODUCTION_I18N.bookInLocationRequired);
        return;
    }

    krtOrderWrite({
        method: 'POST',
        url: `/orders/${_prodContext.orderId}/items/${_prodContext.itemId}/production`,
        payload: {
            amount: k,
            version: _prodContext.version ? parseInt(_prodContext.version, 10) : null,
            consumption,
            skippedMaterialIds,
            bookIn,
        },
        toast: false,
        errorMessage: PRODUCTION_I18N.allocError,
        onSuccess() {
            krtModal.close('production-modal');
            showFrontendSuccessToast(PRODUCTION_I18N.booked);
            if (joborderHooks.refreshOrderSection) {
                joborderHooks.refreshOrderSection([
                    'items',
                    'aggregated',
                    'header',
                    'kpi',
                    'item-handovers',
                    'item-handover-lines',
                ]);
            }
            krtPageSections.publish('inventory', ['stock']);
        },
    });
}
