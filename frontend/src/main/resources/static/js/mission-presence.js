/*
 * Mission detail presence/awareness client (Stufe 3).
 *
 * Opens a WebSocket against /ws/missions/{missionId}/presence and:
 *  - sends {type:"focus", sectionKey} when the user starts editing a section
 *  - sends {type:"heartbeat", sectionKey} every HEARTBEAT_MS while focused
 *  - sends {type:"blur", sectionKey} when focus leaves the section (or tab is hidden)
 *  - renders a small KRT-styled pulse indicator on each .col-header[data-panel-key]
 *    showing who else is currently editing that section.
 *
 * It also carries the live multi-user sync signal: sendChanged(sections) tells peers the local
 * user just changed those mission sections, and an inbound {type:"changed",sections:[...]} frame is
 * re-dispatched as a 'krt:mission-changed' DOM event (a reconnect fires 'krt:mission-resync')
 * which mission-detail.html turns into in-place fragment re-fetches. Only section keys travel over
 * the socket — never mission data.
 *
 * Awareness only — no locks, no save blocking. Reconnects on close with
 * exponential backoff capped at MAX_BACKOFF_MS.
 *
 * All user-visible strings are read from window.MISSION_PRESENCE_I18N, populated
 * by mission-detail.html from Thymeleaf messages.
 *
 * Heartbeat cadence (L-7 from the performance audit): raised from 10 s to 60 s.
 * The backend's MissionPresenceService.ENTRY_TTL was raised from 30 s to 120 s
 * in lockstep, so two missed beats of slack remain before a stale editor gets
 * reaped from a peer's indicator. The trade-off: peers now see "user stopped
 * editing" up to ~120 s after the editor navigates away (was ~30 s before),
 * but the WebSocket frame traffic per active editor drops by 6×. The presence
 * panel never claims real-time precision; this script is bundled only by
 * mission-detail.html so the cost only matters there. If the UX feedback is
 * that the indicator lingers too long, drop both values together — never one
 * without the other, or the indicator will flicker / drop editors prematurely.
 */
