// @ts-check
(function () {
    'use strict';

    const DEFAULT_COALESCE_MS = 400;

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

    const syncSocket = (function () {
        const RECONNECT_BASE_MS = 1000;
        const RECONNECT_MAX_MS = 30000;
        const SOCKET_CAP_CLOSE_CODE = 4029;
        const TERMS_GATE_CLOSE_CODE = 4003;
        const DENY_REASON_INDETERMINATE = 'indeterminate';
        const topics = Object.create(null);
        const publishBuffer = [];
        /** @type {WebSocket | null} */
        let ws = null;
        let reconnectDelay = RECONNECT_BASE_MS;
        /** @type {number | null} */
        let reconnectTimer = null;
        let stopped = false;

        function socketUrl() {
            const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            return proto + '//' + window.location.host + '/ws/sync';
        }

        function isOpen() {
            return ws && ws.readyState === WebSocket.OPEN;
        }

        function rawSend(obj) {
            if (!isOpen() || !ws) {
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
            if (stopped) {
                return;
            }
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
            ws.addEventListener('error', function () {});
        }

        function onOpen() {
            reconnectDelay = RECONNECT_BASE_MS;
            Object.keys(topics).forEach(function (t) {
                if (topics[t].state !== 'denied') {
                    topics[t].state = 'pending';
                    rawSend({ type: 'subscribe', topic: t });
                }
            });
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
                if (typeof entry.handlers.onSubscribed === 'function') {
                    entry.handlers.onSubscribed();
                }
                if (wasAcked && typeof entry.handlers.onResync === 'function') {
                    entry.handlers.onResync();
                }
            } else if (msg.type === 'denied') {
                const retryable =
                    msg.reason === DENY_REASON_INDETERMINATE && !entry.deniedRetryUsed;
                if (retryable) {
                    entry.deniedRetryUsed = true;
                }
                entry.state = retryable ? 'idle' : 'denied';
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
            if (ev && ev.code === TERMS_GATE_CLOSE_CODE) {
                stopped = true;
                if (reconnectTimer) {
                    window.clearTimeout(reconnectTimer);
                    reconnectTimer = null;
                }
                if (window.krtTermsGate) {
                    window.krtTermsGate.redirect(ev.reason);
                }
                return;
            }
            if (ev && ev.code === SOCKET_CAP_CLOSE_CODE) {
                reconnectDelay = RECONNECT_MAX_MS;
            }
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
            const wait = Math.random() * Math.min(RECONNECT_MAX_MS, reconnectDelay);
            reconnectTimer = window.setTimeout(function () {
                reconnectTimer = null;
                reconnectDelay = Math.min(RECONNECT_MAX_MS, reconnectDelay * 2);
                ensureSocket();
            }, wait);
        }

        return {
            subscribe(topic, handlers) {
                if (!topic) {
                    return { unsubscribe() {} };
                }
                const entry =
                    topics[topic] ||
                    (topics[topic] = { state: 'idle', ackedOnce: false, deniedRetryUsed: false });
                entry.handlers = handlers || {};
                ensureSocket();
                if (isOpen()) {
                    entry.state = 'pending';
                    rawSend({ type: 'subscribe', topic });
                }
                return {
                    unsubscribe() {
                        delete topics[topic];
                    },
                };
            },
            sendChanged(topic, sections) {
                if (!topic || stopped) {
                    return;
                }
                const secs = Array.isArray(sections) ? sections : [sections];
                ensureSocket();
                if (!rawSend({ type: 'changed', topic, sections: secs })) {
                    publishBuffer.push({ topic, sections: secs });
                }
            },
            sendPresence(topic, type, sectionKey) {
                if (!topic || !type) {
                    return;
                }
                ensureSocket();
                rawSend({ type, topic, sectionKey });
            },
            subscribedTopics() {
                return Object.keys(topics).filter(function (t) {
                    return topics[t].state === 'subscribed';
                });
            },
        };
    })();

    function createReceiver(cfg) {
        const sections = (cfg && cfg.sections) || {};
        const refreshFn = cfg && cfg.refresh;
        const coalesceMs = cfg && cfg.coalesceMs ? cfg.coalesceMs : DEFAULT_COALESCE_MS;
        const pillCfg = (cfg && cfg.pill) || {};
        const pillId = pillCfg.id || 'krt-livesync-pill';
        const pillClassName = 'krt-livesync-pill';
        const extraBusyTest = cfg && cfg.busyTest;

        const sectionContainers = {};
        Object.keys(sections).forEach(function (sectionKey) {
            sectionContainers[sectionKey] = containerSelector(sections[sectionKey]);
        });
        const allSections = Object.keys(sectionContainers);

        const pendingNow = {};
        const deferred = {};
        /** @type {number | null} */
        let timer = null;

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

        function hidePillIfEmpty() {
            if (Object.keys(deferred).length === 0) {
                const pill = document.getElementById(pillId);
                if (pill) {
                    pill.remove();
                }
            }
        }

        function showPill() {
            if (document.getElementById(pillId)) {
                return;
            }
            const fallbackLabel =
                (window.krtLiveSyncI18n && window.krtLiveSyncI18n.updatesAvailable) || '';
            const label =
                typeof pillCfg.label === 'function'
                    ? pillCfg.label() || fallbackLabel
                    : fallbackLabel;
            const pill = document.createElement('button');
            pill.id = pillId;
            pill.type = 'button';
            pill.className = pillClassName;
            pill.textContent = label;
            pill.addEventListener('click', function () {
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

        function deferAllVisibleSections() {
            let anyVisible = false;
            allSections.forEach(function (sectionKey) {
                const sel = sectionContainers[sectionKey];
                if (!sel || !document.querySelector(sel)) {
                    return;
                }
                deferred[sectionKey] = true;
                anyVisible = true;
            });
            if (anyVisible) {
                showPill();
            }
        }

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
                apply(null);
            });
        }

        if (cfg && cfg.topic) {
            syncSocket.subscribe(cfg.topic, {
                onChanged(sections) {
                    apply(sections);
                },
                onResync() {
                    apply(null);
                },
                onDenied() {
                    deferAllVisibleSections();
                },
            });
        }

        return { apply };
    }

    window.krtLiveSync = {
        createReceiver,
        subscribe: syncSocket.subscribe,
        sendChanged: syncSocket.sendChanged,
        sendPresence: syncSocket.sendPresence,
        subscribedTopics: syncSocket.subscribedTopics,
    };
})();
