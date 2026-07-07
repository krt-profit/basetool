/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Materialbörse board interactions (Flotte & Logistik). Tabs / filters / sort and
 * row selection re-render server fragments through window.krtFetch.swap; interest /
 * deactivate writes go through window.krtFetch.write; the release / edit dialog is the
 * shared window.krtMaterialRelease modal (materialboerse-release.js). No hand-rolled
 * fetch, no full-page reload, no native dialogs. REQ-MARKET-*, REQ-FE-001..014.
 */
(function () {
    'use strict';

    let i18n = window.materialboerseI18n || {};
    if (!window.krtFetch || !document.getElementById('mb-board')) {
        return;
    }

    let SERIALIZE_KEY = 'materialboerse';
    let selectedId = readSelectedId();
    let searchTimer = null;

    // -------- helpers --------------------------------------------------------

    function fmt(template, value) {
        return String(template || '').replace('{0}', value);
    }

    function readSelectedId() {
        let active = document.querySelector('.mb-mrow.is-active[data-offer-id]');
        return active ? active.getAttribute('data-offer-id') : null;
    }

    function val(selector) {
        let el = document.querySelector(selector);
        return el ? el.value.trim() : '';
    }

    function activeTab() {
        let tab = document.querySelector('.tab.active[data-mb-tab]');
        return tab ? tab.getAttribute('data-mb-tab') : 'alle';
    }

    function params() {
        let p = new URLSearchParams();
        p.set('tab', activeTab());
        let qv = val('[data-mb-search]');
        if (qv) {
            p.set('q', qv);
        }
        let minQ = val('[data-mb-minquality]');
        if (minQ) {
            p.set('minQuality', minQ);
        }
        let minA = val('[data-mb-minamount]');
        if (minA) {
            p.set('minAmount', minA);
        }
        let sort = val('[data-mb-sort]');
        if (sort) {
            p.set('sort', sort);
        }
        if (selectedId) {
            p.set('selected', selectedId);
        }
        return p;
    }

    function swapList() {
        let p = params();
        p.set('fragment', 'list');
        return window.krtFetch.swap({
            url: '/materialboerse?' + p.toString(),
            container: '#mb-listwrap',
            fragmentValue: 'list',
            history: false,
        });
    }

    function swapBoard() {
        let p = params();
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
        (root || document).querySelectorAll('[data-mb-ago]').forEach(function (el) {
            let ts = el.getAttribute('data-ts');
            if (!ts) {
                return;
            }
            let then = Date.parse(ts);
            if (isNaN(then)) {
                return;
            }
            let hours = Math.floor((Date.now() - then) / 3600000);
            let text;
            if (hours < 1) {
                text = i18n.agoNow || 'gerade eben';
            } else if (hours < 24) {
                text = fmt(i18n.agoHours || 'vor {0} Std', hours);
            } else {
                let days = Math.round(hours / 24);
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
            let on = btn.getAttribute('data-mb-tab') === tab;
            btn.classList.toggle('active', on);
            btn.setAttribute('aria-selected', on ? 'true' : 'false');
        });
    }

    function markActiveRow(id) {
        document.querySelectorAll('.mb-mrow[data-offer-id]').forEach(function (row) {
            let on = row.getAttribute('data-offer-id') === id;
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

    function setInputVal(selector, value) {
        let el = document.querySelector(selector);
        if (el) {
            el.value = value;
        }
    }

    // -------- writes ---------------------------------------------------------

    function toggleInterest(button) {
        let id = button.getAttribute('data-offer-id');
        let interested = button.getAttribute('data-interested') === 'true';
        window.krtFetch.write({
            method: interested ? 'DELETE' : 'POST',
            url: '/materialboerse/offers/' + id + '/interest/ajax',
            successMessage: interested ? i18n.interestRemoved : i18n.interestAdded,
            errorMessage: i18n.error,
            serialize: SERIALIZE_KEY,
            onSuccess: function () {
                notifyPeers();
                return swapList();
            },
        });
    }

    function notifyPeers() {
        if (window.krtMaterialboardPresence) {
            window.krtMaterialboardPresence.sendChanged(['board']);
        }
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
                        notifyPeers();
                        selectedId = null;
                        return swapBoard();
                    },
                });
            });
    }

    function onReleased(body) {
        if (body && body.id) {
            selectedId = body.id;
        }
        return swapBoard();
    }

    function handleAction(el) {
        if (el.hasAttribute('data-mb-interest')) {
            toggleInterest(el);
        } else if (el.hasAttribute('data-mb-deactivate')) {
            deactivateOffer(el.getAttribute('data-offer-id'));
        } else if (el.hasAttribute('data-mb-edit') && window.krtMaterialRelease) {
            window.krtMaterialRelease.open(
                'edit',
                {
                    offerId: el.getAttribute('data-offer-id'),
                    version: el.getAttribute('data-version'),
                    material: el.getAttribute('data-material'),
                    quality: el.getAttribute('data-quality'),
                    amount: el.getAttribute('data-amount'),
                    remark: el.getAttribute('data-remark'),
                },
                swapBoard,
            );
        } else if (el.hasAttribute('data-mb-open-release') && window.krtMaterialRelease) {
            window.krtMaterialRelease.open('new', {}, onReleased);
        }
    }

    // -------- delegated events -----------------------------------------------

    document.addEventListener('click', function (e) {
        let el;
        if ((el = e.target.closest('[data-mb-tab]'))) {
            setActiveTab(el.getAttribute('data-mb-tab'));
            selectedId = null;
            swapList();
            return;
        }
        if (e.target.closest('[data-mb-reset]')) {
            setInputVal('[data-mb-search]', '');
            setInputVal('[data-mb-minquality]', '');
            setInputVal('[data-mb-minamount]', '');
            setActiveTab('alle');
            selectedId = null;
            swapList();
            return;
        }
        if (e.target.closest('[data-mb-deselect]')) {
            let md = document.querySelector('.mb-md');
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
            let mdSel = document.querySelector('.mb-md');
            if (mdSel) {
                mdSel.classList.add('has-sel');
            }
            swapDetail(selectedId);
        }
    });

    document.addEventListener('input', function (e) {
        if (e.target.matches('[data-mb-search], [data-mb-minquality], [data-mb-minamount]')) {
            debouncedList();
        }
    });

    document.addEventListener('change', function (e) {
        if (e.target.matches('[data-mb-sort]')) {
            swapList();
        }
    });

    // REQ-MARKET-010: a peer released / deactivated / registered interest — re-pull the board list.
    // Debounced; skipped while the release modal is open so an in-progress dialog is not disrupted.
    let peerTimer = null;
    document.addEventListener('krt:materialboerse-changed', function () {
        if (peerTimer) {
            clearTimeout(peerTimer);
        }
        peerTimer = setTimeout(function () {
            let modal = document.getElementById('mb-modal');
            if (modal && !modal.hidden) {
                return;
            }
            swapList();
        }, 400);
    });

    document.addEventListener('krt:swapped', function () {
        applyAgo(document);
        selectedId = readSelectedId();
    });

    applyAgo(document);
})();
