// @ts-check
(function () {
    'use strict';

    /** Focus to restore on close, per dialog. */
    /** @type {WeakMap<HTMLElement, Element | null>} */
    const returnFocus = new WeakMap();

    const FIELDS =
        'input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled])';

    /**
     * Resolves an overlay reference: the element itself or its id.
     *
     * @param {Element | string | null | undefined} ref the overlay or its id
     * @returns {HTMLElement | null} the overlay, or null when nothing matches
     */
    function resolve(ref) {
        if (!ref) {
            return null;
        }
        const el = typeof ref === 'string' ? document.getElementById(ref) : ref;
        return el instanceof HTMLElement ? el : null;
    }

    /**
     * Whether an overlay is currently shown, however it was opened.
     *
     * @param {Element | string | null | undefined} ref the overlay or its id
     * @returns {boolean} true while the overlay is displayed
     */
    function isOpen(ref) {
        const overlay = resolve(ref);
        return !!overlay && window.getComputedStyle(overlay).display !== 'none';
    }

    /**
     * Every overlay currently shown, in document order.
     *
     * @returns {HTMLElement[]} the open overlays
     */
    function openOverlays() {
        const overlays = Array.from(document.querySelectorAll('.krt-modal-overlay')).filter(
            /**
             * @param {Element} o a candidate
             * @returns {o is HTMLElement} whether it is an HTML element
             */
            (o) => o instanceof HTMLElement,
        );
        return overlays.filter((o) => isOpen(o));
    }

    /**
     * The topmost open overlay: the last one opened as a modal dialog, else the last shown in
     * document order.
     *
     * @returns {HTMLElement | null} the topmost open overlay
     */
    function topmost() {
        const open = openOverlays();
        const modal = open.filter((o) => o.matches(':modal'));
        const pool = modal.length ? modal : open;
        return pool.length ? pool[pool.length - 1] : null;
    }

    /**
     * Moves focus into an overlay that just opened: an explicit target, else the `[autofocus]`
     * element, else the first visible field, else the frame itself (made focusable for it).
     *
     * @param {HTMLElement} overlay the overlay
     * @param {Element | null | undefined} explicit the element the caller wants focused, if any
     */
    function focusInside(overlay, explicit) {
        const auto = overlay.querySelector('[autofocus]');
        const fields = Array.from(overlay.querySelectorAll(FIELDS)).filter(
            (el) => el instanceof HTMLElement && el.getClientRects().length > 0,
        );
        const target =
            (explicit instanceof HTMLElement && explicit) ||
            (auto instanceof HTMLElement && auto) ||
            fields[0] ||
            overlay.querySelector('.krt-modal');
        if (target instanceof HTMLElement) {
            if (target.classList.contains('krt-modal') && !target.hasAttribute('tabindex')) {
                target.setAttribute('tabindex', '-1');
            }
            target.focus({ preventScroll: true });
        }
    }

    /**
     * Opens an overlay: class state, top layer and focus.
     *
     * @param {Element | string | null | undefined} ref the overlay or its id
     * @param {{ focus?: Element | null }} [options] `focus` names the element to focus instead of
     *     the default (the `[autofocus]` element, else the first field, else the frame)
     * @returns {HTMLElement | null} the overlay, or null when nothing matched
     */
    function open(ref, options) {
        const overlay = resolve(ref);
        if (!overlay) {
            return null;
        }
        if (!returnFocus.has(overlay) || !isOpen(overlay)) {
            returnFocus.set(overlay, document.activeElement);
        }
        overlay.style.removeProperty('display');
        overlay.classList.remove('krtm-hidden');
        overlay.classList.add('krtm-modal-open');
        if (overlay instanceof HTMLDialogElement && !overlay.open && overlay.isConnected) {
            try {
                overlay.showModal();
            } catch (_notModal) {}
        }
        focusInside(overlay, options ? options.focus : null);
        return overlay;
    }

    /**
     * Closes an overlay and returns focus to where it was when the overlay opened.
     *
     * @param {Element | string | null | undefined} ref the overlay or its id
     * @returns {HTMLElement | null} the overlay, or null when nothing matched
     */
    function close(ref) {
        const overlay = resolve(ref);
        if (!overlay) {
            return null;
        }
        overlay.style.removeProperty('display');
        overlay.classList.remove('krtm-modal-open');
        overlay.classList.add('krtm-hidden');
        if (overlay instanceof HTMLDialogElement && overlay.open) {
            overlay.close();
        }
        overlay.querySelectorAll(':scope > .notification-toast').forEach(function (toast) {
            document.body.appendChild(toast);
        });
        const back = returnFocus.get(overlay);
        returnFocus.delete(overlay);
        if (back instanceof HTMLElement && back.isConnected && !overlay.contains(back)) {
            back.focus({ preventScroll: true });
        }
        return overlay;
    }

    /**
     * Dismisses an overlay on Escape by clicking its enabled close control, so page close handlers
     * run; an overlay without one is closed directly.
     *
     * @param {HTMLElement} overlay the overlay to dismiss
     */
    function dismiss(overlay) {
        const control = overlay.querySelector('.krt-modal-close, [data-modal-dismiss]');
        if (control instanceof HTMLElement && !control.hasAttribute('disabled')) {
            control.click();
        } else {
            close(overlay);
        }
    }

    /**
     * Where a transient overlay must be appended to be visible and interactive.
     *
     * @returns {HTMLElement} the topmost open modal dialog, else the body
     */
    function layerRoot() {
        const top = topmost();
        return top && top.matches(':modal') ? top : document.body;
    }

    document.addEventListener(
        'cancel',
        function (event) {
            const target = event.target;
            if (
                !(target instanceof HTMLDialogElement) ||
                !target.classList.contains('krt-modal-overlay')
            ) {
                return;
            }
            event.preventDefault();
            if (!target.hasAttribute('data-modal-static')) {
                dismiss(target);
            }
        },
        true,
    );

    document.addEventListener('keydown', function (event) {
        if (event.key !== 'Escape' || event.defaultPrevented) {
            return;
        }
        const top = topmost();
        if (!top || top.matches(':modal') || top.hasAttribute('data-modal-static')) {
            return;
        }
        event.preventDefault();
        dismiss(top);
    });

    document.addEventListener('click', function (event) {
        const target = event.target instanceof Element ? event.target : null;
        const dismiss = target ? target.closest('[data-modal-dismiss]') : null;
        if (dismiss) {
            close(dismiss.closest('.krt-modal-overlay'));
        }
    });

    /**
     * Brings a dialog's modal state in line with whether it is displayed, so a hidden dialog never
     * stays in the top layer and leaves the page inert.
     *
     * @param {HTMLDialogElement} dialog the overlay dialog
     */
    function sync(dialog) {
        const shown = isOpen(dialog);
        if (shown && !dialog.open && dialog.isConnected) {
            try {
                dialog.showModal();
            } catch (_notModal) {}
        } else if (!shown && dialog.open) {
            dialog.close();
            dialog.querySelectorAll(':scope > .notification-toast').forEach(function (toast) {
                document.body.appendChild(toast);
            });
        }
    }

    new MutationObserver(function (records) {
        records.forEach(function (record) {
            const target = record.target;
            if (
                target instanceof HTMLDialogElement &&
                target.classList.contains('krt-modal-overlay')
            ) {
                sync(target);
            }
        });
    }).observe(document.documentElement, {
        subtree: true,
        attributes: true,
        attributeFilter: ['style', 'class'],
    });

    window.krtModal = { open, close, isOpen, topmost, layerRoot };

    document.querySelectorAll('dialog.krt-modal-overlay.krtm-modal-open').forEach(function (d) {
        open(d);
    });
})();
