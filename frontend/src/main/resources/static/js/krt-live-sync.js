/*
 * Shared tool-wide live-sync client (REQ-FE-015, ADR-0094).
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

    // ---- Multiplexed /ws/sync transport (REQ-FE-015, ADR-0094) ---------------------------------
    // One lazily-opened WebSocket per tab carries every peer-synced topic. subscribe(topic, …)
    // registers an inbound-change handler for a room (authorized async server-side); sendChanged(
    // topic, sections) publishes a change (no subscription needed — the cross-topic case, e.g. a
    // requester notifying a staff queue it may not read). Only opaque section keys cross the socket;
    // the actual re-render is each page's own authenticated, authorization-checked fragment GET.
    const syncSocket = (function () {
        const RECONNECT_BASE_MS = 1000;
        const RECONNECT_MAX_MS = 30000;
        // Server close code for a per-user socket-cap refusal (F2/#1243, mirrors HTTP 429). This tab
        // is beyond the generous multi-tab cap; don't hammer — back off to the max interval and let
        // it recover quietly once another tab closes and frees a slot.
        const SOCKET_CAP_CLOSE_CODE = 4029;
        // topic -> { handlers, state: 'idle'|'pending'|'subscribed'|'denied', ackedOnce }
        const topics = Object.create(null);
        const publishBuffer = []; // { topic, sections } queued until the socket is open
        let ws = null;
        let reconnectDelay = RECONNECT_BASE_MS;
        let reconnectTimer = null;

        function socketUrl() {
            const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            return proto + '//' + window.location.host + '/ws/sync';
        }

        function isOpen() {
            return ws && ws.readyState === WebSocket.OPEN;
        }

        function rawSend(obj) {
            if (!isOpen()) {
                return false;
            }
            try {
                ws.send(JSON.stringify(obj));
                return true;
            } catch (_e) {
                return false;
            }
        }

        function ensureSocket() {
            if (
                ws &&
                (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)
            ) {
                return;
            }
            try {
                ws = new WebSocket(socketUrl());
            } catch (_e) {
                scheduleReconnect();
                return;
            }
            ws.addEventListener('open', onOpen);
            ws.addEventListener('message', onMessage);
            ws.addEventListener('close', onClose);
            // 'error' is always followed by 'close', where the reconnect is scheduled.
            ws.addEventListener('error', function () {});
        }

        function onOpen() {
            reconnectDelay = RECONNECT_BASE_MS;
            // (Re)subscribe every live topic; a denied topic stays dead (no retry).
            Object.keys(topics).forEach(function (t) {
                if (topics[t].state !== 'denied') {
                    topics[t].state = 'pending';
                    rawSend({ type: 'subscribe', topic: t });
                }
            });
            // Flush any changes published before the socket was open.
            while (publishBuffer.length) {
                const p = publishBuffer.shift();
                rawSend({ type: 'changed', topic: p.topic, sections: p.sections });
            }
        }

        function onMessage(ev) {
            let msg;
            try {
                msg = JSON.parse(ev.data);
            } catch (_e) {
                return;
            }
            const entry = msg && msg.topic ? topics[msg.topic] : null;
            if (!entry) {
                return;
            }
            if (msg.type === 'subscribed') {
                const wasAcked = entry.ackedOnce;
                entry.state = 'subscribed';
                entry.ackedOnce = true;
                // Fired on EVERY ack (first and re-subscribe). The presence adapter uses it to
                // re-announce its active focus once the room is subscribed — a presence frame sent
                // before the server acked the subscribe is dropped (the server only tracks presence
                // for a subscribed topic), so focus must be replayed here.
                if (typeof entry.handlers.onSubscribed === 'function') {
                    entry.handlers.onSubscribed();
                }
                // Only a RE-subscribe (after a reconnect) triggers a resync: signals may have been
                // missed while offline. The very first ack means nothing was missed.
                if (wasAcked && typeof entry.handlers.onResync === 'function') {
                    entry.handlers.onResync();
                }
            } else if (msg.type === 'denied') {
                entry.state = 'denied';
                if (typeof entry.handlers.onDenied === 'function') {
                    entry.handlers.onDenied();
                }
            } else if (msg.type === 'changed') {
                if (typeof entry.handlers.onChanged === 'function') {
                    entry.handlers.onChanged(Array.isArray(msg.sections) ? msg.sections : []);
                }
            } else if (msg.type === 'presence') {
                if (typeof entry.handlers.onPresence === 'function') {
                    entry.handlers.onPresence(msg.sections || {});
                }
            }
        }

        function onClose(ev) {
            ws = null;
            // A per-user socket-cap refusal (F2/#1243): skip the fast retry ramp and go straight to
            // the max backoff so this over-cap tab probes only occasionally for a freed slot.
            if (ev && ev.code === SOCKET_CAP_CLOSE_CODE) {
                reconnectDelay = RECONNECT_MAX_MS;
            }
            // Mark every non-denied topic pending so it re-subscribes on the next open.
            Object.keys(topics).forEach(function (t) {
                if (topics[t].state !== 'denied') {
                    topics[t].state = 'pending';
                }
            });
            if (Object.keys(topics).length || publishBuffer.length) {
                scheduleReconnect();
            }
        }

        function scheduleReconnect() {
            if (reconnectTimer) {
                return;
            }
            // Full-jitter backoff (mission-presence.js precedent): spreads a fleet's reconnects after
            // a frontend redeploy instead of a synchronized thundering herd.
            const wait = Math.random() * Math.min(RECONNECT_MAX_MS, reconnectDelay);
            reconnectTimer = window.setTimeout(function () {
                reconnectTimer = null;
                reconnectDelay = Math.min(RECONNECT_MAX_MS, reconnectDelay * 2);
                ensureSocket();
            }, wait);
        }

        return {
            subscribe: function (topic, handlers) {
                if (!topic) {
                    return { unsubscribe: function () {} };
                }
                const entry =
                    topics[topic] || (topics[topic] = { state: 'idle', ackedOnce: false });
                entry.handlers = handlers || {};
                ensureSocket();
                if (isOpen()) {
                    entry.state = 'pending';
                    rawSend({ type: 'subscribe', topic: topic });
                }
                return {
                    unsubscribe: function () {
                        delete topics[topic];
                    },
                };
            },
            sendChanged: function (topic, sections) {
                if (!topic) {
                    return;
                }
                const secs = Array.isArray(sections) ? sections : [sections];
                ensureSocket();
                if (!rawSend({ type: 'changed', topic: topic, sections: secs })) {
                    publishBuffer.push({ topic: topic, sections: secs });
                }
            },
            // Publishes an editor-presence control frame (`focus` / `heartbeat` / `blur`) for a
            // presence-enabled room (today only mission:{id}). Best-effort awareness: unlike
            // `changed` it is NEVER buffered — a stale focus replayed after a long outage would be
            // wrong — so if the socket is not open the frame is simply dropped and the focus is
            // re-announced on the next subscribe ack (see onSubscribed). The server drops a presence
            // frame for a topic this socket has not subscribed to, so a frame that races ahead of the
            // subscribe ack is harmlessly ignored and re-sent by that same onSubscribed re-announce.
            sendPresence: function (topic, type, sectionKey) {
                if (!topic || !type) {
                    return;
                }
                ensureSocket();
                rawSend({ type: type, topic: topic, sectionKey: sectionKey });
            },
            // Test-support only: the canonical topics this tab has an acknowledged (`subscribed`)
            // subscription for. The two-context live-sync e2e tests poll this to wait deterministically
            // until the passive viewer is registered with the relay before the acting viewer mutates —
            // the /ws/sync analogue of the mission adapter's `missionPresence.socket.readyState === 1`
            // (a subscribe, unlike a mission legacy socket, is only registered once its async server
            // authorization has acked). Not used by application code.
            subscribedTopics: function () {
                return Object.keys(topics).filter(function (t) {
                    return topics[t].state === 'subscribed';
                });
            },
        };
    })();

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
            // Shared fallback comes from the bundle-sourced global set in head.html (F8) — no
            // hardcoded user-visible string here. A page-specific pillCfg.label() still wins.
            const fallbackLabel =
                (window.krtLiveSyncI18n && window.krtLiveSyncI18n.updatesAvailable) || '';
            const label =
                typeof pillCfg.label === 'function'
                    ? pillCfg.label() || fallbackLabel
                    : fallbackLabel;
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

        // Topic-source mode: subscribe to the multiplexed /ws/sync socket and drive the receiver
        // directly from inbound `changed` (opaque section keys) and post-reconnect `resync`. Mission
        // stays on events-source mode (mission-presence.js owns its legacy socket); operation, orders
        // and bank use this.
        if (cfg && cfg.topic) {
            syncSocket.subscribe(cfg.topic, {
                onChanged: function (sections) {
                    apply(sections);
                },
                onResync: function () {
                    apply(null);
                },
            });
        }

        // Expose apply() so a caller can drive the receiver directly if needed.
        return { apply: apply };
    }

    window.krtLiveSync = {
        createReceiver: createReceiver,
        subscribe: syncSocket.subscribe,
        sendChanged: syncSocket.sendChanged,
        sendPresence: syncSocket.sendPresence,
        subscribedTopics: syncSocket.subscribedTopics,
    };
})();
