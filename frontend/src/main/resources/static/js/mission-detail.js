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
 * Mission detail page module (#924 Part 2) — the former inline script blocks of
 * templates/mission-detail.html moved verbatim into one classic script, loaded synchronously at
 * the same end-of-body position right after the page's inline i18n/constants bootstrap.
 *
 * Contents, in original document order: the krtMissionWrite / krtRefreshMissionSection /
 * krtNotifyMissionChanged section-write seam (built via the shared krtFetch.sectionWrite factory,
 * #574 / REQ-FE-010), the out-of-fragment header patch listeners (crew counts, finance tab badge,
 * sticky-header title/status/facts), the live multi-user sync receiver (REQ-FE-010 / ADR-0031),
 * the Ablauf steps and Ziele objectives drag editors, the KRT modal helpers and finance type
 * segments, the participant/frequency/custom-frequency/unit/crew/finance modal wiring with their
 * AJAX interceptors (including the shared #delete-confirm-form unit/participant/crew/finance
 * submit chain), the Verwaltung owner/manager/owning-org-unit/payout handlers and their krtEvents
 * registrations, the tab navigation with the unsaved-changes guard, the in-place core-edit save
 * (#589), the crew board drag & drop + keyboard operation, and the UTC-to-local datetime
 * localiser.
 *
 * The Thymeleaf-interpolated constants (the MSG_* consts, the window.MISSION_*_I18N dictionaries,
 * missionId, window.missionCanEdit) stay in the template's inline th:inline="javascript"
 * bootstrap, which executes before this file; classic scripts share the global lexical
 * environment, so the bare reads below resolve against those bootstrap const bindings.
 * openEditFinanceModal (the template's conditional finance block, th:if="${!isNew}") and the
 * presence bootstrap stay inline in the template and are only referenced late-bound from event
 * callbacks here; showFrontendErrorToast is the window-property function from fragments/toast.html
 * that block 5 historically calls without the window. prefix.
 */
/* global MSG_ERROR_PAYOUT_UPDATE, MSG_ERROR_MANAGER_ADD, MSG_ERROR_MANAGER_REMOVE, MSG_ERROR_OWNER_CHANGE, MSG_CONFIRM_OWNER_CHANGE, MSG_ERROR_OWNING_ORG_UNIT_CHANGE, MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE, MSG_CONFIRM_MANAGER_REMOVE, MSG_ERROR_USER_REQUIRED, missionId, openEditFinanceModal, showFrontendErrorToast */

// ---- #574 in-place AJAX seam (retires window.MissionSubresource) -----------------
// krtMissionWrite wraps window.krtFetch.write and sources the already-localized section/conflict
// strings from MISSION_SUBRES_I18N exactly as the retired MissionSubresource alias did, so every
// call site stays a one-liner while the shared krt-fetch.js carries no mission-specific code.
// Since #924 the trio is produced by the mission-agnostic window.krtFetch.sectionWrite factory;
// only the mission-specific pieces live here: the dictionary keys with their fallbacks, the
// sectionKey -> {container, fragmentValue} map, the page URL and the presence broadcast.
// Everything is handed over LATE-BOUND (dict getter, pageUrl getter, broadcast closure, each
// evaluated per call): the dictionary is assigned by the inline bootstrap and
// window.missionPresence only exists after the conditional presence bootstrap's
// DOMContentLoaded (and only on !isNew pages with an authUserId).
// Single source of truth for the mission's refreshable sections (sectionKey ->
// {container, fragmentValue}). Shared by the write seam below AND the live-sync receiver
// further down, which derives its container map from it — keeping them one object means a
// section added here is automatically picked up by peers' receivers (REQ-FE-010); the two
// maps drifted apart once (objectives/frequencies missing on the receive side) and must not
// diverge again.
const MISSION_SECTIONS = {
    crew: { container: '#crew-board-results', fragmentValue: 'crew-board' },
    finance: { container: '#finance-results', fragmentValue: 'finance' },
    mgmt: { container: '#mission-mgmt-results', fragmentValue: 'mgmt' },
    overview: { container: '#overview-results', fragmentValue: 'overview' },
    steps: { container: '#mission-steps-results', fragmentValue: 'steps-editor' },
    objectives: {
        container: '#mission-objectives-results',
        fragmentValue: 'objectives-editor',
    },
    frequencies: {
        container: '#mission-frequencies-results',
        fragmentValue: 'frequencies-editor',
    },
};

const missionSeam = window.krtFetch.sectionWrite({
    dict: function () {
        return window.MISSION_SUBRES_I18N || {};
    },
    keys: {
        saveSectionPrefix: 'mission.save.section.',
        conflictSectionPrefix: 'mission.conflict.section.',
        successKey: 'mission.save.section.ok',
        successFallback: 'Gespeichert.',
        errorKey: 'mission.save.section.error',
        errorFallback: 'Speichern fehlgeschlagen.',
        conflictTitleKey: 'mission.conflict.toast.title',
        conflictTitleFallback: 'Konflikt',
        reloadLabelKey: 'mission.conflict.action.reload',
        reloadLabelFallback: 'Aktuelle Werte laden',
        dismissLabelKey: 'mission.conflict.action.dismiss',
        dismissLabelFallback: 'Schliessen',
        reloadQuestionKey: 'mission.conflict.action.reload.question',
        reloadQuestionFallback: 'Aktuelle Werte laden?',
        reloadDetailKey: 'mission.conflict.toast.detail',
        reloadDetailFallback: 'Bitte Seite neu laden.',
        refreshErrorKey: 'mission.section.refresh.error',
    },
    sections: MISSION_SECTIONS,
    pageUrl: function () {
        return window.missionId ? '/missions/' + window.missionId : null;
    },
    // Live multi-user sync (REQ-FE-010): tell other users viewing this mission that these
    // sections just changed, so their views re-fetch the same fragments in place. Suppressed
    // when this refresh is itself the application of a peer's signal (opts.broadcast === false),
    // otherwise the inbound change would echo straight back into a loop.
    broadcast: function (keys) {
        if (window.missionPresence && typeof window.missionPresence.sendChanged === 'function') {
            window.missionPresence.sendChanged(keys);
        }
    },
});
window.krtMissionWrite = missionSeam.write;

// krtRefreshMissionSection re-renders one or more mission sections in place via server-rendered
// fragment swaps, replacing the former window.location.reload() after a successful sub-mutation
// (#571/#574): 'crew' -> crew board (#crew-board-results), 'finance' -> finance & payout pane
// (#finance-results), 'mgmt' -> owner/manager panel (#mission-mgmt-results). Participant writes
// pass ['crew','finance'] because a participant also renders in the payout table (list, share and
// participation %). Accepts a single key or an array; returns a Promise resolving when all swaps
// complete so callers can close a modal afterwards.
window.krtRefreshMissionSection = missionSeam.refresh;

// Broadcast-only sibling of krtRefreshMissionSection (REQ-FE-010): signal peers that these
// sections changed WITHOUT re-fetching locally — for handlers that already patched their own DOM
// surgically (payout preference, party-lead, frequency) and so do not need a self re-render.
window.krtNotifyMissionChanged = missionSeam.notify;

// Patch the participant counts that live in the page header — the facts bar (#facts-registered,
// #facts-checked-in) and the crew tab badge (#tab-crew .tab-count) — after every in-place crew
// swap. They sit OUTSIDE the #crew-board-results fragment, so a participant add / edit / delete
// or check-in / out would otherwise leave them stale until a full reload (#571/#574). The
// crewBoard fragment exposes the fresh counts via #crew-count-meta; this generalises the finance
// #finance-count-meta / refreshFinanceAndBadge precedent as a krt:swapped listener so it covers
// every crew-board swap without touching each write call site.
document.addEventListener('krt:swapped', function (ev) {
    const container = ev && ev.detail && ev.detail.container;
    if (!container || container.id !== 'crew-board-results') {
        return;
    }
    const meta = document.getElementById('crew-count-meta');
    if (!meta) {
        return;
    }
    const registered = (meta.getAttribute('data-registered') || '0').trim();
    const checkedIn = (meta.getAttribute('data-checked-in') || '0').trim();
    const registeredEl = document.getElementById('facts-registered');
    const checkedInEl = document.getElementById('facts-checked-in');
    const badge = document.querySelector('#tab-crew .tab-count');
    if (registeredEl) {
        registeredEl.textContent = registered;
    }
    if (checkedInEl) {
        checkedInEl.textContent = checkedIn;
    }
    if (badge) {
        badge.textContent = checkedIn + '/' + registered;
    }
});

// After ANY in-place finance swap — local OR peer-driven (live sync, REQ-FE-010) — patch the
// Finanzen tab badge (#finance-tab-count) from the fresh count the fragment exposes in
// #finance-count-meta. It lives in the tab nav OUTSIDE #finance-results, so a peer-driven swap
// (which does not run the local add/edit/delete handlers) would otherwise leave it stale until a
// reload. Mirrors the crew listener above; makes the count the single source of truth so the
// local handlers no longer need to patch the badge themselves.
document.addEventListener('krt:swapped', function (ev) {
    const container = ev && ev.detail && ev.detail.container;
    if (!container || container.id !== 'finance-results') {
        return;
    }
    const meta = document.getElementById('finance-count-meta');
    const badge = document.getElementById('finance-tab-count');
    if (meta && badge) {
        badge.textContent = (meta.textContent || '0').trim();
    }
});

// After an in-place OVERVIEW swap, patch the parts of the sticky header that live OUTSIDE the
// #overview-results fragment — the mission title (h1), the status pill (text + colour class) and
// the Server-Join / TS facts — from the fresh values the fragment exposes in #overview-head-meta.
// Generalises the crew #crew-count-meta precedent so a peer's core / schedule / status change is
// reflected in the header without a full reload.
document.addEventListener('krt:swapped', function (ev) {
    const container = ev && ev.detail && ev.detail.container;
    if (!container || container.id !== 'overview-results') {
        return;
    }
    const meta = document.getElementById('overview-head-meta');
    if (!meta) {
        return;
    }
    const title = meta.getAttribute('data-title');
    const h1 = document.querySelector('.mission-head-title h1');
    if (h1 && title != null) {
        h1.textContent = title;
    }
    const status = (meta.getAttribute('data-status') || '').trim();
    const statusLabel = meta.getAttribute('data-status-label');
    const pill = document.querySelector('.mission-head-title .status-pill');
    if (pill && status) {
        pill.className = 'status-pill status-' + status;
        if (statusLabel != null) {
            pill.textContent = statusLabel;
        }
    }
    function patchFact(id, utcAttr) {
        const el = document.getElementById(id);
        if (!el) {
            return;
        }
        const utc = meta.getAttribute(utcAttr);
        el.setAttribute('data-utc', utc != null ? utc : 'null');
        if (typeof window.krtLocalizeDates === 'function') {
            window.krtLocalizeDates(el.parentNode || el);
        }
    }
    patchFact('facts-planned-start', 'data-planned-start-utc');
    patchFact('facts-planned-end', 'data-planned-end-utc');
    patchFact('facts-ts', 'data-meeting-utc');
    // The "Leiter" fact shows the Einsatzleiter (the participant designated as mission lead),
    // else the mission owner — computed server-side and exposed on the fragment as data-leader, so
    // a peer's participant / planned-job-type change updates the header too (it lives outside the
    // overview fragment).
    const lead = meta.getAttribute('data-leader');
    const leadEl = document.getElementById('facts-leader');
    if (leadEl && lead != null) {
        leadEl.textContent = lead;
    }
    // The owning-org-unit badge in the sticky header (outside this fragment) mirrors the owning
    // squadron — patch it from the fresh shorthand/name the fragment exposes so a peer's
    // reassignment (REQ-ORG-018) shows in the header too. Empty shorthand = ownerless → cleared.
    const ouSlot = document.getElementById('mission-head-org-badge-slot');
    if (ouSlot) {
        const ouShorthand = meta.getAttribute('data-owning-org-unit-shorthand');
        const ouName = meta.getAttribute('data-owning-org-unit-name');
        ouSlot.textContent = '';
        if (ouShorthand) {
            const badge = document.createElement('span');
            badge.className = 'squadron-badge';
            badge.title = ouName || '';
            badge.textContent = ouShorthand;
            ouSlot.appendChild(badge);
        }
    }
});

// ---- Live multi-user sync receiver (REQ-FE-010 / ADR-0031) ----------------------------------
// mission-presence.js dispatches 'krt:mission-changed' {sections:[...]} when ANOTHER user mutates
// this mission, and 'krt:mission-resync' after a dropped socket reconnects (signals may have been
// missed while offline). We re-fetch the affected section fragments via
// krtRefreshMissionSection(..., {broadcast:false}) — broadcast:false stops the applied change
// from echoing back into a loop. The data never travels over the socket: every peer re-pulls
// through its own authenticated, authorization-checked fragment endpoint, so guest redaction and
// the member-only finance gate still apply per viewer.
(function () {
    // Derived from the seam's MISSION_SECTIONS map (same file, top) so the receiver covers
    // exactly the sections the write side broadcasts — a hand-maintained copy here once lost
    // 'objectives' and 'frequencies', leaving peers' Ziele / Weitere Frequenzen stale until a
    // manual reload.
    const SECTION_CONTAINERS = {};
    Object.keys(MISSION_SECTIONS).forEach(function (sectionKey) {
        SECTION_CONTAINERS[sectionKey] = MISSION_SECTIONS[sectionKey].container;
    });
    const ALL_SECTIONS = Object.keys(SECTION_CONTAINERS);
    const COALESCE_MS = 400;
    const pendingNow = {}; // sections queued for the next debounce tick (Set-like map)
    const deferred = {}; // sections held back because the user is editing (Set-like map)
    let timer = null;

    function anyModalOpen() {
        return Array.prototype.some.call(
            document.querySelectorAll('.krt-modal-overlay'),
            function (o) {
                return window.getComputedStyle(o).display !== 'none';
            },
        );
    }

    // Never yank a section out from under an active edit. A modal open anywhere means the user is
    // mid-edit; focus inside the section's own container means inline editing there.
    function sectionBusy(sectionKey) {
        if (anyModalOpen()) {
            return true;
        }
        const sel = SECTION_CONTAINERS[sectionKey];
        const container = sel && document.querySelector(sel);
        return !!(
            container &&
            document.activeElement &&
            container.contains(document.activeElement)
        );
    }

    function refresh(keys) {
        if (keys.length && window.krtRefreshMissionSection) {
            window.krtRefreshMissionSection(keys, { broadcast: false });
        }
    }

    // Remove the pill once nothing is held back, so it never lingers for an already-current
    // section.
    function hidePillIfEmpty() {
        if (Object.keys(deferred).length === 0) {
            const pill = document.getElementById('mission-livesync-pill');
            if (pill) {
                pill.remove();
            }
        }
    }

    // The busy test is re-applied here, at flush time — not only when the signal first arrived.
    // A section that became busy DURING the COALESCE_MS window (the user opened a modal or
    // focused an inline edit on it) is moved to `deferred` + the pill instead of being swapped
    // out from under the edit. Sections that are safe to refresh are dropped from `deferred` so
    // the pill cannot keep claiming updates for a section that just refreshed in place.
    function flushTimer() {
        timer = null;
        const keys = Object.keys(pendingNow);
        keys.forEach(function (k) {
            delete pendingNow[k];
        });
        const ready = [];
        let nowDeferred = false;
        keys.forEach(function (k) {
            if (sectionBusy(k)) {
                deferred[k] = true;
                nowDeferred = true;
            } else {
                delete deferred[k];
                ready.push(k);
            }
        });
        refresh(ready);
        if (nowDeferred) {
            showPill();
        }
        hidePillIfEmpty();
    }

    function schedule(sectionKey) {
        pendingNow[sectionKey] = true;
        if (!timer) {
            timer = setTimeout(flushTimer, COALESCE_MS);
        }
    }

    function showPill() {
        let pill = document.getElementById('mission-livesync-pill');
        if (pill) {
            return;
        }
        const dict = window.MISSION_LIVESYNC_I18N || {};
        const label =
            dict['mission.livesync.updates_available'] != null &&
            dict['mission.livesync.updates_available'] !== ''
                ? dict['mission.livesync.updates_available']
                : 'Aktualisierungen verfügbar';
        pill = document.createElement('button');
        pill.id = 'mission-livesync-pill';
        pill.type = 'button';
        pill.className = 'mission-livesync-pill';
        pill.textContent = label;
        pill.addEventListener('click', function () {
            // Apply only sections that are no longer busy — a still-open modal / focused edit on
            // a section keeps it deferred (and the pill) rather than being clobbered by an
            // explicit click. (Current fragments are display-oriented and their modals are
            // separate overlays, so this is belt-and-suspenders, but it keeps the guard honest
            // if a future fragment ever embeds an inline-edit field.)
            const ready = [];
            Object.keys(deferred).forEach(function (k) {
                if (sectionBusy(k)) {
                    return;
                }
                delete deferred[k];
                ready.push(k);
            });
            refresh(ready);
            hidePillIfEmpty();
        });
        document.body.appendChild(pill);
    }

    function apply(sections) {
        const keys = Array.isArray(sections) && sections.length ? sections : ALL_SECTIONS;
        let anyDeferred = false;
        keys.forEach(function (sectionKey) {
            const sel = SECTION_CONTAINERS[sectionKey];
            if (!sel || !document.querySelector(sel)) {
                return; // unknown key, or this viewer cannot see the section (e.g. no mgmt panel)
            }
            if (sectionBusy(sectionKey)) {
                deferred[sectionKey] = true;
                anyDeferred = true;
            } else {
                schedule(sectionKey);
            }
        });
        if (anyDeferred) {
            showPill();
        }
    }

    document.addEventListener('krt:mission-changed', function (ev) {
        apply(ev && ev.detail ? ev.detail.sections : null);
    });
    document.addEventListener('krt:mission-resync', function () {
        apply(null); // refresh everything visible after a reconnect
    });
})();

// ---- Ablauf (procedure timeline): overview done-toggle + Verwaltung drag-editor -------------
// Every mutation goes through krtMissionWrite (CSRF + retry-once-on-403 + the shared 409
// reload-confirm) against the /steps/* AJAX proxies, then re-renders the editor + overview
// checklist fragments in place via krtRefreshMissionSection (which also broadcasts to peers,
// REQ-FE-010). The dedicated mission.stepsVersion section counter is read from the nearest
// [data-steps-version] holder and echoed on every write so the next click never 409s; after each
// swap the fresh fragment carries the bumped version. Handlers are delegated on document so they
// survive the innerHTML swaps. No window.location.reload() on success.
(function () {
    function mid() {
        return window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
    }
    function versionFrom(el) {
        const holder = el && el.closest('[data-steps-version]');
        return holder ? Number(holder.getAttribute('data-steps-version')) : null;
    }
    function refreshSteps() {
        if (window.krtRefreshMissionSection) {
            return window.krtRefreshMissionSection(['steps', 'overview']);
        }
        return Promise.resolve();
    }
    function writeStep(opts) {
        return window.krtMissionWrite(
            Object.assign({ sectionKey: 'steps', onSuccess: refreshSteps }, opts),
        );
    }
    function listOrder() {
        return Array.prototype.map.call(
            document.querySelectorAll('#mission-step-list .ae-row'),
            function (r) {
                return r.getAttribute('data-step-id');
            },
        );
    }

    function toggleStep(box) {
        const sid = box.getAttribute('data-step-id');
        const v = versionFrom(box);
        if (!sid || v === null || Number.isNaN(v)) {
            return;
        }
        const done = box.getAttribute('data-done') !== 'true';
        writeStep({
            method: 'PATCH',
            url: '/missions/' + mid() + '/steps/' + sid + '/done/ajax',
            payload: { done: done, stepsVersion: v },
        });
    }
    function addStep(btn) {
        const v = versionFrom(btn);
        if (v === null || Number.isNaN(v)) {
            return;
        }
        const title =
            (window.MISSION_STEP_I18N && window.MISSION_STEP_I18N.default_title) || 'New step';
        writeStep({
            method: 'POST',
            url: '/missions/' + mid() + '/steps/ajax',
            payload: { title: title, meta: null, stepsVersion: v },
        });
    }
    function editStep(row) {
        const sid = row.getAttribute('data-step-id');
        const v = versionFrom(row);
        const titleInput = row.querySelector('.ae-title');
        if (!sid || v === null || Number.isNaN(v) || !titleInput) {
            return;
        }
        const title = titleInput.value.trim();
        const metaInput = row.querySelector('.ae-meta');
        const meta = metaInput ? metaInput.value : null;
        // The backend requires a non-blank title; an emptied title is a no-op (restored on the
        // next swap) rather than a rejected write.
        if (!title) {
            return;
        }
        writeStep({
            method: 'PUT',
            url: '/missions/' + mid() + '/steps/' + sid + '/ajax',
            payload: { title: title, meta: meta, stepsVersion: v },
        });
    }
    async function deleteStep(btn) {
        const row = btn.closest('.ae-row');
        if (!row) {
            return;
        }
        const sid = row.getAttribute('data-step-id');
        const v = versionFrom(row);
        if (!sid || v === null || Number.isNaN(v)) {
            return;
        }
        const msg =
            (window.MISSION_STEP_I18N && window.MISSION_STEP_I18N.delete_confirm) ||
            'Delete this step?';
        const ok = await window.showKrtConfirm(
            msg,
            msg,
            (window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.reload']) ||
                'OK',
            (window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss']) ||
                'Cancel',
        );
        if (!ok) {
            return;
        }
        writeStep({
            method: 'DELETE',
            url: '/missions/' + mid() + '/steps/' + sid + '/ajax?stepsVersion=' + v,
        });
    }
    function reorder(order, v) {
        if (v === null || Number.isNaN(v)) {
            return;
        }
        writeStep({
            method: 'PUT',
            url: '/missions/' + mid() + '/steps/reorder/ajax',
            payload: { stepIds: order, stepsVersion: v },
        });
    }
    function moveStep(btn, dir) {
        const row = btn.closest('.ae-row');
        if (!row) {
            return;
        }
        const v = versionFrom(row);
        const order = listOrder();
        const i = order.indexOf(row.getAttribute('data-step-id'));
        const j = i + dir;
        if (i < 0 || j < 0 || j >= order.length) {
            return;
        }
        const tmp = order[i];
        order[i] = order[j];
        order[j] = tmp;
        reorder(order, v);
    }

    document.addEventListener('click', function (e) {
        const t = e.target.closest('[data-trigger]');
        if (!t) {
            return;
        }
        const trig = t.getAttribute('data-trigger');
        if (trig === 'mission-toggle-step') {
            e.preventDefault();
            toggleStep(t);
        } else if (trig === 'mission-step-add') {
            e.preventDefault();
            addStep(t);
        } else if (trig === 'mission-step-delete') {
            e.preventDefault();
            deleteStep(t);
        } else if (trig === 'mission-step-up') {
            e.preventDefault();
            moveStep(t, -1);
        } else if (trig === 'mission-step-down') {
            e.preventDefault();
            moveStep(t, 1);
        }
    });
    document.addEventListener('keydown', function (e) {
        const t = e.target.closest('[data-trigger="mission-toggle-step"]');
        if (!t) {
            return;
        }
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            toggleStep(t);
        }
    });
    document.addEventListener('change', function (e) {
        const inp = e.target.closest('#mission-step-list .ae-title, #mission-step-list .ae-meta');
        if (!inp) {
            return;
        }
        const row = inp.closest('.ae-row');
        if (row) {
            editStep(row);
        }
    });

    // Drag reorder — scoped to #mission-step-list so the crew board's own drag is untouched
    // (we only preventDefault once a drag that started in the step list is in flight).
    let dragRow = null;
    document.addEventListener('dragstart', function (e) {
        const row = e.target.closest('#mission-step-list .ae-row');
        if (!row) {
            return;
        }
        dragRow = row;
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
        }
    });
    document.addEventListener('dragover', function (e) {
        const list = e.target.closest('#mission-step-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const row = e.target.closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach(function (r) {
            r.classList.remove('drag-over');
        });
        if (row && row !== dragRow) {
            row.classList.add('drag-over');
        }
    });
    document.addEventListener('drop', function (e) {
        const list = e.target.closest('#mission-step-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const target = e.target.closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach(function (r) {
            r.classList.remove('drag-over');
        });
        if (target && target !== dragRow) {
            const v = versionFrom(dragRow);
            const order = listOrder();
            const from = order.indexOf(dragRow.getAttribute('data-step-id'));
            order.splice(
                order.indexOf(target.getAttribute('data-step-id')),
                0,
                order.splice(from, 1)[0],
            );
            reorder(order, v);
        }
        dragRow = null;
    });
    document.addEventListener('dragend', function () {
        document.querySelectorAll('#mission-step-list .ae-row.drag-over').forEach(function (r) {
            r.classList.remove('drag-over');
        });
        dragRow = null;
    });
})();

// ---- Ziele (mission goals): Verwaltung drag-editor (classified, sortable) ---------------------
// Mirrors the Ablauf editor: every mutation goes through krtMissionWrite (CSRF + retry-once-on-403
// + the shared 409 reload-confirm) against the /objectives/* AJAX proxies, then re-renders the
// editor + overview Ziele fragments in place via krtRefreshMissionSection (which also broadcasts to
// peers, REQ-FE-010). The dedicated mission.objectivesVersion section counter is read from the
// nearest [data-objectives-version] holder and echoed on every write. Handlers are delegated on
// document so they survive the innerHTML swaps. No window.location.reload() on success. Goals carry
// no done-toggle (a goal is a scope statement, not a progress item).
(function () {
    function mid() {
        return window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
    }
    function versionFrom(el) {
        const holder = el && el.closest('[data-objectives-version]');
        return holder ? Number(holder.getAttribute('data-objectives-version')) : null;
    }
    function refreshObjectives() {
        if (window.krtRefreshMissionSection) {
            return window.krtRefreshMissionSection(['objectives', 'overview']);
        }
        return Promise.resolve();
    }
    function writeObjective(opts) {
        return window.krtMissionWrite(
            Object.assign({ sectionKey: 'objectives', onSuccess: refreshObjectives }, opts),
        );
    }
    function listOrder() {
        return Array.prototype.map.call(
            document.querySelectorAll('#mission-objective-list .ae-row'),
            function (r) {
                return r.getAttribute('data-objective-id');
            },
        );
    }

    function addObjective(btn) {
        const v = versionFrom(btn);
        if (v === null || Number.isNaN(v)) {
            return;
        }
        const title =
            (window.MISSION_OBJECTIVE_I18N && window.MISSION_OBJECTIVE_I18N.default_title) ||
            'New goal';
        writeObjective({
            method: 'POST',
            url: '/missions/' + mid() + '/objectives/ajax',
            payload: { title: title, kind: 'PRIMARY', objectivesVersion: v },
        });
    }
    function editObjective(row) {
        const oid = row.getAttribute('data-objective-id');
        const v = versionFrom(row);
        const titleInput = row.querySelector('.ae-title');
        const kindSelect = row.querySelector('.ae-kind');
        if (!oid || v === null || Number.isNaN(v) || !titleInput || !kindSelect) {
            return;
        }
        const title = titleInput.value.trim();
        // The backend requires a non-blank title; an emptied title is a no-op (restored on the
        // next swap) rather than a rejected write.
        if (!title) {
            return;
        }
        writeObjective({
            method: 'PUT',
            url: '/missions/' + mid() + '/objectives/' + oid + '/ajax',
            payload: { title: title, kind: kindSelect.value, objectivesVersion: v },
        });
    }
    async function deleteObjective(btn) {
        const row = btn.closest('.ae-row');
        if (!row) {
            return;
        }
        const oid = row.getAttribute('data-objective-id');
        const v = versionFrom(row);
        if (!oid || v === null || Number.isNaN(v)) {
            return;
        }
        const msg =
            (window.MISSION_OBJECTIVE_I18N && window.MISSION_OBJECTIVE_I18N.delete_confirm) ||
            'Delete this goal?';
        const ok = await window.showKrtConfirm(
            msg,
            msg,
            (window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.reload']) ||
                'OK',
            (window.MISSION_SUBRES_I18N &&
                window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss']) ||
                'Cancel',
        );
        if (!ok) {
            return;
        }
        writeObjective({
            method: 'DELETE',
            url: '/missions/' + mid() + '/objectives/' + oid + '/ajax?objectivesVersion=' + v,
        });
    }
    function reorder(order, v) {
        if (v === null || Number.isNaN(v)) {
            return;
        }
        writeObjective({
            method: 'PUT',
            url: '/missions/' + mid() + '/objectives/reorder/ajax',
            payload: { objectiveIds: order, objectivesVersion: v },
        });
    }
    function moveObjective(btn, dir) {
        const row = btn.closest('.ae-row');
        if (!row) {
            return;
        }
        const v = versionFrom(row);
        const order = listOrder();
        const i = order.indexOf(row.getAttribute('data-objective-id'));
        const j = i + dir;
        if (i < 0 || j < 0 || j >= order.length) {
            return;
        }
        const tmp = order[i];
        order[i] = order[j];
        order[j] = tmp;
        reorder(order, v);
    }

    document.addEventListener('click', function (e) {
        const t = e.target.closest('[data-trigger]');
        if (!t) {
            return;
        }
        const trig = t.getAttribute('data-trigger');
        if (trig === 'mission-objective-add') {
            e.preventDefault();
            addObjective(t);
        } else if (trig === 'mission-objective-delete') {
            e.preventDefault();
            deleteObjective(t);
        } else if (trig === 'mission-objective-up') {
            e.preventDefault();
            moveObjective(t, -1);
        } else if (trig === 'mission-objective-down') {
            e.preventDefault();
            moveObjective(t, 1);
        }
    });
    document.addEventListener('change', function (e) {
        const inp = e.target.closest(
            '#mission-objective-list .ae-title, #mission-objective-list .ae-kind',
        );
        if (!inp) {
            return;
        }
        const row = inp.closest('.ae-row');
        if (row) {
            editObjective(row);
        }
    });

    // Drag reorder — scoped to #mission-objective-list so the crew board's / Ablauf's own drag is
    // untouched (we only preventDefault once a drag that started in the goal list is in flight).
    let dragRow = null;
    document.addEventListener('dragstart', function (e) {
        const row = e.target.closest('#mission-objective-list .ae-row');
        if (!row) {
            return;
        }
        dragRow = row;
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
        }
    });
    document.addEventListener('dragover', function (e) {
        const list = e.target.closest('#mission-objective-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const row = e.target.closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach(function (r) {
            r.classList.remove('drag-over');
        });
        if (row && row !== dragRow) {
            row.classList.add('drag-over');
        }
    });
    document.addEventListener('drop', function (e) {
        const list = e.target.closest('#mission-objective-list');
        if (!list || !dragRow) {
            return;
        }
        e.preventDefault();
        const target = e.target.closest('.ae-row');
        list.querySelectorAll('.ae-row').forEach(function (r) {
            r.classList.remove('drag-over');
        });
        if (target && target !== dragRow) {
            const v = versionFrom(dragRow);
            const order = listOrder();
            const from = order.indexOf(dragRow.getAttribute('data-objective-id'));
            order.splice(
                order.indexOf(target.getAttribute('data-objective-id')),
                0,
                order.splice(from, 1)[0],
            );
            reorder(order, v);
        }
        dragRow = null;
    });
    document.addEventListener('dragend', function () {
        document
            .querySelectorAll('#mission-objective-list .ae-row.drag-over')
            .forEach(function (r) {
                r.classList.remove('drag-over');
            });
        dragRow = null;
    });
})();

// ---- KRT modal helpers: open with focus, dismiss buttons, Esc, focus trap. ----
(function () {
    function visibleOverlays() {
        return Array.from(document.querySelectorAll('.krt-modal-overlay')).filter(
            (o) => window.getComputedStyle(o).display !== 'none',
        );
    }
    function focusables(overlay) {
        return Array.from(
            overlay.querySelectorAll(
                'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
            ),
        ).filter((el) => el.offsetParent !== null);
    }
    window.krtModalOpen = function (overlay) {
        if (!overlay) return;
        overlay.style.display = 'flex';
        const f = focusables(overlay);
        if (f.length) f[0].focus();
    };
    window.krtModalClose = function (overlay) {
        if (overlay) overlay.style.display = 'none';
    };
    document.addEventListener('click', function (e) {
        const dismiss = e.target.closest('[data-modal-dismiss]');
        if (dismiss) {
            const overlay = dismiss.closest('.krt-modal-overlay');
            if (overlay) window.krtModalClose(overlay);
        }
    });
    document.addEventListener('keydown', function (e) {
        const open = visibleOverlays();
        if (!open.length) return;
        const top = open[open.length - 1];
        if (e.key === 'Escape') {
            e.preventDefault();
            window.krtModalClose(top);
            return;
        }
        if (e.key === 'Tab' && top.contains(document.activeElement)) {
            const f = focusables(top);
            if (!f.length) return;
            const first = f[0];
            const last = f[f.length - 1];
            if (e.shiftKey && document.activeElement === first) {
                e.preventDefault();
                last.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
                e.preventDefault();
                first.focus();
            }
        }
    });
    // Finance type segment controls: the buttons mirror their value into the hidden
    // type input so the classic Spring form binding keeps working unchanged.
    window.krtSegSet = function (targetId, value) {
        const seg = document.querySelector('.seg[data-seg-target="' + targetId + '"]');
        const input = document.getElementById(targetId);
        if (!seg || !input) return;
        input.value = value;
        seg.querySelectorAll('button[data-type-value]').forEach(function (b) {
            const on = b.getAttribute('data-type-value') === value;
            b.classList.toggle('on-pos', on && value === 'INCOME');
            b.classList.toggle('on-neg', on && value === 'EXPENSE');
            if (!on) {
                b.classList.remove('on-pos', 'on-neg');
            }
            b.setAttribute('aria-pressed', String(on));
        });
    };
    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('.seg[data-seg-target]').forEach(function (seg) {
            const targetId = seg.getAttribute('data-seg-target');
            const input = document.getElementById(targetId);
            seg.querySelectorAll('button[data-type-value]').forEach(function (b) {
                b.addEventListener('click', function () {
                    window.krtSegSet(targetId, b.getAttribute('data-type-value'));
                });
            });
            window.krtSegSet(targetId, input && input.value ? input.value : 'INCOME');
        });
    });
})();

