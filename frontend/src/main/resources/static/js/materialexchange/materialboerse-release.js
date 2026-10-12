/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Shared Materialbörse release / edit modal (REQ-MARKET-002/007). Used by both the
 * Materialbörse board page ("Material anbieten" / "Angebot bearbeiten") and the
 * Mein-Lager page ("Für Börse freigeben" checkbox). Lets the owner choose how much of
 * a Lager row to offer (partial offers, ADR-0086). Exposes window.krtMaterialRelease.
 * The modal markup lives in fragments/materialboerse-modal.html; strings come from
 * window.materialboerseI18n. No native dialogs, no full-page reload.
 */

// @ts-check

import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

function initMaterialboerseRelease() {
    /**
     * The seed values `open` takes from its caller; absent fields are blank.
     *
     * @typedef {object} MaterialReleaseContext
     * @property {string | null} [itemId] the inventory entry to offer
     * @property {string | null} [offerId] the offer being edited
     * @property {string | null} [version] the offer's version
     * @property {string | null} [kind] `MATERIAL` or `ITEM`
     * @property {string | null} [material] the material or item name
     * @property {string | null} [quality] the quality tier
     * @property {string | number | null} [amount] the stock, or the offered quantity in edit mode
     * @property {string | number | null} [available] the entry's total stock in edit mode
     * @property {string | null} [quantityType] `SCU` or `PIECE`
     * @property {string | null} [remark] the remark text
     */

    /**
     * What a dialog session remembers between `open` and its close.
     *
     * @typedef {object} MaterialReleaseState
     * @property {string | null} mode `new`, `lager`, `item`, `edit` or null while closed
     * @property {string | null} itemId the picked inventory entry
     * @property {string | null} productKey the picked blueprint product
     * @property {string | null} offerId the offer being edited
     * @property {string | null} version the offer's version
     * @property {number | null} available the stock ceiling of the amount field
     * @property {string | null} quantityType `SCU`, `PIECE` or null
     * @property {((body: any) => void | Promise<unknown>) | null} onDone runs with the response body on success
     * @property {(() => void) | null} onCancel runs when the dialog is dismissed
     */

    const i18n = { ...window.materialboerseModalI18n, ...window.materialboerseI18n };
    const modal = /** @type {HTMLElement} */ (document.getElementById('mb-modal'));
    if (!modal || !krtFetch) {
        return;
    }

    const SERIALIZE_KEY = 'materialboerse';
    const MATERIALBOARD_TOPIC = 'materialboard';
    /** @type {MaterialReleaseState} */
    let state = {
        mode: null,
        itemId: null,
        productKey: null,
        offerId: null,
        version: null,
        available: null,
        quantityType: null,
        onDone: null,
        onCancel: null,
    };
    let pickerItems = [];
    let productItems = [];
    /** @type {HTMLElement | null} */
    let lastFocused = null;
    let pickerSeq = 0;
    /** @type {number | null} */
    let pickerSearchTimer = null;
    let itemPickerSeq = 0;
    /** @type {number | null} */
    let itemPickerSearchTimer = null;
    const PICKER_SEARCH_DEBOUNCE_MS = 200;
    let pickerListOpen = false;
    let itemPickerListOpen = false;
    let pickerKind = 'MATERIAL';

    function fmt(template, value) {
        return String(template || '').replace('{0}', value);
    }

    /**
     * Finds an element inside the modal.
     *
     * @template {HTMLElement} [T=HTMLElement]
     * @param {string} sel the selector
     * @returns {T | null} the element, or null
     */
    function q(sel) {
        return modal.querySelector(sel);
    }

    /**
     * Whether the modal is currently on screen, read off its computed display.
     */
    function isModalOpen() {
        return (
            window.getComputedStyle(modal).display !== '' &&
            window.getComputedStyle(modal).display !== 'none'
        );
    }

    function setText(sel, text) {
        const el = q(sel);
        if (el) {
            el.textContent = text;
        }
    }

    function toggle(sel, on) {
        const el = q(sel);
        if (el) {
            el.hidden = !on;
        }
    }

    /**
     * Formats an amount in the material's own unit: an integer count + the piece unit for a PIECE
     * material, otherwise the up-to-3-decimal SCU rendering, with localized unit labels.
     */
    function formatAmount(amount, quantityType) {
        const n = Number(amount);
        if (isNaN(n)) {
            return String(amount);
        }
        if (quantityType === 'PIECE') {
            return `${n.toLocaleString('de-DE', { maximumFractionDigits: 0 })} ${krtI18nText(
                i18n.unitPiece,
                'materialboerseModalI18n.unitPiece',
            )}`;
        }
        return `${n.toLocaleString('de-DE', { maximumFractionDigits: 3 })} ${i18n.unitScu || 'SCU'}`;
    }

    function setFacts(material, quality) {
        setText('[data-mb-fact-material]', material || '—');
        setText(
            '[data-mb-fact-quality]',
            quality != null && quality !== '' ? String(quality) : '—',
        );
    }

    /**
     * Sets the offered-amount field: value, max (kept in state.available), unit label and step for
     * the quantity type, and the max hint. A null max disables the input.
     *
     * @param {string | number | null | undefined} value the initial offered amount, or '' / null for empty.
     * @param {string | number | null | undefined} max the item's current stock as the ceiling, or null when unknown.
     */
    function setAmountField(value, max) {
        /** @type {HTMLInputElement | null} */
        const input = q('[data-mb-amount]');
        const hint = q('[data-mb-amount-hint]');
        const unit = q('[data-mb-amount-unit]');
        const isPiece = state.quantityType === 'PIECE';
        const maxNum = max == null || max === '' ? NaN : Number(max);
        state.available = isNaN(maxNum) ? null : maxNum;
        if (unit) {
            unit.textContent = isPiece
                ? krtI18nText(i18n.unitPiece, 'materialboerseModalI18n.unitPiece')
                : i18n.unitScu || 'SCU';
        }
        if (input) {
            input.step = isPiece ? '1' : '0.001';
            if (state.available != null) {
                input.max = String(state.available);
                input.disabled = false;
            } else {
                input.removeAttribute('max');
                input.disabled = true;
            }
            const valNum = value == null || value === '' ? NaN : Number(value);
            input.value = isNaN(valNum) ? '' : String(valNum);
        }
        if (hint) {
            hint.textContent =
                state.available != null
                    ? fmt(
                          krtI18nText(i18n.amountMax, 'materialboerseModalI18n.amountMax'),
                          formatAmount(state.available, state.quantityType),
                      )
                    : '';
        }
    }

    /** Reads the offered amount from the field as a Number (NaN when empty/invalid). */
    function readOfferedAmount() {
        /** @type {HTMLInputElement | null} */
        const input = q('[data-mb-amount]');
        return input ? Number(input.value) : NaN;
    }

    /**
     * Validates the offered amount against >0 and the item's current stock. On failure it shows an
     * error toast and returns null; on success it returns the numeric amount.
     */
    function validateOfferedAmount() {
        const amount = readOfferedAmount();
        if (isNaN(amount) || amount <= 0) {
            showError(i18n.amountInvalid);
            return null;
        }
        if (state.available != null && amount > state.available + 1e-6) {
            showError(i18n.amountExceeds);
            return null;
        }
        return amount;
    }

    /** Shows a transient client-side validation error toast. */
    function showError(message) {
        if (typeof showFrontendErrorToast === 'function') {
            showFrontendErrorToast(message || i18n.error || '');
        }
    }

    function updateCharCount() {
        /** @type {HTMLTextAreaElement | null} */
        const ta = q('[data-mb-remark]');
        const counter = q('[data-mb-charcount]');
        if (ta && counter) {
            counter.textContent = fmt(
                krtI18nText(i18n.charCounter, 'materialboerseModalI18n.charCounter'),
                ta.value.length.toLocaleString('de-DE'),
            );
        }
    }

    /**
     * Opens the modal. The context's quantityType drives the SCU/PIECE unit; for `edit`, amount is
     * the current offered quantity and available is the item's total stock (the ceiling); for
     * `lager` and `new` amount is the item's stock (the default and the maximum).
     *
     * @param {string} mode `new`, `lager`, `item` or `edit`
     * @param {MaterialReleaseContext | null} [ctx] the seed values
     * @param {((body: any) => void | Promise<unknown>) | { onDone?: (body: any) => void | Promise<unknown>, onCancel?: () => void } | null} [doneOrOpts]
     *     an onDone callback taking the response body, or an `{onDone, onCancel}` pair whose
     *     onCancel fires when the dialog is dismissed without submitting
     */
    function open(mode, ctx, doneOrOpts) {
        ctx ||= {};
        /** @type {((body: any) => void | Promise<unknown>) | null} */
        let onDone = null;
        /** @type {(() => void) | null} */
        let onCancel = null;
        if (typeof doneOrOpts === 'function') {
            onDone = doneOrOpts;
        } else if (doneOrOpts) {
            onDone = doneOrOpts.onDone || null;
            onCancel = doneOrOpts.onCancel || null;
        }
        state = {
            mode,
            itemId: ctx.itemId || null,
            productKey: null,
            offerId: ctx.offerId || null,
            version: ctx.version || null,
            available: null,
            quantityType: ctx.quantityType || null,
            onDone,
            onCancel,
        };
        pickerListOpen = false;
        itemPickerListOpen = false;
        const isNew = mode === 'new';
        const isEdit = mode === 'edit';
        const isItem = mode === 'item';
        setText(
            '#mb-modal-title',
            isEdit ? i18n.editTitle : isItem ? i18n.itemTitle : i18n.releaseTitle,
        );
        setText('[data-mb-submit-label]', isEdit ? i18n.submitSave : i18n.submitRelease);
        toggle('[data-mb-picker]', isNew);
        toggle('[data-mb-item-picker]', isItem);
        toggle('[data-mb-facts]', !isItem);
        toggle('[data-mb-amount-block]', !isItem);
        toggle('[data-mb-qty-block]', isItem);
        toggle('[data-mb-qty-error]', false);
        /** @type {HTMLInputElement | null} */
        const qtyInput = q('[data-mb-item-qty]');
        if (qtyInput) {
            qtyInput.value = '';
        }
        if (!isItem) {
            const isStockItem = ctx.kind === 'ITEM';
            setFacts(ctx.material, isStockItem ? null : ctx.quality);
            toggleQualityFact(!isStockItem);
            if (isNew) {
                setAmountField('', null);
            } else {
                const max = isEdit ? ctx.available : ctx.amount;
                setAmountField(ctx.amount, max);
            }
        }

        const ta = /** @type {HTMLTextAreaElement} */ (q('[data-mb-remark]'));
        ta.value = ctx.remark || '';
        updateCharCount();

        if (isNew) {
            pickerKind = 'MATERIAL';
            const materialRadio = /** @type {HTMLInputElement | null} */ (
                modal.querySelector('[data-mb-kind-radio][value="MATERIAL"]')
            );
            if (materialRadio) {
                materialRadio.checked = true;
            }
            loadPicker('');
        } else if (isItem) {
            /** @type {HTMLInputElement | null} */
            const itemInput = q('[data-mb-item-picker-input]');
            if (itemInput) {
                itemInput.value = '';
            }
            loadItemPicker('');
        }

        lastFocused = /** @type {HTMLElement | null} */ (document.activeElement);
        krtModal.open(modal);
        const first = isNew
            ? q('[data-mb-picker-input]')
            : isItem
              ? q('[data-mb-item-picker-input]')
              : ta;
        if (first) {
            first.focus();
        }
    }

    function hide() {
        krtModal.close(modal);
        if (lastFocused && typeof lastFocused.focus === 'function') {
            lastFocused.focus();
        }
    }

    /** Dismiss without submitting — fires the onCancel hook (e.g. to revert a Lager checkbox). */
    function cancel() {
        const onCancel = state.onCancel;
        hide();
        resetState();
        if (onCancel) {
            onCancel();
        }
    }

    /** Close after a successful submit — fires the onDone hook with the response body. */
    function finish(body) {
        const onDone = state.onDone;
        hide();
        resetState();
        if (onDone) {
            return onDone(body);
        }
    }

    function resetState() {
        state = {
            mode: null,
            itemId: null,
            productKey: null,
            offerId: null,
            version: null,
            available: null,
            quantityType: null,
            onDone: null,
            onCancel: null,
        };
    }

    function loadPicker(query) {
        const seq = ++pickerSeq;
        const url = `/materialboerse/releasable-items?kind=${encodeURIComponent(
            pickerKind,
        )}${query ? `&q=${encodeURIComponent(query)}` : ''}`;
        krtFetch
            .getJson(url)
            .then((items) => {
                if (seq !== pickerSeq) {
                    return;
                }
                pickerItems = Array.isArray(items) ? items : [];
                renderPicker();
            })
            .catch(() => {
                if (seq !== pickerSeq) {
                    return;
                }
                pickerItems = [];
                renderPicker();
            });
    }

    /** Debounces a server-side picker search so every keystroke does not fire its own request. */
    function searchPicker(query) {
        if (pickerSearchTimer) {
            clearTimeout(pickerSearchTimer);
        }
        pickerSearchTimer = setTimeout(() => {
            loadPicker(query);
        }, PICKER_SEARCH_DEBOUNCE_MS);
    }

    /**
     * Reveals the already rendered material picker dropdown.
     */
    function openPickerList() {
        pickerListOpen = true;
        const list = q('[data-mb-picker-list]');
        if (list) {
            list.hidden = false;
        }
    }

    /** Closes the material picker dropdown so it stops covering the fields beneath it. */
    function closePickerList() {
        pickerListOpen = false;
        const list = q('[data-mb-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    /**
     * Applies the Material/Item radio selection (REQ-MARKET-002): clears any picked row and reloads
     * the picker, still closed, filtered to that kind.
     *
     * @param {string} kind the chosen row kind ('MATERIAL' or 'ITEM'); anything else falls back to 'MATERIAL'.
     */
    function setPickerKind(kind) {
        pickerKind = kind === 'ITEM' ? 'ITEM' : 'MATERIAL';
        state.itemId = null;
        state.quantityType = null;
        /** @type {HTMLInputElement | null} */
        const input = q('[data-mb-picker-input]');
        if (input) {
            input.value = '';
        }
        setFacts(null, null);
        toggleQualityFact(pickerKind === 'MATERIAL');
        setAmountField('', null);
        closePickerList();
        loadPicker('');
    }

    function renderPicker() {
        const list = q('[data-mb-picker-list]');
        if (!list) {
            return;
        }
        if (!pickerItems.length) {
            krtHtml.set(
                list,
                krtHtml`<li class="krt-combobox__notice">${i18n.pickerEmpty || ''}</li>`,
            );
            list.hidden = !pickerListOpen;
            return;
        }
        const html = pickerItems.map((it) => {
            const isItem = it.kind === 'ITEM';
            let meta =
                (isItem ? '' : `Q ${it.quality} · `) + formatAmount(it.amount, it.quantityType);
            if (it.locationName) {
                meta += ` · ${it.locationName}`;
            }
            if (it.alreadyReleased) {
                meta += ` · ${i18n.pickerAlready || ''}`;
            }
            const stolenChip = it.stolen
                ? krtHtml`<span class="chip chip--danger" data-testid="stolen-chip">${
                      i18n.pickerStolen || ''
                  }</span> `
                : '';
            return krtHtml`<li class="krt-combobox__option" role="option" data-item-id="${
                it.inventoryItemId
            }" data-kind="${it.kind}" data-material="${it.materialName}" data-quantity-type="${
                it.quantityType
            }" data-quality="${isItem ? '' : it.quality}" data-amount="${it.amount}"><strong>${
                it.materialName
            }</strong> ${stolenChip}<small>${meta}</small></li>`;
        });
        krtHtml.set(list, html);
        list.hidden = !pickerListOpen;
    }

    /** Shows or hides the quality fact — item rows (stock-backed item offers) have no quality. */
    function toggleQualityFact(show) {
        toggle('[data-mb-fact-quality-wrap]', show);
    }

    /**
     * Takes over a picked stock row: its facts, its amount ceiling and its name.
     *
     * @param {Element} li the clicked picker option
     */
    function pickItem(li) {
        state.itemId = li.getAttribute('data-item-id');
        state.quantityType = li.getAttribute('data-quantity-type');
        const isItem = li.getAttribute('data-kind') === 'ITEM';
        const amount = li.getAttribute('data-amount');
        setFacts(li.getAttribute('data-material'), isItem ? null : li.getAttribute('data-quality'));
        toggleQualityFact(!isItem);
        setAmountField(amount, amount);
        /** @type {HTMLInputElement | null} */
        const input = q('[data-mb-picker-input]');
        if (input) {
            input.value = li.getAttribute('data-material') ?? '';
        }
        closePickerList();
    }

    function loadItemPicker(query) {
        const seq = ++itemPickerSeq;
        const url = `/materialboerse/offerable-products${query ? `?q=${encodeURIComponent(query)}` : ''}`;
        krtFetch
            .getJson(url)
            .then((items) => {
                if (seq !== itemPickerSeq) {
                    return;
                }
                productItems = Array.isArray(items) ? items : [];
                renderItemPicker();
            })
            .catch(() => {
                if (seq !== itemPickerSeq) {
                    return;
                }
                productItems = [];
                renderItemPicker();
            });
    }

    /** Debounces a server-side blueprint-product search so every keystroke does not fire a request. */
    function searchItemPicker(query) {
        if (itemPickerSearchTimer) {
            clearTimeout(itemPickerSearchTimer);
        }
        itemPickerSearchTimer = setTimeout(() => {
            loadItemPicker(query);
        }, PICKER_SEARCH_DEBOUNCE_MS);
    }

    /** Opens the item (blueprint-product) picker dropdown on an explicit user gesture. */
    function openItemPickerList() {
        itemPickerListOpen = true;
        const list = q('[data-mb-item-picker-list]');
        if (list) {
            list.hidden = false;
        }
    }

    /** Closes the item picker dropdown so it stops covering the fields beneath it. */
    function closeItemPickerList() {
        itemPickerListOpen = false;
        const list = q('[data-mb-item-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    function renderItemPicker() {
        const list = q('[data-mb-item-picker-list]');
        if (!list) {
            return;
        }
        if (!productItems.length) {
            krtHtml.set(
                list,
                krtHtml`<li class="krt-combobox__notice">${i18n.itemPickerEmpty || ''}</li>`,
            );
            list.hidden = !itemPickerListOpen;
            return;
        }
        const html = productItems.map(
            (it) =>
                krtHtml`<li class="krt-combobox__option" role="option" data-product-key="${
                    it.productKey
                }" data-name="${it.name}"><strong>${it.name}</strong>${
                    it.manufacturerName ? krtHtml` <small>${it.manufacturerName}</small>` : ''
                }</li>`,
        );
        krtHtml.set(list, html);
        list.hidden = !itemPickerListOpen;
    }

    /**
     * Takes over a picked blueprint product.
     *
     * @param {Element} li the clicked picker option
     */
    function pickProduct(li) {
        state.productKey = li.getAttribute('data-product-key');
        /** @type {HTMLInputElement | null} */
        const input = q('[data-mb-item-picker-input]');
        if (input) {
            input.value = li.getAttribute('data-name') ?? '';
        }
        closeItemPickerList();
    }

    function submit() {
        const remark = /** @type {HTMLTextAreaElement} */ (q('[data-mb-remark]')).value;
        if (state.mode === 'edit') {
            const offeredAmount = validateOfferedAmount();
            if (offeredAmount === null) {
                return;
            }
            krtFetch.write({
                method: 'PUT',
                url: `/materialboerse/offers/${state.offerId}/remark/ajax`,
                payload: {
                    offeredAmount,
                    remark,
                    version: Number(state.version),
                },
                successMessage: i18n.remarkSaved,
                errorMessage: i18n.error,
                conflict: i18n.conflict,
                serialize: SERIALIZE_KEY,
                onSuccess(body) {
                    notifyPeers();
                    return finish(body);
                },
            });
            return;
        }
        if (state.mode === 'item') {
            submitItem(remark);
            return;
        }
        if (!state.itemId) {
            return;
        }
        const offeredAmount = validateOfferedAmount();
        if (offeredAmount === null) {
            return;
        }
        krtFetch.write({
            method: 'POST',
            url: '/materialboerse/offers/ajax',
            payload: {
                inventoryItemId: state.itemId,
                offeredAmount,
                remark,
            },
            successMessage: i18n.released,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess(body) {
                notifyPeers();
                return finish(body);
            },
        });
    }

    /**
     * Submits an item offer for the picked blueprint product and a whole quantity ≥ 1; on success
     * notifies peers and closes the modal.
     */
    function submitItem(remark) {
        if (!state.productKey) {
            return;
        }
        /** @type {HTMLInputElement | null} */
        const qtyInput = q('[data-mb-item-qty]');
        const quantity = qtyInput ? parseInt(qtyInput.value, 10) : NaN;
        if (isNaN(quantity) || quantity < 1) {
            toggle('[data-mb-qty-error]', true);
            if (qtyInput) {
                qtyInput.focus();
            }
            return;
        }
        toggle('[data-mb-qty-error]', false);
        krtFetch.write({
            method: 'POST',
            url: '/materialboerse/item-offers/ajax',
            payload: { productKey: state.productKey, quantity, remark },
            successMessage: i18n.itemReleased || i18n.released,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess(body) {
                notifyPeers();
                return finish(body);
            },
        });
    }

    function notifyPeers() {
        if (krtLiveSync) {
            krtLiveSync.sendChanged(MATERIALBOARD_TOPIC, ['board']);
        }
    }

    document.addEventListener('click', (e) => {
        const target = /** @type {Element} */ (e.target);
        if (!isModalOpen()) {
            if (
                target.closest('[data-mb-picker-list] .krt-combobox__option') ||
                target.closest('[data-mb-item-picker-list] .krt-combobox__option')
            ) {
                return;
            }
        }
        if (!target.closest('[data-mb-combobox]') && !target.closest('[data-mb-item-combobox]')) {
            closePickerList();
            closeItemPickerList();
        }
        if (target.closest('[data-mb-modal-close], .mb-modal-close') || target === modal) {
            cancel();
            return;
        }
        if (target.closest('[data-mb-modal-submit]')) {
            submit();
            return;
        }
        if (target.closest('[data-mb-amount-max]')) {
            if (state.available != null) {
                /** @type {HTMLInputElement | null} */
                const input = q('[data-mb-amount]');
                if (input) {
                    input.value = String(state.available);
                }
            }
            return;
        }
        if (target.closest('[data-mb-picker-input]')) {
            openPickerList();
            return;
        }
        if (target.closest('[data-mb-item-picker-input]')) {
            openItemPickerList();
            return;
        }
        const li = target.closest('[data-mb-picker-list] .krt-combobox__option');
        if (li) {
            pickItem(li);
            return;
        }
        const pli = target.closest('[data-mb-item-picker-list] .krt-combobox__option');
        if (pli) {
            pickProduct(pli);
        }
    });

    document.addEventListener('input', (e) => {
        const target = /** @type {HTMLInputElement} */ (e.target);
        if (target.matches('[data-mb-remark]')) {
            updateCharCount();
        } else if (target.matches('[data-mb-picker-input]')) {
            openPickerList();
            searchPicker(target.value);
        } else if (target.matches('[data-mb-item-picker-input]')) {
            openItemPickerList();
            searchItemPicker(target.value);
        } else if (target.matches('[data-mb-item-qty]')) {
            toggle('[data-mb-qty-error]', false);
        }
    });

    document.addEventListener('change', (e) => {
        const target = /** @type {HTMLInputElement} */ (e.target);
        if (target.matches('[data-mb-kind-radio]')) {
            setPickerKind(target.value);
        }
    });

    document.addEventListener('keydown', (e) => {
        if (!isModalOpen()) {
            return;
        }
        if (e.key === 'Escape') {
            if (pickerListOpen || itemPickerListOpen) {
                e.preventDefault();
                closePickerList();
                closeItemPickerList();
            } else {
                cancel();
            }
        } else if (e.key === 'Tab') {
            trapFocus(e);
        }
    });

    function trapFocus(e) {
        const focusable = modal.querySelectorAll(
            'button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])',
        );
        const visible = Array.prototype.filter.call(focusable, (el) => {
            return el.offsetParent !== null && !el.hidden;
        });
        if (!visible.length) {
            return;
        }
        const first = visible[0];
        const last = visible[visible.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }

    window.krtMaterialRelease = { open, close: cancel };
}

initMaterialboerseRelease();
