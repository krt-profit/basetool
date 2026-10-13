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

import { showFrontendErrorToast, showKrtConfirm } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { missionHooks } from '/js/mission/mission-hooks.js';

function initMissionDetailTabs() {
    const tabs = /** @type {HTMLElement[]} */ (
        Array.from(document.querySelectorAll('.tab-nav .tab[data-tab]'))
    );
    if (!tabs.length) {
        return;
    }
    const EDIT_KEY = 'verw';
    const viewKeys = tabs.map((t) => t.getAttribute('data-tab'));
    const verwPane = document.getElementById('pane-verw');
    const editBtn = document.getElementById('mission-edit-btn');
    const editDone = document.getElementById('mission-edit-done');
    const validKeys = verwPane && editBtn ? viewKeys.concat(EDIT_KEY) : viewKeys.slice();
    const storeKey = `krt.einsatz.${window.missionId || 'new'}.tab`;
    let dirty = false;
    /** @type {string | null} */
    let current = null;
    let lastView = 'ueb';

    if (verwPane) {
        verwPane.addEventListener('input', () => {
            dirty = true;
        });
        verwPane.addEventListener('submit', () => {
            dirty = false;
        });
    }

    function currentKey() {
        return current;
    }

    /** @param {string} key the tab key to show */
    function apply(key) {
        current = key;
        if (key !== EDIT_KEY) {
            lastView = key;
        }
        const editing = key === EDIT_KEY;
        tabs.forEach((t) => {
            const tabKey = t.getAttribute('data-tab');
            const on = tabKey === key;
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', String(on));
            t.tabIndex = on || (editing && tabKey === lastView) ? 0 : -1;
        });
        document.querySelectorAll('.tab-pane').forEach((p) => {
            p.classList.toggle('on', p.id === `pane-${key}`);
        });
        if (editBtn) {
            editBtn.setAttribute('aria-pressed', String(editing));
        }
        try {
            localStorage.setItem(storeKey, key);
        } catch {}
    }

    /**
     * @param {string} key the tab key to show
     * @param {boolean} push whether to push a history entry
     * @returns {Promise<boolean>} whether the tab changed
     */
    async function show(key, push) {
        if (!validKeys.includes(key) || key === currentKey()) {
            return false;
        }
        if (dirty) {
            const i18n = window.MISSION_TAB_I18N || {};
            const go = await showKrtConfirm(
                krtI18nText(i18n['unsaved.title'], 'MISSION_TAB_I18N[unsaved.title]'),
                krtI18nText(i18n['unsaved.message'], 'MISSION_TAB_I18N[unsaved.message]'),
                krtI18nText(i18n['unsaved.continue'], 'MISSION_TAB_I18N[unsaved.continue]'),
                krtI18nText(i18n['unsaved.cancel'], 'MISSION_TAB_I18N[unsaved.cancel]'),
            );
            if (!go) return false;
            dirty = false;
        }
        apply(key);
        if (push) {
            const url = new URL(window.location.href);
            url.searchParams.set('tab', key);
            url.hash = '';
            history.pushState({ tab: key }, '', url);
        }
        return true;
    }

    async function enterEditMode() {
        if (await show(EDIT_KEY, true)) {
            const title = document.getElementById('mission-edit-title');
            if (title) {
                title.focus();
            }
        }
    }

    async function leaveEditMode() {
        if ((await show(lastView, true)) && editBtn) {
            editBtn.focus();
        }
    }

    /**
     * @param {boolean} useStore whether the remembered tab may be used
     * @returns {string} the tab key to show initially
     */
    function resolveInitial(useStore) {
        const q = new URLSearchParams(window.location.search).get('tab');
        if (q && validKeys.includes(q)) return q;
        const h = (window.location.hash.match(/tab=([\w-]+)/) || [])[1];
        if (h && validKeys.includes(h)) return h;
        if (
            document.querySelector('#pane-verw #mission-form .field-error:not(:empty)') &&
            validKeys.includes(EDIT_KEY)
        )
            return EDIT_KEY;
        if (useStore) {
            try {
                const s = localStorage.getItem(storeKey);
                if (s && validKeys.includes(s)) return s;
            } catch {}
        }
        return 'ueb';
    }

    tabs.forEach((t) =>
        t.addEventListener('click', () => {
            show(/** @type {string} */ (t.getAttribute('data-tab')), true);
        }),
    );

    if (editBtn) {
        editBtn.addEventListener('click', () => {
            if (currentKey() === EDIT_KEY) {
                leaveEditMode();
            } else {
                enterEditMode();
            }
        });
    }
    if (editDone) {
        editDone.addEventListener('click', () => {
            leaveEditMode();
        });
    }

    /** @type {HTMLElement} */ (document.querySelector('.tab-nav')).addEventListener(
        'keydown',
        (e) => {
            if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
            const i = tabs.indexOf(/** @type {HTMLElement} */ (document.activeElement));
            if (i < 0) return;
            e.preventDefault();
            const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
            next.focus();
            show(/** @type {string} */ (next.getAttribute('data-tab')), true);
        },
    );

    window.addEventListener('popstate', () => {
        apply(resolveInitial(false));
    });

    document.querySelectorAll('[data-tab-jump]').forEach((card) => {
        card.addEventListener('click', () => {
            show(/** @type {string} */ (card.getAttribute('data-tab-jump')), true);
        });
    });

    apply(resolveInitial(true));

    const sticky = document.getElementById('mission-head-sticky');
    const pageHeader = /** @type {HTMLElement | null} */ (document.querySelector('body > header'));
    function syncStickyOffset() {
        if (sticky && pageHeader) {
            sticky.style.top = `${pageHeader.offsetHeight}px`;
        }
    }
    window.addEventListener('resize', syncStickyOffset);
    syncStickyOffset();
}