document.addEventListener('DOMContentLoaded', function () {
    // Modal Logic
    const pModal = document.getElementById('participant-modal');
    const pBtn = document.getElementById('add-participant-btn');

    if (pBtn && pModal) {
        pBtn.onclick = function () {
            window.krtModalOpen(pModal);
        };
    }

    // Edit Modal Logic
    const eModal = document.getElementById('edit-participant-modal');
    const eForm = document.getElementById('edit-participant-form');
    const eJob = document.getElementById('edit-job');
    const ePlannedJob = document.getElementById('edit-planned-job');
    const eOrgUnits = document.getElementById('edit-org-units');
    const eComment = document.getElementById('edit-comment');

    if (eModal) {
        // .edit-participant-btn live inside the crew board, which an in-place swap re-renders
        // (#571/#574). Re-bind on krt:swapped; the dataset guard skips buttons that persist
        // across a swap so they are never double-bound.
        function bindEditParticipantButtons() {
            document.querySelectorAll('.edit-participant-btn').forEach((btn) => {
                if (btn.dataset.epBound) return;
                btn.dataset.epBound = '1';
                btn.addEventListener('click', function () {
                    eForm.action = window.safeSameOriginUrl(
                        this.getAttribute('data-action'),
                        eForm.action,
                    );
                    eForm.setAttribute(
                        'data-participant-id',
                        this.getAttribute('data-participant-id') || '',
                    );
                    eJob.value = this.getAttribute('data-job');
                    if (ePlannedJob) ePlannedJob.value = this.getAttribute('data-planned-job');
                    if (eOrgUnits) {
                        // Preselect the participant's current org-unit ids in the multi-select.
                        const selected = (this.getAttribute('data-org-units') || '')
                            .split(',')
                            .filter(Boolean);
                        Array.from(eOrgUnits.options).forEach((opt) => {
                            opt.selected = selected.includes(opt.value);
                        });
                    }
                    eComment.value = this.getAttribute('data-comment');

                    const ePayout = document.getElementById('edit-payout-preference');
                    if (ePayout)
                        ePayout.value = this.getAttribute('data-payout-preference') || 'PAYOUT';

                    const eVersion = document.getElementById('edit-participant-version');
                    const eStartTimeHidden = document.getElementById(
                        'edit-participant-start-time-hidden',
                    );
                    const eEndTimeHidden = document.getElementById(
                        'edit-participant-end-time-hidden',
                    );
                    const eStartTimeUi = document.getElementById('edit-participant-start-time-ui');
                    const eEndTimeUi = document.getElementById('edit-participant-end-time-ui');

                    if (eVersion) eVersion.value = this.getAttribute('data-version') || '';

                    // Modal head shows who is being edited ("mara.k bearbeiten" pattern from the mock).
                    const eTitle = document.getElementById('edit-participant-title');
                    if (eTitle && this.getAttribute('data-name')) {
                        eTitle.textContent = this.getAttribute('data-name');
                    }

                    // The footer "Abmelden" button mirrors the row's delete button: same classic
                    // delete action + participant id, routed through the shared delete-confirm modal.
                    const eUnregister = document.getElementById('edit-participant-unregister-btn');
                    if (eUnregister && window.missionId) {
                        const pid = this.getAttribute('data-participant-id') || '';
                        eUnregister.setAttribute(
                            'data-action',
                            '/missions/' + window.missionId + '/participants/' + pid + '/delete',
                        );
                        eUnregister.setAttribute('data-participant-id', pid);
                        eUnregister.setAttribute('data-name', this.getAttribute('data-name') || '');
                    }

                    // Hidden-Submit-Felder (name="startTime"/"endTime") mit dem UTC-ISO-Wert aus
                    // dem Backend befuellen. Diese werden nur ueberschrieben, wenn der Nutzer
                    // die Zeit aendert (siehe Submit-Handler unten) bzw. der Splitter feuert.
                    const startUtc = this.getAttribute('data-start-time') || '';
                    const endUtc = this.getAttribute('data-end-time') || '';
                    if (eStartTimeHidden) eStartTimeHidden.value = startUtc;
                    if (eEndTimeHidden) eEndTimeHidden.value = endUtc;

                    // UI-Hidden innerhalb der datetime-split-group: UTC-ISO setzen und den
                    // Splitter erneut synchronisieren, damit die sichtbaren date/time-Parts in
                    // der Browser-Lokalzeit vorbelegt werden. Ohne expliziten Sync blieben die
                    // Felder leer, weil der Splitter nur einmal beim DOMContentLoaded laeuft.
                    if (eStartTimeUi) {
                        eStartTimeUi.value = startUtc;
                        if (typeof window.krtSyncDatetimeSplitGroup === 'function') {
                            window.krtSyncDatetimeSplitGroup(
                                eStartTimeUi.closest('.datetime-split-group'),
                            );
                        }
                    }
                    if (eEndTimeUi) {
                        eEndTimeUi.value = endUtc;
                        if (typeof window.krtSyncDatetimeSplitGroup === 'function') {
                            window.krtSyncDatetimeSplitGroup(
                                eEndTimeUi.closest('.datetime-split-group'),
                            );
                        }
                    }

                    window.krtModalOpen(eModal);
                });
            });
        }
        bindEditParticipantButtons();
        document.addEventListener('krt:swapped', bindEditParticipantButtons);

        if (eForm) {
            eForm.addEventListener('submit', function () {
                const eStartTimeUi = document.getElementById('edit-participant-start-time-ui');
                const eEndTimeUi = document.getElementById('edit-participant-end-time-ui');
                const eStartTimeHidden = document.getElementById(
                    'edit-participant-start-time-hidden',
                );
                const eEndTimeHidden = document.getElementById('edit-participant-end-time-hidden');

                function toUtcISOString(localDateStr) {
                    // localDateStr is already YYYY-MM-DDThh:mm and will be handled by the server
                    // Backend will parse this as Europe/Berlin timezone
                    return localDateStr || '';
                }

                // Only overwrite hidden fields if UI fields are rendered (user has permission to edit times)
                if (eStartTimeUi !== null && eStartTimeHidden) {
                    eStartTimeHidden.value = toUtcISOString(eStartTimeUi.value);
                }
                if (eEndTimeUi !== null && eEndTimeHidden) {
                    eEndTimeHidden.value = toUtcISOString(eEndTimeUi.value);
                }
            });
        }
    }

    // Frequenzen werden im Backend und in der Anzeige immer mit `.` als Trennzeichen
    // gespeichert/gerendert. Beim Tippen tolerieren wir beides: ein eingegebenes `,` wird
    // sofort zu `.` normalisiert, damit `parseFloat` in den AJAX-Submit-Handlern (und die
    // klassischen Spring-Form-Bindings) den Wert nicht beim Komma abschneiden.
    document.querySelectorAll('.freq-input').forEach(function (input) {
        input.addEventListener('input', function () {
            if (this.value.indexOf(',') !== -1) {
                this.value = this.value.replace(/,/g, '.');
            }
        });
    });

    const frequencyModal = document.getElementById('frequency-modal');
    const frequencyForm = document.getElementById('frequency-form');

    if (frequencyModal) {
        document.querySelectorAll('.set-freq-btn').forEach((btn) => {
            btn.addEventListener('click', function () {
                // Paket 3B: AJAX submission via MissionSubresource
                frequencyForm.setAttribute('data-action', this.getAttribute('data-action'));
                const typeId = this.getAttribute('data-type-id');
                document.getElementById('freq-type-id').value = typeId;
                const current = this.getAttribute('data-current-value') || '';
                document.getElementById('freq-value').value = current;
                window.krtModalOpen(frequencyModal);
            });
        });

        // Paket 3B: intercept submit and call PUT /frequencies/ajax, then
        // update the displayed value in place without a full page reload.
        // The value renders twice (Uebersicht kv-list + Verwaltung panel),
        // so every matching display/edit-button/row container is synced.
        if (frequencyForm) {
            frequencyForm.addEventListener('submit', async function (event) {
                event.preventDefault();
                const url = frequencyForm.getAttribute('data-action');
                if (!url || !window.krtMissionWrite) {
                    frequencyForm.submit();
                    return;
                }
                const typeId = document.getElementById('freq-type-id').value;
                const rawValue = document.getElementById('freq-value').value;
                const parsed = Number.parseFloat(rawValue);
                if (!typeId || Number.isNaN(parsed)) {
                    return;
                }
                const result = await window.krtMissionWrite({
                    method: 'PUT',
                    url: url,
                    payload: { frequencyTypeId: typeId, value: parsed },
                    sectionKey: 'frequency',
                });
                if (result.ok) {
                    if (Array.isArray(result.body)) {
                        const match = result.body.find(
                            (f) => f && f.frequencyType && f.frequencyType.id === typeId,
                        );
                        if (match) {
                            const formatted = Number(match.value).toFixed(2);
                            document
                                .querySelectorAll(
                                    '.freq-value-display[data-freq-type-id="' + typeId + '"]',
                                )
                                .forEach(function (display) {
                                    display.textContent = formatted;
                                    display.style.display = '';
                                });
                            document
                                .querySelectorAll('.set-freq-btn[data-type-id="' + typeId + '"]')
                                .forEach(function (editBtn) {
                                    editBtn.setAttribute('data-current-value', String(match.value));
                                });
                            // Per-frequency data-version sync: keeps the row's optimistic-lock
                            // counter fresh so a follow-up edit on the same frequency does not
                            // ship a stale version.
                            if (match.version != null) {
                                document
                                    .querySelectorAll(
                                        '[data-freq-type-id="' +
                                            typeId +
                                            '"]:not(.freq-value-display):not(.set-freq-btn)',
                                    )
                                    .forEach(function (rowContainer) {
                                        rowContainer.setAttribute(
                                            'data-version',
                                            String(match.version),
                                        );
                                    });
                            }
                        }
                    }
                    window.krtModalClose(frequencyModal);
                    // The in-place patch above keeps the Verwaltung row (value + data-version for the
                    // next edit) current, but the Übersicht Funk panel now only renders frequencies
                    // that carry a value (#816): a frequency set for the first time has no row to
                    // patch there, so re-render the overview section in place. krtRefreshMissionSection
                    // also broadcasts 'overview' to peers (REQ-FE-010), replacing the prior notify.
                    if (window.krtRefreshMissionSection) {
                        window.krtRefreshMissionSection('overview');
                    } else if (window.krtNotifyMissionChanged) {
                        window.krtNotifyMissionChanged('overview');
                    }
                }
            });
        }
    }

    // ---- Weitere Frequenzen (REQ-MISSION-014): mission-specific custom channels ----------------
    // Add / edit share one modal (empty hidden id = add), delete asks a KRT confirm (no native
    // dialog). Every mutation goes through krtMissionWrite (CSRF + retry-once-on-403 + the shared
    // 409 reload-confirm) against the /frequencies/custom AJAX proxies, then re-renders the editor
    // + overview Funk fragments in place via krtRefreshMissionSection (which also broadcasts to
    // peers, REQ-FE-010). The click handlers are delegated on document so they survive the fragment
    // swap; the modal lives outside the swapped fragment so its listeners persist.
    (function () {
        const customFreqModal = document.getElementById('custom-frequency-modal');
        const customFreqForm = document.getElementById('custom-frequency-form');
        if (!customFreqModal || !customFreqForm) {
            return;
        }
        const idInput = document.getElementById('custom-freq-id');
        const versionInput = document.getElementById('custom-freq-version');
        const nameInput = document.getElementById('custom-freq-name');
        const valueInput = document.getElementById('custom-freq-value');
        const titleEl = document.getElementById('custom-frequency-modal-title');
        const dict = window.MISSION_CUSTOM_FREQ_I18N || {};
        const mid = function () {
            return window.missionId;
        };

        function openAdd() {
            idInput.value = '';
            versionInput.value = '';
            nameInput.value = '';
            valueInput.value = '';
            if (titleEl && dict.add) {
                titleEl.textContent = dict.add;
            }
            window.krtModalOpen(customFreqModal);
            nameInput.focus();
        }
        function openEdit(row) {
            idInput.value = row.getAttribute('data-custom-freq-id') || '';
            versionInput.value = row.getAttribute('data-version') || '';
            nameInput.value = row.getAttribute('data-name') || '';
            valueInput.value = row.getAttribute('data-value') || '';
            if (titleEl && dict.edit) {
                titleEl.textContent = dict.edit;
            }
            window.krtModalOpen(customFreqModal);
            nameInput.focus();
        }
        async function del(row) {
            const fid = row.getAttribute('data-custom-freq-id');
            if (!fid) {
                return;
            }
            const msg = dict.delete_confirm || 'Delete this frequency?';
            const ok = await window.showKrtConfirm(
                msg,
                msg,
                (window.MISSION_SUBRES_I18N &&
                    window.MISSION_SUBRES_I18N['mission.conflict.action.reload']) ||
                    'OK',
                (window.MISSION_SUBRES_I18N &&
                    window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss']) ||
                    'Cancel',
            );
            if (!ok) {
                return;
            }
            const result = await window.krtMissionWrite({
                method: 'DELETE',
                url: '/missions/' + mid() + '/frequencies/' + fid + '/ajax',
                sectionKey: 'frequency',
            });
            if (result.ok && window.krtRefreshMissionSection) {
                window.krtRefreshMissionSection(['frequencies', 'overview']);
            }
        }

        document.addEventListener('click', function (e) {
            const t = e.target.closest('[data-trigger]');
            if (!t) {
                return;
            }
            const trig = t.getAttribute('data-trigger');
            if (trig === 'custom-freq-add') {
                e.preventDefault();
                openAdd();
            } else if (trig === 'custom-freq-edit') {
                e.preventDefault();
                const r = t.closest('.custom-freq-row');
                if (r) {
                    openEdit(r);
                }
            } else if (trig === 'custom-freq-delete') {
                e.preventDefault();
                const r = t.closest('.custom-freq-row');
                if (r) {
                    del(r);
                }
            }
        });

        customFreqForm.addEventListener('submit', async function (event) {
            event.preventDefault();
            const name = (nameInput.value || '').trim();
            const parsed = Number.parseFloat(valueInput.value);
            if (!name || Number.isNaN(parsed)) {
                return;
            }
            const fid = idInput.value;
            const isEdit = !!fid;
            const payload = isEdit
                ? { name: name, value: parsed, version: Number(versionInput.value) }
                : { name: name, value: parsed };
            const result = await window.krtMissionWrite({
                method: isEdit ? 'PUT' : 'POST',
                url: isEdit
                    ? '/missions/' + mid() + '/frequencies/custom/' + fid + '/ajax'
                    : '/missions/' + mid() + '/frequencies/custom/ajax',
                payload: payload,
                sectionKey: 'frequency',
            });
            if (result.ok) {
                window.krtModalClose(customFreqModal);
                if (window.krtRefreshMissionSection) {
                    window.krtRefreshMissionSection(['frequencies', 'overview']);
                }
            }
        });
    })();

    // Autocomplete Logic

    // Delete Modal Logic
    const deleteModal = document.getElementById('delete-confirm-modal');
    const deleteForm = document.getElementById('delete-confirm-form');

    if (deleteModal) {
        // The confirm body names the consequence per sub-section (mock pattern):
        // which entity, and what happens to dependent data.
        function deleteMessageFor(btn) {
            const section = btn.getAttribute('data-sub-section') || 'default';
            const dict = window.MISSION_DELETE_I18N || {};
            let msg = dict[section] || dict['default'] || '';
            msg = msg.replace('{name}', btn.getAttribute('data-name') || '');
            msg = msg.replace('{count}', btn.getAttribute('data-crew-count') || '0');
            return msg;
        }
        // The delete openers live across the crew board and finance pane (both re-rendered by an
        // in-place swap) plus the static admin area; re-bind on krt:swapped with a dataset guard
        // so swapped-in buttons get wired without double-binding the persistent ones (#571/#574).
        // NOTE: .delete-crew-btn (and the data-sub-section="crew" branches below + in the shared
        // delete-confirm submit handler) currently match nothing — crew removal happens via
        // drag-to-pool in moveParticipant() — and are kept as a dormant forward-compatible hook.
        function bindDeleteOpeners() {
            document
                .querySelectorAll(
                    '.delete-participant-btn, .delete-unit-btn, .delete-crew-btn, .delete-mission-btn, .delete-finance-btn',
                )
                .forEach((btn) => {
                    if (btn.dataset.delBound) return;
                    btn.dataset.delBound = '1';
                    btn.addEventListener('click', function () {
                        deleteForm.action = window.safeSameOriginUrl(
                            this.getAttribute('data-action'),
                            deleteForm.action,
                        );
                        // Paket 3C: propagate AJAX-capable sub-section metadata so the
                        // shared delete-confirm-form submit handler can route to the
                        // appropriate /ajax endpoint (no page reload, no parent-version bump).
                        const subSection = this.getAttribute('data-sub-section') || '';
                        let subId;
                        if (subSection === 'crew') {
                            // Crew delete needs BOTH unitId and crewId; the primary subId
                            // is the crewId, the unit is stored as auxiliary metadata so the
                            // AJAX handler can build the nested URL /units/{u}/crew/{c}/ajax.
                            subId = this.getAttribute('data-crew-id') || '';
                            deleteForm.setAttribute(
                                'data-sub-unit-id',
                                this.getAttribute('data-unit-id') || '',
                            );
                        } else {
                            subId =
                                this.getAttribute('data-unit-id') ||
                                this.getAttribute('data-participant-id') ||
                                this.getAttribute('data-crew-id') ||
                                '';
                            deleteForm.removeAttribute('data-sub-unit-id');
                        }
                        deleteForm.setAttribute('data-sub-section', subSection);
                        deleteForm.setAttribute('data-sub-id', subId);
                        const msgEl = document.getElementById('delete-confirm-message');
                        if (msgEl) msgEl.textContent = deleteMessageFor(this);
                        window.krtModalOpen(deleteModal);
                    });
                });
        }
        bindDeleteOpeners();
        document.addEventListener('krt:swapped', bindDeleteOpeners);
    }

    // Add Unit & Crew Logic — #add-unit-btn lives inside the crew board, so re-bind it on
    // krt:swapped (dataset-guarded) after the board re-renders (#571/#574).
    const addUnitModal = document.getElementById('add-unit-modal');
    function bindAddUnitButton() {
        const addUnitBtn = document.getElementById('add-unit-btn');
        if (addUnitBtn && addUnitModal && !addUnitBtn.dataset.auBound) {
            addUnitBtn.dataset.auBound = '1';
            addUnitBtn.addEventListener('click', function () {
                window.krtModalOpen(addUnitModal);
            });
        }
    }
    bindAddUnitButton();
    document.addEventListener('krt:swapped', bindAddUnitButton);

    const editUnitModal = document.getElementById('edit-unit-modal');
    const editUnitForm = document.getElementById('edit-unit-form');
    const editUnitName = document.getElementById('edit-unit-name');
    const editUnitFrequency = document.getElementById('edit-unit-frequency');
    const editUnitHvu = document.getElementById('edit-unit-hvu');

    if (editUnitModal) {
        // .edit-unit-btn live inside the crew board, which an in-place swap re-renders; re-bind
        // on krt:swapped (dataset-guarded) so swapped-in buttons stay wired (#571/#574).
        function bindEditUnitButtons() {
            document.querySelectorAll('.edit-unit-btn').forEach((btn) => {
                if (btn.dataset.euBound) return;
                btn.dataset.euBound = '1';
                btn.addEventListener('click', function () {
                    editUnitForm.action = window.safeSameOriginUrl(
                        this.getAttribute('data-action'),
                        editUnitForm.action,
                    );
                    editUnitForm.setAttribute(
                        'data-unit-id',
                        this.getAttribute('data-unit-id') || '',
                    );
                    editUnitName.value = this.getAttribute('data-name') || '';
                    const shiptypeVal = this.getAttribute('data-shiptype') || '';
                    const shipVal = this.getAttribute('data-ship') || '';
                    const editTypeSelect = document.getElementById('edit-unit-shiptype');
                    const editShipSelect = document.getElementById('edit-unit-ship');
                    if (editTypeSelect && editShipSelect) {
                        editTypeSelect.value = shiptypeVal;
                        editTypeSelect.dispatchEvent(new Event('change'));
                        if (shipVal) {
                            editShipSelect.value = shipVal;
                        }
                    }
                    const freqVal = this.getAttribute('data-frequency');
                    editUnitFrequency.value = freqVal != null ? freqVal : '';
                    const hvuVal = this.getAttribute('data-hvu') === 'true';
                    editUnitHvu.checked = hvuVal;
                    const editUnitResponsible = document.getElementById('edit-unit-responsible');
                    if (editUnitResponsible) {
                        const responsibleVal = this.getAttribute('data-responsible') || '';
                        // After the global enhancer upgrades the <select> into a searchable combobox,
                        // its id lives on the hidden input; use the combobox API so BOTH the submitted
                        // value and the visible textbox reflect the preselected responsible member.
                        if (editUnitResponsible.krtCombobox) {
                            editUnitResponsible.krtCombobox.setValue(responsibleVal);
                        } else {
                            editUnitResponsible.value = responsibleVal;
                        }
                    }
                    const editUnitNote = document.getElementById('edit-unit-note');
                    if (editUnitNote) {
                        const noteVal = this.getAttribute('data-note');
                        editUnitNote.value = noteVal && noteVal !== 'null' ? noteVal : '';
                    }
                    window.krtModalOpen(editUnitModal);
                });
            });
        }
        bindEditUnitButtons();
        document.addEventListener('krt:swapped', bindEditUnitButtons);
    }

    // --- Paket 3C: Units via AJAX (MissionSubresource) -----------------------
    // Writes to units, crew and other sub-panels go through the backend's /slim
    // endpoints via dedicated frontend /ajax proxy routes. On success the page
    // is reloaded so that the server-rendered Thymeleaf view reflects the new
    // state; because the backend no longer bumps the parent Mission.version
    // when a sub-aggregate is written (Option A), concurrent users editing OTHER
    // sub-panels keep their in-flight changes and are not hit by a spurious 409.
    (function wireUnitAjax() {
        if (!window.krtMissionWrite || !window.missionId) {
            return;
        }
        const addForm = document.getElementById('add-unit-form');
        if (addForm) {
            addForm.addEventListener('submit', async function (ev) {
                ev.preventDefault();
                const fd = new FormData(addForm);
                const payload = {
                    name: fd.get('name') || '',
                    shipTypeId: fd.get('shipTypeId') || null,
                    shipId: fd.get('shipId') || null,
                    highValueUnit: fd.get('highValueUnit') === 'true',
                    frequency: fd.get('frequency') ? parseFloat(fd.get('frequency')) : null,
                    responsibleUserId: fd.get('responsibleUserId') || null,
                    note: fd.get('note') || null,
                };
                const res = await window.krtMissionWrite({
                    method: 'POST',
                    url: '/missions/' + window.missionId + '/units/ajax',
                    payload: payload,
                    sectionKey: 'unit',
                });
                if (res.ok) {
                    window.krtModalClose(addForm.closest('.krt-modal-overlay'));
                    // 'overview' too: the Übersicht Funk panel mirrors the units' frequencies (#816),
                    // so a new unit with a frequency must surface there without a reload.
                    window.krtRefreshMissionSection(['crew', 'overview']);
                }
            });
        }

        const editForm = document.getElementById('edit-unit-form');
        if (editForm) {
            editForm.addEventListener('submit', async function (ev) {
                ev.preventDefault();
                const unitId = editForm.getAttribute('data-unit-id');
                if (!unitId) {
                    // No AJAX target -> fall back to classical submit so the user is not blocked.
                    editForm.submit();
                    return;
                }
                const fd = new FormData(editForm);
                const payload = {
                    name: fd.get('name') || '',
                    shipTypeId: fd.get('shipTypeId') || null,
                    shipId: fd.get('shipId') || null,
                    highValueUnit: fd.get('highValueUnit') === 'true',
                    frequency: fd.get('frequency') ? parseFloat(fd.get('frequency')) : null,
                    responsibleUserId: fd.get('responsibleUserId') || null,
                    note: fd.get('note') || null,
                };
                const res = await window.krtMissionWrite({
                    method: 'PUT',
                    url: '/missions/' + window.missionId + '/units/' + unitId + '/ajax',
                    payload: payload,
                    sectionKey: 'unit',
                });
                if (res.ok) {
                    window.krtModalClose(editForm.closest('.krt-modal-overlay'));
                    // 'overview' too: an edited unit frequency is mirrored in the Funk panel (#816).
                    window.krtRefreshMissionSection(['crew', 'overview']);
                }
            });
        }

        // Delete flow: intercept the shared delete-confirm form only when
        // a unit delete triggered it (data-sub-section="unit").
        const dForm = document.getElementById('delete-confirm-form');
        if (dForm) {
            dForm.addEventListener('submit', async function (ev) {
                const sub = dForm.getAttribute('data-sub-section');
                const subId = dForm.getAttribute('data-sub-id');
                if (sub !== 'unit' || !subId) {
                    return; // let the classical POST run for non-unit deletes
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url: '/missions/' + window.missionId + '/units/' + subId + '/ajax',
                    sectionKey: 'unit',
                });
                if (res.ok) {
                    window.krtModalClose(dForm.closest('.krt-modal-overlay'));
                    // 'overview' too: a deleted unit drops out of the Funk panel mirror (#816).
                    window.krtRefreshMissionSection(['crew', 'overview']);
                }
            });
        }
    })();
});

