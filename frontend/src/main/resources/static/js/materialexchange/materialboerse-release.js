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

import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { krtHooks } from '/js/kernel/krt-hooks.js';
import { localisedName } from '/js/kernel/krt-localised-name.js';
import { bindExchangePicker } from '/js/materialexchange/exchange-picker.js';
import { exchangeItemSource, itemIdentityOf } from '/js/materialexchange/exchange-item-options.js';

/** @import { ExchangeItemOption } from '/js/materialexchange/exchange-item-options.js' */
/** @import { ExchangePickerSource } from '/js/materialexchange/exchange-picker.js' */

/** @typedef {ApiDto<'MaterialExchangeReleasableItemDto'>} ReleasableRow */

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
     * @property {string | null} gameItemId the picked item no blueprint produces
     * @property {string | null} offerId the offer being edited
     * @property {string | null} version the offer's version
     * @property {number | null} available the stock ceiling of the amount field
     * @property {string | null} quantityType `SCU`, `PIECE` or null
     * @property {((body: any) => void | Promise<unknown>) | null} onDone runs with the response body on success
     * @property {(() => void) | null} onCancel runs when the dialog is dismissed
     */

    const i18n = { ...window.materialboerseModalI18n, ...window.materialboerseI18n };
    const modal = /** @type {HTMLElement} */ (document.getElementById('mb-modal'));
    if (!modal) {
        return;
    }

    const SERIALIZE_KEY = 'materialboerse';
    const MATERIALBOARD_TOPIC = 'materialboard';
    const KIND_RADIO = 'input[name="mb-release-kind"]';
    /** @type {MaterialReleaseState} */
    let state = {
        mode: null,
        itemId: null,
        productKey: null,
        gameItemId: null,
        offerId: null,
        version: null,
        available: null,
        quantityType: null,
        onDone: null,
        onCancel: null,
    };
    /** @type {HTMLElement | null} */
    let lastFocused = null;
    let pickerKind = 'MATERIAL';

    /**
     * Fills the `{0}` placeholder of a message template.
     *
     * @param {string | null | undefined} template the localized template
     * @param {string} value the replacement
     * @returns {string} the filled text
     */
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

    /**
     * Sets the text of an element inside the modal.
     *
     * @param {string} sel the selector
     * @param {string} text the new text
     */
    function setText(sel, text) {
        const el = q(sel);
        if (el) {
            el.textContent = text;
        }
    }

    /**
     * Shows or hides an element inside the modal.
     *
     * @param {string} sel the selector
     * @param {boolean} on true to show
     */
    function toggle(sel, on) {
        const el = q(sel);
        if (el) {
            el.hidden = !on;
        }
    }

    /**
     * Formats an amount in the material's own unit: an integer count + the piece unit for a PIECE
     * material, otherwise the up-to-3-decimal SCU rendering, with localized unit labels.
     *
     * @param {string | number | null | undefined} amount the amount
     * @param {string | null | undefined} quantityType `SCU` or `PIECE`
     * @returns {string} the rendered amount with its unit
     */
    function formatAmount(amount, quantityType) {
        const n = Number(amount);
        if (isNaN(n)) {
            return String(amount);
        }
        if (quantityType === 'PIECE') {
            return `${n.toLocaleString(document.documentElement.lang || undefined, { maximumFractionDigits: 0 })} ${krtI18nText(
                i18n.unitPiece,
                'materialboerseModalI18n.unitPiece',
            )}`;
        }
        return `${n.toLocaleString(document.documentElement.lang || undefined, { maximumFractionDigits: 3 })} ${i18n.unitScu || 'SCU'}`;
    }

    /**
     * Shows the picked material and quality in the fact row.
     *
     * @param {string | null | undefined} material the material or item name
     * @param {string | number | null | undefined} quality the quality tier
     */
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

    /**
     * Shows a transient client-side validation error toast.
     *
     * @param {string | null | undefined} message the text; the generic error text when empty
     */
    function showError(message) {
        showFrontendErrorToast(message || i18n.error || '');
    }

    function updateCharCount() {
        /** @type {HTMLTextAreaElement | null} */
        const ta = q('[data-mb-remark]');
        const counter = q('[data-mb-charcount]');
        if (ta && counter) {
            counter.textContent = fmt(
                krtI18nText(i18n.charCounter, 'materialboerseModalI18n.charCounter'),
                ta.value.length.toLocaleString(document.documentElement.lang || undefined),
            );
        }
    }

    /**
     * Labels a releasable stock row with its name, quality, stock, location and markers, so rows
     * of the same material stay apart in the picker.
     *
     * @param {ReleasableRow} row the stock row
     * @returns {string} the option label
     */
    function rowLabel(row) {
        const parts = [localisedName(row.materialName, row.materialNameDe)];
        if (row.kind !== 'ITEM') {
            parts.push(`Q ${row.quality}`);
        }
        parts.push(formatAmount(row.amount, row.quantityType));
        if (row.locationName) {
            parts.push(row.locationName);
        }
        if (row.alreadyReleased) {
            parts.push(i18n.pickerAlready || '');
        }
        if (row.stolen) {
            parts.push(i18n.pickerStolen || '');
        }
        return parts.join(' · ');
    }

    /**
     * The caller's releasable stock rows of the chosen kind; the kind is applied server-side
     * before the row cap (REQ-MARKET-002).
     *
     * @type {ExchangePickerSource<ReleasableRow>}
     */
    const releasableRowSource = {
        url: (query) =>
            `/materialboerse/releasable-items?kind=${encodeURIComponent(pickerKind)}${
                query ? `&q=${encodeURIComponent(query)}` : ''
            }`,
        rows: (body) => (Array.isArray(body) ? body : []),
        value: (row) => row.inventoryItemId ?? '',
        label: rowLabel,
    };

    /**
     * Shows or hides the quality fact — item rows (stock-backed item offers) have no quality.
     *
     * @param {boolean} show true to show
     */
    function toggleQualityFact(show) {
        toggle('[data-mb-fact-quality-wrap]', show);
    }

    /** Drops the picked stock row: its facts and its amount ceiling. */
    function resetRowPick() {
        state.itemId = null;
        state.quantityType = null;
        setFacts(null, null);
        toggleQualityFact(pickerKind === 'MATERIAL');
        setAmountField('', null);
    }

    /**
     * Takes over a picked stock row — its facts and its amount ceiling — or drops the pick.
     *
     * @param {ReleasableRow | null} row the picked row, or null
     */
    function pickRow(row) {
        if (!row) {
            resetRowPick();
            return;
        }
        const isItem = row.kind === 'ITEM';
        state.itemId = row.inventoryItemId ?? null;
        state.quantityType = row.quantityType ?? null;
        setFacts(localisedName(row.materialName, row.materialNameDe), isItem ? null : row.quality);
        toggleQualityFact(!isItem);
        setAmountField(row.amount, row.amount);
    }

    /**
     * Takes over a picked item, or drops the pick.
     *
     * @param {ExchangeItemOption | null} option the picked item, or null
     */
    function pickItemOption(option) {
        const identity = itemIdentityOf(option);
        state.productKey = identity.productKey;
        state.gameItemId = identity.gameItemId;
    }

    const rowPicker = bindExchangePicker(
        /** @type {HTMLSelectElement} */ (q('[data-mb-row-select]')),
        releasableRowSource,
        { placeholder: i18n.pickerPlaceholder, noResults: i18n.pickerEmpty },
        pickRow,
    );
    const itemPicker = bindExchangePicker(
        /** @type {HTMLSelectElement} */ (q('[data-mb-item-select]')),
        exchangeItemSource,
        { placeholder: i18n.itemPickerPlaceholder, noResults: i18n.itemPickerEmpty },
        pickItemOption,
    );

    /**
     * Applies the Material/Item segment (REQ-MARKET-002): drops any picked row; the next search
     * lists only rows of that kind.
     *
     * @param {string} kind the chosen row kind ('MATERIAL' or 'ITEM'); anything else falls back to 'MATERIAL'.
     */
    function setPickerKind(kind) {
        pickerKind = kind === 'ITEM' ? 'ITEM' : 'MATERIAL';
        rowPicker.clear();
        resetRowPick();
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
            gameItemId: null,
            offerId: ctx.offerId || null,
            version: ctx.version || null,
            available: null,
            quantityType: ctx.quantityType || null,
            onDone,
            onCancel,
        };
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
                modal.querySelector(`${KIND_RADIO}[value="MATERIAL"]`)
            );
            if (materialRadio) {
                materialRadio.checked = true;
            }
            rowPicker.clear();
        } else if (isItem) {
            itemPicker.clear();
        }

        lastFocused = /** @type {HTMLElement | null} */ (document.activeElement);
        krtModal.open(modal);
        if (isNew) {
            rowPicker.focus();
        } else if (isItem) {
            itemPicker.focus();
        } else {
            ta.focus();
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

    /**
     * Close after a successful submit — fires the onDone hook with the response body.
     *
     * @param {any} body the response body
     * @returns {void | Promise<unknown>} the hook's result
     */
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
            gameItemId: null,
            offerId: null,
            version: null,
            available: null,
            quantityType: null,
            onDone: null,
            onCancel: null,
        };
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
     * Submits an item offer for the picked item and a whole quantity ≥ 1; on success notifies peers
     * and closes the modal.
     *
     * @param {string} remark the remark text
     */
    function submitItem(remark) {
        if (!state.productKey && !state.gameItemId) {
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
            payload: {
                productKey: state.productKey,
                gameItemId: state.gameItemId,
                quantity,
                remark,
            },
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
        krtPageSections.publish(MATERIALBOARD_TOPIC, ['board']);
    }

    document.addEventListener('click', (e) => {
        const target = /** @type {Element} */ (e.target);
        if (target.closest('[data-mb-modal-close], .mb-modal-close') || target === modal) {
            cancel();
            return;
        }
        if (target.closest('[data-mb-modal-submit]')) {
            submit();
            return;
        }
        if (target.closest('[data-mb-amount-max]') && state.available != null) {
            /** @type {HTMLInputElement | null} */
            const input = q('[data-mb-amount]');
            if (input) {
                input.value = String(state.available);
            }
        }
    });

    document.addEventListener('input', (e) => {
        const target = /** @type {HTMLInputElement} */ (e.target);
        if (target.matches('[data-mb-remark]')) {
            updateCharCount();
        } else if (target.matches('[data-mb-item-qty]')) {
            toggle('[data-mb-qty-error]', false);
        }
    });

    document.addEventListener('change', (e) => {
        const target = /** @type {HTMLInputElement} */ (e.target);
        if (modal.contains(target) && target.matches(KIND_RADIO)) {
            setPickerKind(target.value);
        }
    });

    document.addEventListener('keydown', (e) => {
        if (!isModalOpen()) {
            return;
        }
        if (e.key === 'Escape' && !e.defaultPrevented) {
            cancel();
        } else if (e.key === 'Tab') {
            trapFocus(e);
        }
    });

    /**
     * Keeps Tab inside the modal.
     *
     * @param {KeyboardEvent} e the Tab key event
     */
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

    krtHooks.materialRelease = { open, close: cancel };
    window.krtMaterialRelease = krtHooks.materialRelease;
}

initMaterialboerseRelease();