initMissionDetailTabs();

function initMissionDetailTabs2() {
    const missionForm = /** @type {HTMLFormElement | null} */ (
        document.getElementById('mission-form')
    );
    if (!missionForm || missionForm.dataset.missionEdit !== 'true') return;
    const form = missionForm;
    const D = window.MISSION_SUBRES_I18N || {};
    /**
     * The mission dictionary's string for `key`, rendered as its name and reported when missing.
     *
     * @param {string} key the MISSION_SUBRES_I18N key
     * @returns {string} the localized string
     */
    function msg(key) {
        return krtI18nText(D[key], `MISSION_SUBRES_I18N[${key}]`);
    }
    const SAVED = msg('mission.save.section.ok');
    const FAILED = msg('mission.save.section.error');

    /**
     * @param {{version?: number | null, coreVersion?: number | null,
     *     scheduleVersion?: number | null, flagsVersion?: number | null} | null | undefined} v
     *     the saved mission's section versions
     */
    function writeVersions(v) {
        if (!v) return;
        /**
         * @param {string} id the hidden version input id
         * @param {number | null | undefined} val the version to write
         */
        function set(id, val) {
            const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
            if (el && val != null) el.value = String(val);
        }
        set('mission-version', v.version);
        set('mission-core-version', v.coreVersion);
        set('mission-schedule-version', v.scheduleVersion);
        set('mission-flags-version', v.flagsVersion);
    }
    function clearFieldErrors() {
        form.querySelectorAll('.field-error[data-error-for]').forEach((el) => {
            el.textContent = '';
        });
    }
    /** @param {Record<string, string>} map the validation message per field name */
    function renderFieldErrors(map) {
        clearFieldErrors();
        Object.keys(map || {}).forEach((field) => {
            const slot = form.querySelector(`.field-error[data-error-for="${field}"]`);
            if (slot) slot.textContent = map[field];
            else showFrontendErrorToast(map[field]);
        });
    }
    /** @param {ApiProblem & {code?: string}} problem the 409 problem body */
    function handleConflict(problem) {
        const code = problem && problem.code;
        if (code === 'OPTIMISTIC_LOCK' || code === 'PESSIMISTIC_LOCK') {
            showKrtConfirm(
                msg('mission.conflict.toast.title'),
                msg('mission.conflict.action.reload.question'),
                msg('mission.conflict.action.reload'),
                msg('mission.conflict.action.dismiss'),
            ).then((ok) => {
                if (ok) window.location.reload();
            });
        } else {
            const ownerRequired = krtFetch.ownerOrgUnitRequiredMessage(problem);
            showFrontendErrorToast(
                ownerRequired ||
                    (problem && problem.detail) ||
                    msg('mission.conflict.toast.detail'),
            );
        }
    }
    /** @type {Record<string, string[]>} */
    const SECTION_FIELDS = {
        core: ['name', 'description', 'calendarLink', 'status', 'operationId', 'meetingPoint'],
        schedule: [
            'meetingTime',
            'plannedStartTime',
            'plannedEndTime',
            'actualStartTime',
            'actualEndTime',
        ],
        flags: ['isInternal'],
    };
    /**
     * @param {FormData} fd the form data
     * @param {string[]} fields the field names of one section
     * @returns {string} a comparable snapshot of the section's values
     */
    function sectionSnapshot(fd, fields) {
        return fields
            .map((n) => {
                return `${n}=${fd.getAll(n).join(',')}`;
            })
            .join('|');
    }
    /** @type {Record<string, string>} */
    const initialSnapshot = {};
    (function captureInitialSnapshot() {
        const fd0 = new FormData(form);
        Object.keys(SECTION_FIELDS).forEach((sec) => {
            initialSnapshot[sec] = sectionSnapshot(fd0, SECTION_FIELDS[sec]);
        });
    })();
    function markDirtySections() {
        const fd = new FormData(form);
        /**
         * @param {string} id the hidden dirty-flag input id
         * @param {boolean} dirty whether the section changed
         */
        function setFlag(id, dirty) {
            const el = /** @type {HTMLInputElement | null} */ (document.getElementById(id));
            if (el) el.value = dirty ? 'true' : 'false';
        }
        setFlag(
            'mission-dirty-core',
            sectionSnapshot(fd, SECTION_FIELDS.core) !== initialSnapshot.core,
        );
        setFlag(
            'mission-dirty-schedule',
            sectionSnapshot(fd, SECTION_FIELDS.schedule) !== initialSnapshot.schedule,
        );
        setFlag(
            'mission-dirty-flags',
            sectionSnapshot(fd, SECTION_FIELDS.flags) !== initialSnapshot.flags,
        );
    }

    async function submitInPlace() {
        markDirtySections();
        await krtFetch.submitForm({
            form,
            url: form.action,
            serialize: 'section:schedule',
            successMessage: SAVED,
            errorMessage: FAILED,
            submitter: document.querySelector('button[type="submit"][form="mission-form"]'),
            onError(status, body) {
                if (status === 422) {
                    renderFieldErrors(body || {});
                    return true;
                }
                if (status === 409) {
                    handleConflict(body || {});
                    return true;
                }
                return false;
            },
            onSuccess(body) {
                writeVersions(body);
                clearFieldErrors();
                if (missionHooks.refresh) {
                    return missionHooks.refresh('overview');
                }
                return undefined;
            },
        });
    }
    form.addEventListener('submit', (e) => {
        e.preventDefault();
        submitInPlace();
    });
}

