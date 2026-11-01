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

/* global OC_I18N */

import {
    showFrontendErrorToast,
    showFrontendSuccessToast,
    showKrtConfirm,
} from '/js/kernel/toast.js';
import { krtModal } from '/js/kernel/krt-modal.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtPageSections } from '/js/kernel/krt-page-sections.js';

const ORG_CHART_UNITS_SECTION = 'units';

const chart = document.getElementById('oc-chart');
const editHint = document.getElementById('oc-edit-hint');
const modal = document.getElementById('oc-modal');
const modalContent = modal ? modal.querySelector('.krt-modal') : null;
/** @type {HTMLElement | null} */
let lastTrigger = null;

const orgChart = krtPageSections.create({
    dict() {
        return { 'orgchart.refresh.failed': OC_I18N.refreshFailed };
    },
    keys: { refreshErrorKey: 'orgchart.refresh.failed' },
    topicClass: 'ORG_STRUCTURE',
    sections: {
        chart: { container: '#oc-chart', fragmentValue: 'chartBody' },
    },
    pageUrl() {
        return '/org-chart';
    },
    topic: 'org-structure',
    alsoBroadcast: [ORG_CHART_UNITS_SECTION],
    receiver: chart
        ? {
              coalesceMs: 1500,
              busyTest() {
                  return !!modal && window.getComputedStyle(modal).display !== 'none';
              },
              refresh(keys, run) {
                  keepChartScroll(() => run(keys));
              },
          }
        : undefined,
});

/**
 * Runs a chart re-render and puts the horizontal scroll position back once the new chart landed.
 *
 * @param {() => Promise<boolean[]>} render the re-render
 * @param {number} [keepScroll] the scroll position to restore instead of the current one
 */
function keepChartScroll(render, keepScroll) {
    if (!chart) {
        window.location.reload();
        return;
    }
    const savedX = typeof keepScroll === 'number' ? keepScroll : chart.scrollLeft;
    render().then((flags) => {
        if (!flags.every(Boolean)) {
            return;
        }
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

/**
 * @param {string} id the input id
 * @returns {string} the input's value, or an empty string when absent
 */
function field(id) {
    const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
    return el ? el.value : '';
}

/**
 * @param {string} id the input id
 * @param {string | null | undefined} value the value to set; null and undefined clear it
 */
function setField(id, value) {
    const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
    if (el) {
        el.value = value === null || value === undefined ? '' : value;
    }
}

function closeModal() {
    if (!modal) {
        return;
    }
    setBackgroundInert(false);
    krtModal.close(modal);
    if (lastTrigger && typeof lastTrigger.focus === 'function') {
        lastTrigger.focus();
    }
    lastTrigger = null;
}

/**
 * @param {string} id the element id
 * @param {boolean} hidden whether the element is hidden
 */
function setHidden(id, hidden) {
    const el = document.getElementById(id);
    if (el) {
        el.style.display = hidden ? 'none' : '';
    }
}

/** @param {boolean} inert whether the page regions behind the dialog are made inert */
function setBackgroundInert(inert) {
    const regions = ['header', 'main', '#sidebar', '#sidebar-overlay'];
    regions.forEach((sel) => {
        const el = document.querySelector(sel);
        if (!el) {
            return;
        }
        if (inert) {
            if (el.hasAttribute('inert')) {
                return;
            }
            el.setAttribute('inert', '');
            el.setAttribute('aria-hidden', 'true');
            el.setAttribute('data-oc-inert', '');
        } else if (el.hasAttribute('data-oc-inert')) {
            el.removeAttribute('inert');
            el.removeAttribute('aria-hidden');
            el.removeAttribute('data-oc-inert');
        }
    });
}

/** @returns {HTMLElement[]} the visible focusable elements of the dialog */
function modalFocusable() {
    if (!modalContent) {
        return [];
    }
    const selector =
        'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    return Array.prototype.slice.call(modalContent.querySelectorAll(selector)).filter((el) => {
        return el.getClientRects().length > 0;
    });
}

/**
 * @typedef {object} OrgChartModalContext
 * @property {string | null} [positionType]
 * @property {string | null} [orgUnitId]
 * @property {string | null} [parentId]
 * @property {string | null} [positionId]
 * @property {string | null} [version]
 * @property {string | null} [name]
 * @property {string | null} [displayName]
 * @property {string | null} [rankLabel]
 * @property {string | null} [userId]
 * @property {boolean} [userOptional]
 * @property {boolean} [staffChoice]
 * @property {boolean} [needsName]
 */

/**
 * @param {string} mode `create`, `reassign`, `rename` or `grandAdmiral`
 * @param {OrgChartModalContext} ctx the values the dialog is prefilled with
 */
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

    const needsStaffType = !!ctx.staffChoice;
    const needsName = mode === 'rename' || !!ctx.needsName;
    const needsUser = mode !== 'rename';
    setHidden('oc-stafftype-group', !needsStaffType);
    setHidden('oc-name-group', !needsName);
    setHidden('oc-user-group', !needsUser);

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
        titleEl.textContent = ctx.rankLabel ? `${base} — ${ctx.rankLabel}` : base;
    }
    const submitBtn = modal.querySelector('[data-trigger="oc-modal-submit"]');
    if (submitBtn) {
        submitBtn.textContent = needsName ? OC_I18N.save : OC_I18N.submit;
    }

    krtModal.open(modal);
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
    setBackgroundInert(true);
}

