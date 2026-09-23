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

    const i18n = window.materialgesuchI18n || {};
    const modal = document.getElementById('mg-modal');
    if (!modal || !window.krtFetch) {
        return;
    }

    const SERIALIZE_KEY = 'materialgesuch';
    // REQ-FE-015 (ADR-0094): the global live-sync room a request create/edit publishes to over the
    // shared multiplexed /ws/sync socket, on the 'requests' section key (the offers use 'board').
    const MATERIALBOARD_TOPIC = 'materialboard';
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
    const PICKER_SEARCH_DEBOUNCE_MS = 200;
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

    function showError(message) {
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message || i18n.error || '');
        }
    }

    function updateCharCount() {
        const ta = q('[data-mg-remark]');
        const counter = q('[data-mg-charcount]');
        if (ta && counter) {
            counter.textContent = fmt(
                window.krtI18nText(i18n.charCounter, 'materialgesuchI18n.charCounter'),
                ta.value.length.toLocaleString('de-DE'),
            );
        }
    }

    /** Sets the quantity field's unit label + numeric step for the current kind/material type. */
    function applyQuantityUnit() {
        const isPiece = state.kind === 'ITEM' || state.quantityType === 'PIECE';
        const unit = q('[data-mg-qty-unit]');
        const input = q('[data-mg-qty]');
        if (unit) {
            unit.textContent = isPiece
                ? window.krtI18nText(i18n.unitPiece, 'materialgesuchI18n.unitPiece')
                : i18n.unitScu || 'SCU';
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
        const isEdit = mode === 'edit';
        state = {
            mode,
            requestId: ctx.requestId || null,
            version: ctx.version || null,
            kind: ctx.kind === 'ITEM' ? 'ITEM' : 'MATERIAL',
            materialId: null,
            productKey: null,
            quantityType: ctx.quantityType === 'PIECE' ? 'PIECE' : 'SCU',
            onDone,
            onCancel,
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

        const ta = q('[data-mg-remark]');
        ta.value = ctx.remark || '';
        updateCharCount();

        lastFocused = document.activeElement;
        modal.style.display = 'flex';
        const first = isEdit
            ? q('[data-mg-qty]')
            : state.kind === 'ITEM'
              ? q('[data-mg-item-picker-input]')
              : q('[data-mg-picker-input]');
        if (first) {
            first.focus();
        }
    }

    function setValue(sel, value) {
        const el = q(sel);
        if (el) {
            el.value = value == null || value === '' ? '' : String(value);
        }
    }

    function setKindRadio(kind) {
        const radio = modal.querySelector('[data-mg-kind-radio][value="' + kind + '"]');
        if (radio) {
            radio.checked = true;
        }
    }

    /** Shows the material combobox for a MATERIAL request, the item combobox for an ITEM request. */
    function applyKindBlocks() {
        const isItem = state.kind === 'ITEM';
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
        const onCancel = state.onCancel;
        hide();
        if (onCancel) {
            onCancel();
        }
    }

    function finish(body) {
        const onDone = state.onDone;
        hide();
        if (onDone) {
            return onDone(body);
        }
    }

    // -------- material catalogue picker --------

    function loadMaterialPicker(query) {
        const seq = ++materialSeq;
        const url =
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
        const list = q('[data-mg-picker-list]');
        if (list) {
            list.hidden = false;
        }
    }

    function closeMaterialList() {
        materialListOpen = false;
        const list = q('[data-mg-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    function renderMaterialPicker() {
        const list = q('[data-mg-picker-list]');
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
        // Accumulated from literals and escapeHtml / escapeAttr calls only (FE-SEC-05).
        let html = '';
        materialItems.forEach(function (it) {
            const unit =
                it.quantityType === 'PIECE'
                    ? window.krtI18nText(i18n.unitPiece, 'materialgesuchI18n.unitPiece')
                    : i18n.unitScu || 'SCU';
            html +=
                '<li class="krt-combobox__option" role="option" data-material-id="' +
                escapeAttr(it.id) +
                '" data-name="' +
                escapeAttr(it.name) +
                '" data-quantity-type="' +
                escapeAttr(it.quantityType) +
                '"><strong>' +
                escapeHtml(it.name) +
                '</strong> <small>' +
                escapeHtml(unit) +
                '</small></li>';
        });
        list.innerHTML = html;
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
        const seq = ++itemSeq;
        const url =
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
        const list = q('[data-mg-item-picker-list]');
        if (list) {
            list.hidden = false;
        }
    }

    function closeItemList() {
        itemListOpen = false;
        const list = q('[data-mg-item-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    function renderItemPicker() {
        const list = q('[data-mg-item-picker-list]');
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
        // Accumulated from literals and escapeHtml / escapeAttr calls only (FE-SEC-05).
        let html = '';
        productItems.forEach(function (it) {
            html +=
                '<li class="krt-combobox__option" role="option" data-product-key="' +
                escapeAttr(it.productKey) +
                '" data-name="' +
                escapeAttr(it.name) +
                '"><strong>' +
                escapeHtml(it.name) +
                '</strong>';
            if (it.manufacturerName) {
                html += ' <small>' + escapeHtml(it.manufacturerName) + '</small>';
            }
            html += '</li>';
        });
        list.innerHTML = html;
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
        const remark = q('[data-mg-remark]').value;
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
            window.krtFetch.write({
                method: 'PUT',
                url: '/materialboerse/requests/' + state.requestId + '/ajax',
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
        window.krtFetch.write({
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
        const li = e.target.closest('[data-mg-picker-list] .krt-combobox__option');
        if (li) {
            pickMaterial(li);
            return;
        }
        const pli = e.target.closest('[data-mg-item-picker-list] .krt-combobox__option');
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
        const focusable = modal.querySelectorAll(
            'button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])',
        );
        const visible = Array.prototype.filter.call(focusable, function (el) {
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

    window.krtMaterialRequest = { open, close: cancel };
})();
