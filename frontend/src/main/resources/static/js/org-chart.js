/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Org-chart editor page module (/org-chart), extracted verbatim from the former inline script of
 * org-chart.html (ADR-0069, follow-up to #924).
 *
 * A self-invoking strict-mode IIFE (kept as-is) that drives in-place org-chart editing: the
 * add/reassign/rename/remove/vacate modal (JSON writes with fresh krtCsrf headers + retry-on-403),
 * the #571 fragment-swap chart refresh with horizontal-scroll restoration, an accessible focus-trap
 * dialog with background inert, and full ARIA-tree keyboard navigation (roving tabindex, arrow/Home/
 * End) re-initialised on krt:swapped. Collapse/expand are local view toggles (no server call).
 *
 * The localized strings live in the OC_I18N dict defined by the inline Thymeleaf bootstrap block of
 * org-chart.html, which executes immediately before this classic script.
 */

/* global OC_I18N */

(function () {
    'use strict';
    const chart = document.getElementById('oc-chart');
    const editHint = document.getElementById('oc-edit-hint');
    const modal = document.getElementById('oc-modal');
    const modalContent = modal ? modal.querySelector('.modal-content') : null;
    let lastTrigger = null;

    // In-place chart refresh after a successful edit (epic #571 / REQ-FE-005). The whole tree
    // is re-rendered via ?fragment=chartBody and swapped into the stable #oc-chart container,
    // re-stamping every data-version and rebuilding the add affordances + ARIA tree atomically
    // — no full-page reload. The innerHTML assignment resets the container's horizontal scroll,
    // so the caller captures it BEFORE closeModal() (whose focus()/inert-clear reflow can zero
    // it on Chromium/Firefox) and refreshChart re-applies it across animation frames until the
    // freshly-swapped tree's layout settles; krtFetch.swap (preserveScroll) keeps the page's
    // vertical scroll.
    function refreshChart(keepScroll) {
        if (!chart || !window.krtFetch) {
            window.location.reload();
            return;
        }
        const savedX = typeof keepScroll === 'number' ? keepScroll : chart.scrollLeft;
        window.krtFetch
            .swap({
                url: '/org-chart',
                container: chart,
                fragmentValue: 'chartBody',
                errorMessage: OC_I18N.refreshFailed,
            })
            .then(function (ok) {
                if (!ok) {
                    return;
                }
                // Re-apply the captured offset until it sticks. The innerHTML swap resets the
                // container scroll and the fresh tree lays out asynchronously, so a single
                // assignment clamps toward 0 while scrollWidth is below its final value (the browser
                // does not re-expand scrollLeft once layout grows). Re-assert savedX each frame
                // until the chart's max scroll has stabilised AND the offset has landed; bounded by
                // a deadline so a refresh that narrows the chart (savedX then unreachable) cannot
                // spin. Mirrors the former reload-path restoreScrollState settle loop.
                const deadline = performance.now() + 5000;
                let prevMax = -1;
                (function reapply() {
                    const maxScroll = chart.scrollWidth - chart.clientWidth;
                    chart.scrollLeft = savedX;
                    const landed = chart.scrollLeft >= Math.min(savedX, maxScroll) - 1;
                    const settled = maxScroll === prevMax;
                    prevMax = maxScroll;
                    if (savedX <= 0 || (landed && settled) || performance.now() >= deadline) {
                        return;
                    }
                    window.requestAnimationFrame(reapply);
                })();
            });
    }

    // One JSON write, headers read fresh from window.krtCsrf (epic #571 / REQ-SEC-010) so the
    // single meta-tag CSRF token is always current; retry-on-403 lives in send().
    function doFetch(method, url, body) {
        const opts = { method: method, headers: window.krtCsrf.headers() };
        if (body !== null) {
            opts.body = JSON.stringify(body);
        }
        return fetch(url, opts);
    }

    function field(id) {
        const el = document.getElementById(id);
        return el ? el.value : '';
    }

    function setField(id, value) {
        const el = document.getElementById(id);
        if (el) {
            el.value = value === null || value === undefined ? '' : value;
        }
    }

    function closeModal() {
        if (!modal) {
            return;
        }
        setBackgroundInert(false);
        modal.style.display = 'none';
        // Return focus to the control that opened the dialog (captured on open).
        if (lastTrigger && typeof lastTrigger.focus === 'function') {
            lastTrigger.focus();
        }
        lastTrigger = null;
    }

    function setHidden(id, hidden) {
        const el = document.getElementById(id);
        if (el) {
            el.style.display = hidden ? 'none' : '';
        }
    }

    // While the dialog is open, take the page chrome (header, sidebar, and the
    // chart itself inside <main>) out of the tab order and hide it from assistive
    // tech, so Tab and the screen-reader cursor stay inside the dialog.
    function setBackgroundInert(inert) {
        const regions = ['header', 'main', '#sidebar', '#sidebar-overlay'];
        regions.forEach(function (sel) {
            const el = document.querySelector(sel);
            if (!el) {
                return;
            }
            if (inert) {
                el.setAttribute('inert', '');
                el.setAttribute('aria-hidden', 'true');
            } else {
                el.removeAttribute('inert');
                el.removeAttribute('aria-hidden');
            }
        });
    }

    // The visible, focusable controls inside the dialog, in DOM order — drives the
    // Tab/Shift+Tab focus trap. Hidden field groups (display:none) have no client
    // rects and are skipped, as are the hidden <input> state fields.
    function modalFocusable() {
        if (!modalContent) {
            return [];
        }
        const selector =
            'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
        return Array.prototype.slice
            .call(modalContent.querySelectorAll(selector))
            .filter(function (el) {
                return el.getClientRects().length > 0;
            });
    }

    function openModal(mode, ctx) {
        if (!modal) {
            return;
        }
        setField('oc-mode', mode);
        setField('oc-position-type', ctx.positionType);
        setField('oc-org-unit-id', ctx.orgUnitId);
        setField('oc-parent-id', ctx.parentId);
        setField('oc-position-id', ctx.positionId);
        setField('oc-version', ctx.version);
        setField('oc-user-optional', ctx.userOptional ? '1' : '');
        setField('oc-staff-choice', ctx.staffChoice ? '1' : '');
        setField('oc-name', ctx.name);

        // A staff-type picker shows only when adding a Stab member; a name field shows for
        // renaming or creating a Kommando; the user picker shows for everything except a pure
        // rename. A Kommando create may leave the leader empty.
        const needsStaffType = !!ctx.staffChoice;
        const needsName = mode === 'rename' || !!ctx.needsName;
        const needsUser = mode !== 'rename';
        setHidden('oc-stafftype-group', !needsStaffType);
        setHidden('oc-name-group', !needsName);
        setHidden('oc-user-group', !needsUser);

        // The chart editor only ever sets a free-text holder now (accounts are mirror-only,
        // REQ-ROLE-006), so the modal loads the current typed name and offers nothing else.
        setField('oc-display-name', ctx.displayName);

        const titleEl = document.getElementById('oc-modal-title');
        if (titleEl) {
            let base;
            if (mode === 'reassign') {
                base = OC_I18N.reassignTitle;
            } else if (mode === 'rename') {
                base = OC_I18N.renameTitle;
            } else if (needsStaffType) {
                base = OC_I18N.staffTitle;
            } else {
                base = OC_I18N.title;
            }
            titleEl.textContent = ctx.rankLabel ? base + ' — ' + ctx.rankLabel : base;
        }
        const submitBtn = modal.querySelector('[data-trigger="oc-modal-submit"]');
        if (submitBtn) {
            submitBtn.textContent = needsName ? OC_I18N.save : OC_I18N.submit;
        }

        modal.style.display = 'flex';
        let focusEl;
        if (needsStaffType) {
            focusEl = document.getElementById('oc-stafftype');
        } else if (needsName) {
            focusEl = document.getElementById('oc-name');
        } else {
            focusEl = document.getElementById('oc-display-name');
        }
        if (focusEl) {
            focusEl.focus();
        }
        // Trap focus + hide the page behind the dialog from tab order and AT.
        setBackgroundInert(true);
    }

    function send(method, url, body) {
        doFetch(method, url, body)
            .then(function (resp) {
                // A bare 403 is the CSRF filter rejecting a stale token (stale tab / re-login
                // session rotation); refresh once and retry, mirroring krtFetch.write.
                if (resp.status === 403) {
                    return window.krtCsrf.refresh().then(function (refreshed) {
                        return refreshed ? doFetch(method, url, body) : resp;
                    });
                }
                return resp;
            })
            .then(function (resp) {
                if (resp.ok) {
                    window.showFrontendSuccessToast(OC_I18N.saved);
                    // Capture the horizontal scroll BEFORE closeModal(): closeModal() returns focus
                    // to the trigger and clears `inert` on <main>, whose reflow can reset
                    // chart.scrollLeft to 0 on Chromium/Firefox — read it here so refreshChart
                    // restores the user's real offset.
                    const keepScroll = chart ? chart.scrollLeft : 0;
                    closeModal();
                    // Re-render the tree in place — re-stamps every data-version, so the next edit
                    // does not 409 (no reload). #574/#578/#579 chose the same fragment-swap over a
                    // node patch because the add affordances + ARIA order are derived state.
                    refreshChart(keepScroll);
                    return null;
                }
                return resp
                    .json()
                    .catch(function () {
                        return {};
                    })
                    .then(function (data) {
                        if (data && data.code === 'OPTIMISTIC_LOCK') {
                            // The version the modal carried is stale; close it and re-render so the
                            // user retries against the freshly-stamped chart instead of re-409ing.
                            window.showFrontendErrorToast(OC_I18N.conflict);
                            const keepScroll = chart ? chart.scrollLeft : 0;
                            closeModal();
                            refreshChart(keepScroll);
                            return;
                        }
                        const message = data && data.detail ? data.detail : OC_I18N.genericError;
                        window.showFrontendErrorToast(message);
                    });
            })
            .catch(function () {
                window.showFrontendErrorToast(OC_I18N.genericError);
            });
    }

    function submitModal() {
        const mode = field('oc-mode');
        const positionId = encodeURIComponent(field('oc-position-id'));
        const version = parseInt(field('oc-version'), 10);

        if (mode === 'rename') {
            send('PUT', '/org-chart/positions/' + positionId + '/ajax', {
                name: field('oc-name'),
                version: version,
            });
            return;
        }

        // The chart editor only ever sets a free-text holder now (accounts are mirror-only,
        // REQ-ROLE-006); a backend reject is the backstop if a userId ever reaches it.
        const userOptional = field('oc-user-optional') === '1';
        const displayName = field('oc-display-name').trim();

        if (mode === 'reassign') {
            if (!displayName) {
                window.showFrontendErrorToast(OC_I18N.displayNameRequired);
                return;
            }
            send('PUT', '/org-chart/positions/' + positionId + '/ajax', {
                displayName: displayName,
                version: version,
            });
            return;
        }

        // create — the Stab button picks its type from the modal select; every other
        // create carries a fixed position type from its add button.
        const staffChoice = field('oc-staff-choice') === '1';
        const positionType = staffChoice ? field('oc-stafftype') : field('oc-position-type');
        if (!displayName && !userOptional) {
            window.showFrontendErrorToast(OC_I18N.displayNameRequired);
            return;
        }
        const body = { positionType: positionType };
        // An empty name on an optional (COMMAND_LEAD) holder stays a leaderless Kommando.
        if (displayName) {
            body.displayName = displayName;
        }
        if (positionType === 'COMMAND_LEAD') {
            body.name = field('oc-name');
        }
        const orgUnitId = field('oc-org-unit-id');
        if (orgUnitId) {
            body.orgUnitId = orgUnitId;
        }
        const parentId = field('oc-parent-id');
        if (parentId) {
            body.parentId = parentId;
        }
        send('POST', '/org-chart/positions/ajax', body);
    }

    if (window.krtEvents && typeof window.krtEvents.on === 'function') {
        window.krtEvents.on('click', 'oc-toggle-edit', function (btn) {
            if (!chart) {
                return;
            }
            const editing = chart.classList.toggle('editing');
            btn.setAttribute('aria-pressed', editing ? 'true' : 'false');
            btn.textContent = editing ? OC_I18N.editDone : OC_I18N.edit;
            if (editHint) {
                editHint.classList.toggle('oc-edit-hint--on', editing);
            }
        });

        window.krtEvents.on('click', 'oc-add', function (btn) {
            lastTrigger = btn;
            openModal('create', {
                positionType: btn.getAttribute('data-position-type'),
                orgUnitId: btn.getAttribute('data-org-unit-id'),
                parentId: btn.getAttribute('data-parent-id'),
                rankLabel: btn.getAttribute('data-rank-label'),
                needsName: btn.getAttribute('data-needs-name') === 'true',
                userOptional: btn.getAttribute('data-user-optional') === 'true',
            });
        });

        window.krtEvents.on('click', 'oc-add-staff', function (btn) {
            lastTrigger = btn;
            openModal('create', { staffChoice: true });
        });

        window.krtEvents.on('click', 'oc-reassign', function (btn) {
            lastTrigger = btn;
            openModal('reassign', {
                positionId: btn.getAttribute('data-position-id'),
                version: btn.getAttribute('data-version'),
                userId: btn.getAttribute('data-user-id'),
                displayName: btn.getAttribute('data-display-name'),
                rankLabel: btn.getAttribute('data-rank-label'),
            });
        });

        window.krtEvents.on('click', 'oc-rename', function (btn) {
            lastTrigger = btn;
            openModal('rename', {
                positionId: btn.getAttribute('data-position-id'),
                version: btn.getAttribute('data-version'),
                name: btn.getAttribute('data-name'),
            });
        });

        window.krtEvents.on('click', 'oc-remove', function (btn) {
            const isCommand = btn.getAttribute('data-position-type') === 'COMMAND_LEAD';
            const message = isCommand ? OC_I18N.removeConfirmCommand : OC_I18N.removeConfirm;
            const title = OC_I18N.removeConfirmTitle;
            window.showKrtConfirm(title, message).then(function (ok) {
                if (ok) {
                    const id = encodeURIComponent(btn.getAttribute('data-position-id'));
                    send('DELETE', '/org-chart/positions/' + id + '/ajax', null);
                }
            });
        });

        // Vacate just the Kommandoleiter of a Kommando: clears the holder but keeps the
        // Kommandogruppe (and its Stv. + Ensigns) — distinct from oc-remove on the group head.
        window.krtEvents.on('click', 'oc-vacate', function (btn) {
            const title = OC_I18N.vacateConfirmTitle;
            const message = OC_I18N.vacateConfirm;
            window.showKrtConfirm(title, message).then(function (ok) {
                if (ok) {
                    const id = encodeURIComponent(btn.getAttribute('data-position-id'));
                    const version = encodeURIComponent(btn.getAttribute('data-version'));
                    send(
                        'DELETE',
                        '/org-chart/positions/' + id + '/leader/ajax?version=' + version,
                        null,
                    );
                }
            });
        });

        // Collapse / expand one Bereich (everyone, not just admins). Hides the Bereich's body
        // (Stab + Staffeln/SKs) below its Bereichsleiter, flips aria-expanded on the toggle and
        // on the Bereichsleiter treeitem, and — when collapsing — moves the tree's single
        // tabbable item to the always-visible Bereichsleiter so the roving tabindex never points
        // at a now-hidden node. No server call; purely a local view toggle.
        window.krtEvents.on('click', 'oc-collapse', function (btn) {
            const bodyId = btn.getAttribute('aria-controls');
            const body = bodyId ? document.getElementById(bodyId) : null;
            if (!body) {
                return;
            }
            const expand = btn.getAttribute('aria-expanded') === 'false';
            btn.setAttribute('aria-expanded', expand ? 'true' : 'false');
            body.hidden = !expand;
            const wrap = btn.closest('.oc-leader-wrap');
            const leader = wrap ? wrap.querySelector('[role="treeitem"]') : null;
            if (leader) {
                leader.setAttribute('aria-expanded', expand ? 'true' : 'false');
                if (!expand) {
                    const tree = btn.closest('.oc-tree');
                    if (tree) {
                        Array.prototype.forEach.call(
                            tree.querySelectorAll('[role="treeitem"]'),
                            function (n) {
                                n.setAttribute('tabindex', n === leader ? '0' : '-1');
                            },
                        );
                    }
                }
            }
        });

        window.krtEvents.on('click', 'oc-modal-cancel', closeModal);
        window.krtEvents.on('click', 'oc-modal-submit', submitModal);
    }

    if (modal) {
        modal.addEventListener('click', function (event) {
            if (event.target === modal) {
                closeModal();
            }
        });
        // Esc closes the dialog; Tab/Shift+Tab cycle focus within .modal-content.
        modal.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') {
                event.preventDefault();
                closeModal();
                return;
            }
            if (event.key !== 'Tab') {
                return;
            }
            const focusables = modalFocusable();
            if (!focusables.length) {
                return;
            }
            const first = focusables[0];
            const last = focusables[focusables.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first.focus();
            }
        });
    }

    // ---- Tree keyboard navigation (ARIA roving tabindex) -------------------
    // The chart is an ARIA tree: every box is a role="treeitem" carrying its
    // depth in aria-level (1 = Bereichsleiter … 6 = Stv./Ensign in a Kommando).
    // The DOM is a CSS-drawn flat pre-order list — boxes are siblings joined by
    // connector divs, not physically nested — so parent/child/sibling are derived
    // purely from aria-level over the flattened treeitem order. Exactly one
    // treeitem is tabbable at a time; the arrow keys move focus and preserve that
    // invariant (↑/↓ between siblings, ←/→ between levels, Home/End to the ends).
    // Wrapped in initOneTree()/initTrees() and re-run on krt:swapped: a chart refresh replaces the
    // .oc-tree elements, so the roving-tabindex init + the keydown listeners must re-bind on the
    // fresh elements (the listeners lived on the now-discarded trees). The chart now holds MULTIPLE
    // independent ARIA trees — the Organisationsleitung, one per Bereich, and the legacy/ungrouped
    // tier (epic #692 / REQ-ORG-018) — so each is initialised separately: every tree keeps its own
    // roving tabindex (exactly one tabbable item per tree) and its own keydown listener. chart is
    // the stable swap container that survives the swap.
    function initOneTree(tree) {
        if (!tree) {
            return;
        }
        // Visible treeitems only: a Bereich collapsed via its oc-collapse toggle hides its body
        // (display:none), so those treeitems have a null offsetParent and drop out of the
        // flattened nav order + roving tabindex — arrow keys skip a collapsed Bereich's subtree.
        const treeItems = function () {
            return Array.prototype.slice
                .call(tree.querySelectorAll('[role="treeitem"]'))
                .filter(function (el) {
                    return el.offsetParent !== null;
                });
        };
        const levelOf = function (el) {
            return parseInt(el.getAttribute('aria-level'), 10) || 1;
        };

        // Initial roving state: the first item is tabbable, the rest are reachable
        // only via the arrow keys. A treeitem whose next item in document order is
        // one level deeper has children, so it is marked aria-expanded (collapsible Bereiche
        // start expanded; the oc-collapse handler flips aria-expanded + hides the body).
        const initial = treeItems();
        initial.forEach(function (el, i) {
            el.setAttribute('tabindex', i === 0 ? '0' : '-1');
            const next = initial[i + 1];
            if (next && levelOf(next) > levelOf(el)) {
                el.setAttribute('aria-expanded', 'true');
            }
        });

        const focusItem = function (el) {
            if (!el) {
                return;
            }
            treeItems().forEach(function (n) {
                n.setAttribute('tabindex', n === el ? '0' : '-1');
            });
            el.focus();
        };

        // sibling = nearest item at the same level not separated by a shallower
        // (ancestor-boundary) item; child = the immediately following deeper item;
        // parent = the nearest preceding shallower item.
        const nextSibling = function (list, i) {
            const lvl = levelOf(list[i]);
            for (let j = i + 1; j < list.length; j++) {
                const l = levelOf(list[j]);
                if (l < lvl) {
                    return null;
                }
                if (l === lvl) {
                    return list[j];
                }
            }
            return null;
        };
        const prevSibling = function (list, i) {
            const lvl = levelOf(list[i]);
            for (let j = i - 1; j >= 0; j--) {
                const l = levelOf(list[j]);
                if (l < lvl) {
                    return null;
                }
                if (l === lvl) {
                    return list[j];
                }
            }
            return null;
        };
        const firstChild = function (list, i) {
            const next = list[i + 1];
            return next && levelOf(next) === levelOf(list[i]) + 1 ? next : null;
        };
        const parentOf = function (list, i) {
            const lvl = levelOf(list[i]);
            for (let j = i - 1; j >= 0; j--) {
                if (levelOf(list[j]) < lvl) {
                    return list[j];
                }
            }
            return null;
        };

        tree.addEventListener('keydown', function (event) {
            // Only act when a treeitem itself holds focus — never when focus is on
            // an inner edit/remove button (those keep their native behaviour).
            const current = event.target;
            if (!current || current.getAttribute('role') !== 'treeitem') {
                return;
            }
            const list = treeItems();
            const i = list.indexOf(current);
            if (i < 0) {
                return;
            }
            let target;
            switch (event.key) {
                case 'ArrowDown':
                    target = nextSibling(list, i);
                    break;
                case 'ArrowUp':
                    target = prevSibling(list, i);
                    break;
                case 'ArrowRight':
                    target = firstChild(list, i);
                    break;
                case 'ArrowLeft':
                    target = parentOf(list, i);
                    break;
                case 'Home':
                    target = list[0];
                    break;
                case 'End':
                    target = list[list.length - 1];
                    break;
                default:
                    return;
            }
            if (target) {
                event.preventDefault();
                focusItem(target);
            }
        });
    }
    // Initialise every tree in the chart (OL + one per Bereich + the legacy tier).
    function initTrees() {
        if (!chart) {
            return;
        }
        Array.prototype.forEach.call(chart.querySelectorAll('.oc-tree'), initOneTree);
    }
    initTrees();
    // A successful edit re-renders #oc-chart's contents (replacing every .oc-tree); re-init the
    // keyboard navigation on the fresh subtrees. Scoped to the chart container so unrelated swaps
    // elsewhere on the page do not trigger a wasted re-init.
    document.addEventListener('krt:swapped', function (e) {
        if (e && e.detail && e.detail.container === chart) {
            initTrees();
        }
    });
})();
