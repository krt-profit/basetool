// @ts-check
document.addEventListener('DOMContentLoaded', function () {
    if (window.__unsavedChangesInitialized) return;
    window.__unsavedChangesInitialized = true;

    let isDirty = false;
    /** @type {string | null} */
    let targetUrl = null;

    const modal = document.getElementById('unsaved-changes-modal');
    const leaveBtn = document.getElementById('unsaved-leave-btn');
    const stayBtn = document.getElementById('unsaved-stay-btn');
    const closeBtn = document.getElementById('unsaved-close-btn');

    document.addEventListener('input', function (e) {
        const form = /** @type {Element} */ (e.target).closest('form');
        if (form && !form.classList.contains('no-track')) {
            isDirty = true;
        }
    });

    document.addEventListener('change', function (e) {
        const form = /** @type {Element} */ (e.target).closest('form');
        if (form && !form.classList.contains('no-track')) {
            isDirty = true;
        }
    });

    window.resetUnsavedChanges = function () {
        isDirty = false;
    };

    document.addEventListener('submit', function () {
        isDirty = false;
    });

    document.addEventListener('click', function (event) {
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

    [stayBtn, closeBtn].forEach(function (btn) {
        if (!btn) return;
        btn.addEventListener('click', function () {
            if (modal) window.krtModal.close(modal);
            targetUrl = null;
        });
    });

    if (leaveBtn) {
        leaveBtn.addEventListener('click', function () {
            isDirty = false;
            window.removeEventListener('beforeunload', beforeUnloadHandler);
            if (targetUrl) {
                window.location.href = targetUrl;
            }
        });
    }

    if (modal) {
        window.addEventListener('click', function (event) {
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
