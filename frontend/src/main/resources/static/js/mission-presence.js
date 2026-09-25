(function () {
    'use strict';

    const HEARTBEAT_MS = 60000;
    const SECTION_SELECTOR = '[data-panel-key]';

    function i18n(key) {
        const dict = window.MISSION_PRESENCE_I18N || {};
        return window.krtI18nText(dict[key], 'MISSION_PRESENCE_I18N[' + key + ']');
    }

    function MissionPresence(missionId, currentUserId) {
        this.missionId = missionId;
        this.topic = 'mission:' + missionId;
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
            onSubscribed() {
                if (self.activeSection) {
                    self._sendPresence('focus', self.activeSection);
                    self._ensureHeartbeat();
                }
            },
            onResync() {
                document.dispatchEvent(new CustomEvent('krt:mission-resync'));
            },
            onChanged(sections) {
                document.dispatchEvent(
                    new CustomEvent('krt:mission-changed', {
                        detail: { sections: Array.isArray(sections) ? sections : [] },
                    }),
                );
            },
            onPresence(sections) {
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
        if (this.activeSection) {
            this._sendPresence('blur', this.activeSection);
            this.activeSection = null;
        }
        if (this.subscription && typeof this.subscription.unsubscribe === 'function') {
            this.subscription.unsubscribe();
        }
        this.subscription = null;
    };

    MissionPresence.prototype._sendPresence = function (type, sectionKey) {
        if (window.krtLiveSync && typeof window.krtLiveSync.sendPresence === 'function') {
            window.krtLiveSync.sendPresence(this.topic, type, sectionKey);
        }
    };

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
                tooltip = i18n('mission.presence.editing.solo');
                tooltip = tooltip + ' ' + (names[0] || '');
            } else {
                tooltip = i18n('mission.presence.editing.multi');
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