document.addEventListener('DOMContentLoaded', function () {
    // --- Paket 3C (Option b): Participants via AJAX (MissionSubresource) ----
    // Same pattern as wireUnitAjax: intercept add/edit/delete + check-in/check-out
    // submits and route them through the backend's /slim participant endpoints
    // via dedicated frontend /ajax proxy routes. On success the page is reloaded
    // so the server-rendered Thymeleaf view reflects the new state.
    (function wireParticipantAjax() {
        if (!window.krtMissionWrite || !window.missionId) {
            return;
        }

        // Add form: POST /missions/{id}/participants/ajax
        const addForm = document.getElementById('add-participant-form');
        if (addForm) {
            addForm.addEventListener('submit', async function (ev) {
                ev.preventDefault();
                const fd = new FormData(addForm);
                // Org units only matter for guest entries; when a registered user is selected
                // the backend derives the affiliations and ignores the submitted list. Send the
                // multi-select values regardless — the backend ignores them for registered users.
                const addOrgUnitIds =
                    addForm.querySelector('#participant-user-id') &&
                    addForm.querySelector('#participant-user-id').value
                        ? []
                        : fd.getAll('orgUnitIds').filter(Boolean);
                const payload = {
                    userId: fd.get('userId') || null,
                    guestName: fd.get('guestName') || null,
                    desiredJobTypeId: fd.get('desiredJobTypeId') || null,
                    orgUnitIds: addOrgUnitIds,
                    comment: fd.get('comment') || null,
                    payoutPreference: fd.get('payoutPreference') || null,
                };
                const res = await window.krtMissionWrite({
                    method: 'POST',
                    url: '/missions/' + window.missionId + '/participants/ajax',
                    payload: payload,
                    sectionKey: 'participant',
                });
                if (res.ok) {
                    window.krtModalClose(addForm.closest('.krt-modal-overlay'));
                    addForm.reset();
                    const addUserId = addForm.querySelector('#participant-user-id');
                    if (addUserId) {
                        addUserId.value = '';
                    }
                    window.krtRefreshMissionSection(['crew', 'finance']);
                }
            });
        }

        // Edit form: PUT /missions/{id}/participants/{pid}/ajax
        const editForm = document.getElementById('edit-participant-form');
        if (editForm) {
            editForm.addEventListener('submit', async function (ev) {
                const pId = editForm.getAttribute('data-participant-id');
                if (!pId) {
                    return; // fall back to classical submit
                }
                ev.preventDefault();
                const fd = new FormData(editForm);
                const versionRaw = fd.get('version');
                const payload = {
                    desiredMissionJobTypeId: fd.get('desiredJobTypeId') || null,
                    plannedMissionJobTypeId: fd.get('plannedMissionJobTypeId') || null,
                    orgUnitIds: fd.getAll('orgUnitIds').filter(Boolean),
                    payoutPreference: fd.get('payoutPreference') || null,
                    comment: fd.get('comment') || null,
                    startTime: fd.get('startTime') || null,
                    endTime: fd.get('endTime') || null,
                    version: versionRaw ? parseInt(versionRaw, 10) : null,
                };
                const res = await window.krtMissionWrite({
                    method: 'PUT',
                    url: '/missions/' + window.missionId + '/participants/' + pId + '/ajax',
                    payload: payload,
                    sectionKey: 'participant',
                });
                if (res.ok) {
                    window.krtModalClose(editForm.closest('.krt-modal-overlay'));
                    // 'overview' too: editing a participant's planned mission job type can change
                    // who the Einsatzleiter is, and the facts-bar "Leiter" is patched off the
                    // overview fragment's data-leader (REQ-MISSION-013).
                    window.krtRefreshMissionSection(['crew', 'finance', 'overview']);
                }
            });
        }

        // Check-in / Check-out forms: ONE document-delegated submit listener so the forms keep
        // working after the crew board is re-rendered by an in-place swap (the per-form binding
        // they used before died on the first innerHTML swap). POST .../check-in|check-out/ajax.
        document.addEventListener('submit', async function (ev) {
            const f = ev.target;
            if (
                !f ||
                typeof f.matches !== 'function' ||
                !f.matches('form.participant-action-form')
            ) {
                return;
            }
            const pId = f.getAttribute('data-participant-id');
            const action = f.getAttribute('data-participant-action');
            if (!pId || (action !== 'check-in' && action !== 'check-out')) {
                return; // fall back to classical submit
            }
            ev.preventDefault();
            const res = await window.krtMissionWrite({
                method: 'POST',
                url:
                    '/missions/' +
                    window.missionId +
                    '/participants/' +
                    pId +
                    '/' +
                    action +
                    '/ajax',
                sectionKey: 'participant',
            });
            if (res.ok) {
                window.krtRefreshMissionSection(['crew', 'finance']);
            }
        });

        // Delete flow: intercept the shared delete-confirm form when triggered by a
        // participant delete (data-sub-section="participant").
        const dForm = document.getElementById('delete-confirm-form');
        if (dForm) {
            dForm.addEventListener('submit', async function (ev) {
                const sub = dForm.getAttribute('data-sub-section');
                const subId = dForm.getAttribute('data-sub-id');
                if (sub !== 'participant' || !subId) {
                    return; // let the classical POST (or unit handler) run
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url: '/missions/' + window.missionId + '/participants/' + subId + '/ajax',
                    sectionKey: 'participant',
                });
                if (res.ok) {
                    window.krtModalClose(dForm.closest('.krt-modal-overlay'));
                    // 'overview' too: unregistering a participant may remove the Einsatzleiter, so
                    // the facts-bar "Leiter" (patched off the overview's data-leader) must refresh.
                    window.krtRefreshMissionSection(['crew', 'finance', 'overview']);
                }
            });
        }
    })();

    // Edit-crew modal: opened from the board chip-select ("Funktionen bearbeiten…").
    const editCrewModal = document.getElementById('edit-crew-modal');
    const editCrewForm = document.getElementById('edit-crew-form');
    const editCrewJobs = document.getElementById('edit-crew-jobs');

    if (editCrewModal && editCrewForm && editCrewJobs) {
        window.krtOpenEditCrewModal = function (source) {
            editCrewForm.action = window.safeSameOriginUrl(
                source.getAttribute('data-action') || '/missions',
                editCrewForm.action,
            );
            // Paket 3C (Option c - Crew): store AJAX target + unit/crew ids on
            // the shared edit-crew-form so wireCrewAjax can intercept the submit.
            editCrewForm.setAttribute(
                'data-ajax-action',
                source.getAttribute('data-action-ajax') || '',
            );
            editCrewForm.setAttribute('data-unit-id', source.getAttribute('data-unit-id') || '');
            editCrewForm.setAttribute('data-crew-id', source.getAttribute('data-crew-id') || '');
            const jobIds = (source.getAttribute('data-jobs') || '').split(',');

            for (let i = 0; i < editCrewJobs.options.length; i++) {
                editCrewJobs.options[i].selected = jobIds.includes(editCrewJobs.options[i].value);
            }

            window.krtModalOpen(editCrewModal);
        };
    }

    // Legacy backdrop-click close keeps working on the new krt-modal overlays.
    window.addEventListener('click', function (event) {
        if (event.target.classList && event.target.classList.contains('krt-modal-overlay')) {
            window.krtModalClose(event.target);
        }
    });

    // --- Paket 3C (Option c - Crew): Crew via AJAX (MissionSubresource) -----
    // Intercepts edit-crew-form (update) and the shared delete-confirm-form
    // (for data-sub-section="crew") and routes them through the backend's
    // /slim crew endpoints via dedicated frontend /ajax proxies. Crew ADD now
    // happens through the board (drop / click / keyboard) instead of a modal.
    (function wireCrewAjax() {
        if (!window.krtMissionWrite || !window.missionId) {
            return;
        }

        function collectJobTypeIds(form) {
            const select = form.querySelector('select[name="jobTypeIds"]');
            if (!select) return [];
            return Array.from(select.selectedOptions || [])
                .map((o) => o.value)
                .filter((v) => v);
        }

        // Update flow: PUT /missions/{id}/units/{u}/crew/{c}/ajax
        const editForm = document.getElementById('edit-crew-form');
        if (editForm) {
            editForm.addEventListener('submit', async function (ev) {
                const ajaxUrl = editForm.getAttribute('data-ajax-action');
                const unitId = editForm.getAttribute('data-unit-id');
                const crewId = editForm.getAttribute('data-crew-id');
                if (!ajaxUrl || !unitId || !crewId) {
                    return; // fall back to classical submit
                }
                ev.preventDefault();
                const payload = {
                    jobTypeIds: collectJobTypeIds(editForm),
                };
                const res = await window.krtMissionWrite({
                    method: 'PUT',
                    url: ajaxUrl,
                    payload: payload,
                    sectionKey: 'crew',
                });
                if (res.ok) {
                    window.krtModalClose(editForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection('crew');
                }
            });
        }

        // Delete flow: intercept the shared delete-confirm form when triggered by
        // a crew delete (data-sub-section="crew"). Requires both unitId and crewId.
        const dForm = document.getElementById('delete-confirm-form');
        if (dForm) {
            dForm.addEventListener('submit', async function (ev) {
                const sub = dForm.getAttribute('data-sub-section');
                const crewId = dForm.getAttribute('data-sub-id');
                const unitId = dForm.getAttribute('data-sub-unit-id');
                if (sub !== 'crew' || !crewId || !unitId) {
                    return; // let the classical POST (or unit/participant handlers) run
                }
                ev.preventDefault();
                const res = await window.krtMissionWrite({
                    method: 'DELETE',
                    url:
                        '/missions/' +
                        window.missionId +
                        '/units/' +
                        unitId +
                        '/crew/' +
                        crewId +
                        '/ajax',
                    sectionKey: 'crew',
                });
                if (res.ok) {
                    window.krtModalClose(dForm.closest('.krt-modal-overlay'));
                    window.krtRefreshMissionSection('crew');
                }
            });
        }
    })();

    // Autocomplete Logic (participant add modal)
    const searchInput = document.getElementById('participant-search-input');
    const userIdInput = document.getElementById('participant-user-id');
    const resultsDiv = document.getElementById('participant-search-results');

    if (searchInput && resultsDiv && userIdInput) {
        let debounceTimer;

        searchInput.addEventListener('input', function () {
            const val = this.value;

            // Reset ID on any input change
            userIdInput.value = '';

            // No registered user selected anymore -> reveal the guest org-unit picker again.
            const orgUnitsGroup = document.getElementById('participant-org-units-group');
            if (orgUnitsGroup) {
                orgUnitsGroup.style.display = '';
            }

            clearTimeout(debounceTimer);

            if (!val) {
                closeAllLists();
                return;
            }

            debounceTimer = setTimeout(() => {
                fetch('/users/search?query=' + encodeURIComponent(val))
                    .then((response) => {
                        if (!response.ok) throw new Error('Network response was not ok');
                        return response.json();
                    })
                    .then((users) => {
                        closeAllLists();
                        if (!users || users.length === 0) return;

                        users.forEach((user) => {
                            const div = document.createElement('div');
                            const regex = new RegExp(
                                '(' + val.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')',
                                'gi',
                            );
                            const displayName = user.effectiveName || '';
                            // Use DOM nodes instead of innerHTML so displayName is treated as text.
                            displayName.split(regex).forEach((part, i) => {
                                if (i % 2 === 1) {
                                    const strong = document.createElement('strong');
                                    strong.textContent = part;
                                    div.appendChild(strong);
                                } else if (part) {
                                    div.appendChild(document.createTextNode(part));
                                }
                            });

                            div.addEventListener('click', function () {
                                searchInput.value = displayName;
                                userIdInput.value = user.id;
                                // A registered user's org units are derived server-side, so hide
                                // the guest-only picker once a user is matched.
                                const orgUnitsGroup = document.getElementById(
                                    'participant-org-units-group',
                                );
                                if (orgUnitsGroup) {
                                    orgUnitsGroup.style.display = 'none';
                                }
                                closeAllLists();
                            });
                            resultsDiv.appendChild(div);
                        });
                    })
                    .catch((err) => console.error('Error fetching users:', err));
            }, 300);
        });

        function closeAllLists() {
            while (resultsDiv.firstChild) {
                resultsDiv.removeChild(resultsDiv.firstChild);
            }
        }

        document.addEventListener('click', function (e) {
            if (e.target !== searchInput) {
                closeAllLists();
            }
        });
    }

    // Party Lead autocomplete — same /users/search mechanic as the participant add: picking a
    // member fills the hidden userId, typing a free-text name clears it so the backend resolves
    // the name (unique member -> linked, unknown -> kept as a guest handle).
    const partyLeadInput = document.getElementById('party-lead-search-input');
    const partyLeadUserId = document.getElementById('party-lead-user-id');
    const partyLeadResults = document.getElementById('party-lead-search-results');
    const partyLeadClearBtn = document.getElementById('party-lead-clear-btn');
    const partyLeadForm = document.getElementById('party-lead-form');

    if (partyLeadInput && partyLeadResults && partyLeadUserId) {
        let partyLeadDebounce;

        function partyLeadCloseLists() {
            while (partyLeadResults.firstChild) {
                partyLeadResults.removeChild(partyLeadResults.firstChild);
            }
        }

        partyLeadInput.addEventListener('input', function () {
            const val = this.value;
            // Any manual edit drops a previously resolved id; the backend re-resolves the name.
            partyLeadUserId.value = '';
            clearTimeout(partyLeadDebounce);
            if (!val) {
                partyLeadCloseLists();
                return;
            }
            partyLeadDebounce = setTimeout(() => {
                fetch('/users/search?query=' + encodeURIComponent(val))
                    .then((response) => {
                        if (!response.ok) throw new Error('Network response was not ok');
                        return response.json();
                    })
                    .then((users) => {
                        partyLeadCloseLists();
                        if (!users || users.length === 0) return;
                        users.forEach((user) => {
                            const div = document.createElement('div');
                            const regex = new RegExp(
                                '(' + val.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')',
                                'gi',
                            );
                            const displayName = user.effectiveName || '';
                            displayName.split(regex).forEach((part, i) => {
                                if (i % 2 === 1) {
                                    const strong = document.createElement('strong');
                                    strong.textContent = part;
                                    div.appendChild(strong);
                                } else if (part) {
                                    div.appendChild(document.createTextNode(part));
                                }
                            });
                            div.addEventListener('click', function () {
                                partyLeadInput.value = displayName;
                                partyLeadUserId.value = user.id;
                                partyLeadCloseLists();
                            });
                            partyLeadResults.appendChild(div);
                        });
                    })
                    .catch((err) => console.error('Error fetching users:', err));
            }, 300);
        });

        document.addEventListener('click', function (e) {
            if (e.target !== partyLeadInput) {
                partyLeadCloseLists();
            }
        });
    }

    if (partyLeadClearBtn && partyLeadForm && partyLeadInput && partyLeadUserId) {
        partyLeadClearBtn.addEventListener('click', function () {
            // Clear the inputs and submit empty userId + guestName so the backend clears the
            // party lead. requestSubmit() fires the 'submit' event (unlike .submit()) so the
            // in-place interceptor below handles it.
            partyLeadInput.value = '';
            partyLeadUserId.value = '';
            partyLeadForm.requestSubmit();
        });
    }

    // In-place party-lead set/clear (#574): patch the display + bumped partyLeadVersion without a
    // reload. The autocomplete form is not re-rendered, so its per-element handlers stay valid.
    if (partyLeadForm && partyLeadInput && partyLeadUserId) {
        partyLeadForm.addEventListener('submit', async function (ev) {
            ev.preventDefault();
            const versionInput = document.getElementById('party-lead-version');
            const res = await window.krtMissionWrite({
                method: 'PUT',
                url: '/missions/' + window.missionId + '/party-lead/ajax',
                payload: {
                    userId: partyLeadUserId.value || null,
                    guestName: partyLeadInput.value || null,
                    version:
                        versionInput && versionInput.value !== ''
                            ? parseInt(versionInput.value, 10)
                            : 0,
                },
                sectionKey: 'party_lead',
            });
            if (!res.ok || !res.body) {
                return;
            }
            const dto = res.body;
            const display = document.getElementById('party-lead-display');
            const overview = document.getElementById('overview-party-lead');
            const noneLabel = (display && display.getAttribute('data-none-label')) || '';
            const resolvedName = dto.partyLeadUser
                ? dto.partyLeadUser.effectiveName || ''
                : dto.partyLeadGuestName || noneLabel;
            if (display) display.textContent = resolvedName;
            if (overview) overview.textContent = resolvedName;
            // The facts-bar "Leiter" now shows the Einsatzleiter (mission-lead participant), not
            // the party lead, so a party-lead change no longer patches it here (REQ-MISSION-013).
            if (versionInput && dto.partyLeadVersion != null) {
                versionInput.value = dto.partyLeadVersion;
            }
            partyLeadUserId.value = dto.partyLeadUser ? dto.partyLeadUser.id || '' : '';
            partyLeadInput.value = dto.partyLeadUser
                ? dto.partyLeadUser.effectiveName || ''
                : dto.partyLeadGuestName || '';
            // Already patched in place above; just signal peers to re-render their overview
            // (party lead is mirrored there) — live sync, REQ-FE-010.
            if (window.krtNotifyMissionChanged) {
                window.krtNotifyMissionChanged('overview');
            }
        });
    }
});