/**
 * @param {string} method the HTTP method
 * @param {string} url the target URL
 * @param {Record<string, unknown> | null} body the JSON payload, or null for none
 */
function send(method, url, body) {
    krtFetch.write({
        method,
        url,
        payload: body === null ? undefined : body,
        toast: false,
        errorMessage: OC_I18N.genericError,
        onSuccess() {
            showFrontendSuccessToast(OC_I18N.saved);
            const keepScroll = chart ? chart.scrollLeft : 0;
            closeModal();
            keepChartScroll(() => orgChart.refresh('chart'), keepScroll);
        },
        onError(_status, data) {
            if (data && data.code === 'OPTIMISTIC_LOCK') {
                showFrontendErrorToast(OC_I18N.conflict);
                const keepScroll = chart ? chart.scrollLeft : 0;
                closeModal();
                keepChartScroll(() => orgChart.refresh('chart', { broadcast: false }), keepScroll);
                return true;
            }
            const message = data && data.detail ? data.detail : OC_I18N.genericError;
            showFrontendErrorToast(message);
            return true;
        },
    });
}

function submitModal() {
    const mode = field('oc-mode');
    const positionId = encodeURIComponent(field('oc-position-id'));
    const version = parseInt(field('oc-version'), 10);

    if (mode === 'rename') {
        send('PUT', `/org-chart/positions/${positionId}/ajax`, {
            name: field('oc-name'),
            version,
        });
        return;
    }

    if (mode === 'grandAdmiral') {
        const gaName = field('oc-display-name').trim();
        if (!gaName) {
            showFrontendErrorToast(OC_I18N.displayNameRequired);
            return;
        }
        const olId = encodeURIComponent(field('oc-org-unit-id'));
        send('PUT', `/organisation/leitung/organisationsleitung/${olId}/grand-admiral/ajax`, {
            displayName: gaName,
        });
        return;
    }

    const userOptional = field('oc-user-optional') === '1';
    const displayName = field('oc-display-name').trim();

    if (mode === 'reassign') {
        if (!displayName) {
            showFrontendErrorToast(OC_I18N.displayNameRequired);
            return;
        }
        send('PUT', `/org-chart/positions/${positionId}/ajax`, {
            displayName,
            version,
        });
        return;
    }

    const staffChoice = field('oc-staff-choice') === '1';
    const positionType = staffChoice ? field('oc-stafftype') : field('oc-position-type');
    if (!displayName && !userOptional) {
        showFrontendErrorToast(OC_I18N.displayNameRequired);
        return;
    }
    /** @type {{positionType: string, displayName?: string, name?: string, orgUnitId?: string, parentId?: string}} */
    const body = { positionType };
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

krtEvents.on('click', 'oc-toggle-edit', (btn) => {
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

krtEvents.on('click', 'oc-add', (btn) => {
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

krtEvents.on('click', 'oc-add-staff', (btn) => {
    lastTrigger = btn;
    openModal('create', { staffChoice: true });
});

krtEvents.on('click', 'oc-ga-add', (btn) => {
    lastTrigger = btn;
    openModal('grandAdmiral', {
        orgUnitId: btn.getAttribute('data-org-unit-id'),
        rankLabel: btn.getAttribute('data-rank-label'),
        displayName: btn.getAttribute('data-display-name'),
    });
});

krtEvents.on('click', 'oc-ga-remove', (btn) => {
    showKrtConfirm?.(OC_I18N.removeConfirmTitle, OC_I18N.removeConfirm).then((ok) => {
        if (ok) {
            const olId = encodeURIComponent(String(btn.getAttribute('data-org-unit-id')));
            send(
                'DELETE',
                `/organisation/leitung/organisationsleitung/${olId}/grand-admiral/ajax`,
                null,
            );
        }
    });
});

krtEvents.on('click', 'oc-reassign', (btn) => {
    lastTrigger = btn;
    openModal('reassign', {
        positionId: btn.getAttribute('data-position-id'),
        version: btn.getAttribute('data-version'),
        userId: btn.getAttribute('data-user-id'),
        displayName: btn.getAttribute('data-display-name'),
        rankLabel: btn.getAttribute('data-rank-label'),
    });
});

krtEvents.on('click', 'oc-rename', (btn) => {
    lastTrigger = btn;
    openModal('rename', {
        positionId: btn.getAttribute('data-position-id'),
        version: btn.getAttribute('data-version'),
        name: btn.getAttribute('data-name'),
    });
});

krtEvents.on('click', 'oc-remove', (btn) => {
    const isCommand = btn.getAttribute('data-position-type') === 'COMMAND_LEAD';
    const message = isCommand ? OC_I18N.removeConfirmCommand : OC_I18N.removeConfirm;
    const title = OC_I18N.removeConfirmTitle;
    showKrtConfirm?.(title, message).then((ok) => {
        if (ok) {
            const id = encodeURIComponent(String(btn.getAttribute('data-position-id')));
            send('DELETE', `/org-chart/positions/${id}/ajax`, null);
        }
    });
});

krtEvents.on('click', 'oc-vacate', (btn) => {
    const title = OC_I18N.vacateConfirmTitle;
    const message = OC_I18N.vacateConfirm;
    showKrtConfirm?.(title, message).then((ok) => {
        if (ok) {
            const id = encodeURIComponent(String(btn.getAttribute('data-position-id')));
            const version = encodeURIComponent(String(btn.getAttribute('data-version')));
            send('DELETE', `/org-chart/positions/${id}/leader/ajax?version=${version}`, null);
        }
    });
});

krtEvents.on('click', 'oc-collapse', (btn) => {
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
                Array.prototype.forEach.call(tree.querySelectorAll('[role="treeitem"]'), (n) => {
                    n.setAttribute('tabindex', n === leader ? '0' : '-1');
                });
            }
        }
    }
});

krtEvents.on('click', 'oc-modal-cancel', closeModal);
krtEvents.on('click', 'oc-modal-submit', submitModal);

if (modal) {
    modal.addEventListener('click', (event) => {
        if (event.target === modal) {
            closeModal();
        }
    });
    modal.addEventListener('keydown', (event) => {
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

/** @param {HTMLElement | null | undefined} tree the `.oc-tree` element */
function initOneTree(tree) {
    if (!tree) {
        return;
    }
    /** @returns {HTMLElement[]} the visible tree items */
    const treeItems = function () {
        return Array.prototype.slice
            .call(tree.querySelectorAll('[role="treeitem"]'))
            .filter((el) => {
                return el.offsetParent !== null;
            });
    };
    /**
     * @param {Element} el a tree item
     * @returns {number} its `aria-level`, or 1
     */
    const levelOf = function (el) {
        return parseInt(/** @type {string} */ (el.getAttribute('aria-level')), 10) || 1;
    };

    const initial = treeItems();
    initial.forEach((el, i) => {
        el.setAttribute('tabindex', i === 0 ? '0' : '-1');
        const next = initial[i + 1];
        if (next && levelOf(next) > levelOf(el)) {
            el.setAttribute('aria-expanded', 'true');
        }
    });

    /** @param {HTMLElement | null} el the tree item to focus */
    const focusItem = function (el) {
        if (!el) {
            return;
        }
        treeItems().forEach((n) => {
            n.setAttribute('tabindex', n === el ? '0' : '-1');
        });
        el.focus();
    };

    /**
     * @param {HTMLElement[]} list the visible tree items
     * @param {number} i the current index
     * @returns {HTMLElement | null} the next item on the same level, if any
     */
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
    /**
     * @param {HTMLElement[]} list the visible tree items
     * @param {number} i the current index
     * @returns {HTMLElement | null} the previous item on the same level, if any
     */
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
    /**
     * @param {HTMLElement[]} list the visible tree items
     * @param {number} i the current index
     * @returns {HTMLElement | null} the item's first child, if any
     */
    const firstChild = function (list, i) {
        const next = list[i + 1];
        return next && levelOf(next) === levelOf(list[i]) + 1 ? next : null;
    };
    /**
     * @param {HTMLElement[]} list the visible tree items
     * @param {number} i the current index
     * @returns {HTMLElement | null} the item's parent, if any
     */
    const parentOf = function (list, i) {
        const lvl = levelOf(list[i]);
        for (let j = i - 1; j >= 0; j--) {
            if (levelOf(list[j]) < lvl) {
                return list[j];
            }
        }
        return null;
    };

    tree.addEventListener('keydown', (event) => {
        const current = /** @type {HTMLElement | null} */ (event.target);
        if (!current || current.getAttribute('role') !== 'treeitem') {
            return;
        }
        const list = treeItems();
        const i = list.indexOf(current);
        if (i < 0) {
            return;
        }
        /** @type {HTMLElement | null | undefined} */
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
function initTrees() {
    if (!chart) {
        return;
    }
    Array.prototype.forEach.call(chart.querySelectorAll('.oc-tree'), initOneTree);
}
initTrees();
document.addEventListener('krt:swapped', (e) => {
    if (e && e.detail && e.detail.container === chart) {
        initTrees();
    }
});

function initOrgChart() {
    if (!chart) {
        return;
    }
    const scroller = chart;
    const bar = document.createElement('div');
    bar.className = 'oc-scrollbar';
    bar.setAttribute('aria-hidden', 'true');
    const track = document.createElement('div');
    track.className = 'oc-scrollbar-track';
    bar.appendChild(track);
    chart.insertAdjacentElement('afterend', bar);

    function measure() {
        const overflow = scroller.scrollWidth - scroller.clientWidth;
        if (overflow <= 1) {
            bar.classList.remove('oc-scrollbar--active');
            scroller.classList.remove('oc-chart--proxied');
            return;
        }
        track.style.width = `${scroller.scrollWidth}px`;
        bar.classList.add('oc-scrollbar--active');
        scroller.classList.add('oc-chart--proxied');
    }

    /**
     * @param {HTMLElement} from the element that scrolled
     * @param {HTMLElement} to the element that follows proportionally
     */
    function mirror(from, to) {
        const fromRange = from.scrollWidth - from.clientWidth;
        const toRange = to.scrollWidth - to.clientWidth;
        const target = fromRange > 0 ? (from.scrollLeft / fromRange) * toRange : 0;
        if (Math.abs(to.scrollLeft - target) > 0.5) {
            to.scrollLeft = target;
        }
    }
    bar.addEventListener('scroll', () => {
        mirror(bar, scroller);
    });
    scroller.addEventListener('scroll', () => {
        mirror(scroller, bar);
    });

    let rafPending = false;
    function scheduleMeasure() {
        if (rafPending) {
            return;
        }
        rafPending = true;
        window.requestAnimationFrame(() => {
            rafPending = false;
            measure();
        });
    }
    window.addEventListener('resize', scheduleMeasure);
    if (window.MutationObserver) {
        const observer = new MutationObserver(scheduleMeasure);
        observer.observe(chart, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['hidden', 'class', 'style'],
        });
    }
    measure();
}

initOrgChart();

/**
 * Marks the unit or person named by the `highlight` query parameter, as the quick access links
 * them, and scrolls the first one into view.
 */
function highlightFromUrl() {
    const key = new URLSearchParams(window.location.search).get('highlight');
    if (!chart || !key) return;
    const targets = Array.from(chart.querySelectorAll('[data-oc-key]')).filter((el) => {
        return el.getAttribute('data-oc-key') === key;
    });
    targets.forEach((el) => {
        el.classList.add('is-highlighted');
    });
    if (targets.length > 0) targets[0].scrollIntoView({ block: 'center', inline: 'center' });
}

highlightFromUrl();
