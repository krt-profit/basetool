/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Materialbörse Gesuche create / edit modal (REQ-MARKET-015/016). Used by the Materialbörse board
 * page ("Material suchen" / "Item suchen" / "Gesuch bearbeiten"). Unlike the offer release modal a
 * request has NO backing Lager row: the material/item is chosen from the catalogue, the desired
 * quantity is a free number and an optional minimum quality applies to both kinds. Exposes
 * window.krtMaterialRequest. Markup lives in fragments/materialgesuch-modal.html; strings come from
 * window.materialgesuchI18n. No native dialogs, no full-page reload.
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

/**
 * The catalogue material search of a material request.
 *
 * @type {ExchangePickerSource<ApiDto<'MaterialDto'>>}
 */
const requestMaterialSource = {
    url: (query) =>
        `/materialboerse/request-materials${query ? `?q=${encodeURIComponent(query)}` : ''}`,
    rows: (page) => (page && Array.isArray(page.content) ? page.content : []),
    value: (material) => material.id ?? '',
    label: (material) => localisedName(material.name, material.nameDe),
};

function initMaterialgesuchModal() {
    /**
     * The seed values `open` takes from its caller; absent fields are blank.
     *
     * @typedef {object} MaterialRequestContext
     * @property {string | null} [requestId] the request being edited
     * @property {string | null} [version] the request's version
     * @property {string | null} [kind] `MATERIAL` or `ITEM`
     * @property {string | null} [subject] the requested material or item name
     * @property {string | number | null} [minQuality] the minimum quality
     * @property {string | number | null} [amount] the desired quantity
     * @property {string | null} [quantityType] `SCU` or `PIECE`
     * @property {string | null} [remark] the remark text
     */

    /**
     * What a dialog session remembers between `open` and its close.
     *
     * @typedef {object} MaterialRequestState
     * @property {string | null} mode `new`, `edit` or null while closed
     * @property {string | null} requestId the request being edited
     * @property {string | null} version the request's version
     * @property {string} kind `MATERIAL` or `ITEM`
     * @property {string | null} materialId the picked material
     * @property {string | null} productKey the picked blueprint product
     * @property {string | null} gameItemId the picked item no blueprint produces
     * @property {string} quantityType `SCU` or `PIECE`
     * @property {((body: any) => void | Promise<unknown>) | null} onDone runs with the response body on success
     * @property {(() => void) | null} onCancel runs when the dialog is dismissed
     */

    const i18n = { ...window.materialgesuchModalI18n, ...window.materialgesuchI18n };
    const modal = /** @type {HTMLElement} */ (document.getElementById('mg-modal'));
    if (!modal) {
        return;
    }

    const SERIALIZE_KEY = 'materialgesuch';
    const MATERIALBOARD_TOPIC = 'materialboard';
    const KIND_RADIO = 'input[name="mg-request-kind"]';
    /** @type {MaterialRequestState} */
    let state = {
        mode: null,
        requestId: null,
        version: null,
        kind: 'MATERIAL',
        materialId: null,
        productKey: null,
        gameItemId: null,
        quantityType: 'SCU',
        onDone: null,
        onCancel: null,
    };
    /** @type {HTMLElement | null} */
    let lastFocused = null;

    /**
     * Fills the `{0}` placeholder of a localized template.
     *
     * @param {string | undefined} template the localized template
     * @param {string} value the value to insert
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
     * @param {string | null} text the text
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
     * Shows an error toast.
     *
     * @param {string | undefined} message the text; the generic error label when empty
     */
    function showError(message) {
        showFrontendErrorToast(message || i18n.error || '');
    }

    function updateCharCount() {
        /** @type {HTMLTextAreaElement | null} */
        const ta = q('[data-mg-remark]');
        const counter = q('[data-mg-charcount]');
        if (ta && counter) {
            counter.textContent = fmt(
                krtI18nText(i18n.charCounter, 'materialgesuchModalI18n.charCounter'),
                ta.value.length.toLocaleString(document.documentElement.lang || undefined),
            );
        }
    }

    /** Sets the quantity field's unit label + numeric step for the current kind/material type. */
    function applyQuantityUnit() {
        const isPiece = state.kind === 'ITEM' || state.quantityType === 'PIECE';
        const unit = q('[data-mg-qty-unit]');
        /** @type {HTMLInputElement | null} */
        const input = q('[data-mg-qty]');
        if (unit) {
            unit.textContent = isPiece
                ? krtI18nText(i18n.unitPiece, 'materialgesuchModalI18n.unitPiece')
                : i18n.unitScu || 'SCU';
        }
        if (input) {
            input.step = isPiece ? '1' : '0.001';
        }
    }

    /**
     * Takes over a picked material, or drops the pick: its id and its unit.
     *
     * @param {ApiDto<'MaterialDto'> | null} material the picked material, or null
     */
    function pickMaterial(material) {
        state.materialId = material?.id ?? null;
        state.quantityType = material?.quantityType === 'PIECE' ? 'PIECE' : 'SCU';
        applyQuantityUnit();
    }

    /**
     * Takes over a picked item, or drops the pick.
     *
     * @param {ExchangeItemOption | null} option the picked item, or null
     */
    function pickItem(option) {
        const identity = itemIdentityOf(option);
        state.productKey = identity.productKey;
        state.gameItemId = identity.gameItemId;
    }

    const materialPicker = bindExchangePicker(
        /** @type {HTMLSelectElement} */ (q('[data-mg-material-select]')),
        requestMaterialSource,
        { placeholder: i18n.materialPickerPlaceholder, noResults: i18n.materialPickerEmpty },
        pickMaterial,
    );
    const itemPicker = bindExchangePicker(
        /** @type {HTMLSelectElement} */ (q('[data-mg-item-select]')),
        exchangeItemSource,
        { placeholder: i18n.itemPickerPlaceholder, noResults: i18n.itemPickerEmpty },
        pickItem,
    );

    /**
     * Opens the modal. For `new` the context carries the kind; for `edit` the request's fields.
     *
     * @param {string} mode `new` or `edit`
     * @param {MaterialRequestContext | null} [ctx] the seed values
     * @param {((body: any) => void | Promise<unknown>) | { onDone?: (body: any) => void | Promise<unknown>, onCancel?: () => void } | null} [doneOrOpts]
     *     an onDone callback taking the response body, or an `{onDone, onCancel}` pair
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
        const isEdit = mode === 'edit';
        state = {
            mode,
            requestId: ctx.requestId || null,
            version: ctx.version || null,
            kind: ctx.kind === 'ITEM' ? 'ITEM' : 'MATERIAL',
            materialId: null,
            productKey: null,
            gameItemId: null,
            quantityType: ctx.quantityType === 'PIECE' ? 'PIECE' : 'SCU',
            onDone,
            onCancel,
        };

        if (isEdit) {
            setText('#mg-modal-title', i18n.editTitle);
        } else {
            applyNewRequestTitle();
        }
        setText('[data-mg-submit-label]', isEdit ? i18n.submitSave : i18n.submitCreate);
        toggle('[data-mg-picker]', !isEdit);
        toggle('[data-mg-subject-facts]', isEdit);
        toggle('[data-mg-quality-error]', false);
        toggle('[data-mg-qty-error]', false);
        setKindRadio(state.kind);

        if (isEdit) {
            setText('[data-mg-fact-subject]', ctx.subject || '—');
            setValue('[data-mg-min-quality]', ctx.minQuality != null ? ctx.minQuality : '');
            setValue('[data-mg-qty]', ctx.amount != null ? ctx.amount : '');
        } else {
            applyKindBlocks();
            materialPicker.clear();
            itemPicker.clear();
            setValue('[data-mg-min-quality]', '');
            setValue('[data-mg-qty]', '');
        }
        applyQuantityUnit();

        const ta = /** @type {HTMLTextAreaElement} */ (q('[data-mg-remark]'));
        ta.value = ctx.remark || '';
        updateCharCount();

        lastFocused = /** @type {HTMLElement | null} */ (document.activeElement);
        krtModal.open(modal);
        if (isEdit) {
            q('[data-mg-qty]')?.focus();
        } else if (state.kind === 'ITEM') {
            itemPicker.focus();
        } else {
            materialPicker.focus();
        }
    }

    /**
     * Sets the value of an input inside the modal.
     *
     * @param {string} sel the selector
     * @param {unknown} value the value; null, undefined and the empty string clear the input
     */
    function setValue(sel, value) {
        /** @type {HTMLInputElement | null} */
        const el = q(sel);
        if (el) {
            el.value = value == null || value === '' ? '' : String(value);
        }
    }

    /**
     * Checks the kind segment with the value.
     *
     * @param {string} kind `MATERIAL` or `ITEM`
     */
    function setKindRadio(kind) {
        const radio = /** @type {HTMLInputElement | null} */ (
            modal.querySelector(`${KIND_RADIO}[value="${kind}"]`)
        );
        if (radio) {
            radio.checked = true;
        }
    }

    /** Titles a new-request dialog by the current request kind. */
    function applyNewRequestTitle() {
        setText(
            '#mg-modal-title',
            state.kind === 'ITEM' ? i18n.itemRequestTitle : i18n.requestTitle,
        );
    }

    /** Shows the material picker for a MATERIAL request, the item picker for an ITEM request. */
    function applyKindBlocks() {
        const isItem = state.kind === 'ITEM';
        toggle('[data-mg-material-block]', !isItem);
        toggle('[data-mg-item-block]', isItem);
    }

    /**
     * Switches the request kind and drops the pick.
     *
     * @param {string} kind `ITEM` for an item request, anything else for a material request
     */
    function setKind(kind) {
        state.kind = kind === 'ITEM' ? 'ITEM' : 'MATERIAL';
        state.materialId = null;
        state.productKey = null;
        state.gameItemId = null;
        state.quantityType = 'SCU';
        materialPicker.clear();
        itemPicker.clear();
        applyNewRequestTitle();
        applyKindBlocks();
        applyQuantityUnit();
    }

    function hide() {
        krtModal.close(modal);
        if (lastFocused && typeof lastFocused.focus === 'function') {
            lastFocused.focus();
        }
    }

    function cancel() {
        const onCancel = state.onCancel;
        hide();
        if (onCancel) {
            onCancel();
        }
    }

    /**
     * Closes the dialog and hands the response body to the opener's callback.
     *
     * @param {any} body the response body
     * @returns {void | Promise<unknown>} what the callback returns
     */
    function finish(body) {
        const onDone = state.onDone;
        hide();
        if (onDone) {
            return onDone(body);
        }
    }

    /** Reads the optional min-quality field; returns undefined when blank, null on an invalid value. */
    function readMinQuality() {
        /** @type {HTMLInputElement | null} */
        const input = q('[data-mg-min-quality]');
        const raw = input ? input.value.trim() : '';
        if (raw === '') {
            return undefined;
        }
        const n = Number(raw);
        if (isNaN(n) || n < 0 || n > 1000 || Math.floor(n) !== n) {
            return null;
        }
        return n;
    }

    /** Reads the desired-quantity field; returns null when non-positive or (for items) non-whole. */
    function readQuantity() {
        /** @type {HTMLInputElement | null} */
        const input = q('[data-mg-qty]');
        const n = input ? Number(input.value) : NaN;
        if (isNaN(n) || n <= 0) {
            return null;
        }
        const isPiece = state.kind === 'ITEM' || state.quantityType === 'PIECE';
        if (isPiece && Math.floor(n) !== n) {
            return null;
        }
        return n;
    }

    function submit() {
        const remark = /** @type {HTMLTextAreaElement} */ (q('[data-mg-remark]')).value;
        const minQuality = readMinQuality();
        if (minQuality === null) {
            toggle('[data-mg-quality-error]', true);
            return;
        }
        toggle('[data-mg-quality-error]', false);
        const quantity = readQuantity();
        if (quantity === null) {
            toggle('[data-mg-qty-error]', true);
            const qtyInput = q('[data-mg-qty]');
            if (qtyInput) {
                qtyInput.focus();
            }
            return;
        }
        toggle('[data-mg-qty-error]', false);

        if (state.mode === 'edit') {
            krtFetch.write({
                method: 'PUT',
                url: `/materialboerse/requests/${state.requestId}/ajax`,
                payload: {
                    desiredAmount: quantity,
                    minQuality: minQuality === undefined ? null : minQuality,
                    remark,
                    version: Number(state.version),
                },
                successMessage: i18n.updated,
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

        if (state.kind === 'ITEM') {
            if (!state.productKey && !state.gameItemId) {
                showError(i18n.subjectRequired);
                return;
            }
            krtFetch.write({
                method: 'POST',
                url: '/materialboerse/item-requests/ajax',
                payload: {
                    productKey: state.productKey,
                    gameItemId: state.gameItemId,
                    minQuality: minQuality === undefined ? null : minQuality,
                    quantity,
                    remark,
                },
                successMessage: i18n.created,
                errorMessage: i18n.error,
                serialize: SERIALIZE_KEY,
                onSuccess(body) {
                    notifyPeers();
                    return finish(body);
                },
            });
            return;
        }

        if (!state.materialId) {
            showError(i18n.subjectRequired);
            return;
        }
        krtFetch.write({
            method: 'POST',
            url: '/materialboerse/requests/ajax',
            payload: {
                materialId: state.materialId,
                minQuality: minQuality === undefined ? null : minQuality,
                requestedAmount: quantity,
                remark,
            },
            successMessage: i18n.created,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess(body) {
                notifyPeers();
                return finish(body);
            },
        });
    }

    function notifyPeers() {
        krtPageSections.publish(MATERIALBOARD_TOPIC, ['requests']);
    }

    document.addEventListener('click', (e) => {
        const target = /** @type {Element} */ (e.target);
        if (target.closest('[data-mg-modal-close], .mg-modal-close') || target === modal) {
            cancel();
            return;
        }
        if (target.closest('[data-mg-modal-submit]')) {
            submit();
        }
    });

    document.addEventListener('input', (e) => {
        const target = /** @type {HTMLInputElement} */ (e.target);
        if (target.matches('[data-mg-remark]')) {
            updateCharCount();
        } else if (target.matches('[data-mg-qty]')) {
            toggle('[data-mg-qty-error]', false);
        } else if (target.matches('[data-mg-min-quality]')) {
            toggle('[data-mg-quality-error]', false);
        }
    });

    document.addEventListener('change', (e) => {
        const target = /** @type {HTMLInputElement} */ (e.target);
        if (modal.contains(target) && target.matches(KIND_RADIO)) {
            setKind(target.value);
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
     * Keeps Tab focus inside the modal.
     *
     * @param {KeyboardEvent} e the Tab keydown event
     */
    function trapFocus(e) {
        const focusable = modal.querySelectorAll(
            'button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])',
        );
        const visible = /** @type {HTMLElement[]} */ (
            Array.prototype.filter.call(focusable, (/** @type {HTMLElement} */ el) => {
                return el.offsetParent !== null && !el.hidden;
            })
        );
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

    krtHooks.materialRequest = { open, close: cancel };
    window.krtMaterialRequest = krtHooks.materialRequest;
}

initMaterialgesuchModal();