function setNowToInput(inputId) {
    const input = document.getElementById(inputId);
    if (!input) return;

    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    const hours = String(now.getHours()).padStart(2, '0');
    const minutes = String(now.getMinutes()).padStart(2, '0');

    const group = input.closest('.datetime-split-group');
    if (group) {
        const dateInput = group.querySelector('.date-part');
        const timeInput = group.querySelector('.time-part');
        if (dateInput && timeInput) {
            dateInput.value = `${year}-${month}-${day}`;
            timeInput.value = `${hours}:${minutes}`;
            dateInput.dispatchEvent(new Event('input', { bubbles: true }));
        }
    } else {
        input.value = `${year}-${month}-${day}T${hours}:${minutes}`;
        input.dispatchEvent(new Event('change', { bubbles: true }));
    }

    // actualStartTime/actualEndTime are persisted immediately and patched in place (#574).
    if (inputId === 'actualStartTime' || inputId === 'actualEndTime') {
        saveActualTimeInPlace(inputId, now);
    }
}

async function saveActualTimeInPlace(field, nowDate) {
    const currentMissionId =
        window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
    if (!currentMissionId) {
        console.error('[mission-detail] Mission ID nicht gefunden');
        return;
    }
    // actual-time only touches the schedule section, so it carries the dedicated
    // scheduleVersion counter — concurrent edits on the core or flags section therefore
    // never trigger a 409 here (and vice versa).
    const versionInput =
        document.getElementById('mission-schedule-version') ||
        document.getElementById('mission-version');
    const version = versionInput ? versionInput.value : null;
    if (version === null || version === '') {
        console.error(
            '[mission-detail] Schedule-Version nicht gefunden (mission-schedule-version input)',
        );
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(
                window.MSG_MISSION_ACTUAL_TIME_ERROR || 'Fehler beim Speichern.',
            );
        }
        return;
    }

    // Routed through krtMissionWrite (#574): CSRF construction + retry-once-on-403 and the shared
    // OPTIMISTIC_LOCK reload-confirm replace the former hand-rolled fetch + unconditional reload.
    await window.krtMissionWrite({
        method: 'POST',
        url: '/missions/' + encodeURIComponent(currentMissionId) + '/actual-time',
        payload: { field: field, value: nowDate.toISOString(), version: Number(version) },
        sectionKey: 'schedule',
        toast: false,
        onSuccess: function (dto) {
            // actual-time PATCHes /schedule, so the schedule version (and the top-level version)
            // bump — write them back so a follow-up schedule edit / actual-time set does not 409.
            if (dto && dto.scheduleVersion != null && versionInput) {
                versionInput.value = dto.scheduleVersion;
            }
            const topVersionInput = document.getElementById('mission-version');
            if (dto && dto.version != null && topVersionInput) {
                topVersionInput.value = dto.version;
            }
            // Patch the Tab-1 overview display for the field just set and re-localise it.
            const span = document.getElementById(
                field === 'actualStartTime' ? 'overview-actual-start' : 'overview-actual-end',
            );
            if (span) {
                span.setAttribute('data-utc', String(nowDate.getTime()));
                if (typeof window.krtLocalizeDates === 'function') {
                    window.krtLocalizeDates(span.parentNode || span);
                }
            }
            // Participation % in the payout table is derived from the actual times — refresh it.
            // 'overview' too: the Tab-1 actual start/end mirror this change for peers (REQ-FE-010).
            window.krtRefreshMissionSection(['finance', 'overview']);
        },
    });
}

