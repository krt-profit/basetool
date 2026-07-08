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
        offerId: null,
        version: null,
        onDone: null,
        onCancel: null,
    };
    let pickerItems = [];
    let lastFocused = null;

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

    function formatScu(amount) {
        let n = Number(amount);
        if (isNaN(n)) {
            return String(amount);
        }
        return n.toLocaleString('de-DE', { maximumFractionDigits: 3 }) + ' SCU';
    }

    function setFacts(material, quality, amount) {
        setText('[data-mb-fact-material]', material || '—');
        setText(
            '[data-mb-fact-quality]',
            quality != null && quality !== '' ? String(quality) : '—',
        );
        setText('[data-mb-fact-amount]', amount != null && amount !== '' ? formatScu(amount) : '—');
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
     * @param ctx {itemId?, material?, quality?, amount?, offerId?, version?, remark?}
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
            offerId: ctx.offerId || null,
            version: ctx.version || null,
            onDone: onDone,
            onCancel: onCancel,
        };
        let isNew = mode === 'new';
        let isEdit = mode === 'edit';
        setText('[data-mb-modal-title]', isEdit ? i18n.editTitle : i18n.releaseTitle);
        setText('[data-mb-submit-label]', isEdit ? i18n.submitSave : i18n.submitRelease);
        toggle('[data-mb-picker]', isNew);
        setFacts(ctx.material, ctx.quality, ctx.amount);

        let ta = q('[data-mb-remark]');
        ta.value = ctx.remark || '';
        updateCharCount();

        if (isNew) {
            loadPicker('');
        }

        lastFocused = document.activeElement;
        // .krt-modal-overlay is display:none by default; open by setting inline display:flex
        // (the app-wide modal contract), NOT by clearing a hidden attribute — the latter left
        // the CSS display:none in place, so the modal opened invisibly (REQ-MARKET-002/007).
        modal.style.display = 'flex';
        let first = isNew ? q('[data-mb-picker-input]') : ta;
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
            offerId: null,
            version: null,
            onDone: null,
            onCancel: null,
        };
    }

    function loadPicker(query) {
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
                pickerItems = Array.isArray(items) ? items : [];
                renderPicker();
            })
            .catch(function () {
                pickerItems = [];
                renderPicker();
            });
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
                    formatScu(it.amount) +
                    (it.locationName ? ' · ' + escapeHtml(it.locationName) : '') +
                    (it.alreadyReleased ? ' · ' + escapeHtml(i18n.pickerAlready || '') : '');
                return (
                    '<li class="krt-combobox__option" role="option" data-item-id="' +
                    it.inventoryItemId +
                    '" data-material="' +
                    escapeHtml(it.materialName) +
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

    function renderPickerFiltered(query) {
        let query2 = (query || '').toLowerCase();
        let list = q('[data-mb-picker-list]');
        if (!list) {
            return;
        }
        list.querySelectorAll('.krt-combobox__option').forEach(function (li) {
            let mat = (li.getAttribute('data-material') || '').toLowerCase();
            li.style.display = mat.indexOf(query2) >= 0 ? '' : 'none';
        });
        list.hidden = false;
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

    function notifyPeers() {
        if (window.krtMaterialboardPresence) {
            window.krtMaterialboardPresence.sendChanged(['board']);
        }
    }

    // -------- events (scoped to the modal DOM) --------

    document.addEventListener('click', function (e) {
        if (!isModalOpen()) {
            if (e.target.closest('[data-mb-picker-list] .krt-combobox__option')) {
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
        }
    });

    document.addEventListener('input', function (e) {
        if (e.target.matches('[data-mb-remark]')) {
            updateCharCount();
        } else if (e.target.matches('[data-mb-picker-input]')) {
            renderPickerFiltered(e.target.value);
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
