import { krtTermsGate } from '/js/kernel/krt-fetch.js';

const DEFAULT_COALESCE_MS = 400;

/**
 * @typedef {object} TopicEntry
 * @property {string} state `idle`, `pending`, `subscribed` or `denied`
 * @property {boolean} ackedOnce whether the relay acknowledged the subscription before
 * @property {number} deniedAttempts consecutive indeterminate denials
 * @property {number | null} resubscribeTimer the pending re-subscribe timer, if any
 * @property {KrtLiveSyncHandlers} [handlers] the callbacks registered by `subscribe`
 */

/**
 * @param {KrtSectionContainer | null | undefined} sectionValue a section map value
 * @returns {string | null} the container selector, or null
 */
function containerSelector(sectionValue) {
    if (typeof sectionValue === 'string') {
        return sectionValue;
    }
    return sectionValue && sectionValue.container ? sectionValue.container : null;
}

function anyModalOpen() {
    return Array.prototype.some.call(document.querySelectorAll('.krt-modal-overlay'), (o) => {
        return window.getComputedStyle(o).display !== 'none';
    });
}

const syncSocket = (function () {
    const RECONNECT_BASE_MS = 1000;
    const RECONNECT_MAX_MS = 30000;
    const SOCKET_CAP_CLOSE_CODE = 4029;
    const TERMS_GATE_CLOSE_CODE = 4003;
    const DENY_REASON_INDETERMINATE = 'indeterminate';
    const RESUBSCRIBE_BASE_MS = 1000;
    const RESUBSCRIBE_MAX_MS = 30000;
    const RESUBSCRIBE_MAX_ATTEMPTS = 5;
    const topics = Object.create(null);
    /** @type {{topic: string, sections: string[]}[]} */
    const publishBuffer = [];
    /** @type {WebSocket | null} */
    let ws = null;
    let reconnectDelay = RECONNECT_BASE_MS;
    /** @type {number | null} */
    let reconnectTimer = null;
    let stopped = false;

    function socketUrl() {
        const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        return `${proto}//${window.location.host}/ws/sync`;
    }

    function isOpen() {
        return ws && ws.readyState === WebSocket.OPEN;
    }

    /**
     * @param {object} obj the message to serialize and send
     * @returns {boolean} whether the socket accepted it
     */
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
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
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
        ws.addEventListener('error', () => {});
    }

    function onOpen() {
        reconnectDelay = RECONNECT_BASE_MS;
        Object.keys(topics).forEach((t) => {
            if (topics[t].state !== 'denied' || topics[t].deniedAttempts > 0) {
                topics[t].deniedAttempts = 0;
                topics[t].state = 'pending';
                rawSend({ type: 'subscribe', topic: t });
            }
        });
        while (publishBuffer.length) {
            const p = /** @type {{topic: string, sections: string[]}} */ (publishBuffer.shift());
            rawSend({ type: 'changed', topic: p.topic, sections: p.sections });
        }
    }

    /** @param {TopicEntry} entry the topic whose re-subscribe timer is cancelled */
    function clearResubscribe(entry) {
        if (entry.resubscribeTimer) {
            window.clearTimeout(entry.resubscribeTimer);
            entry.resubscribeTimer = null;
        }
    }

    /**
     * @param {string} topic the denied topic
     * @param {TopicEntry} entry the topic's state
     */
    function scheduleResubscribe(topic, entry) {
        clearResubscribe(entry);
        const ceiling = Math.min(
            RESUBSCRIBE_MAX_MS,
            RESUBSCRIBE_BASE_MS * Math.pow(2, entry.deniedAttempts),
        );
        entry.deniedAttempts += 1;
        const wait = RESUBSCRIBE_BASE_MS + Math.random() * ceiling;
        entry.resubscribeTimer = window.setTimeout(() => {
            entry.resubscribeTimer = null;
            if (topics[topic] !== entry || entry.state !== 'idle' || !isOpen()) {
                return;
            }
            entry.state = 'pending';
            rawSend({ type: 'subscribe', topic });
        }, wait);
    }

    /** @param {MessageEvent} ev the relay message */
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
            entry.deniedAttempts = 0;
            clearResubscribe(entry);
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
                msg.reason === DENY_REASON_INDETERMINATE &&
                entry.deniedAttempts < RESUBSCRIBE_MAX_ATTEMPTS;
            entry.state = retryable ? 'idle' : 'denied';
            if (retryable) {
                scheduleResubscribe(msg.topic, entry);
            }
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

    /** @param {CloseEvent} ev the close event */
    function onClose(ev) {
        ws = null;
        if (ev && ev.code === TERMS_GATE_CLOSE_CODE) {
            stopped = true;
            if (reconnectTimer) {
                window.clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
            krtTermsGate.redirect(ev.reason);
            return;
        }
        if (ev && ev.code === SOCKET_CAP_CLOSE_CODE) {
            reconnectDelay = RECONNECT_MAX_MS;
        }
        Object.keys(topics).forEach((t) => {
            clearResubscribe(topics[t]);
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
        reconnectTimer = window.setTimeout(() => {
            reconnectTimer = null;
            reconnectDelay = Math.min(RECONNECT_MAX_MS, reconnectDelay * 2);
            ensureSocket();
        }, wait);
    }

    return {
        /**
         * @param {string} topic the topic room
         * @param {KrtLiveSyncHandlers} handlers the callbacks to register
         * @returns {{unsubscribe(): void}} the subscription handle
         */
        subscribe(topic, handlers) {
            if (!topic) {
                return { unsubscribe() {} };
            }
            const entry = (topics[topic] ||= {
                state: 'idle',
                ackedOnce: false,
                deniedAttempts: 0,
                resubscribeTimer: null,
            });
            entry.handlers = handlers || {};
            ensureSocket();
            if (isOpen()) {
                entry.state = 'pending';
                rawSend({ type: 'subscribe', topic });
            }
            return {
                unsubscribe() {
                    clearResubscribe(entry);
                    delete topics[topic];
                },
            };
        },
        /**
         * @param {string} topic the topic room
         * @param {string | string[]} sections the changed section keys
         */
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
        /**
         * @param {string} topic the topic room
         * @param {string} type the presence message type
         * @param {string} sectionKey the section the presence refers to
         */
        sendPresence(topic, type, sectionKey) {
            if (!topic || !type) {
                return;
            }
            ensureSocket();
            rawSend({ type, topic, sectionKey });
        },
        subscribedTopics() {
            return Object.keys(topics).filter((t) => {
                return topics[t].state === 'subscribed';
            });
        },
    };
})();

/**
 * Builds a receiver that applies inbound peer changes: coalesces them, defers busy sections behind
 * a pill and calls `cfg.refresh` with the sections ready to re-render.
 *
 * @param {KrtReceiverConfig} cfg the receiver configuration
 * @returns {KrtReceiver} the receiver
 */
function createReceiver(cfg) {
    /** @type {Record<string, KrtSectionContainer>} */
    const sections = (cfg && cfg.sections) || {};
    const refreshFn = cfg && cfg.refresh;
    const coalesceMs = cfg && cfg.coalesceMs ? cfg.coalesceMs : DEFAULT_COALESCE_MS;
    /** @type {NonNullable<KrtReceiverConfig['pill']>} */
    const pillCfg = (cfg && cfg.pill) || {};
    const pillId = pillCfg.id || 'krt-livesync-pill';
    const pillClassName = 'krt-livesync-pill';
    const extraBusyTest = cfg && cfg.busyTest;

    /** @type {Record<string, string | null>} */
    const sectionContainers = {};
    Object.keys(sections).forEach((sectionKey) => {
        sectionContainers[sectionKey] = containerSelector(sections[sectionKey]);
    });
    const allSections = Object.keys(sectionContainers);

    /** @type {Record<string, boolean>} */
    const pendingNow = {};
    /** @type {Record<string, boolean>} */
    const deferred = {};
    /** @type {number | null} */
    let timer = null;

    /**
     * @param {string} sectionKey the section to test
     * @returns {boolean} whether the section must not be re-rendered now
     */
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

    /** @param {string[]} keys the sections to hand to the configured refresh callback */
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
            typeof pillCfg.label === 'function' ? pillCfg.label() || fallbackLabel : fallbackLabel;
        const pill = document.createElement('button');
        pill.id = pillId;
        pill.type = 'button';
        pill.className = pillClassName;
        pill.textContent = label;
        pill.addEventListener('click', () => {
            /** @type {string[]} */
            const ready = [];
            Object.keys(deferred).forEach((k) => {
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
        allSections.forEach((sectionKey) => {
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
        keys.forEach((k) => {
            delete pendingNow[k];
        });
        /** @type {string[]} */
        const ready = [];
        let nowDeferred = false;
        keys.forEach((k) => {
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

    /** @param {string} sectionKey the section to re-render after the coalescing delay */
    function schedule(sectionKey) {
        pendingNow[sectionKey] = true;
        if (!timer) {
            timer = setTimeout(flushTimer, coalesceMs + Math.random() * coalesceMs);
        }
    }

    /** @param {string[] | null | undefined} incomingSections the changed sections, all if empty */
    function apply(incomingSections) {
        const keys =
            Array.isArray(incomingSections) && incomingSections.length
                ? incomingSections
                : allSections;
        let anyDeferred = false;
        keys.forEach((sectionKey) => {
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

    /** @type {NonNullable<KrtReceiverConfig['events']>} */
    const events = (cfg && cfg.events) || {};
    if (events.changed) {
        document.addEventListener(events.changed, (ev) => {
            const detail = /** @type {CustomEvent<{sections?: string[]} | null>} */ (ev).detail;
            apply(detail ? detail.sections : null);
        });
    }
    if (events.resync) {
        document.addEventListener(events.resync, () => {
            apply(null);
        });
    }

    /** @type {{unsubscribe(): void} | null} */
    let subscription = null;

    /**
     * Subscribes to a topic room and drops the subscription of the previous one.
     *
     * @param {string | null | undefined} topic the topic room, or nothing to stay unsubscribed
     */
    function retarget(topic) {
        if (subscription) {
            subscription.unsubscribe();
            subscription = null;
        }
        if (!topic) {
            return;
        }
        subscription = syncSocket.subscribe(topic, {
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

    if (cfg && cfg.topic) {
        retarget(cfg.topic);
    }

    return { apply, retarget };
}

/** @type {KrtLiveSyncApi} */
export const krtLiveSync = {
    createReceiver,
    subscribe: syncSocket.subscribe,
    sendChanged: syncSocket.sendChanged,
    sendPresence: syncSocket.sendPresence,
    subscribedTopics: syncSocket.subscribedTopics,
};

window.krtLiveSync = krtLiveSync;