async function changeMissionOwner() {
    const userId = document.getElementById('new-owner-id').value;
    if (!userId || userId.trim() === '') {
        showFrontendErrorToast(
            typeof MSG_ERROR_USER_REQUIRED !== 'undefined'
                ? MSG_ERROR_USER_REQUIRED
                : 'Bitte wählen Sie einen Benutzer aus',
        );
        return;
    }

    const ownerConfirmed = await window.showKrtConfirm(
        MSG_CONFIRM_OWNER_CHANGE,
        MSG_CONFIRM_OWNER_CHANGE,
        (window.MISSION_SUBRES_I18N &&
            window.MISSION_SUBRES_I18N['mission.conflict.action.reload']) ||
            'OK',
        (window.MISSION_SUBRES_I18N &&
            window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss']) ||
            'Abbrechen',
    );
    if (!ownerConfirmed) {
        return;
    }

    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            showFrontendErrorToast('Systemfehler: Mission ID nicht gefunden');
            return;
        }

        const cleanMissionId = String(currentMissionId).trim();
        const cleanUserId = String(userId).trim();

        const result = await window.krtMissionWrite({
            method: 'PUT',
            url: `/missions/${cleanMissionId}/owner/${cleanUserId}`,
            sectionKey: 'owner',
        });
        if (result.ok) {
            window.krtRefreshMissionSection('mgmt');
        }
    } catch (err) {
        showFrontendErrorToast(
            `${typeof MSG_ERROR_OWNER_CHANGE !== 'undefined' ? MSG_ERROR_OWNER_CHANGE : 'Fehler beim Ändern des Besitzers'} (Error: ${err.message})`,
        );
    }
}