(function () {
    'use strict';

    const HEARTBEAT_MS = 60000;
    const INITIAL_BACKOFF_MS = 1000;
    const MAX_BACKOFF_MS = 30000;
    const SECTION_SELECTOR = '[data-panel-key]';

    function i18n(key, fallback) {
        const dict = window.MISSION_PRESENCE_I18N || {};
        return dict[key] != null && dict[key] !== '' ? dict[key] : fallback || key;
    }

    function buildSocketUrl(missionId) {
        const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
        return (
            proto +
            '://' +
            window.location.host +
            '/ws/missions/' +
            encodeURIComponent(missionId) +
            '/presence'
        );
    }

    function MissionPresence(missionId, currentUserId) {
        this.missionId = missionId;
        // Stable identifier of the local user — used to filter the local user out of the
        // indicator (we don't want to see "you are editing this section").
        this.currentUserId = currentUserId || null;
        this.socket = null;
        this.backoff = INITIAL_BACKOFF_MS;
        this.heartbeatTimer = null;
        this.activeSection = null;
        this.lastState = {};
        this.closedByUs = false;
        // Tracks whether we have ever had an open socket, so onopen can tell a fresh connect
        // (initial page load — already in sync) from a re-connect (we may have missed 'changed'
        // signals while offline, so the page must resync).
        this.hasConnected = false;
        this._onFocusIn = this._onFocusIn.bind(this);
        this._onFocusOut = this._onFocusOut.bind(this);
        this._onVisibility = this._onVisibility.bind(this);
        this._tick = this._tick.bind(this);
    }

    MissionPresence.prototype.start = function () {
        document.addEventListener('focusin', this._onFocusIn);
        document.addEventListener('focusout', this._onFocusOut);
        document.addEventListener('visibilitychange', this._onVisibility);
        this._connect();
    };

    MissionPresence.prototype.stop = function () {
        this.closedByUs = true;
        document.removeEventListener('focusin', this._onFocusIn);
        document.removeEventListener('focusout', this._onFocusOut);
        document.removeEventListener('visibilitychange', this._onVisibility);
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
        if (this.socket) {
            try {
                this.socket.close();
            } catch (_e) {
                /* swallow */
            }
            this.socket = null;
        }
    };

    MissionPresence.prototype._connect = function () {
        const url = buildSocketUrl(this.missionId);
        let socket;
        try {
            socket = new WebSocket(url);
        } catch (_e) {
            this._scheduleReconnect();
            return;
        }
        this.socket = socket;
        const self = this;
        socket.onopen = function () {
            self.backoff = INITIAL_BACKOFF_MS;
            // After a re-connect (not the initial open) we may have missed 'changed' signals while
            // the socket was down — ask the page to resync every visible section so a peer's edit
            // made during the outage is not lost. The initial load is already fresh, so skip it.
            if (self.hasConnected) {
                document.dispatchEvent(new CustomEvent('krt:mission-resync'));
            }
            self.hasConnected = true;
            // If the user was already focused on a section before the socket opened
            // (e.g. after a reconnect), re-announce that focus so the indicator on
            // other clients reappears without waiting for the next focusin event.
            if (self.activeSection) {
                self._send({ type: 'focus', sectionKey: self.activeSection });
                self._ensureHeartbeat();
            }
        };
        socket.onmessage = function (ev) {
            let msg;
            try {
                msg = JSON.parse(ev.data);
            } catch (_e) {
                return;
            }
            if (!msg) {
                return;
            }
            if (msg.type === 'presence' && msg.sections) {
                self.lastState = msg.sections;
                self._render();
            } else if (msg.type === 'changed' && Array.isArray(msg.sections)) {
                // A peer mutated the mission. Hand the affected section keys to the page, which
                // re-fetches those fragments in place (guarded against yanking a section the local
                // user is actively editing). No mission data rides on the socket — only keys.
                document.dispatchEvent(
                    new CustomEvent('krt:mission-changed', { detail: { sections: msg.sections } }),
                );
            }
        };
        socket.onclose = function () {
            self.socket = null;
            if (!self.closedByUs) {
                self._scheduleReconnect();
            }
        };
        socket.onerror = function () {
            // onerror is followed by onclose; nothing to do here.
        };
    };

    MissionPresence.prototype._scheduleReconnect = function () {
        // Full jitter (mission-scale hardening, ADR-0078 / finding S2 reconnect-storm): a frontend
        // redeploy or a brief network blip drops the presence socket for ALL viewers of a mission at
        // once. An un-jittered backoff makes every client reconnect in the same instant and fire its
        // resync refetch as one synchronized burst against the backend, which could starve the DB
        // pool and trip the shared circuit breaker into the very outage the resync is meant to
        // recover from. Spreading the reconnect uniformly across [0, backoff] decorrelates the herd.
        const delay = Math.random() * this.backoff;
        this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
        const self = this;
        setTimeout(function () {
            if (!self.closedByUs) {
                self._connect();
            }
        }, delay);
    };

    MissionPresence.prototype._send = function (payload) {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            try {
                this.socket.send(JSON.stringify(payload));
            } catch (_e) {
                /* drop */
            }
        }
    };

    // Announce to peers that the given mission sections were just changed by the local user, so
    // their views re-fetch those fragments in place. Best-effort: if the socket is not open the
    // signal is silently dropped (peers still catch up via the resync on their next reconnect, or
    // a manual reload). Called from window.krtRefreshMissionSection in mission-detail.html.
    MissionPresence.prototype.sendChanged = function (sections) {
        if (Array.isArray(sections) && sections.length > 0) {
            this._send({ type: 'changed', sections: sections });
        }
    };

    MissionPresence.prototype._ensureHeartbeat = function () {
        if (this.heartbeatTimer) return;
        this.heartbeatTimer = setInterval(this._tick, HEARTBEAT_MS);
    };

    MissionPresence.prototype._stopHeartbeat = function () {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
    };

    MissionPresence.prototype._tick = function () {
        if (this.activeSection) {
            this._send({ type: 'heartbeat', sectionKey: this.activeSection });
        }
    };

    MissionPresence.prototype._onFocusIn = function (ev) {
        const section = this._sectionOf(ev.target);
        if (!section || section === this.activeSection) {
            return;
        }
        // Switching from one section to another: blur the old one first so the
        // indicator on the other clients does not flash both sections active.
        if (this.activeSection) {
            this._send({ type: 'blur', sectionKey: this.activeSection });
        }
        this.activeSection = section;
        this._send({ type: 'focus', sectionKey: section });
        this._ensureHeartbeat();
    };

    MissionPresence.prototype._onFocusOut = function (ev) {
        if (!this.activeSection) {
            return;
        }
        // relatedTarget is the element gaining focus; if it's still inside the
        // same section, this is an internal tab — do not blur.
        const next = ev.relatedTarget;
        if (next && this._sectionOf(next) === this.activeSection) {
            return;
        }
        this._send({ type: 'blur', sectionKey: this.activeSection });
        this.activeSection = null;
        this._stopHeartbeat();
    };

    MissionPresence.prototype._onVisibility = function () {
        if (document.visibilityState === 'hidden' && this.activeSection) {
            // Tab hidden — release the indicator so peers do not see us "editing"
            // a section we have effectively stopped editing.
            this._send({ type: 'blur', sectionKey: this.activeSection });
            this._stopHeartbeat();
        }
    };

    MissionPresence.prototype._sectionOf = function (element) {
        if (!element || !element.closest) {
            return null;
        }
        const container = element.closest(SECTION_SELECTOR);
        if (!container) {
            return null;
        }
        return container.getAttribute('data-panel-key');
    };

    MissionPresence.prototype._render = function () {
        const allPanels = document.querySelectorAll(SECTION_SELECTOR);
        const state = this.lastState || {};
        const self = this;
        allPanels.forEach(function (panel) {
            const key = panel.getAttribute('data-panel-key');
            const header = panel.querySelector('.col-header') || panel;
            let indicator = header.querySelector('.krt-presence-indicator');
            const editors = (state[key] || []).filter(function (e) {
                return !self.currentUserId || e.userId !== self.currentUserId;
            });
            if (editors.length === 0) {
                if (indicator) {
                    indicator.remove();
                }
                return;
            }
            if (!indicator) {
                indicator = document.createElement('span');
                indicator.className = 'krt-presence-indicator';
                indicator.setAttribute('aria-hidden', 'false');
                indicator.appendChild(document.createElement('span')).className =
                    'krt-presence-dot';
                const label = document.createElement('span');
                label.className = 'krt-presence-count';
                indicator.appendChild(label);
                header.appendChild(indicator);
            }
            const dot = indicator.querySelector('.krt-presence-dot');
            const label = indicator.querySelector('.krt-presence-count');
            if (label) {
                label.textContent = String(editors.length);
            }
            const names = editors
                .map(function (e) {
                    return e.displayName || '';
                })
                .filter(Boolean);
            let tooltip;
            if (editors.length === 1) {
                tooltip = i18n('mission.presence.editing.solo', 'wird gerade bearbeitet von');
                tooltip = tooltip + ' ' + (names[0] || '');
            } else {
                tooltip = i18n('mission.presence.editing.multi', 'wird gerade bearbeitet von');
                tooltip = tooltip + ' ' + names.join(', ');
            }
            indicator.setAttribute('title', tooltip);
            if (dot) {
                dot.setAttribute('aria-label', tooltip);
            }
        });
    };

    window.MissionPresence = MissionPresence;
})();
