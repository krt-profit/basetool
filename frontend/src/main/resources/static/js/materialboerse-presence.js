/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Materialbörse board live-sync client (REQ-MARKET-010, ADR-0081 D4). Connects to the
 * board relay (/ws/materialboerse/board), lets a page announce that it changed the board
 * (window.krtMaterialboardPresence.sendChanged), and turns a peer's "changed" frame into a
 * krt:materialboerse-changed DOM event the board page listens for to re-pull its fragment.
 * Only the opaque "board" section key crosses the socket. Loaded on both the board page and
 * the Mein-Lager page (via the shared release-modal fragment) so a Lager release also notifies
 * board viewers.
 */
(function () {
    'use strict';

    if (!('WebSocket' in window)) {
        return;
    }

    let socket = null;
    let reconnectTimer = null;
    let backoff = 1000;
    let MAX_BACKOFF = 30000;

    function endpoint() {
        let proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        return proto + '//' + window.location.host + '/ws/materialboerse/board';
    }

    function connect() {
        try {
            socket = new WebSocket(endpoint());
        } catch (_e) {
            scheduleReconnect();
            return;
        }
        socket.addEventListener('open', function () {
            backoff = 1000;
        });
        socket.addEventListener('message', function (ev) {
            let data;
            try {
                data = JSON.parse(ev.data);
            } catch (_e) {
                return;
            }
            if (data && data.type === 'changed') {
                document.dispatchEvent(
                    new CustomEvent('krt:materialboerse-changed', {
                        detail: { sections: data.sections || [] },
                    }),
                );
            }
        });
        socket.addEventListener('close', scheduleReconnect);
        socket.addEventListener('error', function () {
            try {
                socket.close();
            } catch (_e) {
                /* ignore */
            }
        });
    }

    function scheduleReconnect() {
        if (reconnectTimer) {
            return;
        }
        reconnectTimer = setTimeout(function () {
            reconnectTimer = null;
            backoff = Math.min(backoff * 2, MAX_BACKOFF);
            connect();
        }, backoff);
    }

    function sendChanged(sections) {
        if (socket && socket.readyState === WebSocket.OPEN) {
            try {
                socket.send(JSON.stringify({ type: 'changed', sections: sections || ['board'] }));
            } catch (_e) {
                /* best-effort */
            }
        }
    }

    window.krtMaterialboardPresence = { sendChanged: sendChanged };
    connect();
})();