// Repaints the sticky-header owning-org-unit badge in place for the ACTING user after a
// reassignment (REQ-ORG-018), straight from the returned DTO so it updates without waiting for a
// fragment round-trip. Peers repaint theirs via the overview krt:swapped listener (which reads
// #overview-head-meta). The badge sits outside every swap container. Built via DOM APIs (not
// innerHTML) so the name/shorthand are inserted as text. Ownerless empties the slot
// (display:contents → no leftover gap).
function updateMissionHeadOrgBadge(owningSquadron) {
    const slot = document.getElementById('mission-head-org-badge-slot');
    if (!slot) {
        return;
    }
    slot.textContent = '';
    if (owningSquadron && owningSquadron.shorthand) {
        const badge = document.createElement('span');
        badge.className = 'squadron-badge';
        badge.title = owningSquadron.name || '';
        badge.textContent = owningSquadron.shorthand || '';
        slot.appendChild(badge);
    }
}

async function changeMissionOwningOrgUnit() {
    const select = document.getElementById('new-owning-org-unit-id');
    const row = document.getElementById('owning-org-unit-row');
    if (!select || !row) {
        return;
    }
    const rawValue = select.value;
    // Empty value = the "Keine" option → ownerless target (null).
    const owningOrgUnitId = rawValue && rawValue.trim() !== '' ? rawValue.trim() : null;
    const versionAttr = row.getAttribute('data-owning-org-unit-version');
    const version = versionAttr != null && versionAttr !== '' ? parseInt(versionAttr, 10) : 0;

    const confirmed = await window.showKrtConfirm(
        MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE,
        MSG_CONFIRM_OWNING_ORG_UNIT_CHANGE,
        (window.MISSION_SUBRES_I18N &&
            window.MISSION_SUBRES_I18N['mission.conflict.action.reload']) ||
            'OK',
        (window.MISSION_SUBRES_I18N &&
            window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss']) ||
            'Abbrechen',
    );
    if (!confirmed) {
        return;
    }

    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            showFrontendErrorToast('Systemfehler: Mission ID nicht gefunden');
            return;
        }
        const cleanMissionId = String(currentMissionId).trim();

        const result = await window.krtMissionWrite({
            method: 'PUT',
            url: `/missions/${cleanMissionId}/owning-org-unit/ajax`,
            payload: { owningOrgUnitId: owningOrgUnitId, version: version },
            sectionKey: 'owningOrgUnit',
        });
        if (result.ok) {
            if (result.body) {
                updateMissionHeadOrgBadge(result.body.owningSquadron);
            }
            // Re-render the management panel in place (read-only value, dropdown selection, bumped
            // owningOrgUnitVersion) and broadcast it to peers (REQ-FE-010). The owning-squadron badge
            // lives in the sticky header outside every swap container: the actor patched it locally
            // above, so signal peers to re-render the overview fragment, whose krt:swapped listener
            // repaints their badge from #overview-head-meta.
            window.krtRefreshMissionSection('mgmt');
            if (window.krtNotifyMissionChanged) {
                window.krtNotifyMissionChanged('overview');
            }
        }
    } catch (err) {
        showFrontendErrorToast(
            `${typeof MSG_ERROR_OWNING_ORG_UNIT_CHANGE !== 'undefined' ? MSG_ERROR_OWNING_ORG_UNIT_CHANGE : 'Fehler beim Ändern der verantwortlichen Einheit'} (Error: ${err.message})`,
        );
    }
}

async function removeMissionManager(userId) {
    const removeConfirmed = await window.showKrtConfirm(
        typeof MSG_CONFIRM_MANAGER_REMOVE !== 'undefined'
            ? MSG_CONFIRM_MANAGER_REMOVE
            : 'Verwalter entfernen?',
        typeof MSG_CONFIRM_MANAGER_REMOVE !== 'undefined'
            ? MSG_CONFIRM_MANAGER_REMOVE
            : 'Verwalter entfernen?',
        (window.MISSION_SUBRES_I18N &&
            window.MISSION_SUBRES_I18N['mission.conflict.action.reload']) ||
            'OK',
        (window.MISSION_SUBRES_I18N &&
            window.MISSION_SUBRES_I18N['mission.conflict.action.dismiss']) ||
            'Abbrechen',
    );
    if (!removeConfirmed) return;
    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            return;
        }

        const result = await window.krtMissionWrite({
            method: 'DELETE',
            url: `/missions/${currentMissionId}/managers/${userId}`,
            sectionKey: 'manager',
        });
        if (result.ok) {
            window.krtRefreshMissionSection('mgmt');
        }
    } catch (err) {
        console.error(err);
        showFrontendErrorToast(MSG_ERROR_MANAGER_REMOVE);
    }
}

async function addMissionManager() {
    const userId = document.getElementById('new-manager-id').value;
    if (!userId || userId.trim() === '') {
        showFrontendErrorToast(
            typeof MSG_ERROR_USER_REQUIRED !== 'undefined'
                ? MSG_ERROR_USER_REQUIRED
                : 'Bitte wählen Sie einen Benutzer aus',
        );
        return;
    }
    try {
        const currentMissionId =
            window.missionId || (typeof missionId !== 'undefined' ? missionId : null);
        if (!currentMissionId) {
            console.error('Mission ID not found');
            showFrontendErrorToast('Systemfehler: Mission ID nicht gefunden');
            return;
        }

        const cleanMissionId = String(currentMissionId).trim();
        const cleanUserId = String(userId).trim();
        const result = await window.krtMissionWrite({
            method: 'POST',
            url: `/missions/${cleanMissionId}/managers/${cleanUserId}`,
            sectionKey: 'manager',
        });
        if (result.ok) {
            window.krtRefreshMissionSection('mgmt');
        }
    } catch (err) {
        console.error('Network or unexpected error:', err);
        showFrontendErrorToast(`${MSG_ERROR_MANAGER_ADD} (Error: ${err.message})`);
    }
}

