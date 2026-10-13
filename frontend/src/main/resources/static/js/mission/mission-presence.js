import { krtLiveSync } from '/js/kernel/krt-live-sync.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { missionHooks } from '/js/mission/mission-hooks.js';

const HEARTBEAT_MS = 60000;
const SECTION_SELECTOR = '[data-panel-key]';

/**
 * The mutable state of one presence channel, as the prototype methods see it.
 *
 * @typedef {object} MissionPresenceSelf
 * @property {string} missionId the mission the channel belongs to
 * @property {string} topic the live-sync room, `mission:<id>`
 * @property {string | null} currentUserId the viewer, whose own focus is not indicated
 * @property {{ unsubscribe(): void } | null} subscription the live-sync subscription
 * @property {number | null} heartbeatTimer the interval handle while a section is focused
 * @property {string | null} activeSection the focused section key, if any
 * @property {Record<string, KrtPresenceEditor[]>} lastState the latest editors per section
 * @property {(ev: FocusEvent) => void} _onFocusIn bound focusin listener
 * @property {(ev: FocusEvent) => void} _onFocusOut bound focusout listener
 * @property {() => void} _onVisibility bound visibilitychange listener
 * @property {() => void} _tick bound heartbeat tick
 */

/**
 * The constructor of a presence channel, which `new MissionPresence(…)` yields.
 *
 * @typedef {new (
 *     missionId: string,
 *     currentUserId: string | null | undefined,
 * ) => { start(): void, stop(): void, sendChanged(sections: string[]): void }} MissionPresenceConstructor
 */

/**
 * Looks up a presence tooltip label.
 *
 * @param {string} key the message key
 * @returns {string} the localized label
 */
function i18n(key) {
    const dict = window.MISSION_PRESENCE_I18N || {};
    return krtI18nText(dict[key], `MISSION_PRESENCE_I18N[${key}]`);
}

/**
 * Presence channel of one mission page.
 *
 * @this {MissionPresenceSelf}
 * @param {string} missionId the mission to track
 * @param {string | null | undefined} currentUserId the viewer's user id
 */
function MissionPresence(missionId, currentUserId) {
    this.missionId = missionId;
    this.topic = `mission:${missionId}`;
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
    if (typeof krtLiveSync.subscribe !== 'function') {
        return;
    }
    document.addEventListener('focusin', this._onFocusIn);
    document.addEventListener('focusout', this._onFocusOut);
    document.addEventListener('visibilitychange', this._onVisibility);
    const self = this;
    this.subscription = krtLiveSync.subscribe(this.topic, {
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

/**
 * Announces this viewer's presence in a section to the room.
 *
 * @param {string} type `focus`, `blur` or `heartbeat`
 * @param {string} sectionKey the section's `data-panel-key`
 */
MissionPresence.prototype._sendPresence = function (type, sectionKey) {
    if (typeof krtLiveSync.sendPresence === 'function') {
        krtLiveSync.sendPresence(this.topic, type, sectionKey);
    }
};

/**
 * Tells the room's peers which sections this viewer changed.
 *
 * @param {string[]} sections the changed section keys
 */
MissionPresence.prototype.sendChanged = function (sections) {
    if (
        Array.isArray(sections) &&
        sections.length > 0 &&
        typeof krtLiveSync.sendChanged === 'function'
    ) {
        krtLiveSync.sendChanged(this.topic, sections);
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

/**
 * Starts presence for the section that received focus.
 *
 * @param {FocusEvent} ev the focusin event
 */
MissionPresence.prototype._onFocusIn = function (ev) {
    const section = this._sectionOf(/** @type {Element | null} */ (ev.target));
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

/**
 * Ends presence when focus leaves the active section.
 *
 * @param {FocusEvent} ev the focusout event
 */
MissionPresence.prototype._onFocusOut = function (ev) {
    if (!this.activeSection) {
        return;
    }
    const next = /** @type {Element | null} */ (ev.relatedTarget);
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

/**
 * Resolves the section panel that contains an element.
 *
 * @param {Element | null} element the focus target
 * @returns {string | null} the panel's `data-panel-key`, or null outside any section
 */
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
    allPanels.forEach((panel) => {
        const key = panel.getAttribute('data-panel-key');
        const header = panel.querySelector('.col-header') || panel;
        let indicator = header.querySelector('.krt-presence-indicator');
        const editors = (state[String(key)] || []).filter((/** @type {KrtPresenceEditor} */ e) => {
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
            indicator.appendChild(document.createElement('span')).className = 'krt-presence-dot';
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
            .map((/** @type {KrtPresenceEditor} */ e) => {
                return e.displayName || '';
            })
            .filter(Boolean);
        let tooltip;
        if (editors.length === 1) {
            tooltip = i18n('mission.presence.editing.solo');
            tooltip = `${tooltip} ${names[0] || ''}`;
        } else {
            tooltip = i18n('mission.presence.editing.multi');
            tooltip = `${tooltip} ${names.join(', ')}`;
        }
        indicator.setAttribute('title', tooltip);
        if (dot) {
            dot.setAttribute('aria-label', tooltip);
        }
    });
};

document.addEventListener('DOMContentLoaded', () => {
    if (!window.missionId || window.missionPresenceUserId == null) {
        return;
    }
    const presence = new /** @type {MissionPresenceConstructor} */ (
        /** @type {unknown} */ (MissionPresence)
    )(window.missionId, window.missionPresenceUserId);
    presence.start();
    missionHooks.presence = presence;
});
