// @ts-check
document.addEventListener('DOMContentLoaded', () => {
    if (window.__unsavedChangesInitialized) return;
    window.__unsavedChangesInitialized = true;

    let isDirty = false;
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
     * Arms the guard for an edit, ahead of the control's own listeners so a submit they trigger
     * for the same edit clears it again.
     *
     * @param {Event} e the input or change event
     */
    function markDirty(e) {
        const target = /** @type {Element} */ (e.target);
        if (target instanceof Element && tracksEdits(target.closest('form'))) {
            isDirty = true;
        }
    }

    document.addEventListener('input', markDirty, true);
    document.addEventListener('change', markDirty, true);

    window.resetUnsavedChanges = function () {
        isDirty = false;
    };

    document.addEventListener('submit', () => {
        isDirty = false;
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
                window.krtModal.open(modal);
            }
        }
    });

    [stayBtn, closeBtn].forEach((btn) => {
        if (!btn) return;
        btn.addEventListener('click', () => {
            if (modal) window.krtModal.close(modal);
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
                window.krtModal.close(modal);
                targetUrl = null;
            }
        });
    }

    const beforeUnloadHandler = function (event) {
        if (isDirty) {
            event.preventDefault();
            event.returnValue = '';
        }
    };

    window.addEventListener('beforeunload', beforeUnloadHandler);
});
