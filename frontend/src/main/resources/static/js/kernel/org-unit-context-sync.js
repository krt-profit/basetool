import { krtModal } from '/js/kernel/krt-modal.js';
import { hasUnsavedChanges, resetUnsavedChanges } from '/js/kernel/unsaved-changes.js';

/** The channel every tab of this origin announces its active org-unit context on (REQ-ORG-032). */
const CHANNEL_NAME = 'krt-active-org-unit';

/** Session-storage key: the context this tab reloads for, taken by the next page load. */
const RELOADED_FOR_KEY = 'krt-active-org-unit-reloaded-for';

/**
 * What a page announces on the channel.
 *
 * @typedef {object} ContextAnnouncement
 * @property {string} context the page's effective org-unit context key
 * @property {boolean} followed whether the page is a reload that followed another tab's
 *     announcement
 */

/**
 * Reads this tab's session-storage entry.
 *
 * @param {string} key the entry's key
 * @returns {string | null} the stored value, or null when absent or storage is unavailable
 */
function readStored(key) {
    try {
        return window.sessionStorage.getItem(key);
    } catch (_unavailable) {
        return null;
    }
}

/**
 * Writes or removes this tab's session-storage entry; storage that is unavailable is skipped.
 *
 * @param {string} key the entry's key
 * @param {string | null} value the value to store, or null to remove the entry
 */
function writeStored(key, value) {
    try {
        if (value === null) {
            window.sessionStorage.removeItem(key);
        } else {
            window.sessionStorage.setItem(key, value);
        }
    } catch (_unavailable) {}
}

/**
 * Reads the context this tab's previous page reloaded for and removes it, so it guards only the
 * page that reload rendered.
 *
 * @returns {string | null} the context the reload followed, or null after any other navigation
 */
function takeReloadedFor() {
    const reloadedFor = readStored(RELOADED_FOR_KEY);
    writeStored(RELOADED_FOR_KEY, null);
    return reloadedFor;
}

/**
 * Reloads this tab so it renders the context another tab announced.
 *
 * @param {string} announced the context another tab announced
 */
function reloadFor(announced) {
    writeStored(RELOADED_FOR_KEY, announced);
    window.location.reload();
}

/**
 * Reads an announcement from a channel message.
 *
 * @param {unknown} data the message's payload
 * @returns {ContextAnnouncement | null} the announcement, or null for any other payload
 */
function readAnnouncement(data) {
    if (typeof data !== 'object' || data === null) {
        return null;
    }
    const fields = /** @type {{ context?: unknown, followed?: unknown }} */ (data);
    return typeof fields.context === 'string' && fields.context !== ''
        ? { context: fields.context, followed: fields.followed === true }
        : null;
}

/**
 * Announces this page's effective org-unit context to the browser's other tabs and follows theirs
 * (REQ-ORG-032): a tab that shows another context reloads, or first asks through the KRT dialog
 * while it holds unsaved input. A page that a followed reload rendered ignores the followed
 * announcements of the context it reloaded for, which ends a reload loop between two tabs that
 * disagree; any other announcement of that context, a switch included, still reloads it.
 */
function initOrgUnitContextSync() {
    const reloadedFor = takeReloadedFor();
    const marker = document.querySelector('[data-active-org-unit-context]');
    if (!(marker instanceof HTMLElement) || typeof BroadcastChannel !== 'function') {
        return;
    }
    const context = marker.dataset.activeOrgUnitContext || '';
    if (!context) {
        return;
    }

    const modal = document.getElementById('org-unit-changed-modal');
    /** @type {string | null} */
    let pending = null;
    const reloadButton = document.getElementById('org-unit-changed-reload-btn');
    if (reloadButton) {
        reloadButton.addEventListener('click', () => {
            resetUnsavedChanges();
            reloadFor(pending || context);
        });
    }

    const channel = new BroadcastChannel(CHANNEL_NAME);
    channel.addEventListener('message', (event) => {
        const announcement = readAnnouncement(event.data);
        if (!announcement) {
            return;
        }
        if (announcement.context === context) {
            pending = null;
            if (modal && krtModal.isOpen(modal)) {
                krtModal.close(modal);
            }
            return;
        }
        if (announcement.followed && announcement.context === reloadedFor) {
            return;
        }
        if (!hasUnsavedChanges()) {
            reloadFor(announcement.context);
            return;
        }
        pending = announcement.context;
        if (modal && !krtModal.isOpen(modal)) {
            krtModal.open(modal);
        }
    });
    /** @type {ContextAnnouncement} */
    const own = { context, followed: reloadedFor !== null };
    channel.postMessage(own);
}

document.addEventListener('DOMContentLoaded', initOrgUnitContextSync);
