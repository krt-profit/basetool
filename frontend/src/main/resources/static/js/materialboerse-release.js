/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Shared Materialbörse release / edit modal (REQ-MARKET-002/007). Used by both the
 * Materialbörse board page ("Material anbieten" / "Bemerkung bearbeiten") and the
 * Mein-Lager page ("Für Börse freigeben" checkbox). Exposes window.krtMaterialRelease.
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
    let state = {
        mode: null,
        itemId: null,
        productKey: null,
        offerId: null,
        version: null,
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
        return modal.style.display !== '' && modal.style.display !== 'none';
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

    function setFacts(material, quality, amount, quantityType) {
        setText('[data-mb-fact-material]', material || '—');
        setText(
            '[data-mb-fact-quality]',
            quality != null && quality !== '' ? String(quality) : '—',
        );
        setText(
            '[data-mb-fact-amount]',
            amount != null && amount !== '' ? formatAmount(amount, quantityType) : '—',
        );
    }

    /** Shows/hides an element inside the modal via the hidden attribute. */
    function showEl(sel, on) {
        let el = q(sel);
        if (el) {
            el.hidden = !on;
        }
    }

    /** Formats a whole-piece item quantity (item offers are always counted in pieces). */
    function formatQuantity(quantity) {
        let n = Number(quantity);
        if (isNaN(n)) {
            return String(quantity);
        }
        return (
            n.toLocaleString('de-DE', { maximumFractionDigits: 0 }) +
            ' ' +
            (i18n.unitPiece || 'Stk')
        );
    }

    /**
     * Populates and toggles the read-only fact strip for the given mode/context (REQ-MARKET-012). A
     * material offer shows Material + Qualität + Menge (live/derived from the Lager row); an item
     * offer shows Item + Menge with no quality — and the Menge fact only in 'edit' mode (in 'item'
     * mode the quantity is entered in the input field below, so the fact cell is hidden).
     */
    function applyFacts(mode, ctx) {
        let isItem = mode === 'item' || (mode === 'edit' && ctx.kind === 'ITEM');
        let label = q('[data-mb-fact-primary-label]');
        if (label) {
            label.textContent = isItem ? i18n.factItem || 'Item' : i18n.factMaterial || 'Material';
        }
        setText('[data-mb-fact-material]', (isItem ? ctx.itemName : ctx.material) || '—');
        showEl('[data-mb-fact-quality-cell]', !isItem);
        if (!isItem) {
            setText(
                '[data-mb-fact-quality]',
                ctx.quality != null && ctx.quality !== '' ? String(ctx.quality) : '—',
            );
        }
        let showAmount = !isItem || mode === 'edit';
        showEl('[data-mb-fact-amount-cell]', showAmount);
        if (showAmount) {
            if (isItem) {
                setText(
                    '[data-mb-fact-amount]',
                    ctx.itemQuantity != null && ctx.itemQuantity !== ''
                        ? formatQuantity(ctx.itemQuantity)
                        : '—',
                );
            } else {
                setText(
                    '[data-mb-fact-amount]',
                    ctx.amount != null && ctx.amount !== ''
                        ? formatAmount(ctx.amount, ctx.quantityType)
                        : '—',
                );
            }
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
     * @param ctx {itemId?, material?, quantityType?, quality?, amount?, offerId?, version?, remark?}
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
            onDone: onDone,
            onCancel: onCancel,
        };
        let isNew = mode === 'new';
        let isEdit = mode === 'edit';
        let isItem = mode === 'item';
        setText(
            '[data-mb-modal-title]',
            isEdit ? i18n.editTitle : isItem ? i18n.itemTitle : i18n.releaseTitle,
        );
        setText('[data-mb-submit-label]', isEdit ? i18n.submitSave : i18n.submitRelease);
        toggle('[data-mb-picker]', isNew);
        toggle('[data-mb-item-picker]', isItem);
        showEl('[data-mb-qty-block]', isItem);
        showEl('[data-mb-qty-error]', false);
        let qtyInput = q('[data-mb-item-qty]');
        if (qtyInput) {
            qtyInput.value = '';
        }
        applyFacts(mode, ctx);

        let ta = q('[data-mb-remark]');
        ta.value = ctx.remark || '';
        updateCharCount();

        if (isNew) {
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
            onDone: null,
            onCancel: null,
        };
    }

    function loadPicker(query) {
        let seq = ++pickerSeq;
        let url =
            '/materialboerse/releasable-items' + (query ? '?q=' + encodeURIComponent(query) : '');
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

    function renderPicker() {
        let list = q('[data-mb-picker-list]');
        if (!list) {
            return;
        }
        if (!pickerItems.length) {
            list.innerHTML =
                '<li class="krt-combobox__notice">' + escapeHtml(i18n.pickerEmpty || '') + '</li>';
            list.hidden = false;
            return;
        }
        list.innerHTML = pickerItems
            .map(function (it) {
                let meta =
                    'Q ' +
                    it.quality +
                    ' · ' +
                    formatAmount(it.amount, it.quantityType) +
                    (it.locationName ? ' · ' + escapeHtml(it.locationName) : '') +
                    (it.alreadyReleased ? ' · ' + escapeHtml(i18n.pickerAlready || '') : '');
                return (
                    '<li class="krt-combobox__option" role="option" data-item-id="' +
                    it.inventoryItemId +
                    '" data-material="' +
                    escapeHtml(it.materialName) +
                    '" data-quantity-type="' +
                    escapeHtml(it.quantityType) +
                    '" data-quality="' +
                    it.quality +
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
        list.hidden = false;
    }

    function pickItem(li) {
        state.itemId = li.getAttribute('data-item-id');
        setFacts(
            li.getAttribute('data-material'),
            li.getAttribute('data-quality'),
            li.getAttribute('data-amount'),
            li.getAttribute('data-quantity-type'),
        );
        let input = q('[data-mb-picker-input]');
        if (input) {
            input.value = li.getAttribute('data-material');
        }
        let list = q('[data-mb-picker-list]');
        if (list) {
            list.hidden = true;
        }
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
            list.hidden = false;
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
        list.hidden = false;
    }

    function pickProduct(li) {
        state.productKey = li.getAttribute('data-product-key');
        let name = li.getAttribute('data-name');
        setText('[data-mb-fact-material]', name || '—');
        let input = q('[data-mb-item-picker-input]');
        if (input) {
            input.value = name;
        }
        let list = q('[data-mb-item-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    function submit() {
        let remark = q('[data-mb-remark]').value;
        if (state.mode === 'edit') {
            window.krtFetch.write({
                method: 'PUT',
                url: '/materialboerse/offers/' + state.offerId + '/remark/ajax',
                payload: { remark: remark, version: Number(state.version) },
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
        if (!state.itemId) {
            return;
        }
        window.krtFetch.write({
            method: 'POST',
            url: '/materialboerse/offers/ajax',
            payload: { inventoryItemId: state.itemId, remark: remark },
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
     * item-offer proxy and, on success, notifies peers and closes the modal exactly like a material
     * release.
     */
    function submitItem(remark) {
        if (!state.productKey) {
            return;
        }
        let qtyInput = q('[data-mb-item-qty]');
        let quantity = qtyInput ? parseInt(qtyInput.value, 10) : NaN;
        if (isNaN(quantity) || quantity < 1) {
            showEl('[data-mb-qty-error]', true);
            if (qtyInput) {
                qtyInput.focus();
            }
            return;
        }
        showEl('[data-mb-qty-error]', false);
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
        if (window.krtMaterialboardPresence) {
            window.krtMaterialboardPresence.sendChanged(['board']);
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
        if (e.target.closest('[data-mb-modal-close]') || e.target === modal) {
            cancel();
            return;
        }
        if (e.target.closest('[data-mb-modal-submit]')) {
            submit();
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
            searchPicker(e.target.value);
        } else if (e.target.matches('[data-mb-item-picker-input]')) {
            searchItemPicker(e.target.value);
        } else if (e.target.matches('[data-mb-item-qty]')) {
            showEl('[data-mb-qty-error]', false);
        }
    });

    document.addEventListener('keydown', function (e) {
        if (!isModalOpen()) {
            return;
        }
        if (e.key === 'Escape') {
            cancel();
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
