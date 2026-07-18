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
(function () {
    'use strict';

    let i18n = window.materialboerseI18n || {};
    let modal = document.getElementById('mb-modal');
    if (!modal || !window.krtFetch) {
        return;
    }

    let SERIALIZE_KEY = 'materialboerse';
    // REQ-FE-015 (ADR-0094): the global live-sync room a release/edit publishes to over the shared
    // multiplexed /ws/sync socket (window.krtLiveSync), so board viewers re-pull the list.
    let MATERIALBOARD_TOPIC = 'materialboard';
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
    let lastFocused = null;
    // Server-side picker search: the input debounces a fresh /releasable-items query rather than
    // filtering a once-loaded, alphabetically-capped snapshot in the client — otherwise a material
    // past the server's row cap (e.g. late-alphabet "Savrilium") is unreachable and the release
    // silently no-ops. pickerSeq drops stale responses so the last-typed query always wins. The item
    // (blueprint-product) picker has its own sequence/timer for the same reason (REQ-MARKET-012).
    let pickerSeq = 0;
    let pickerSearchTimer = null;
    let itemPickerSeq = 0;
    let itemPickerSearchTimer = null;
    let PICKER_SEARCH_DEBOUNCE_MS = 200;
    // The two picker dropdowns start CLOSED and open only on an explicit user gesture (clicking into
    // or typing in the combobox), so the floating, absolutely-positioned listbox never covers the
    // fields below it the moment the modal opens — the modal auto-focuses the picker input, and a
    // programmatic focus must not pop the list. renderPicker / renderItemPicker honour these flags
    // instead of force-showing the list on every render (incl. the modal-open prefetch).
    let pickerListOpen = false;
    let itemPickerListOpen = false;
    // The Material/Item radio above the release picker (REQ-MARKET-002): which row kind the
    // /releasable-items query is narrowed to ('MATERIAL' | 'ITEM'). A server-side filter, so a row
    // past the picker's row cap is never hidden. Reset to 'MATERIAL' on every open of 'new' mode.
    let pickerKind = 'MATERIAL';

    function fmt(template, value) {
        return String(template || '').replace('{0}', value);
    }

    function q(sel) {
        return modal.querySelector(sel);
    }

    /**
     * Whether the modal is currently on screen. Per the design-system contract the
     * .krt-modal-overlay default is display:none and a modal is opened by setting an
     * inline display:flex (styles.css), so visibility is read off the inline display,
     * not the hidden attribute.
     */
    function isModalOpen() {
        return (
            window.getComputedStyle(modal).display !== '' &&
            window.getComputedStyle(modal).display !== 'none'
        );
    }

    function setText(sel, text) {
        let el = q(sel);
        if (el) {
            el.textContent = text;
        }
    }

    function toggle(sel, on) {
        let el = q(sel);
        if (el) {
            el.hidden = !on;
        }
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
        });
    }

    /**
     * Formats an amount in the material's own unit: an integer count + the piece unit for a PIECE
     * material, otherwise the up-to-3-decimal SCU rendering. Fixes issue #1182, where every offer
     * was shown as SCU regardless of the material's quantity type. The unit labels come from
     * window.materialboerseI18n (localized), with ASCII fallbacks only if the bootstrap is absent.
     */
    function formatAmount(amount, quantityType) {
        let n = Number(amount);
        if (isNaN(n)) {
            return String(amount);
        }
        if (quantityType === 'PIECE') {
            return (
                n.toLocaleString('de-DE', { maximumFractionDigits: 0 }) +
                ' ' +
                (i18n.unitPiece || 'Stk')
            );
        }
        return (
            n.toLocaleString('de-DE', { maximumFractionDigits: 3 }) + ' ' + (i18n.unitScu || 'SCU')
        );
    }

    function setFacts(material, quality) {
        setText('[data-mb-fact-material]', material || '—');
        setText(
            '[data-mb-fact-quality]',
            quality != null && quality !== '' ? String(quality) : '—',
        );
    }

    /**
     * Sets the editable offered-amount field: its current value, the max the owner may offer (the
     * item's current stock, kept in state.available for validation), the unit label + step for the
     * material's quantity type (SCU vs PIECE, #1199/#1182), and the "max. X verfügbar" hint. A null
     * max disables the input (release-new mode before an item is picked).
     * @param value the initial offered amount, or '' / null for empty.
     * @param max the item's current stock as the ceiling, or null for "unknown / disabled".
     */
    function setAmountField(value, max) {
        let input = q('[data-mb-amount]');
        let hint = q('[data-mb-amount-hint]');
        let unit = q('[data-mb-amount-unit]');
        let isPiece = state.quantityType === 'PIECE';
        let maxNum = max == null || max === '' ? NaN : Number(max);
        state.available = isNaN(maxNum) ? null : maxNum;
        if (unit) {
            unit.textContent = isPiece ? i18n.unitPiece || 'Stück' : i18n.unitScu || 'SCU';
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
            let valNum = value == null || value === '' ? NaN : Number(value);
            input.value = isNaN(valNum) ? '' : String(valNum);
        }
        if (hint) {
            hint.textContent =
                state.available != null
                    ? fmt(
                          i18n.amountMax || 'max. {0} verfügbar',
                          formatAmount(state.available, state.quantityType),
                      )
                    : '';
        }
    }

    /** Reads the offered amount from the field as a Number (NaN when empty/invalid). */
    function readOfferedAmount() {
        let input = q('[data-mb-amount]');
        return input ? Number(input.value) : NaN;
    }

    /**
     * Validates the offered amount against >0 and the item's current stock. On failure it shows an
     * error toast and returns null; on success it returns the numeric amount.
     */
    function validateOfferedAmount() {
        let amount = readOfferedAmount();
        if (isNaN(amount) || amount <= 0) {
            showError(i18n.amountInvalid);
            return null;
        }
        // Tolerate float noise so offering the whole row (value === max) is never rejected.
        if (state.available != null && amount > state.available + 1e-6) {
            showError(i18n.amountExceeds);
            return null;
        }
        return amount;
    }

    /** Shows a transient client-side validation error toast. */
    function showError(message) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message || i18n.error || '');
        }
    }

    function updateCharCount() {
        let ta = q('[data-mb-remark]');
        let counter = q('[data-mb-charcount]');
        if (ta && counter) {
            counter.textContent = fmt(
                i18n.charCounter || '{0} / 20.000',
                ta.value.length.toLocaleString('de-DE'),
            );
        }
    }

    /**
     * Opens the modal.
     * @param mode 'new' | 'lager' | 'edit'
     * @param ctx {itemId?, material?, quantityType?, quality?, amount?, available?, offerId?,
     *        version?, remark?} — quantityType drives the SCU/PIECE unit; for 'edit', amount is the
     *        current offered quantity and available is the item's total stock (the ceiling); for
     *        'lager'/'new' amount is the item's stock (the default + max).
     * @param doneOrOpts either an onDone callback(body), or {onDone, onCancel} — onCancel fires when
     *        the dialog is dismissed without submitting (e.g. to revert a Lager checkbox).
     */
    function open(mode, ctx, doneOrOpts) {
        ctx = ctx || {};
        let onDone = null;
        let onCancel = null;
        if (typeof doneOrOpts === 'function') {
            onDone = doneOrOpts;
        } else if (doneOrOpts) {
            onDone = doneOrOpts.onDone || null;
            onCancel = doneOrOpts.onCancel || null;
        }
        state = {
            mode: mode,
            itemId: ctx.itemId || null,
            productKey: null,
            offerId: ctx.offerId || null,
            version: ctx.version || null,
            available: null,
            quantityType: ctx.quantityType || null,
            onDone: onDone,
            onCancel: onCancel,
        };
        // Every open starts with both picker dropdowns closed; the user opens the relevant one.
        pickerListOpen = false;
        itemPickerListOpen = false;
        let isNew = mode === 'new';
        let isEdit = mode === 'edit';
        let isItem = mode === 'item';
        setText(
            '[data-mb-modal-title]',
            isEdit ? i18n.editTitle : isItem ? i18n.itemTitle : i18n.releaseTitle,
        );
        setText('[data-mb-submit-label]', isEdit ? i18n.submitSave : i18n.submitRelease);
        toggle('[data-mb-picker]', isNew);
        // Item mode (REQ-MARKET-012): show the blueprint-product picker + quantity input, hide the
        // material facts strip + offered-amount block (a craftable item has no live stock, so the
        // picker input alone carries the chosen item's name).
        toggle('[data-mb-item-picker]', isItem);
        toggle('[data-mb-facts]', !isItem);
        toggle('[data-mb-amount-block]', !isItem);
        toggle('[data-mb-qty-block]', isItem);
        toggle('[data-mb-qty-error]', false);
        let qtyInput = q('[data-mb-item-qty]');
        if (qtyInput) {
            qtyInput.value = '';
        }
        if (!isItem) {
            // A stock-backed item row (REQ-MARKET-014), whether an 'edit' of its offer or a fresh
            // 'lager' release from the Mein-Lager item leaf toggle: the amount block edits the
            // whole-unit item quantity (ctx.quantityType === 'PIECE', ctx.available = the backing
            // row's stock) and there is no quality — the facts show the item name only. A material
            // row (ctx.kind absent) keeps its quality fact.
            let isStockItem = ctx.kind === 'ITEM';
            setFacts(ctx.material, isStockItem ? null : ctx.quality);
            toggleQualityFact(!isStockItem);
            if (isNew) {
                // No row picked yet: disable the amount field until the picker selection sets its max
                // (and pickItem decides whether the picked row carries a quality fact).
                setAmountField('', null);
            } else {
                // 'edit': value = current offered amount/quantity, ceiling = item's total stock
                // (ctx.available). 'lager': value = ceiling = the item's stock (whole row by default).
                let max = isEdit ? ctx.available : ctx.amount;
                setAmountField(ctx.amount, max);
            }
        }

        let ta = q('[data-mb-remark]');
        ta.value = ctx.remark || '';
        updateCharCount();

        if (isNew) {
            // Reset the Material/Item radio to Material and prefetch that kind (the user re-picks).
            pickerKind = 'MATERIAL';
            let materialRadio = modal.querySelector('[data-mb-kind-radio][value="MATERIAL"]');
            if (materialRadio) {
                materialRadio.checked = true;
            }
            loadPicker('');
        } else if (isItem) {
            let itemInput = q('[data-mb-item-picker-input]');
            if (itemInput) {
                itemInput.value = '';
            }
            loadItemPicker('');
        }

        lastFocused = document.activeElement;
        // .krt-modal-overlay is display:none by default; open by setting inline display:flex
        // (the app-wide modal contract), NOT by clearing a hidden attribute — the latter left
        // the CSS display:none in place, so the modal opened invisibly (REQ-MARKET-002/007).
        modal.style.display = 'flex';
        let first = isNew
            ? q('[data-mb-picker-input]')
            : isItem
              ? q('[data-mb-item-picker-input]')
              : ta;
        if (first) {
            first.focus();
        }
    }

    function hide() {
        modal.style.display = 'none';
        if (lastFocused && typeof lastFocused.focus === 'function') {
            lastFocused.focus();
        }
    }

    /** Dismiss without submitting — fires the onCancel hook (e.g. to revert a Lager checkbox). */
    function cancel() {
        let onCancel = state.onCancel;
        hide();
        resetState();
        if (onCancel) {
            onCancel();
        }
    }

    /** Close after a successful submit — fires the onDone hook with the response body. */
    function finish(body) {
        let onDone = state.onDone;
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
        let seq = ++pickerSeq;
        let url =
            '/materialboerse/releasable-items?kind=' +
            encodeURIComponent(pickerKind) +
            (query ? '&q=' + encodeURIComponent(query) : '');
        fetch(url, {
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
            credentials: 'same-origin',
        })
            .then(function (r) {
                return r.ok ? r.json() : [];
            })
            .then(function (items) {
                if (seq !== pickerSeq) {
                    return; // a newer search superseded this response
                }
                pickerItems = Array.isArray(items) ? items : [];
                renderPicker();
            })
            .catch(function () {
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
        pickerSearchTimer = setTimeout(function () {
            loadPicker(query);
        }, PICKER_SEARCH_DEBOUNCE_MS);
    }

    /**
     * Opens the material picker dropdown (a user clicked into or typed in the combobox). The rows
     * are already rendered from the modal-open prefetch, so this just reveals them; a still-in-flight
     * fetch fills them in via renderPicker, which respects pickerListOpen.
     */
    function openPickerList() {
        pickerListOpen = true;
        let list = q('[data-mb-picker-list]');
        if (list) {
            list.hidden = false;
        }
    }

    /** Closes the material picker dropdown so it stops covering the fields beneath it. */
    function closePickerList() {
        pickerListOpen = false;
        let list = q('[data-mb-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    /**
     * Applies the Material/Item radio selection (REQ-MARKET-002): narrows the picker to the chosen
     * kind and clears any row already picked (the two kinds carry different facts + units), then
     * reloads the picker from the server filtered to that kind. The list is left closed — the user
     * opens the combobox to pick from the newly filtered set.
     * @param kind the chosen row kind ('MATERIAL' or 'ITEM'); anything else falls back to 'MATERIAL'.
     */
    function setPickerKind(kind) {
        pickerKind = kind === 'ITEM' ? 'ITEM' : 'MATERIAL';
        state.itemId = null;
        state.quantityType = null;
        let input = q('[data-mb-picker-input]');
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
        let list = q('[data-mb-picker-list]');
        if (!list) {
            return;
        }
        if (!pickerItems.length) {
            list.innerHTML =
                '<li class="krt-combobox__notice">' + escapeHtml(i18n.pickerEmpty || '') + '</li>';
            list.hidden = !pickerListOpen;
            return;
        }
        list.innerHTML = pickerItems
            .map(function (it) {
                // The picker carries both material rows and game-item rows (stock-backed item
                // offers, REQ-MARKET-014). An item row has no quality — omit the "Q x ·" prefix and
                // render a blank data-quality so picking it hides the quality fact.
                let isItem = it.kind === 'ITEM';
                let meta =
                    (isItem ? '' : 'Q ' + it.quality + ' · ') +
                    formatAmount(it.amount, it.quantityType) +
                    (it.locationName ? ' · ' + escapeHtml(it.locationName) : '') +
                    (it.alreadyReleased ? ' · ' + escapeHtml(i18n.pickerAlready || '') : '');
                return (
                    '<li class="krt-combobox__option" role="option" data-item-id="' +
                    it.inventoryItemId +
                    '" data-kind="' +
                    escapeHtml(it.kind) +
                    '" data-material="' +
                    escapeHtml(it.materialName) +
                    '" data-quantity-type="' +
                    escapeHtml(it.quantityType) +
                    '" data-quality="' +
                    (isItem ? '' : it.quality) +
                    '" data-amount="' +
                    it.amount +
                    '"><strong>' +
                    escapeHtml(it.materialName) +
                    '</strong> <small>' +
                    meta +
                    '</small></li>'
                );
            })
            .join('');
        list.hidden = !pickerListOpen;
    }

    /** Shows or hides the quality fact — item rows (stock-backed item offers) have no quality. */
    function toggleQualityFact(show) {
        toggle('[data-mb-fact-quality-wrap]', show);
    }

    function pickItem(li) {
        state.itemId = li.getAttribute('data-item-id');
        state.quantityType = li.getAttribute('data-quantity-type');
        let isItem = li.getAttribute('data-kind') === 'ITEM';
        let amount = li.getAttribute('data-amount');
        // An item row has no quality; a material row shows it. Releasing an item row posts the same
        // /offers/ajax payload — the backend detects the game-item row and creates a stock-backed
        // item offer (REQ-MARKET-014).
        setFacts(li.getAttribute('data-material'), isItem ? null : li.getAttribute('data-quality'));
        toggleQualityFact(!isItem);
        // Offer the whole picked row by default; its stock is the ceiling. setAmountField reads
        // state.quantityType (just set) to render the SCU/PIECE unit + step (PIECE for item rows).
        setAmountField(amount, amount);
        let input = q('[data-mb-picker-input]');
        if (input) {
            input.value = li.getAttribute('data-material');
        }
        closePickerList();
    }

    // -------- item (blueprint-product) picker (item offers, REQ-MARKET-012) --------

    function loadItemPicker(query) {
        let seq = ++itemPickerSeq;
        let url =
            '/materialboerse/offerable-products' + (query ? '?q=' + encodeURIComponent(query) : '');
        fetch(url, {
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
            credentials: 'same-origin',
        })
            .then(function (r) {
                return r.ok ? r.json() : [];
            })
            .then(function (items) {
                if (seq !== itemPickerSeq) {
                    return; // a newer search superseded this response
                }
                productItems = Array.isArray(items) ? items : [];
                renderItemPicker();
            })
            .catch(function () {
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
        itemPickerSearchTimer = setTimeout(function () {
            loadItemPicker(query);
        }, PICKER_SEARCH_DEBOUNCE_MS);
    }

    /** Opens the item (blueprint-product) picker dropdown on an explicit user gesture. */
    function openItemPickerList() {
        itemPickerListOpen = true;
        let list = q('[data-mb-item-picker-list]');
        if (list) {
            list.hidden = false;
        }
    }

    /** Closes the item picker dropdown so it stops covering the fields beneath it. */
    function closeItemPickerList() {
        itemPickerListOpen = false;
        let list = q('[data-mb-item-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    function renderItemPicker() {
        let list = q('[data-mb-item-picker-list]');
        if (!list) {
            return;
        }
        if (!productItems.length) {
            list.innerHTML =
                '<li class="krt-combobox__notice">' +
                escapeHtml(i18n.itemPickerEmpty || '') +
                '</li>';
            list.hidden = !itemPickerListOpen;
            return;
        }
        list.innerHTML = productItems
            .map(function (it) {
                let meta = it.manufacturerName ? escapeHtml(it.manufacturerName) : '';
                return (
                    '<li class="krt-combobox__option" role="option" data-product-key="' +
                    escapeHtml(it.productKey) +
                    '" data-name="' +
                    escapeHtml(it.name) +
                    '"><strong>' +
                    escapeHtml(it.name) +
                    '</strong>' +
                    (meta ? ' <small>' + meta + '</small>' : '') +
                    '</li>'
                );
            })
            .join('');
        list.hidden = !itemPickerListOpen;
    }

    function pickProduct(li) {
        state.productKey = li.getAttribute('data-product-key');
        let input = q('[data-mb-item-picker-input]');
        if (input) {
            input.value = li.getAttribute('data-name');
        }
        closeItemPickerList();
    }

    function submit() {
        let remark = q('[data-mb-remark]').value;
        if (state.mode === 'edit') {
            let offeredAmount = validateOfferedAmount();
            if (offeredAmount === null) {
                return;
            }
            window.krtFetch.write({
                method: 'PUT',
                url: '/materialboerse/offers/' + state.offerId + '/remark/ajax',
                payload: {
                    offeredAmount: offeredAmount,
                    remark: remark,
                    version: Number(state.version),
                },
                successMessage: i18n.remarkSaved,
                errorMessage: i18n.error,
                conflict: i18n.conflict,
                serialize: SERIALIZE_KEY,
                onSuccess: function (body) {
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
        // 'new' / 'lager': an item must be chosen first (the amount field stays disabled until then).
        if (!state.itemId) {
            return;
        }
        let offeredAmount = validateOfferedAmount();
        if (offeredAmount === null) {
            return;
        }
        window.krtFetch.write({
            method: 'POST',
            url: '/materialboerse/offers/ajax',
            payload: {
                inventoryItemId: state.itemId,
                offeredAmount: offeredAmount,
                remark: remark,
            },
            successMessage: i18n.released,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess: function (body) {
                notifyPeers();
                return finish(body);
            },
        });
    }

    /**
     * Submits an item offer (#1185): requires a picked blueprint product and a whole quantity ≥ 1
     * (validated client-side; the backend re-validates the product and the quantity). POSTs to the
     * item-offer proxy and, on success, notifies peers and closes the modal like a material release.
     */
    function submitItem(remark) {
        if (!state.productKey) {
            return;
        }
        let qtyInput = q('[data-mb-item-qty]');
        let quantity = qtyInput ? parseInt(qtyInput.value, 10) : NaN;
        if (isNaN(quantity) || quantity < 1) {
            toggle('[data-mb-qty-error]', true);
            if (qtyInput) {
                qtyInput.focus();
            }
            return;
        }
        toggle('[data-mb-qty-error]', false);
        window.krtFetch.write({
            method: 'POST',
            url: '/materialboerse/item-offers/ajax',
            payload: { productKey: state.productKey, quantity: quantity, remark: remark },
            successMessage: i18n.itemReleased || i18n.released,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess: function (body) {
                notifyPeers();
                return finish(body);
            },
        });
    }

    function notifyPeers() {
        if (window.krtLiveSync) {
            window.krtLiveSync.sendChanged(MATERIALBOARD_TOPIC, ['board']);
        }
    }

    // -------- events (scoped to the modal DOM) --------

    document.addEventListener('click', function (e) {
        if (!isModalOpen()) {
            if (
                e.target.closest('[data-mb-picker-list] .krt-combobox__option') ||
                e.target.closest('[data-mb-item-picker-list] .krt-combobox__option')
            ) {
                return;
            }
        }
        // A click outside either combobox (its input or its listbox) dismisses an open dropdown, so
        // the floating list never lingers over the other inputs. Clicks on the input / an option
        // stay inside the .krt-combobox wrapper (data-mb-combobox / data-mb-item-combobox) and are
        // handled by the dedicated branches below.
        if (
            !e.target.closest('[data-mb-combobox]') &&
            !e.target.closest('[data-mb-item-combobox]')
        ) {
            closePickerList();
            closeItemPickerList();
        }
        if (e.target.closest('[data-mb-modal-close]') || e.target === modal) {
            cancel();
            return;
        }
        if (e.target.closest('[data-mb-modal-submit]')) {
            submit();
            return;
        }
        if (e.target.closest('[data-mb-amount-max]')) {
            // "Alles": fill the offered amount with the item's full available stock.
            if (state.available != null) {
                let input = q('[data-mb-amount]');
                if (input) {
                    input.value = String(state.available);
                }
            }
            return;
        }
        // Clicking into a picker input opens its dropdown. The modal-open auto-focus is a
        // programmatic focus() with no click, so the list stays closed until the user reaches for it.
        if (e.target.closest('[data-mb-picker-input]')) {
            openPickerList();
            return;
        }
        if (e.target.closest('[data-mb-item-picker-input]')) {
            openItemPickerList();
            return;
        }
        let li = e.target.closest('[data-mb-picker-list] .krt-combobox__option');
        if (li) {
            pickItem(li);
            return;
        }
        let pli = e.target.closest('[data-mb-item-picker-list] .krt-combobox__option');
        if (pli) {
            pickProduct(pli);
        }
    });

    document.addEventListener('input', function (e) {
        if (e.target.matches('[data-mb-remark]')) {
            updateCharCount();
        } else if (e.target.matches('[data-mb-picker-input]')) {
            openPickerList();
            searchPicker(e.target.value);
        } else if (e.target.matches('[data-mb-item-picker-input]')) {
            openItemPickerList();
            searchItemPicker(e.target.value);
        } else if (e.target.matches('[data-mb-item-qty]')) {
            toggle('[data-mb-qty-error]', false);
        }
    });

    document.addEventListener('change', function (e) {
        if (e.target.matches('[data-mb-kind-radio]')) {
            setPickerKind(e.target.value);
        }
    });

    document.addEventListener('keydown', function (e) {
        if (!isModalOpen()) {
            return;
        }
        if (e.key === 'Escape') {
            // Escape first dismisses an open picker dropdown; only when none is open does it close
            // the whole modal (mirrors the shared combobox's escape-closes-the-list-first behaviour).
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
        let focusable = modal.querySelectorAll(
            'button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])',
        );
        let visible = Array.prototype.filter.call(focusable, function (el) {
            return el.offsetParent !== null && !el.hidden;
        });
        if (!visible.length) {
            return;
        }
        let first = visible[0];
        let last = visible[visible.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }

    window.krtMaterialRelease = { open: open, close: cancel };
})();
