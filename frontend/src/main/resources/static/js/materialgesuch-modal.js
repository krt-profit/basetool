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
(function () {
    'use strict';

    let i18n = window.materialgesuchI18n || {};
    let modal = document.getElementById('mg-modal');
    if (!modal || !window.krtFetch) {
        return;
    }

    let SERIALIZE_KEY = 'materialgesuch';
    // REQ-FE-015 (ADR-0094): the global live-sync room a request create/edit publishes to over the
    // shared multiplexed /ws/sync socket, on the 'requests' section key (the offers use 'board').
    let MATERIALBOARD_TOPIC = 'materialboard';
    let state = {
        mode: null,
        requestId: null,
        version: null,
        kind: 'MATERIAL',
        materialId: null,
        productKey: null,
        quantityType: 'SCU',
        onDone: null,
        onCancel: null,
    };
    let lastFocused = null;
    let PICKER_SEARCH_DEBOUNCE_MS = 200;
    // Both catalogue comboboxes start CLOSED and open only on an explicit gesture, so the floating
    // list never covers the fields below it the moment the modal opens (see materialboerse-release.js).
    let materialSeq = 0;
    let materialTimer = null;
    let materialListOpen = false;
    let materialItems = [];
    let itemSeq = 0;
    let itemTimer = null;
    let itemListOpen = false;
    let productItems = [];

    function fmt(template, value) {
        return String(template || '').replace('{0}', value);
    }

    function q(sel) {
        return modal.querySelector(sel);
    }

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

    function showError(message) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message || i18n.error || '');
        }
    }

    function updateCharCount() {
        let ta = q('[data-mg-remark]');
        let counter = q('[data-mg-charcount]');
        if (ta && counter) {
            counter.textContent = fmt(
                i18n.charCounter || '{0} / 20.000',
                ta.value.length.toLocaleString('de-DE'),
            );
        }
    }

    /** Sets the quantity field's unit label + numeric step for the current kind/material type. */
    function applyQuantityUnit() {
        let isPiece = state.kind === 'ITEM' || state.quantityType === 'PIECE';
        let unit = q('[data-mg-qty-unit]');
        let input = q('[data-mg-qty]');
        if (unit) {
            unit.textContent = isPiece ? i18n.unitPiece || 'Stück' : i18n.unitScu || 'SCU';
        }
        if (input) {
            input.step = isPiece ? '1' : '0.001';
        }
    }

    /**
     * Opens the modal.
     * @param mode 'new' | 'edit'
     * @param ctx for 'new': {kind}. For 'edit': {requestId, version, kind, subject, minQuality,
     *        amount, quantityType, remark}.
     * @param doneOrOpts an onDone callback(body), or {onDone, onCancel}.
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
        let isEdit = mode === 'edit';
        state = {
            mode: mode,
            requestId: ctx.requestId || null,
            version: ctx.version || null,
            kind: ctx.kind === 'ITEM' ? 'ITEM' : 'MATERIAL',
            materialId: null,
            productKey: null,
            quantityType: ctx.quantityType === 'PIECE' ? 'PIECE' : 'SCU',
            onDone: onDone,
            onCancel: onCancel,
        };
        materialListOpen = false;
        itemListOpen = false;

        setText(
            '[data-mg-modal-title]',
            isEdit
                ? i18n.editTitle
                : state.kind === 'ITEM'
                  ? i18n.itemRequestTitle
                  : i18n.requestTitle,
        );
        setText('[data-mg-submit-label]', isEdit ? i18n.submitSave : i18n.submitCreate);
        toggle('[data-mg-picker]', !isEdit);
        toggle('[data-mg-subject-facts]', isEdit);
        toggle('[data-mg-quality-error]', false);
        toggle('[data-mg-qty-error]', false);

        if (isEdit) {
            setText('[data-mg-fact-subject]', ctx.subject || '—');
            setKindRadio(state.kind);
            setValue('[data-mg-min-quality]', ctx.minQuality != null ? ctx.minQuality : '');
            setValue('[data-mg-qty]', ctx.amount != null ? ctx.amount : '');
        } else {
            setKindRadio(state.kind);
            applyKindBlocks();
            setValue('[data-mg-picker-input]', '');
            setValue('[data-mg-item-picker-input]', '');
            setValue('[data-mg-min-quality]', '');
            setValue('[data-mg-qty]', '');
            loadMaterialPicker('');
            loadItemPicker('');
        }
        applyQuantityUnit();

        let ta = q('[data-mg-remark]');
        ta.value = ctx.remark || '';
        updateCharCount();

        lastFocused = document.activeElement;
        modal.style.display = 'flex';
        let first = isEdit
            ? q('[data-mg-qty]')
            : state.kind === 'ITEM'
              ? q('[data-mg-item-picker-input]')
              : q('[data-mg-picker-input]');
        if (first) {
            first.focus();
        }
    }

    function setValue(sel, value) {
        let el = q(sel);
        if (el) {
            el.value = value == null || value === '' ? '' : String(value);
        }
    }

    function setKindRadio(kind) {
        let radio = modal.querySelector('[data-mg-kind-radio][value="' + kind + '"]');
        if (radio) {
            radio.checked = true;
        }
    }

    /** Shows the material combobox for a MATERIAL request, the item combobox for an ITEM request. */
    function applyKindBlocks() {
        let isItem = state.kind === 'ITEM';
        toggle('[data-mg-material-block]', !isItem);
        toggle('[data-mg-item-block]', isItem);
    }

    function setKind(kind) {
        state.kind = kind === 'ITEM' ? 'ITEM' : 'MATERIAL';
        state.materialId = null;
        state.productKey = null;
        state.quantityType = 'SCU';
        applyKindBlocks();
        applyQuantityUnit();
        closeMaterialList();
        closeItemList();
    }

    function hide() {
        modal.style.display = 'none';
        if (lastFocused && typeof lastFocused.focus === 'function') {
            lastFocused.focus();
        }
    }

    function cancel() {
        let onCancel = state.onCancel;
        hide();
        if (onCancel) {
            onCancel();
        }
    }

    function finish(body) {
        let onDone = state.onDone;
        hide();
        if (onDone) {
            return onDone(body);
        }
    }

    // -------- material catalogue picker --------

    function loadMaterialPicker(query) {
        let seq = ++materialSeq;
        let url =
            '/materialboerse/request-materials' + (query ? '?q=' + encodeURIComponent(query) : '');
        fetch(url, {
            headers: { 'X-Requested-With': 'XMLHttpRequest' },
            credentials: 'same-origin',
        })
            .then(function (r) {
                return r.ok ? r.json() : null;
            })
            .then(function (page) {
                if (seq !== materialSeq) {
                    return;
                }
                materialItems = page && Array.isArray(page.content) ? page.content : [];
                renderMaterialPicker();
            })
            .catch(function () {
                if (seq !== materialSeq) {
                    return;
                }
                materialItems = [];
                renderMaterialPicker();
            });
    }

    function searchMaterialPicker(query) {
        if (materialTimer) {
            clearTimeout(materialTimer);
        }
        materialTimer = setTimeout(function () {
            loadMaterialPicker(query);
        }, PICKER_SEARCH_DEBOUNCE_MS);
    }

    function openMaterialList() {
        materialListOpen = true;
        let list = q('[data-mg-picker-list]');
        if (list) {
            list.hidden = false;
        }
    }

    function closeMaterialList() {
        materialListOpen = false;
        let list = q('[data-mg-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    function renderMaterialPicker() {
        let list = q('[data-mg-picker-list]');
        if (!list) {
            return;
        }
        if (!materialItems.length) {
            list.innerHTML =
                '<li class="krt-combobox__notice">' +
                escapeHtml(i18n.materialPickerEmpty || '') +
                '</li>';
            list.hidden = !materialListOpen;
            return;
        }
        list.innerHTML = materialItems
            .map(function (it) {
                let unit =
                    it.quantityType === 'PIECE' ? i18n.unitPiece || 'Stück' : i18n.unitScu || 'SCU';
                return (
                    '<li class="krt-combobox__option" role="option" data-material-id="' +
                    escapeHtml(it.id) +
                    '" data-name="' +
                    escapeHtml(it.name) +
                    '" data-quantity-type="' +
                    escapeHtml(it.quantityType) +
                    '"><strong>' +
                    escapeHtml(it.name) +
                    '</strong> <small>' +
                    escapeHtml(unit) +
                    '</small></li>'
                );
            })
            .join('');
        list.hidden = !materialListOpen;
    }

    function pickMaterial(li) {
        state.materialId = li.getAttribute('data-material-id');
        state.quantityType = li.getAttribute('data-quantity-type') === 'PIECE' ? 'PIECE' : 'SCU';
        setValue('[data-mg-picker-input]', li.getAttribute('data-name'));
        applyQuantityUnit();
        closeMaterialList();
    }

    // -------- item (blueprint-product) picker --------

    function loadItemPicker(query) {
        let seq = ++itemSeq;
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
                if (seq !== itemSeq) {
                    return;
                }
                productItems = Array.isArray(items) ? items : [];
                renderItemPicker();
            })
            .catch(function () {
                if (seq !== itemSeq) {
                    return;
                }
                productItems = [];
                renderItemPicker();
            });
    }

    function searchItemPicker(query) {
        if (itemTimer) {
            clearTimeout(itemTimer);
        }
        itemTimer = setTimeout(function () {
            loadItemPicker(query);
        }, PICKER_SEARCH_DEBOUNCE_MS);
    }

    function openItemList() {
        itemListOpen = true;
        let list = q('[data-mg-item-picker-list]');
        if (list) {
            list.hidden = false;
        }
    }

    function closeItemList() {
        itemListOpen = false;
        let list = q('[data-mg-item-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    function renderItemPicker() {
        let list = q('[data-mg-item-picker-list]');
        if (!list) {
            return;
        }
        if (!productItems.length) {
            list.innerHTML =
                '<li class="krt-combobox__notice">' +
                escapeHtml(i18n.itemPickerEmpty || '') +
                '</li>';
            list.hidden = !itemListOpen;
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
        list.hidden = !itemListOpen;
    }

    function pickProduct(li) {
        state.productKey = li.getAttribute('data-product-key');
        setValue('[data-mg-item-picker-input]', li.getAttribute('data-name'));
        closeItemList();
    }

    // -------- validation + submit --------

    /** Reads the optional min-quality field; returns undefined when blank, null on an invalid value. */
    function readMinQuality() {
        let input = q('[data-mg-min-quality]');
        let raw = input ? input.value.trim() : '';
        if (raw === '') {
            return undefined;
        }
        let n = Number(raw);
        if (isNaN(n) || n < 0 || n > 1000 || Math.floor(n) !== n) {
            return null;
        }
        return n;
    }

    /** Reads the desired-quantity field; returns null when non-positive or (for items) non-whole. */
    function readQuantity() {
        let input = q('[data-mg-qty]');
        let n = input ? Number(input.value) : NaN;
        if (isNaN(n) || n <= 0) {
            return null;
        }
        let isPiece = state.kind === 'ITEM' || state.quantityType === 'PIECE';
        if (isPiece && Math.floor(n) !== n) {
            return null;
        }
        return n;
    }

    function submit() {
        let remark = q('[data-mg-remark]').value;
        let minQuality = readMinQuality();
        if (minQuality === null) {
            toggle('[data-mg-quality-error]', true);
            return;
        }
        toggle('[data-mg-quality-error]', false);
        let quantity = readQuantity();
        if (quantity === null) {
            toggle('[data-mg-qty-error]', true);
            let qtyInput = q('[data-mg-qty]');
            if (qtyInput) {
                qtyInput.focus();
            }
            return;
        }
        toggle('[data-mg-qty-error]', false);

        if (state.mode === 'edit') {
            window.krtFetch.write({
                method: 'PUT',
                url: '/materialboerse/requests/' + state.requestId + '/ajax',
                payload: {
                    desiredAmount: quantity,
                    minQuality: minQuality === undefined ? null : minQuality,
                    remark: remark,
                    version: Number(state.version),
                },
                successMessage: i18n.updated,
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

        if (state.kind === 'ITEM') {
            if (!state.productKey) {
                showError(i18n.subjectRequired);
                return;
            }
            window.krtFetch.write({
                method: 'POST',
                url: '/materialboerse/item-requests/ajax',
                payload: {
                    productKey: state.productKey,
                    minQuality: minQuality === undefined ? null : minQuality,
                    quantity: quantity,
                    remark: remark,
                },
                successMessage: i18n.created,
                errorMessage: i18n.error,
                serialize: SERIALIZE_KEY,
                onSuccess: function (body) {
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
        window.krtFetch.write({
            method: 'POST',
            url: '/materialboerse/requests/ajax',
            payload: {
                materialId: state.materialId,
                minQuality: minQuality === undefined ? null : minQuality,
                requestedAmount: quantity,
                remark: remark,
            },
            successMessage: i18n.created,
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
            window.krtLiveSync.sendChanged(MATERIALBOARD_TOPIC, ['requests']);
        }
    }

    // -------- events (scoped to the modal DOM) --------

    document.addEventListener('click', function (e) {
        if (!isModalOpen()) {
            if (
                e.target.closest('[data-mg-picker-list] .krt-combobox__option') ||
                e.target.closest('[data-mg-item-picker-list] .krt-combobox__option')
            ) {
                return;
            }
        }
        if (
            !e.target.closest('[data-mg-combobox]') &&
            !e.target.closest('[data-mg-item-combobox]')
        ) {
            closeMaterialList();
            closeItemList();
        }
        if (e.target.closest('[data-mg-modal-close]') || e.target === modal) {
            cancel();
            return;
        }
        if (e.target.closest('[data-mg-modal-submit]')) {
            submit();
            return;
        }
        if (e.target.closest('[data-mg-picker-input]')) {
            openMaterialList();
            return;
        }
        if (e.target.closest('[data-mg-item-picker-input]')) {
            openItemList();
            return;
        }
        let li = e.target.closest('[data-mg-picker-list] .krt-combobox__option');
        if (li) {
            pickMaterial(li);
            return;
        }
        let pli = e.target.closest('[data-mg-item-picker-list] .krt-combobox__option');
        if (pli) {
            pickProduct(pli);
        }
    });

    document.addEventListener('input', function (e) {
        if (e.target.matches('[data-mg-remark]')) {
            updateCharCount();
        } else if (e.target.matches('[data-mg-picker-input]')) {
            openMaterialList();
            searchMaterialPicker(e.target.value);
        } else if (e.target.matches('[data-mg-item-picker-input]')) {
            openItemList();
            searchItemPicker(e.target.value);
        } else if (e.target.matches('[data-mg-qty]')) {
            toggle('[data-mg-qty-error]', false);
        } else if (e.target.matches('[data-mg-min-quality]')) {
            toggle('[data-mg-quality-error]', false);
        }
    });

    document.addEventListener('change', function (e) {
        if (e.target.matches('[data-mg-kind-radio]')) {
            setKind(e.target.value);
        }
    });

    document.addEventListener('keydown', function (e) {
        if (!isModalOpen()) {
            return;
        }
        if (e.key === 'Escape') {
            if (materialListOpen || itemListOpen) {
                e.preventDefault();
                closeMaterialList();
                closeItemList();
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

    window.krtMaterialRequest = { open: open, close: cancel };
})();
