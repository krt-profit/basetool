/*
 * Shared tool-wide live-sync client (REQ-FE-015, ADR-0092).
 *
 * Exposes window.krtLiveSync with the reusable receiver factory extracted from the mission-detail
 * live-sync receiver, so every peer-synced surface (mission, operation, orders, bank, materialboard)
 * shares one coalescing / busy-guard / deferred-pill / resync implementation instead of a bespoke
 * copy per page. Only opaque section keys ever drive a receiver; the actual re-render is the page's
 * own authenticated, authorization-checked fragment GET (data never rides the socket).
 *
 * createReceiver(cfg) wires a page's inbound-change handling:
 *   cfg.sections   sectionKey -> { container } (or sectionKey -> container string). Single source of
 *                  truth shared with the page's write seam, so a key added on one side is covered on
 *                  the other (REQ-FE-010 three-mirror-points rule).
 *   cfg.refresh    fn(keys): re-render the given sections in place. MUST suppress re-broadcast
 *                  (the caller passes {broadcast:false}) so an applied peer change cannot echo back.
 *   cfg.events     { changed, resync }: DOM event names to listen on. `changed` carries
 *                  detail.sections; `resync` refreshes everything visible (post-reconnect). Used by
 *                  the mission adapter, which owns its own socket and re-dispatches these events.
 *   cfg.pill       { id, className, label() }: the "updates available" deferred-refresh pill.
 *   cfg.busyTest   optional fn(sectionKey, container) -> boolean, OR-ed with the default busy test
 *                  (any open modal, or focus inside the section container) to hold back a refresh
 *                  the local user would experience as a yank (e.g. a drag in progress).
 *   cfg.coalesceMs optional debounce window (default 400 ms); full-jittered like the reconnect
 *                  backoff so a room's peers do not refetch in one synchronized burst (#1125).
 */
(function () {
    'use strict';

    const DEFAULT_COALESCE_MS = 400;

    // Reads the container selector for a section key from a seam map that may store either a plain
    // selector string or a { container } object (the mission MISSION_SECTIONS shape).
    function containerSelector(sectionValue) {
        if (typeof sectionValue === 'string') {
            return sectionValue;
        }
        return sectionValue && sectionValue.container ? sectionValue.container : null;
    }

    function anyModalOpen() {
        return Array.prototype.some.call(
            document.querySelectorAll('.krt-modal-overlay'),
            function (o) {
                return window.getComputedStyle(o).display !== 'none';
            },
        );
    }

    // Builds the coalescing / busy-guard / deferred-pill receiver for one page (or one topic).
    function createReceiver(cfg) {
        const sections = (cfg && cfg.sections) || {};
        const refreshFn = cfg && cfg.refresh;
        const coalesceMs = cfg && cfg.coalesceMs ? cfg.coalesceMs : DEFAULT_COALESCE_MS;
        const pillCfg = (cfg && cfg.pill) || {};
        const pillId = pillCfg.id || 'krt-livesync-pill';
        const pillClassName = pillCfg.className || 'krt-livesync-pill';
        const extraBusyTest = cfg && cfg.busyTest;

        const sectionContainers = {};
        Object.keys(sections).forEach(function (sectionKey) {
            sectionContainers[sectionKey] = containerSelector(sections[sectionKey]);
        });
        const allSections = Object.keys(sectionContainers);

        const pendingNow = {}; // sections queued for the next debounce tick (Set-like map)
        const deferred = {}; // sections held back because the user is editing (Set-like map)
        let timer = null;

        // Never yank a section out from under an active edit. A modal open anywhere means the user
        // is mid-edit; focus inside the section's own container means inline editing there; a page
        // may add its own predicate (e.g. a drag in progress) via cfg.busyTest.
        function sectionBusy(sectionKey) {
            if (anyModalOpen()) {
                return true;
            }
            const sel = sectionContainers[sectionKey];
            const container = sel && document.querySelector(sel);
            if (container && document.activeElement && container.contains(document.activeElement)) {
                return true;
            }
            if (typeof extraBusyTest === 'function') {
                return !!extraBusyTest(sectionKey, container);
            }
            return false;
        }

        function refresh(keys) {
            if (keys.length && typeof refreshFn === 'function') {
                refreshFn(keys);
            }
        }

        // Remove the pill once nothing is held back, so it never lingers for an already-current
        // section.
        function hidePillIfEmpty() {
            if (Object.keys(deferred).length === 0) {
                const pill = document.getElementById(pillId);
                if (pill) {
                    pill.remove();
                }
            }
        }

        function showPill() {
            let pill = document.getElementById(pillId);
            if (pill) {
                return;
            }
            const label =
                typeof pillCfg.label === 'function'
                    ? pillCfg.label() || 'Aktualisierungen verfügbar'
                    : 'Aktualisierungen verfügbar';
            pill = document.createElement('button');
            pill.id = pillId;
            pill.type = 'button';
            pill.className = pillClassName;
            pill.textContent = label;
            pill.addEventListener('click', function () {
                // Apply only sections that are no longer busy — a still-open modal / focused edit on
                // a section keeps it deferred (and the pill) rather than being clobbered by an
                // explicit click.
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

        // The busy test is re-applied here, at flush time — not only when the signal first arrived.
        // A section that became busy DURING the coalesce window is moved to `deferred` + the pill
        // instead of being swapped out from under the edit. Sections that are safe to refresh are
        // dropped from `deferred` so the pill cannot keep claiming updates for a section that just
        // refreshed in place.
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
                // #1125: full-jitter the coalesce window so peers that all received the same
                // `changed` frame within microseconds do not fire their fragment refetches in one
                // synchronized burst (worst case ~2x coalesceMs instead of coalesceMs).
                timer = setTimeout(flushTimer, coalesceMs + Math.random() * coalesceMs);
            }
        }

        function apply(incomingSections) {
            const keys =
                Array.isArray(incomingSections) && incomingSections.length
                    ? incomingSections
                    : allSections;
            let anyDeferred = false;
            keys.forEach(function (sectionKey) {
                const sel = sectionContainers[sectionKey];
                if (!sel || !document.querySelector(sel)) {
                    // Unknown key, or this viewer cannot see the section (guest redaction, requester
                    // view, absent panel) — silently skip; that asymmetry is the auth model.
                    return;
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

        const events = (cfg && cfg.events) || {};
        if (events.changed) {
            document.addEventListener(events.changed, function (ev) {
                apply(ev && ev.detail ? ev.detail.sections : null);
            });
        }
        if (events.resync) {
            document.addEventListener(events.resync, function () {
                apply(null); // refresh everything visible after a reconnect
            });
        }

        // Expose apply() so a transport that owns its own subscription (added in a later step) can
        // drive the receiver directly instead of via DOM events.
        return { apply: apply };
    }

    window.krtLiveSync = { createReceiver: createReceiver };
})();