initMissionDetailTabs2();

function initMissionDetailTabs3() {
    if (!window.missionCanEdit) {
        return;
    }
    const crewBoard = document.getElementById('crew-board-results');
    if (!crewBoard) {
        return;
    }
    const board = crewBoard;

    /** @type {Element | null} */
    let dragged = null;
    /** @type {Element | null} */
    let selected = null;
    let droppedOnZone = false;

    const EDGE_ZONE_PX = 72;
    const MAX_SCROLL_STEP_PX = 22;
    let autoScrollDir = 0;
    let autoScrollStep = 0;
    /** @type {number | null} */
    let autoScrollRaf = null;

    function autoScrollTick() {
        if (autoScrollDir === 0) {
            autoScrollRaf = null;
            return;
        }
        window.scrollBy(0, autoScrollDir * autoScrollStep);
        autoScrollRaf = window.requestAnimationFrame(autoScrollTick);
    }

    function stopAutoScroll() {
        autoScrollDir = 0;
        autoScrollStep = 0;
        if (autoScrollRaf !== null) {
            window.cancelAnimationFrame(autoScrollRaf);
            autoScrollRaf = null;
        }
    }

    /**
     * Returns the viewport y at which the visible page ends: the top of the pinned bottom bar
     * (the phone tab bar or the fixed footer) when one is shown, otherwise the viewport height.
     *
     * @returns {number} the lowest client y that still shows page content
     */
    function visibleBottom() {
        let bottom = window.innerHeight;
        document.querySelectorAll('.mobile-tabbar, .krt-footer').forEach((bar) => {
            if (getComputedStyle(bar).position !== 'fixed') return;
            const r = bar.getBoundingClientRect();
            if (r.height > 0 && r.top < bottom) bottom = r.top;
        });
        return bottom;
    }

    /** @param {number} y the pointer's client y */
    function driveEdgeScroll(y) {
        const h = visibleBottom();
        if (y <= EDGE_ZONE_PX) {
            autoScrollDir = -1;
            autoScrollStep = Math.ceil((MAX_SCROLL_STEP_PX * (EDGE_ZONE_PX - y)) / EDGE_ZONE_PX);
        } else if (y >= h - EDGE_ZONE_PX) {
            autoScrollDir = 1;
            autoScrollStep = Math.min(
                MAX_SCROLL_STEP_PX,
                Math.ceil((MAX_SCROLL_STEP_PX * (y - (h - EDGE_ZONE_PX))) / EDGE_ZONE_PX),
            );
        } else {
            autoScrollDir = 0;
            autoScrollStep = 0;
        }
        if (autoScrollDir !== 0 && autoScrollRaf === null) {
            autoScrollRaf = window.requestAnimationFrame(autoScrollTick);
        }
    }

    /** @param {Element | null} row the row to select, or null to clear */
    function setSelected(row) {
        if (selected) {
            selected.classList.remove('is-selected');
            selected.setAttribute('aria-pressed', 'false');
        }
        selected = row;
        if (selected) {
            selected.classList.add('is-selected');
            selected.setAttribute('aria-pressed', 'true');
        }
    }

    /**
     * @param {Element} row the participant row
     * @param {Element} zone the target drop zone
     */
    async function moveParticipant(row, zone) {
        if (!missionHooks.write || !window.missionId) return;
        const participantId = row.getAttribute('data-participant-id');
        const srcCrewId = row.getAttribute('data-crew-id') || '';
        const srcUnitId = row.getAttribute('data-unit-id') || '';
        const targetUnitId = zone.getAttribute('data-unit-id') || '';
        if (!participantId || srcUnitId === targetUnitId) return;

        if (srcCrewId && srcUnitId) {
            const del = await missionHooks.write({
                method: 'DELETE',
                url: `/missions/${window.missionId}/units/${srcUnitId}/crew/${srcCrewId}/ajax`,
                sectionKey: 'crew',
            });
            if (!del.ok) return;
            if (!targetUnitId) {
                missionHooks.refresh('crew');
                return;
            }
        }
        if (targetUnitId) {
            await missionHooks.write({
                method: 'POST',
                url: `/missions/${window.missionId}/units/${targetUnitId}/crew/ajax`,
                payload: { participantId, jobTypeIds: [] },
                sectionKey: 'crew',
            });
            missionHooks.refresh('crew');
        }
    }

    board.addEventListener('dragstart', (e) => {
        const row = /** @type {Element} */ (e.target).closest('.person-row');
        if (!row || !board.contains(row)) return;
        dragged = row;
        droppedOnZone = false;
        if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
    });
    board.addEventListener('dragend', () => {
        if (dragged && !droppedOnZone) {
            const pool = document.getElementById('board-pool');
            if (
                pool &&
                dragged.getAttribute('data-crew-id') &&
                dragged.getAttribute('data-unit-id')
            ) {
                moveParticipant(dragged, pool);
            }
        }
        stopAutoScroll();
        dragged = null;
        droppedOnZone = false;
    });

    board.addEventListener('click', (e) => {
        if (suppressClick) {
            suppressClick = false;
            return;
        }
        const row = /** @type {Element} */ (e.target).closest('.person-row');
        if (row && board.contains(row)) {
            if (
                /** @type {Element} */ (e.target).closest('button') ||
                /** @type {Element} */ (e.target).closest('select') ||
                /** @type {Element} */ (e.target).closest('form') ||
                /** @type {Element} */ (e.target).closest('a')
            )
                return;
            setSelected(selected === row ? null : row);
            return;
        }
        const zone = /** @type {Element} */ (e.target).closest('.drop-zone');
        if (zone && board.contains(zone)) {
            if (
                /** @type {Element} */ (e.target).closest('button') ||
                /** @type {Element} */ (e.target).closest('select') ||
                /** @type {Element} */ (e.target).closest('a')
            )
                return;
            if (selected && !zone.contains(selected)) {
                const sel = selected;
                setSelected(null);
                moveParticipant(sel, zone);
            }
        }
    });

    board.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        if (!e.target || typeof (/** @type {Element} */ (e.target).closest) !== 'function') return;
        const row = /** @type {Element} */ (e.target).closest('.person-row');
        if (row && e.target === row) {
            e.preventDefault();
            setSelected(selected === row ? null : row);
            return;
        }
        const zone = /** @type {Element} */ (e.target).closest('.drop-zone');
        if (zone && e.target === zone && selected && !zone.contains(selected)) {
            e.preventDefault();
            const sel = selected;
            setSelected(null);
            moveParticipant(sel, zone);
        }
    });

    board.addEventListener('dragover', (e) => {
        const zone = /** @type {Element} */ (e.target).closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        e.preventDefault();
        zone.classList.add('is-over');
    });
    board.addEventListener('dragleave', (e) => {
        const zone = /** @type {Element} */ (e.target).closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        zone.classList.remove('is-over');
    });
    board.addEventListener('drop', (e) => {
        const zone = /** @type {Element} */ (e.target).closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        e.preventDefault();
        zone.classList.remove('is-over');
        droppedOnZone = true;
        stopAutoScroll();
        if (dragged && !zone.contains(dragged)) {
            moveParticipant(dragged, zone);
        }
    });

    document.addEventListener('dragover', (e) => {
        if (!dragged) return;
        driveEdgeScroll(e.clientY);
    });

    const LONG_PRESS_MS = 320;
    const MOVE_CANCEL_PX = 12;

    /** @type {Element | null} */
    let touchRow = null;
    let touchActive = false;
    /** @type {Element | null} */
    let touchZone = null;
    /** @type {number | null} */
    let touchPointerId = null;
    /** @type {number | null} */
    let pressTimer = null;
    let pressX = 0;
    let pressY = 0;
    let suppressClick = false;

    function clearPressTimer() {
        if (pressTimer !== null) {
            window.clearTimeout(pressTimer);
            pressTimer = null;
        }
    }

    /**
     * @param {number} x the client x
     * @param {number} y the client y
     * @returns {Element | null} the drop zone under the point
     */
    function zoneAt(x, y) {
        const el = document.elementFromPoint(x, y);
        if (!el || typeof el.closest !== 'function') return null;
        const zone = el.closest('.drop-zone');
        return zone && board.contains(zone) ? zone : null;
    }

    /** @param {Element | null} zone the zone under the touch, or null */
    function setTouchZone(zone) {
        if (touchZone === zone) return;
        if (touchZone) touchZone.classList.remove('is-over');
        touchZone = zone;
        if (touchZone) touchZone.classList.add('is-over');
    }

    function endTouchDrag() {
        clearPressTimer();
        if (touchRow) {
            touchRow.classList.remove('is-touch-dragging');
            if (touchPointerId !== null) {
                try {
                    touchRow.releasePointerCapture(touchPointerId);
                } catch (_e) {}
            }
        }
        setTouchZone(null);
        stopAutoScroll();
        touchRow = null;
        touchActive = false;
        touchPointerId = null;
    }

    board.addEventListener('pointerdown', (e) => {
        suppressClick = false;
        if (e.pointerType === 'mouse') return;
        const row = /** @type {Element} */ (e.target).closest('.person-row');
        if (!row || !board.contains(row)) return;
        if (
            /** @type {Element} */ (e.target).closest('button') ||
            /** @type {Element} */ (e.target).closest('select') ||
            /** @type {Element} */ (e.target).closest('form') ||
            /** @type {Element} */ (e.target).closest('a')
        )
            return;
        endTouchDrag();
        touchRow = row;
        touchPointerId = e.pointerId;
        pressX = e.clientX;
        pressY = e.clientY;
        pressTimer = window.setTimeout(() => {
            pressTimer = null;
            if (!touchRow) return;
            touchActive = true;
            touchRow.classList.add('is-touch-dragging');
            try {
                touchRow.setPointerCapture(/** @type {number} */ (touchPointerId));
            } catch (_e) {}
        }, LONG_PRESS_MS);
    });

    document.addEventListener('pointermove', (e) => {
        if (!touchRow || e.pointerId !== touchPointerId) return;
        if (!touchActive) {
            if (
                Math.abs(e.clientX - pressX) > MOVE_CANCEL_PX ||
                Math.abs(e.clientY - pressY) > MOVE_CANCEL_PX
            ) {
                endTouchDrag();
            }
            return;
        }
        setTouchZone(zoneAt(e.clientX, e.clientY));
        driveEdgeScroll(e.clientY);
    });

    document.addEventListener('pointerup', (e) => {
        if (!touchRow || e.pointerId !== touchPointerId) return;
        if (!touchActive) {
            endTouchDrag();
            return;
        }
        const row = touchRow;
        const overBar = e.clientY >= visibleBottom();
        const zone = overBar ? null : zoneAt(e.clientX, e.clientY);
        endTouchDrag();
        suppressClick = true;
        if (overBar) return;
        if (zone) {
            if (!zone.contains(row)) moveParticipant(row, zone);
            return;
        }
        const pool = document.getElementById('board-pool');
        if (pool && row.getAttribute('data-crew-id') && row.getAttribute('data-unit-id')) {
            moveParticipant(row, pool);
        }
    });

    document.addEventListener('pointercancel', (e) => {
        if (!touchRow || e.pointerId !== touchPointerId) return;
        endTouchDrag();
    });

    document.addEventListener(
        'touchmove',
        (e) => {
            if (touchActive) e.preventDefault();
        },
        { passive: false },
    );

    board.addEventListener('contextmenu', (e) => {
        if (touchRow) e.preventDefault();
    });

    board.addEventListener('change', async (e) => {
        const sel = /** @type {HTMLSelectElement | null} */ (
            /** @type {Element} */ (e.target).closest('.crew-role-select')
        );
        if (!sel || !board.contains(sel)) return;
        const value = sel.value;
        if (value === '__edit') {
            sel.value = sel.getAttribute('data-current') || '';
            if (typeof missionHooks.openEditCrewModal === 'function') {
                missionHooks.openEditCrewModal(sel);
            }
            return;
        }
        if (value === '__multi') {
            return;
        }
        const ajaxUrl = sel.getAttribute('data-action-ajax');
        if (!ajaxUrl || !missionHooks.write) return;
        const versionAttr = sel.getAttribute('data-version');
        const res = await missionHooks.write({
            method: 'PUT',
            url: ajaxUrl,
            payload: {
                jobTypeIds: value ? [value] : [],
                version: versionAttr ? Number(versionAttr) : null,
            },
            sectionKey: 'crew',
        });
        if (res.ok) {
            missionHooks.refresh('crew');
        } else {
            sel.value = sel.getAttribute('data-current') || '';
        }
    });

    document.addEventListener('krt:swapped', (ev) => {
        if (ev.detail && ev.detail.container === board) {
            selected = null;
            dragged = null;
            droppedOnZone = false;
            endTouchDrag();
            stopAutoScroll();
        }
    });
}