async function updatePayoutPreference(selectElement) {
    const url = selectElement.getAttribute('data-payout-url');
    const value = selectElement.value;
    // Routed through krtFetch.write (#574): gains CSRF construction + retry-once-on-403 (a stale
    // tab no longer silently fails) and unified problem+json handling. Stays a pure in-place
    // patch — a single preference toggle changes no list structure, so no fragment swap.
    const result = await window.krtFetch.write({
        method: 'POST',
        url: url,
        payload: { preference: value },
        toast: false,
        errorMessage:
            typeof MSG_ERROR_PAYOUT_UPDATE !== 'undefined'
                ? MSG_ERROR_PAYOUT_UPDATE
                : 'Speichern fehlgeschlagen.',
    });
    if (!result.ok) {
        // krtFetch already surfaced the error toast; roll the select back to its last value.
        selectElement.value = selectElement.getAttribute('data-original-value') || 'PAYOUT';
        return;
    }
    const missionDto = result.body;
    if (!missionDto) return;
    selectElement.setAttribute('data-original-value', value);
    // The payout preference shows in the finance/payout table; signal peers to re-render it
    // (this handler patches its own view in place, REQ-FE-010).
    if (window.krtNotifyMissionChanged) {
        window.krtNotifyMissionChanged('finance');
    }
    const participantId = selectElement.getAttribute('data-participant-id');
    if (participantId && missionDto.participants) {
        const updatedParticipant = missionDto.participants.find((p) => p.id === participantId);
        if (updatedParticipant) {
            // Update the payout preference on the edit-button(s) so the next modal pre-fills
            // with the new value.
            document
                .querySelectorAll(
                    '.edit-participant-btn[data-participant-id="' + participantId + '"]',
                )
                .forEach((btn) => {
                    btn.setAttribute('data-payout-preference', value);
                });
            // Container-wide data-version sync: the participant's version renders on the payout
            // <tr> AND on its board person-row (plus their action buttons/modals). Every container
            // carrying the participant id is synced so a follow-up click anywhere in the page
            // never ships a stale version (spurious 409).
            if (updatedParticipant.version !== undefined) {
                document
                    .querySelectorAll('[data-participant-id="' + participantId + '"]')
                    .forEach((container) => {
                        window.krtFetch.syncVersion(container, updatedParticipant.version);
                    });
            }
        }
    }
}

// --- Finance entries via AJAX (#574): add/edit/delete swap the finance pane in place instead
//     of POST->redirect. The classic forms/links remain the no-JavaScript fallback. ---
document.addEventListener('DOMContentLoaded', function () {
    if (!window.krtMissionWrite || !window.missionId) {
        return;
    }
    const financeMissionId = window.missionId;

    // Re-render the finance fragment in place; the Finanzen tab badge is patched by the
    // document-level krt:swapped listener above (the single source of truth, shared with
    // peer-driven swaps), so this no longer patches the badge itself.
    function refreshFinanceAndBadge() {
        return window.krtRefreshMissionSection('finance');
    }

    const addForm = document.getElementById('add-finance-form');
    if (addForm) {
        addForm.addEventListener('submit', async function (ev) {
            ev.preventDefault();
            const fd = new FormData(addForm);
            const res = await window.krtMissionWrite({
                method: 'POST',
                url: '/missions/' + financeMissionId + '/finance-entries/ajax',
                payload: {
                    participantId: fd.get('participantId') || null,
                    type: fd.get('type') || 'INCOME',
                    amount: fd.get('amount') || null,
                    note: fd.get('note') || null,
                },
                sectionKey: 'finance',
            });
            if (res.ok) {
                window.krtModalClose(addForm.closest('.krt-modal-overlay'));
                addForm.reset();
                refreshFinanceAndBadge();
            }
        });
    }

    const editForm = document.getElementById('edit-finance-form');
    if (editForm) {
        editForm.addEventListener('submit', async function (ev) {
            const entryId = editForm.getAttribute('data-entry-id');
            if (!entryId) {
                return; // no AJAX target -> classic submit
            }
            ev.preventDefault();
            const fd = new FormData(editForm);
            const versionRaw = fd.get('version');
            const res = await window.krtMissionWrite({
                method: 'PUT',
                url: '/missions/' + financeMissionId + '/finance-entries/' + entryId + '/ajax',
                payload: {
                    type: fd.get('type') || 'INCOME',
                    amount: fd.get('amount') || null,
                    note: fd.get('note') || null,
                    version: versionRaw ? parseInt(versionRaw, 10) : null,
                },
                sectionKey: 'finance',
            });
            if (res.ok) {
                window.krtModalClose(editForm.closest('.krt-modal-overlay'));
                refreshFinanceAndBadge();
            }
        });
    }

    // Delete: extend the shared delete-confirm-form chain with a finance branch (the entry id is
    // parsed from the action the opener set, since the finance button carries no data-sub-id).
    const dForm = document.getElementById('delete-confirm-form');
    if (dForm) {
        dForm.addEventListener('submit', async function (ev) {
            if (dForm.getAttribute('data-sub-section') !== 'finance') {
                return; // let the unit/participant/crew handlers or the classic POST run
            }
            ev.preventDefault();
            const match = (dForm.getAttribute('action') || '').match(
                /finance-entries\/([^/]+)\/delete/,
            );
            const entryId = match ? match[1] : null;
            if (!entryId) {
                return;
            }
            const res = await window.krtMissionWrite({
                method: 'DELETE',
                url: '/missions/' + financeMissionId + '/finance-entries/' + entryId + '/ajax',
                sectionKey: 'finance',
            });
            if (res.ok) {
                window.krtModalClose(dForm.closest('.krt-modal-overlay'));
                refreshFinanceAndBadge();
            }
        });
    }
});

document.addEventListener('DOMContentLoaded', function () {
    function setupShipFilter(typeSelectId, shipSelectId) {
        const typeSelect = document.getElementById(typeSelectId);
        const shipSelect = document.getElementById(shipSelectId);
        if (!typeSelect || !shipSelect) return;

        function filterShips(resetShip = true) {
            const selectedType = typeSelect.value;
            if (!selectedType) {
                shipSelect.disabled = true;
                if (resetShip) shipSelect.value = '';
            } else {
                shipSelect.disabled = false;
            }

            Array.from(shipSelect.options).forEach((opt) => {
                if (!opt.value) return;
                if (opt.dataset.typeId === selectedType) {
                    opt.hidden = false;
                    opt.disabled = false;
                } else {
                    opt.hidden = true;
                    opt.disabled = true;
                    if (resetShip && shipSelect.value === opt.value) {
                        shipSelect.value = '';
                    }
                }
            });
        }

        typeSelect.addEventListener('change', () => filterShips(true));

        const modal = typeSelect.closest('.krt-modal');
        if (modal) {
            const initialType = modal.dataset.initShiptype;
            const initialShip = modal.dataset.initShip;
            if (initialType) {
                typeSelect.value = initialType;
                filterShips(false);
                if (initialShip) {
                    shipSelect.value = initialShip;
                }
            }
        }
    }

    setupShipFilter('add-unit-shiptype', 'add-unit-ship');
    setupShipFilter('edit-unit-shiptype', 'edit-unit-ship');
});

// CSP-safe delegated bindings (open/close finance modals fall through to the global
// *-modal-display common handlers, the rest call into page-local functions defined above).
//
// Defer until DOMContentLoaded if the document is still parsing: a future head-script
// load-order regression (cf. 56751e2) that leaves `window.krtEvents` undefined at
// parse time would otherwise silently drop every registration, with the Jetzt button
// (mission-set-now) and Owner/Manager/Payout/Finance handlers all stuck inert.
// Logging the missing global makes the next breakage visible in the console instead
// of silently failing.
function registerMissionDetailEventHandlers() {
    if (!window.krtEvents || typeof window.krtEvents.on !== 'function') {
        console.error(
            '[mission-detail] window.krtEvents missing — page click handlers not registered. ' +
                'event-delegation.js must load before mission-detail body scripts.',
        );
        return;
    }
    window.krtEvents.on('click', 'mission-set-now', function (el) {
        setNowToInput(el.getAttribute('data-input-id'));
    });
    window.krtEvents.on('click', 'mission-change-owner', changeMissionOwner);
    window.krtEvents.on('click', 'mission-change-owning-org-unit', changeMissionOwningOrgUnit);
    window.krtEvents.on('click', 'mission-remove-manager', function (el) {
        removeMissionManager(el.getAttribute('data-manager-id'));
    });
    window.krtEvents.on('click', 'mission-add-manager', addMissionManager);
    window.krtEvents.on('change', 'mission-update-payout', function (el) {
        updatePayoutPreference(el);
    });
    window.krtEvents.on('click', 'mission-open-edit-finance', function (el) {
        openEditFinanceModal(
            el.getAttribute('data-id'),
            el.getAttribute('data-note'),
            el.getAttribute('data-type'),
            el.getAttribute('data-amount'),
            el.getAttribute('data-version'),
        );
    });
}
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', registerMissionDetailEventHandlers);
} else {
    registerMissionDetailEventHandlers();
}

// ---- Tab navigation: ?tab= deeplink (priority) > #tab= > server-side error hint >
//      last tab from localStorage > "ueb". Browser back/forward re-applies the URL state.
(function () {
    const tabs = Array.from(document.querySelectorAll('.tab-nav .tab[data-tab]'));
    if (!tabs.length) {
        return; // create page renders without tabs
    }
    const validKeys = tabs.map((t) => t.getAttribute('data-tab'));
    const storeKey = 'krt.einsatz.' + (window.missionId || 'new') + '.tab';
    let dirty = false;

    // Unsaved-changes guard: any input in the Verwaltung pane marks the page dirty
    // until one of its forms is submitted (replaces the old panel-collapse states).
    const verwPane = document.getElementById('pane-verw');
    if (verwPane) {
        verwPane.addEventListener('input', function () {
            dirty = true;
        });
        verwPane.addEventListener('submit', function () {
            dirty = false;
        });
    }

    function currentKey() {
        const active = tabs.find((t) => t.classList.contains('active'));
        return active ? active.getAttribute('data-tab') : null;
    }

    function apply(key) {
        tabs.forEach((t) => {
            const on = t.getAttribute('data-tab') === key;
            t.classList.toggle('active', on);
            t.setAttribute('aria-selected', String(on));
            t.tabIndex = on ? 0 : -1;
        });
        document.querySelectorAll('.tab-pane').forEach((p) => {
            p.classList.toggle('on', p.id === 'pane-' + key);
        });
        try {
            localStorage.setItem(storeKey, key);
        } catch {
            /* private mode */
        }
    }

    async function show(key, push) {
        if (!validKeys.includes(key) || key === currentKey()) {
            return;
        }
        if (dirty && typeof window.showKrtConfirm === 'function') {
            const i18n = window.MISSION_TAB_I18N || {};
            const go = await window.showKrtConfirm(
                i18n['unsaved.title'] || 'Unsaved changes',
                i18n['unsaved.message'] || 'Unsaved changes will be lost. Switch anyway?',
                i18n['unsaved.continue'] || 'Switch tab',
                i18n['unsaved.cancel'] || 'Cancel',
            );
            if (!go) return;
            dirty = false;
        }
        apply(key);
        if (push) {
            const url = new URL(window.location);
            url.searchParams.set('tab', key);
            url.hash = '';
            history.pushState({ tab: key }, '', url);
        }
    }

    function resolveInitial(useStore) {
        const q = new URLSearchParams(window.location.search).get('tab');
        if (q && validKeys.includes(q)) return q;
        const h = (window.location.hash.match(/tab=([\w-]+)/) || [])[1];
        if (h && validKeys.includes(h)) return h;
        // A re-render with server-side validation errors must land on the form. Scope to
        // :not(:empty) because #589 made the core-edit .field-error divs always-present (empty
        // when there is no error) so the client can fill them — a bare `.field-error` would now
        // always match and wrongly pin every load to the Verwaltung tab.
        if (
            document.querySelector('#pane-verw #mission-form .field-error:not(:empty)') &&
            validKeys.includes('verw')
        )
            return 'verw';
        if (useStore) {
            try {
                const s = localStorage.getItem(storeKey);
                if (s && validKeys.includes(s)) return s;
            } catch {
                /* private mode */
            }
        }
        return 'ueb';
    }

    tabs.forEach((t) =>
        t.addEventListener('click', function () {
            show(t.getAttribute('data-tab'), true);
        }),
    );

    // Arrow-key navigation per WAI-ARIA tabs pattern.
    document.querySelector('.tab-nav').addEventListener('keydown', function (e) {
        if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
        const i = tabs.indexOf(document.activeElement);
        if (i < 0) return;
        e.preventDefault();
        const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
        next.focus();
        show(next.getAttribute('data-tab'), true);
    });

    window.addEventListener('popstate', function () {
        apply(resolveInitial(false));
    });

    // Overview summary cards jump into their tabs.
    document.querySelectorAll('[data-tab-jump]').forEach((card) => {
        card.addEventListener('click', function () {
            show(card.getAttribute('data-tab-jump'), true);
        });
    });

    apply(resolveInitial(true));

    // Sticky offset: the global <header> is itself sticky at top 0, so the mission
    // head sticks directly beneath its real rendered height.
    const sticky = document.getElementById('mission-head-sticky');
    const pageHeader = document.querySelector('body > header');
    function syncStickyOffset() {
        if (sticky && pageHeader) {
            sticky.style.top = pageHeader.offsetHeight + 'px';
        }
    }
    window.addEventListener('resize', syncStickyOffset);
    syncStickyOffset();
})();

