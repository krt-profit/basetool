import { krtModal } from '/js/kernel/krt-modal.js';
import {
    clearAllUnsaved,
    clearUnsaved,
    keepAsDraft,
    markUnsaved,
} from '/js/kernel/krt-unsaved-marks.js';

let isDirty = false;

/**
 * Clears the unsaved-changes guard and every form's unsaved mark, for a save that did not submit a
 * form.
 */
export function resetUnsavedChanges() {
    isDirty = false;
    clearAllUnsaved();
}

/**
 * Clears the unsaved-changes guard for a dialog closed without saving and keeps its form's draft
 * (REQ-FE-041): the form's unsaved mark becomes its draft mark, and the marks of other forms stay.
 *
 * @param {HTMLFormElement} form the closed dialog's form
 */
export function releaseUnsavedChanges(form) {
    isDirty = false;
    keepAsDraft(form);
}

/**
 * Tells whether the page holds an edit that has not been saved.
 *
 * @returns {boolean} true while the unsaved-changes guard is armed
 */
export function hasUnsavedChanges() {
    return isDirty;
}

document.addEventListener('DOMContentLoaded', () => {
    if (window.__unsavedChangesInitialized) return;
    window.__unsavedChangesInitialized = true;

    /** @type {string | null} */
    let targetUrl = null;

    const modal = document.getElementById('unsaved-changes-modal');
    const leaveBtn = document.getElementById('unsaved-leave-btn');
    const stayBtn = document.getElementById('unsaved-stay-btn');
    const closeBtn = document.getElementById('unsaved-close-btn');

    /**
     * Tells whether an edit inside the given form counts as unsaved data.
     *
     * A form marked `no-track` and a form with an explicit `method="get"` hold a query, not data,
     * so editing them never arms the guard (REQ-FE-024).
     *
     * @param {HTMLFormElement | null} form the form the edited control belongs to
     * @returns {boolean} true when the edit has to arm the guard
     */
    function tracksEdits(form) {
        if (!form || form.classList.contains('no-track')) {
            return false;
        }
        return (form.getAttribute('method') || '').toLowerCase() !== 'get';
    }

    /**
     * Arms the guard and marks the edited form, ahead of the control's own listeners so a submit
     * they trigger for the same edit clears the guard again.
     *
     * @param {Event} e the input or change event
     */
    function markDirty(e) {
        const target = /** @type {Element} */ (e.target);
        const form = target instanceof Element ? target.closest('form') : null;
        if (form && tracksEdits(form)) {
            isDirty = true;
            markUnsaved(form);
        }
    }

    document.addEventListener('input', markDirty, true);
    document.addEventListener('change', markDirty, true);

    // A submit keeps the form's mark: only a successful save (krtFetch) or a reset clears it.
    document.addEventListener('submit', () => {
        isDirty = false;
    });

    document.addEventListener('reset', (event) => {
        if (event.target instanceof HTMLFormElement) {
            clearUnsaved(event.target);
        }
    });

    document.addEventListener('click', (event) => {
        const a = /** @type {Element} */ (event.target).closest('a');

        if (!a || !a.href) return;

        const href = a.getAttribute('href');
        if (!href || href === '#' || href.startsWith('#') || a.target === '_blank') {
            return;
        }
        const protocol = (a.protocol || '').toLowerCase();
        if (protocol === 'javascript:' || protocol === 'data:' || protocol === 'vbscript:') {
            return;
        }

        if (isDirty) {
            event.preventDefault();
            targetUrl = a.href;

            if (modal) {
                krtModal.open(modal);
            }
        }
    });

    [stayBtn, closeBtn].forEach((btn) => {
        if (!btn) return;
        btn.addEventListener('click', () => {
            if (modal) krtModal.close(modal);
            targetUrl = null;
        });
    });

    if (leaveBtn) {
        leaveBtn.addEventListener('click', () => {
            isDirty = false;
            window.removeEventListener('beforeunload', beforeUnloadHandler);
            if (targetUrl) {
                window.location.href = targetUrl;
            }
        });
    }

    if (modal) {
        window.addEventListener('click', (event) => {
            if (event.target === modal) {
                krtModal.close(modal);
                targetUrl = null;
            }
        });
    }

    const beforeUnloadHandler = function (/** @type {BeforeUnloadEvent} */ event) {
        if (isDirty) {
            event.preventDefault();
            event.returnValue = '';
        }
    };

    window.addEventListener('beforeunload', beforeUnloadHandler);
});
