// @ts-check
/*
 * window.krtModal — the one open/close contract for every `.krt-modal-overlay` dialog
 * (FE-SIMP-04 / FE-SIMP-04b, REQ-UI-013, ADR-0177).
 *
 * Before this file, 90 of the app's 96 dialogs carried their own copy of the open/close code, and
 * 97 call sites wrote `overlay.style.display` directly. The copies disagreed on everything a
 * dialog owes its user: whether Escape closed it, whether focus moved into it, whether focus came
 * back to the button that opened it, and — the defect that kept returning — whether the inline
 * `display` a script wrote could still be overruled by the class-based open/close of the shared
 * `open-modal-display` / `close-modal-display` triggers (an inline declaration outranks a class
 * rule, so a class-opened dialog closed by `style.display = 'none'` could never be reopened).
 *
 * The contract:
 *
 * - `open(dialog)` clears any inline `display`, removes `krtm-hidden`, adds `krtm-modal-open`, and
 *   — because every overlay is a native `<dialog>` since FE-SIMP-04b — calls `showModal()`. That
 *   puts the dialog in the top layer and makes the rest of the page inert, which is the focus
 *   trap and the background lock the copies each approximated. Focus moves to the
 *   `[autofocus]` element, else the first visible field, else the frame (never to a button).
 * - `close(dialog)` reverses all of it and returns focus to the element that had it when the
 *   dialog opened, if that element is still in the document.
 * - Escape dismisses the topmost open dialog the way its own close control (✕) would — by clicking
 *   it, so whatever the page does on close still runs — or closes it directly when it has none.
 *   For a `<dialog>` the browser raises Escape as a `cancel` event; it is intercepted so the close
 *   runs through this contract and the class state follows. A dialog may opt out with
 *   `data-modal-static` (a confirmation that must be answered).
 * - The classes stay: `krt-live-sync.js`'s "is any dialog open?" guard, the layout guard and the
 *   E2E suite all read `.krt-modal-overlay` and its computed `display`, and a `<dialog>` that is
 *   opened by class alone (an old code path, a test that reveals every dialog) still shows — as a
 *   non-modal overlay, exactly the way the `<div>` did.
 * - `layerRoot()` answers where a transient overlay — a toast, the KRT confirm, a download link —
 *   must be appended to be seen and clickable: inside the topmost open modal dialog, because
 *   everything outside it is inert and painted below the top layer; else `document.body`.
 */
(function () {
    'use strict';

    /** Focus to restore on close, per dialog. */
    /** @type {WeakMap<HTMLElement, Element | null>} */
    const returnFocus = new WeakMap();

    // The controls focus may land on when a dialog opens: fields only. A dialog without a field is a
    // confirmation, and landing on its first BUTTON would put Enter on "Delete".
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
            } catch (_notModal) {
                // A dialog inside a hidden or detached subtree cannot become modal; the class
                // state above still shows it the way the pre-<dialog> overlay was shown.
            }
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
        // A toast raised while the dialog was open was appended INTO it (layerRoot); hand it back
        // to the body so a success toast outlives the dialog its save just closed.
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
     * Escape: dismisses an overlay the way its own close control would. A page often does more on
     * close than hide the dialog — reset an unsaved-changes flag, clear a picker, forget the row it
     * was about — and that code hangs off the close button. Clicking the button keeps Escape and ✕
     * one behaviour; only a dialog without one is closed directly.
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

    // Escape on a native modal dialog arrives as `cancel`. Close it through the contract so the
    // class state and the returned focus follow — the browser's own close would drop [open] and
    // leave `krtm-modal-open` behind, which keeps the <dialog> visible as a non-modal overlay.
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

    // Escape for an overlay that is open by class alone (not modal): the browser raises no cancel.
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

    // `data-modal-dismiss` inside an overlay closes it (the mission-detail convention).
    document.addEventListener('click', function (event) {
        const target = event.target instanceof Element ? event.target : null;
        const dismiss = target ? target.closest('[data-modal-dismiss]') : null;
        if (dismiss) {
            close(dismiss.closest('.krt-modal-overlay'));
        }
    });

    /**
     * Brings a dialog's modal state in line with whether it is displayed. A code path that shows or
     * hides an overlay without this contract — an inline `style.display`, a class toggle — would
     * otherwise leave a dialog in the top layer while hidden: invisible, and the page behind it
     * still inert, so nothing on it could be clicked. The observer below makes that impossible.
     *
     * @param {HTMLDialogElement} dialog the overlay dialog
     */
    function sync(dialog) {
        const shown = isOpen(dialog);
        if (shown && !dialog.open && dialog.isConnected) {
            try {
                dialog.showModal();
            } catch (_notModal) {
                /* hidden subtree: stays a class-shown overlay */
            }
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

    // A dialog the server rendered open (a validation re-render re-shows its form with the errors)
    // carries `krtm-modal-open` from the template. This script is deferred, so the document is
    // parsed: upgrade those to real modal dialogs now, so they trap focus like any other.
    document.querySelectorAll('dialog.krt-modal-overlay.krtm-modal-open').forEach(function (d) {
        open(d);
    });
})();