initMissionDetailTabs3();

/**
 * Renders the element's `data-utc` epoch-millisecond value in the viewer's local time zone.
 *
 * @param {HTMLElement} el the element whose text receives the localized date or time
 */
function krtFormatLocalDateTime(el) {
    const utcMsStr = el.getAttribute('data-utc');
    if (utcMsStr && utcMsStr !== 'null') {
        const date = new Date(parseInt(utcMsStr, 10));
        if (!isNaN(date.getTime())) {
            const hours = String(date.getHours()).padStart(2, '0');
            const minutes = String(date.getMinutes()).padStart(2, '0');
            if (el.getAttribute('data-format') === 'time') {
                el.innerText = `${hours}:${minutes}`;
                return;
            }
            const day = String(date.getDate()).padStart(2, '0');
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const year = date.getFullYear();
            el.innerText = `${day}.${month}.${year} ${hours}:${minutes}`;
            return;
        }
    }
    el.innerText = '—';
}

missionHooks.localizeDates = function (root) {
    const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
    /** @type {NodeListOf<HTMLElement>} */ (scope.querySelectorAll('.krt-local-dt')).forEach(
        krtFormatLocalDateTime,
    );
    /** @type {NodeListOf<HTMLElement>} */ (
        scope.querySelectorAll('.refinery-endsat-local')
    ).forEach((el) => {
        const utcMsStr = el.getAttribute('data-utc');
        if (utcMsStr && utcMsStr !== 'null') {
            krtFormatLocalDateTime(el);
        } else {
            el.innerText = '-';
        }
    });
};

document.addEventListener('DOMContentLoaded', () => {
    missionHooks.localizeDates(document);
});
document.addEventListener('krt:swapped', (ev) => {
    missionHooks.localizeDates((ev.detail && ev.detail.container) || document);
});