// #589: in-place save of the mission core-edit form. The classic POST->redirect (updateMission)
// stays the no-JS fallback; this intercepts the EDIT form (data-mission-edit) and posts to the
// X-Requested-With twin (updateMissionAjax). On 200 it writes the four fresh versions back into
// the hidden inputs (so a second consecutive save does not 409) and toasts success WITHOUT a
// navigation; on 422 it renders the {field:message} map into the per-field .field-error slots; on
// 409 it offers the sanctioned conflict reload. The CREATE form (no data-mission-edit) keeps its
// classic navigate-to-the-new-mission submit. The pane-verw 'submit' listener (above) still fires
// through preventDefault, so the unsaved-changes tab guard is cleared exactly as before.
(function () {
    const form = document.getElementById('mission-form');
    if (!form || form.dataset.missionEdit !== 'true') return;
    const D = window.MISSION_SUBRES_I18N || {};
    function msg(key, fallback) {
        return D[key] != null && D[key] !== '' ? D[key] : fallback;
    }
    const SAVED = msg('mission.save.section.ok', 'Gespeichert.');
    const FAILED = msg('mission.save.section.error', 'Speichern fehlgeschlagen.');

    function buildHeaders() {
        const h = { 'X-Requested-With': 'XMLHttpRequest' };
        if (window.krtCsrf) {
            const t = window.krtCsrf.token();
            const n = window.krtCsrf.headerName();
            if (t && n) h[n] = t;
        }
        return h;
    }
    function writeVersions(v) {
        if (!v) return;
        function set(id, val) {
            const el = document.getElementById(id);
            if (el && val != null) el.value = val;
        }
        set('mission-version', v.version);
        set('mission-core-version', v.coreVersion);
        set('mission-schedule-version', v.scheduleVersion);
        set('mission-flags-version', v.flagsVersion);
    }
    function clearFieldErrors() {
        form.querySelectorAll('.field-error[data-error-for]').forEach(function (el) {
            el.textContent = '';
        });
    }
    function renderFieldErrors(map) {
        clearFieldErrors();
        Object.keys(map || {}).forEach(function (field) {
            const slot = form.querySelector('.field-error[data-error-for="' + field + '"]');
            if (slot) slot.textContent = map[field];
            // No matching slot (e.g. a future backend-added constraint) -> toast so the validation
            // message is never silently dropped.
            else if (window.showFrontendErrorToast) window.showFrontendErrorToast(map[field]);
        });
    }
    function handleConflict(problem) {
        const code = problem && problem.code;
        if (code === 'OPTIMISTIC_LOCK' || code === 'PESSIMISTIC_LOCK') {
            if (typeof window.showKrtConfirm === 'function') {
                window
                    .showKrtConfirm(
                        msg('mission.conflict.toast.title', 'Konflikt'),
                        msg('mission.conflict.action.reload.question', ''),
                        msg('mission.conflict.action.reload', ''),
                        msg('mission.conflict.action.dismiss', ''),
                    )
                    .then(function (ok) {
                        if (ok) window.location.reload();
                    });
            } else {
                window.location.reload();
            }
        } else if (window.showFrontendErrorToast) {
            window.showFrontendErrorToast(
                (problem && problem.detail) || msg('mission.conflict.toast.detail', FAILED),
            );
        }
    }
    let inFlight = false;
    async function submitInPlace() {
        if (!window.krtCsrf) {
            form.submit();
            return;
        }
        if (inFlight) return; // ignore a rapid second submit while one is still in flight
        inFlight = true;
        const submitBtn = document.querySelector('button[type="submit"][form="mission-form"]');
        if (submitBtn) submitBtn.disabled = true;
        try {
            const fd = new FormData(form);
            let res;
            try {
                res = await fetch(form.action, {
                    method: 'POST',
                    body: fd,
                    headers: buildHeaders(),
                });
                if (res.status === 403 && window.krtCsrf.refresh) {
                    const refreshed = await window.krtCsrf.refresh();
                    if (refreshed)
                        res = await fetch(form.action, {
                            method: 'POST',
                            body: fd,
                            headers: buildHeaders(),
                        });
                }
            } catch {
                if (window.showFrontendErrorToast) window.showFrontendErrorToast(FAILED);
                return;
            }
            if (res.status === 422) {
                let map = {};
                try {
                    map = await res.json();
                } catch {
                    /* non-JSON body */
                }
                renderFieldErrors(map);
                return;
            }
            if (res.status === 409) {
                let problem = {};
                try {
                    problem = await res.json();
                } catch {
                    /* non-JSON body */
                }
                handleConflict(problem);
                return;
            }
            if (!res.ok) {
                if (window.showFrontendErrorToast) window.showFrontendErrorToast(FAILED);
                return;
            }
            let versions = null;
            try {
                versions = await res.json();
            } catch {
                /* non-JSON body */
            }
            writeVersions(versions);
            clearFieldErrors();
            if (window.showFrontendSuccessToast) window.showFrontendSuccessToast(SAVED);
            // Re-render the overview pane (name / status / schedule / flags mirror the edited
            // core data) and signal peers to do the same (live multi-user sync, REQ-FE-010). The
            // form lives in the Verwaltung tab, so swapping the hidden overview pane is invisible
            // to this user but keeps it consistent and fixes the prior stale-overview behaviour.
            if (window.krtRefreshMissionSection) {
                window.krtRefreshMissionSection('overview');
            }
        } finally {
            inFlight = false;
            if (submitBtn) submitBtn.disabled = false;
        }
    }
    form.addEventListener('submit', function (e) {
        e.preventDefault();
        submitInPlace();
    });
})();

// ---- Crew board: drag & drop + click fallback + keyboard operation. ----
// Drop on a unit = the same backend action as the old "Crew zuweisen" modal
// (POST crew, empty function set — the function is picked via the chip-select);
// drop on the pool = remove the assignment; unit→unit = remove + add.
//
// All handlers are DELEGATED on the stable #crew-board-results wrapper (#571/#574): an in-place
// crew swap replaces the wrapper's innerHTML but the wrapper itself persists, so a single set of
// container-level listeners keeps the board fully operable after every swap with no re-init. The
// native drag events (dragstart/dragover/drop) bubble, so one listener per type covers all rows
// and zones. The dragged/selected closure state is cleared on krt:swapped (the swapped-out nodes
// are detached) so a follow-up action never references a stale row.
(function () {
    if (!window.missionCanEdit) {
        return; // the board is read-only without edit permission
    }
    const board = document.getElementById('crew-board-results');
    if (!board) {
        return;
    }

    let dragged = null;
    let selected = null;
    // True once the active drag was released on a real drop-zone; lets `dragend`
    // distinguish "released over no unit" (→ remove the assignment) from a genuine
    // zone drop (which already handled the move).
    let droppedOnZone = false;

    // ---- Edge auto-scroll while dragging a crew row. ----
    // Native HTML5 drag does not scroll the page, so a unit scrolled out of view is
    // unreachable as a drop target on a long board. We drive the scroll ourselves:
    // a document-level `dragover` (fires as the pointer moves) sets a direction when
    // the pointer enters the top/bottom edge band, and a rAF loop keeps scrolling —
    // even while the pointer is held still in the band — until the drag ends or the
    // pointer leaves the band. Speed eases with distance into the band.
    const EDGE_ZONE_PX = 72;
    const MAX_SCROLL_STEP_PX = 22;
    let autoScrollDir = 0; // -1 = up, +1 = down, 0 = idle
    let autoScrollStep = 0;
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

    // Refreshes the 'crew' section only — NOT ['crew','finance'] like the other participant
    // writes. A crew move mutates the Crew join entities, not the Participant entity, so the
    // participant's @Version is unchanged and the payout <tr> in #finance-results (which carries
    // data-version=${p.version}) stays in sync without a re-render. If a future backend change
    // ever bumps the Participant @Version on a crew move, switch this to ['crew','finance'].
    async function moveParticipant(row, zone) {
        if (!window.krtMissionWrite || !window.missionId) return;
        const participantId = row.getAttribute('data-participant-id');
        const srcCrewId = row.getAttribute('data-crew-id') || '';
        const srcUnitId = row.getAttribute('data-unit-id') || '';
        const targetUnitId = zone.getAttribute('data-unit-id') || '';
        if (!participantId || srcUnitId === targetUnitId) return;

        if (srcCrewId && srcUnitId) {
            const del = await window.krtMissionWrite({
                method: 'DELETE',
                url:
                    '/missions/' +
                    window.missionId +
                    '/units/' +
                    srcUnitId +
                    '/crew/' +
                    srcCrewId +
                    '/ajax',
                sectionKey: 'crew',
            });
            if (!del.ok) return;
            if (!targetUnitId) {
                window.krtRefreshMissionSection('crew');
                return;
            }
        }
        if (targetUnitId) {
            await window.krtMissionWrite({
                method: 'POST',
                url: '/missions/' + window.missionId + '/units/' + targetUnitId + '/crew/ajax',
                payload: { participantId: participantId, jobTypeIds: [] },
                sectionKey: 'crew',
            });
            // Re-render regardless of the add outcome: after a successful delete the server
            // state already changed and the board must reflect it.
            window.krtRefreshMissionSection('crew');
        }
    }

    // Drag start/end on a row (delegated — drag events bubble to the container).
    board.addEventListener('dragstart', function (e) {
        const row = e.target.closest('.person-row');
        if (!row || !board.contains(row)) return;
        dragged = row;
        droppedOnZone = false;
        if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
    });
    // A drag released outside every drop-zone — over no unit and not over the pool —
    // falls back to removing the unit assignment, so a participant deep in a long
    // board can be unassigned by dragging into empty space (mirrors a pool drop).
    // Pool rows (no unit) are a no-op. dragend always fires, so it also halts the
    // edge auto-scroll and clears the drag state.
    board.addEventListener('dragend', function () {
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

    // Click: a row toggles its selection; a zone (empty space) receives the selected person.
    board.addEventListener('click', function (e) {
        const row = e.target.closest('.person-row');
        if (row && board.contains(row)) {
            if (
                e.target.closest('button') ||
                e.target.closest('select') ||
                e.target.closest('form') ||
                e.target.closest('a')
            )
                return;
            setSelected(selected === row ? null : row);
            return;
        }
        const zone = e.target.closest('.drop-zone');
        if (zone && board.contains(zone)) {
            if (e.target.closest('button') || e.target.closest('select') || e.target.closest('a'))
                return;
            if (selected && !zone.contains(selected)) {
                const sel = selected;
                setSelected(null);
                moveParticipant(sel, zone);
            }
        }
    });

    // Keyboard: Enter/Space toggles a focused row or, on a focused zone, assigns the selection.
    board.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        if (!e.target || typeof e.target.closest !== 'function') return;
        const row = e.target.closest('.person-row');
        if (row && e.target === row) {
            e.preventDefault();
            setSelected(selected === row ? null : row);
            return;
        }
        const zone = e.target.closest('.drop-zone');
        if (zone && e.target === zone && selected && !zone.contains(selected)) {
            e.preventDefault();
            const sel = selected;
            setSelected(null);
            moveParticipant(sel, zone);
        }
    });

    // Drag over/leave/drop on a zone (delegated).
    board.addEventListener('dragover', function (e) {
        const zone = e.target.closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        e.preventDefault();
        zone.classList.add('is-over');
    });
    board.addEventListener('dragleave', function (e) {
        const zone = e.target.closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        zone.classList.remove('is-over');
    });
    board.addEventListener('drop', function (e) {
        const zone = e.target.closest('.drop-zone');
        if (!zone || !board.contains(zone)) return;
        e.preventDefault();
        zone.classList.remove('is-over');
        droppedOnZone = true;
        stopAutoScroll();
        if (dragged && !zone.contains(dragged)) {
            moveParticipant(dragged, zone);
        }
    });

    // Edge auto-scroll driver — document-level so the band stays live even when the
    // pointer leaves the board (e.g. over the sticky header) mid-drag. Gated on an
    // active crew drag; never calls preventDefault, so it does not affect drop
    // eligibility (the board-level dragover still governs that per zone).
    document.addEventListener('dragover', function (e) {
        if (!dragged) return;
        const y = e.clientY;
        const h = window.innerHeight;
        if (y <= EDGE_ZONE_PX) {
            autoScrollDir = -1;
            autoScrollStep = Math.ceil((MAX_SCROLL_STEP_PX * (EDGE_ZONE_PX - y)) / EDGE_ZONE_PX);
        } else if (y >= h - EDGE_ZONE_PX) {
            autoScrollDir = 1;
            autoScrollStep = Math.ceil(
                (MAX_SCROLL_STEP_PX * (y - (h - EDGE_ZONE_PX))) / EDGE_ZONE_PX,
            );
        } else {
            autoScrollDir = 0;
            autoScrollStep = 0;
        }
        if (autoScrollDir !== 0 && autoScrollRaf === null) {
            autoScrollRaf = window.requestAnimationFrame(autoScrollTick);
        }
    });

    // On-board function chip-select (delegated): quick single-function change via the crew
    // update endpoint; "__edit" opens the multi-select crew modal, "__multi" is a no-op label.
    board.addEventListener('change', async function (e) {
        const sel = e.target.closest('.crew-role-select');
        if (!sel || !board.contains(sel)) return;
        const value = sel.value;
        if (value === '__edit') {
            sel.value = sel.getAttribute('data-current') || '';
            if (typeof window.krtOpenEditCrewModal === 'function') {
                window.krtOpenEditCrewModal(sel);
            }
            return;
        }
        if (value === '__multi') {
            return;
        }
        const ajaxUrl = sel.getAttribute('data-action-ajax');
        if (!ajaxUrl || !window.krtMissionWrite) return;
        const res = await window.krtMissionWrite({
            method: 'PUT',
            url: ajaxUrl,
            payload: { jobTypeIds: value ? [value] : [] },
            sectionKey: 'crew',
        });
        if (res.ok) {
            window.krtRefreshMissionSection('crew');
        } else {
            sel.value = sel.getAttribute('data-current') || '';
        }
    });

    // After an in-place board swap the previously selected/dragged nodes are detached; clear the
    // closure state so a follow-up action never references a stale row.
    document.addEventListener('krt:swapped', function (ev) {
        if (ev.detail && ev.detail.container === board) {
            selected = null;
            dragged = null;
            droppedOnZone = false;
            stopAutoScroll();
        }
    });
})();

function krtFormatLocalDateTime(el) {
    const utcMsStr = el.getAttribute('data-utc');
    if (utcMsStr && utcMsStr !== 'null') {
        const date = new Date(parseInt(utcMsStr, 10));
        if (!isNaN(date)) {
            const hours = String(date.getHours()).padStart(2, '0');
            const minutes = String(date.getMinutes()).padStart(2, '0');
            // The facts bar shows time only (data-format="time") — the full date lives in the
            // Übersicht details; everywhere else keeps the full dd.MM.yyyy HH:mm.
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

// Localises every UTC timestamp under root (default: document). Exposed on window so the
// actual-time in-place patch can re-localise its display, and re-run on krt:swapped so swapped-in
// fragments (finance refinery times, crew board) are localised too — a one-shot DOMContentLoaded
// pass would otherwise leave swapped content showing raw UTC (#571/#574).
window.krtLocalizeDates = function (root) {
    const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
    scope.querySelectorAll('.krt-local-dt').forEach(krtFormatLocalDateTime);
    scope.querySelectorAll('.refinery-endsat-local').forEach(function (el) {
        const utcMsStr = el.getAttribute('data-utc');
        if (utcMsStr && utcMsStr !== 'null') {
            krtFormatLocalDateTime(el);
        } else {
            el.innerText = '-';
        }
    });
};

document.addEventListener('DOMContentLoaded', function () {
    window.krtLocalizeDates(document);
});
document.addEventListener('krt:swapped', function (ev) {
    window.krtLocalizeDates((ev.detail && ev.detail.container) || document);
});
