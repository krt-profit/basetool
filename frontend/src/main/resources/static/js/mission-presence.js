/*
 * Mission detail presence/awareness client (Stufe 3).
 *
 * Rides the shared tool-wide live-sync socket (`/ws/sync`, krt-live-sync.js, REQ-FE-015 / ADR-0094):
 * this is a thin adapter that subscribes to the mission's `mission:{id}` topic room and, while the
 * user is editing a section, it:
 *  - sends {type:"focus", sectionKey} when the user starts editing a section
 *  - sends {type:"heartbeat", sectionKey} every HEARTBEAT_MS while focused
 *  - sends {type:"blur", sectionKey} when focus leaves the section (or tab is hidden)
 *  - renders a small KRT-styled pulse indicator on each .col-header[data-panel-key]
 *    showing who else is currently editing that section (driven by inbound {type:"presence"} frames).
 *
 * It also carries the live multi-user sync signal: sendChanged(sections) tells peers the local
 * user just changed those mission sections, and an inbound {type:"changed",sections:[...]} frame is
 * re-dispatched as a 'krt:mission-changed' DOM event (a reconnect re-subscribe fires
 * 'krt:mission-resync') which mission-detail.html turns into in-place fragment re-fetches. Only
 * section keys travel over the socket — never mission data.
 *
 * Awareness only — no locks, no save blocking. The socket lifecycle (connect, reconnect with
 * full-jitter backoff, per-user cap handling) is owned by the shared krt-live-sync.js transport;
 * this adapter only subscribes/publishes on it. Since #1236 there is no bespoke
 * `/ws/missions/{id}/presence` socket — the one-release legacy alias was removed.
 *
 * All user-visible strings are read from window.MISSION_PRESENCE_I18N, populated
 * by mission-detail.html from Thymeleaf messages.
 *
 * Heartbeat cadence (L-7 from the performance audit): raised from 10 s to 60 s.
 * The backend's presence ENTRY_TTL was raised from 30 s to 120 s in lockstep, so two missed beats
 * of slack remain before a stale editor gets reaped from a peer's indicator. The trade-off: peers
 * now see "user stopped editing" up to ~120 s after the editor navigates away (was ~30 s before),
 * but the WebSocket frame traffic per active editor drops by 6×. The presence panel never claims
 * real-time precision; this script is bundled only by mission-detail.html so the cost only matters
 * there. If the UX feedback is that the indicator lingers too long, drop both values together —
 * never one without the other, or the indicator will flicker / drop editors prematurely.
 */
(function () {
    'use strict';

    const HEARTBEAT_MS = 60000;
    const SECTION_SELECTOR = '[data-panel-key]';

    function i18n(key, fallback) {
        const dict = window.MISSION_PRESENCE_I18N || {};
        return dict[key] != null && dict[key] !== '' ? dict[key] : fallback || key;
    }

    function MissionPresence(missionId, currentUserId) {
        this.missionId = missionId;
        // Canonical live-sync topic room for this mission — the same string the acting client's
        // broadcast, the server relay and every peer's receiver key on.
        this.topic = 'mission:' + missionId;
        // Stable identifier of the local user — used to filter the local user out of the
        // indicator (we don't want to see "you are editing this section").
        this.currentUserId = currentUserId || null;
        this.subscription = null;
        this.heartbeatTimer = null;
        this.activeSection = null;
        this.lastState = {};
        this._onFocusIn = this._onFocusIn.bind(this);
        this._onFocusOut = this._onFocusOut.bind(this);
        this._onVisibility = this._onVisibility.bind(this);
        this._tick = this._tick.bind(this);
    }

    MissionPresence.prototype.start = function () {
        if (!window.krtLiveSync || typeof window.krtLiveSync.subscribe !== 'function') {
            return;
        }
        document.addEventListener('focusin', this._onFocusIn);
        document.addEventListener('focusout', this._onFocusOut);
        document.addEventListener('visibilitychange', this._onVisibility);
        const self = this;
        this.subscription = window.krtLiveSync.subscribe(this.topic, {
            // Fired on every subscribe ack (first connect and each reconnect re-subscribe). If the
            // user was already focused on a section, re-announce that focus so the indicator on
            // other clients (re)appears — a presence frame sent before this ack is dropped by the
            // server, so this is where a focus that raced the ack, or one held across a reconnect,
            // is replayed.
            onSubscribed: function () {
                if (self.activeSection) {
                    self._sendPresence('focus', self.activeSection);
                    self._ensureHeartbeat();
                }
            },
            // Fired only on a RE-subscribe after a dropped socket: 'changed' signals may have been
            // missed while offline, so ask the page to resync every visible section. The initial
            // subscribe is already fresh, so it never triggers a resync.
            onResync: function () {
                document.dispatchEvent(new CustomEvent('krt:mission-resync'));
            },
            // A peer mutated the mission. Hand the affected section keys to the page, which re-fetches
            // those fragments in place (guarded against yanking a section the local user is actively
            // editing). No mission data rides on the socket — only keys.
            onChanged: function (sections) {
                document.dispatchEvent(
                    new CustomEvent('krt:mission-changed', {
                        detail: { sections: Array.isArray(sections) ? sections : [] },
                    }),
                );
            },
            // Inbound editor-presence snapshot for this room: render who else is editing which section.
            onPresence: function (sections) {
                self.lastState = sections || {};
                self._render();
            },
        });
    };

    MissionPresence.prototype.stop = function () {
        document.removeEventListener('focusin', this._onFocusIn);
        document.removeEventListener('focusout', this._onFocusOut);
        document.removeEventListener('visibilitychange', this._onVisibility);
        this._stopHeartbeat();
        // Release the indicator on peers before we go, so we do not linger as an "editor".
        if (this.activeSection) {
            this._sendPresence('blur', this.activeSection);
            this.activeSection = null;
        }
        if (this.subscription && typeof this.subscription.unsubscribe === 'function') {
            this.subscription.unsubscribe();
        }
        this.subscription = null;
    };

    // Sends an editor-presence control frame for this mission's room over the shared socket.
    MissionPresence.prototype._sendPresence = function (type, sectionKey) {
        if (window.krtLiveSync && typeof window.krtLiveSync.sendPresence === 'function') {
            window.krtLiveSync.sendPresence(this.topic, type, sectionKey);
        }
    };

    // Announce to peers that the given mission sections were just changed by the local user, so
    // their views re-fetch those fragments in place. Best-effort: if the socket is not open the
    // shared transport buffers the signal until it reconnects (peers still catch up via the resync
    // on their next reconnect, or a manual reload). Called from window.krtRefreshMissionSection in
    // mission-detail.html via the write seam's broadcast closure.
    MissionPresence.prototype.sendChanged = function (sections) {
        if (
            Array.isArray(sections) &&
            sections.length > 0 &&
            window.krtLiveSync &&
            typeof window.krtLiveSync.sendChanged === 'function'
        ) {
            window.krtLiveSync.sendChanged(this.topic, sections);
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
            this._sendPresence('heartbeat', this.activeSection);
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
            this._sendPresence('blur', this.activeSection);
        }
        this.activeSection = section;
        this._sendPresence('focus', section);
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
        this._sendPresence('blur', this.activeSection);
        this.activeSection = null;
        this._stopHeartbeat();
    };

    MissionPresence.prototype._onVisibility = function () {
        if (document.visibilityState === 'hidden' && this.activeSection) {
            // Tab hidden — release the indicator so peers do not see us "editing"
            // a section we have effectively stopped editing.
            this._sendPresence('blur', this.activeSection);
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
