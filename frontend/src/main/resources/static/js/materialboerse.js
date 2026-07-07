/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Materialbörse (Flotte & Logistik) page interactions. Tabs / filters / sort and
 * row selection re-render server fragments through window.krtFetch.swap; the
 * release / edit / deactivate / interest writes go through window.krtFetch.write
 * (no hand-rolled fetch, no full-page reload, no native dialogs). REQ-MARKET-*,
 * REQ-FE-001..014.
 */
(function () {
    'use strict';

    var i18n = window.materialboerseI18n || {};
    if (!window.krtFetch || !document.getElementById('mb-board')) {
        return;
    }

    var SERIALIZE_KEY = 'materialboerse';
    var selectedId = readSelectedId();
    var searchTimer = null;

    // -------- helpers --------------------------------------------------------

    function fmt(template, value) {
        return String(template || '').replace('{0}', value);
    }

    function readSelectedId() {
        var active = document.querySelector('.mb-mrow.is-active[data-offer-id]');
        return active ? active.getAttribute('data-offer-id') : null;
    }

    function val(selector) {
        var el = document.querySelector(selector);
        return el ? el.value.trim() : '';
    }

    function activeTab() {
        var tab = document.querySelector('.tab.active[data-mb-tab]');
        return tab ? tab.getAttribute('data-mb-tab') : 'alle';
    }

    function params() {
        var p = new URLSearchParams();
        p.set('tab', activeTab());
        var q = val('[data-mb-search]');
        if (q) {
            p.set('q', q);
        }
        var minQ = val('[data-mb-minquality]');
        if (minQ) {
            p.set('minQuality', minQ);
        }
        var minA = val('[data-mb-minamount]');
        if (minA) {
            p.set('minAmount', minA);
        }
        var sort = val('[data-mb-sort]');
        if (sort) {
            p.set('sort', sort);
        }
        if (selectedId) {
            p.set('selected', selectedId);
        }
        return p;
    }

    function swapList() {
        var p = params();
        p.set('fragment', 'list');
        return window.krtFetch.swap({
            url: '/materialboerse?' + p.toString(),
            container: '#mb-listwrap',
            fragmentValue: 'list',
            history: false,
        });
    }

    function swapBoard() {
        var p = params();
        p.set('fragment', 'board');
        return window.krtFetch.swap({
            url: '/materialboerse?' + p.toString(),
            container: '#mb-board',
            fragmentValue: 'board',
            history: false,
        });
    }

    function swapDetail(id) {
        return window.krtFetch.swap({
            url: '/materialboerse?fragment=detail&selected=' + encodeURIComponent(id),
            container: '#mb-detail',
            fragmentValue: 'detail',
            history: false,
        });
    }

    // -------- relative time ("Freigegeben vor X") ----------------------------

    function applyAgo(root) {
        var scope = root || document;
        scope.querySelectorAll('[data-mb-ago]').forEach(function (el) {
            var ts = el.getAttribute('data-ts');
            if (!ts) {
                return;
            }
            var then = Date.parse(ts);
            if (isNaN(then)) {
                return;
            }
            var hours = Math.floor((Date.now() - then) / 3600000);
            var text;
            if (hours < 1) {
                text = i18n.agoNow || 'gerade eben';
            } else if (hours < 24) {
                text = fmt(i18n.agoHours || 'vor {0} Std', hours);
            } else {
                var days = Math.round(hours / 24);
                text =
                    days === 1
                        ? i18n.agoDayOne || 'vor 1 Tag'
                        : fmt(i18n.agoDays || 'vor {0} Tagen', days);
            }
            el.textContent = text;
        });
    }

    // -------- tabs / filters / selection -------------------------------------

    function setActiveTab(tab) {
        document.querySelectorAll('.tab[data-mb-tab]').forEach(function (btn) {
            var on = btn.getAttribute('data-mb-tab') === tab;
            btn.classList.toggle('active', on);
            btn.setAttribute('aria-selected', on ? 'true' : 'false');
        });
    }

    function markActiveRow(id) {
        document.querySelectorAll('.mb-mrow[data-offer-id]').forEach(function (row) {
            var on = row.getAttribute('data-offer-id') === id;
            row.classList.toggle('is-active', on);
            row.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
    }

    function debouncedList() {
        if (searchTimer) {
            clearTimeout(searchTimer);
        }
        searchTimer = setTimeout(function () {
            selectedId = null;
            swapList();
        }, 250);
    }

    // -------- writes ---------------------------------------------------------

    function toggleInterest(button) {
        var id = button.getAttribute('data-offer-id');
        var interested = button.getAttribute('data-interested') === 'true';
        window.krtFetch.write({
            method: interested ? 'DELETE' : 'POST',
            url: '/materialboerse/offers/' + id + '/interest/ajax',
            successMessage: interested ? i18n.interestRemoved : i18n.interestAdded,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess: function () {
                return swapList();
            },
        });
    }

    function deactivateOffer(id) {
        window
            .showKrtConfirm(
                i18n.deactivateConfirmTitle,
                i18n.deactivateConfirmBody,
                i18n.confirmYes,
                i18n.confirmNo,
            )
            .then(function (ok) {
                if (!ok) {
                    return;
                }
                window.krtFetch.write({
                    method: 'POST',
                    url: '/materialboerse/offers/' + id + '/deactivate/ajax',
                    successMessage: i18n.deactivated,
                    errorMessage: i18n.error,
                    serialize: SERIALIZE_KEY,
                    onSuccess: function () {
                        selectedId = null;
                        return swapBoard();
                    },
                });
            });
    }

    // -------- release / edit modal -------------------------------------------

    var modal = document.getElementById('mb-modal');
    var modalState = { mode: null, itemId: null, offerId: null, version: null };
    var pickerItems = [];
    var lastFocused = null;

    function openModal(mode, ctx) {
        modalState = {
            mode: mode,
            itemId: ctx.itemId || null,
            offerId: ctx.offerId || null,
            version: ctx.version || null,
        };
        var isNew = mode === 'new';
        var isEdit = mode === 'edit';
        setModalText('[data-mb-modal-title]', isEdit ? i18n.editTitle : i18n.releaseTitle);
        setModalText('[data-mb-submit-label]', isEdit ? i18n.submitSave : i18n.submitRelease);
        toggle('[data-mb-picker]', isNew);

        setFacts(ctx.material, ctx.quality, ctx.amount);
        var ta = modal.querySelector('[data-mb-remark]');
        ta.value = ctx.remark || '';
        updateCharCount();

        if (isNew) {
            loadPicker('');
        }

        lastFocused = document.activeElement;
        modal.hidden = false;
        var first = isNew ? modal.querySelector('[data-mb-picker-input]') : ta;
        if (first) {
            first.focus();
        }
    }

    function closeModal() {
        modal.hidden = true;
        modalState = { mode: null, itemId: null, offerId: null, version: null };
        if (lastFocused && typeof lastFocused.focus === 'function') {
            lastFocused.focus();
        }
    }

    function setModalText(selector, text) {
        var el = modal.querySelector(selector);
        if (el) {
            el.textContent = text;
        }
    }

    function toggle(selector, on) {
        var el = modal.querySelector(selector);
        if (el) {
            el.hidden = !on;
        }
    }

    function setFacts(material, quality, amount) {
        setModalText('[data-mb-fact-material]', material || '—');
        setModalText(
            '[data-mb-fact-quality]',
            quality != null && quality !== '' ? String(quality) : '—',
        );
        setModalText(
            '[data-mb-fact-amount]',
            amount != null && amount !== '' ? formatScu(amount) : '—',
        );
    }

    function formatScu(amount) {
        var n = Number(amount);
        if (isNaN(n)) {
            return String(amount);
        }
        return n.toLocaleString('de-DE', { maximumFractionDigits: 3 }) + ' SCU';
    }

    function updateCharCount() {
        var ta = modal.querySelector('[data-mb-remark]');
        var counter = modal.querySelector('[data-mb-charcount]');
        if (ta && counter) {
            counter.textContent = fmt(
                i18n.charCounter || '{0} / 20.000',
                ta.value.length.toLocaleString('de-DE'),
            );
        }
    }

    function loadPicker(query) {
        var url =
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
        var list = modal.querySelector('[data-mb-picker-list]');
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
                var meta =
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
        modalState.itemId = li.getAttribute('data-item-id');
        setFacts(
            li.getAttribute('data-material'),
            li.getAttribute('data-quality'),
            li.getAttribute('data-amount'),
        );
        var input = modal.querySelector('[data-mb-picker-input]');
        if (input) {
            input.value = li.getAttribute('data-material');
        }
        var list = modal.querySelector('[data-mb-picker-list]');
        if (list) {
            list.hidden = true;
        }
    }

    function submitModal() {
        var remark = modal.querySelector('[data-mb-remark]').value;
        if (modalState.mode === 'edit') {
            window.krtFetch.write({
                method: 'PUT',
                url: '/materialboerse/offers/' + modalState.offerId + '/remark/ajax',
                payload: { remark: remark, version: Number(modalState.version) },
                successMessage: i18n.remarkSaved,
                errorMessage: i18n.error,
                conflict: i18n.conflict,
                serialize: SERIALIZE_KEY,
                onSuccess: function () {
                    closeModal();
                    return swapBoard();
                },
            });
            return;
        }
        if (!modalState.itemId) {
            return;
        }
        window.krtFetch.write({
            method: 'POST',
            url: '/materialboerse/offers/ajax',
            payload: { inventoryItemId: modalState.itemId, remark: remark },
            successMessage: i18n.released,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess: function (body) {
                if (body && body.id) {
                    selectedId = body.id;
                }
                closeModal();
                return swapBoard();
            },
        });
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
        });
    }

    // -------- delegated events ------------------------------------------------

    document.addEventListener('click', function (e) {
        var el;

        if ((el = e.target.closest('[data-mb-tab]'))) {
            setActiveTab(el.getAttribute('data-mb-tab'));
            selectedId = null;
            swapList();
            return;
        }
        if ((el = e.target.closest('[data-mb-reset]'))) {
            setInputVal('[data-mb-search]', '');
            setInputVal('[data-mb-minquality]', '');
            setInputVal('[data-mb-minamount]', '');
            setActiveTab('alle');
            selectedId = null;
            swapList();
            return;
        }
        if ((el = e.target.closest('[data-mb-deselect]'))) {
            var md = document.querySelector('.mb-md');
            if (md) {
                md.classList.remove('has-sel');
            }
            return;
        }
        if (
            (el = e.target.closest('[data-mb-interest]')) ||
            (el = e.target.closest('[data-mb-deactivate]')) ||
            (el = e.target.closest('[data-mb-edit]')) ||
            (el = e.target.closest('[data-mb-open-release]'))
        ) {
            handleAction(el);
            return;
        }
        if ((el = e.target.closest('[data-mb-select]'))) {
            selectedId = el.getAttribute('data-offer-id');
            markActiveRow(selectedId);
            var mdSel = document.querySelector('.mb-md');
            if (mdSel) {
                mdSel.classList.add('has-sel');
            }
            swapDetail(selectedId);
            return;
        }
        // modal controls
        if (e.target.closest('[data-mb-modal-close]') || e.target === modal) {
            closeModal();
            return;
        }
        if (e.target.closest('[data-mb-modal-submit]')) {
            submitModal();
            return;
        }
        if ((el = e.target.closest('[data-mb-picker-list] .krt-combobox__option'))) {
            pickItem(el);
            return;
        }
    });

    function handleAction(el) {
        if (el.hasAttribute('data-mb-interest')) {
            toggleInterest(el);
        } else if (el.hasAttribute('data-mb-deactivate')) {
            deactivateOffer(el.getAttribute('data-offer-id'));
        } else if (el.hasAttribute('data-mb-edit')) {
            openModal('edit', {
                offerId: el.getAttribute('data-offer-id'),
                version: el.getAttribute('data-version'),
                material: el.getAttribute('data-material'),
                quality: el.getAttribute('data-quality'),
                amount: el.getAttribute('data-amount'),
                remark: el.getAttribute('data-remark'),
            });
        } else if (el.hasAttribute('data-mb-open-release')) {
            openModal('new', {});
        }
    }

    document.addEventListener('input', function (e) {
        if (e.target.matches('[data-mb-search], [data-mb-minquality], [data-mb-minamount]')) {
            debouncedList();
            return;
        }
        if (e.target.matches('[data-mb-remark]')) {
            updateCharCount();
            return;
        }
        if (e.target.matches('[data-mb-picker-input]')) {
            renderPickerFiltered(e.target.value);
        }
    });

    document.addEventListener('change', function (e) {
        if (e.target.matches('[data-mb-sort]')) {
            swapList();
        }
    });

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && modal && !modal.hidden) {
            closeModal();
            return;
        }
        if (e.key === 'Tab' && modal && !modal.hidden) {
            trapFocus(e);
        }
    });

    function renderPickerFiltered(query) {
        var q = (query || '').toLowerCase();
        var list = modal.querySelector('[data-mb-picker-list]');
        if (!list) {
            return;
        }
        list.querySelectorAll('.krt-combobox__option').forEach(function (li) {
            var mat = (li.getAttribute('data-material') || '').toLowerCase();
            li.style.display = mat.indexOf(q) >= 0 ? '' : 'none';
        });
        list.hidden = false;
    }

    function trapFocus(e) {
        var focusable = modal.querySelectorAll(
            'button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])',
        );
        var visible = Array.prototype.filter.call(focusable, function (el) {
            return el.offsetParent !== null && !el.hidden;
        });
        if (!visible.length) {
            return;
        }
        var first = visible[0];
        var last = visible[visible.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }

    function setInputVal(selector, value) {
        var el = document.querySelector(selector);
        if (el) {
            el.value = value;
        }
    }

    // Re-apply relative time + selection tracking after every fragment swap.
    document.addEventListener('krt:swapped', function () {
        applyAgo(document);
        selectedId = readSelectedId();
    });

    applyAgo(document);
})();
