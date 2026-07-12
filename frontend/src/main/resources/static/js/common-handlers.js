/*
 * Shared CSP-safe event handlers — registered globally so every template can
 * declare a logical action via `data-trigger="<name>"` instead of an inline
 * `onclick="…"` (which would require the dropped `script-src-attr 'unsafe-inline'`
 * CSP allowance).
 *
 * Pattern: each entry pairs a short, kebab-case action name with a function that
 * receives the matched element and the original DOM event. State (URL templates,
 * form ids, table ids, …) comes from `data-*` attributes on the element itself
 * so the JS stays declarative and free of hard-coded selectors.
 *
 * Naming: the prefix is left off for actions that are genuinely page-agnostic
 * ("navigate-href", "close-modal"). Page-specific actions still live in
 * dedicated `<template>.js` modules with their own short prefix
 * (e.g. `pi-` for personal-inventory) so a refactor of one page cannot trip a
 * collision across the codebase.
 *
 * It also hosts the one global page-lifecycle handler the app needs (bfcache
 * restore — see below); that listener is registered independently of the
 * `data-trigger` registry so it survives even a missing `krtEvents`.
 */
(function () {
    'use strict';

    /*
     * Force a fresh server render when the browser restores this document from its
     * back/forward cache (bfcache). A bfcache restore reinstates the in-memory DOM
     * snapshot taken when the user navigated away — NOT a fresh GET — so any
     * server-rendered aggregate baked into that snapshot (a bank account-card
     * balance, a list count, a status pill) still shows whatever it was BEFORE the
     * user's edit on the page they had navigated forward to. The in-place mutation
     * foundation (`krtFetch`, REQ-FE-001..007) only keeps the *active* document
     * fresh; it cannot touch a sibling document the browser later replays from
     * bfcache. Reloading on `event.persisted` re-runs the GET so the restored
     * overview reflects current state. This is the second — and only other —
     * sanctioned reload beside the optimistic-lock conflict confirm (spec
     * REQ-FE-008, ADR-0013).
     *
     * It cannot loop: the document the reload produces is a fresh load, whose own
     * `pageshow` fires with `persisted === false`, so the reload only ever fires for
     * a genuine bfcache restore, never on the page it itself produced. Registered
     * before the `krtEvents` guard below so it is unaffected by the delegation
     * registry's presence.
     */
    window.addEventListener('pageshow', function (event) {
        if (event.persisted) {
            window.location.reload();
        }
    });

    /*
     * Keep a "?" field-hint tooltip (fragments/scu-hint) inside its clipping container. The bubble is
     * a pure-CSS tooltip centred on its disc; inside a scrolling `.krt-modal-body` (overflow-y: auto,
     * which per the CSS overflow spec also computes overflow-x to auto) it is clipped horizontally, so
     * a disc near the container's left/right edge had its tooltip text cut off. On hover/focus of a
     * `.scu-hint` we measure the bubble against its nearest overflow-clipping ancestor (or the
     * viewport) and, if it would overflow, set `--hint-shift` to slide it back inside; the CSS bubble
     * transform and its arrow honour that variable (the arrow counter-shifts so it still points at the
     * disc). Registered before the `krtEvents` guard so it works even without the delegation registry.
     */
    function hintClippingAncestor(el) {
        let node = el.parentElement;
        while (node && node !== document.body && node !== document.documentElement) {
            const style = window.getComputedStyle(node);
            if (style.overflowX !== 'visible' || style.overflowY !== 'visible') {
                return node;
            }
            node = node.parentElement;
        }
        return null;
    }

    function positionHintBubble(hint) {
        const bubble = hint.querySelector('.scu-hint__bubble');
        if (!bubble) {
            return;
        }
        // Reset before measuring so the rect reflects the un-shifted (centred) position.
        bubble.style.setProperty('--hint-shift', '0px');
        const rect = bubble.getBoundingClientRect();
        const pad = 6;
        let minX = pad;
        let maxX = document.documentElement.clientWidth - pad;
        const clip = hintClippingAncestor(hint);
        if (clip) {
            const cr = clip.getBoundingClientRect();
            minX = cr.left + pad;
            maxX = cr.right - pad;
        }
        let shift = 0;
        if (rect.left < minX) {
            shift = minX - rect.left;
        } else if (rect.right > maxX) {
            shift = maxX - rect.right;
        }
        if (shift !== 0) {
            bubble.style.setProperty('--hint-shift', Math.round(shift) + 'px');
        }
    }

    function repositionHintFrom(target) {
        const hint = target && target.closest ? target.closest('.scu-hint') : null;
        if (hint) {
            positionHintBubble(hint);
        }
    }

    document.addEventListener('mouseover', function (event) {
        repositionHintFrom(event.target);
    });
    document.addEventListener('focusin', function (event) {
        repositionHintFrom(event.target);
    });

    if (!window.krtEvents || typeof window.krtEvents.on !== 'function') {
        // event-delegation.js has not loaded — abort silently. Production fragments/head.html
        // wires event-delegation.js BEFORE this file, so the guard only fires if a test slice
        // bypasses the fragment.
        return;
    }
    let on = window.krtEvents.on;

    /**
     * Strict whitelist regex for same-origin path URLs. Matches a string that starts with
     * {@code '/'}, whose second character is neither {@code '/'} nor {@code '\\'} (rejects
     * protocol-relative {@code //attacker} and Windows UNC {@code /\\share}), and whose
     * remainder contains no whitespace, angle brackets, quotes, or backticks (HTML/JS
     * meta-characters). Capture groups split the input into path / search / hash so each part
     * can be assigned to the corresponding {@link HTMLAnchorElement} setter, which validates
     * its argument structurally and cannot be tricked into changing the URL's scheme.
     */
    let SAFE_PATH_REGEX = /^(\/[^/\\][^?#\s<>"'`]*)(\?[^#\s<>"'`]*)?(#[^\s<>"'`]*)?$/;

    /**
     * Navigate to a same-origin path safely. Sets {@code pathname} / {@code search} /
     * {@code hash} on a freshly-created {@code <a>} anchored at the current origin, then reads
     * back the validated {@code href} for navigation. The {@link HTMLAnchorElement.pathname},
     * {@link HTMLAnchorElement.search}, and {@link HTMLAnchorElement.hash} setters are
     * structurally narrower than {@code location.href}: they can only mutate their respective
     * URL components, so even if the regex check were somehow bypassed they could never change
     * the scheme to {@code javascript:} (the structural-safety pattern from the CodeQL
     * {@code js/xss-through-dom} docs — analogue of {@code $.find} versus {@code $()}).
     *
     * @param raw  the candidate URL string (typically a {@code data-*} attribute value)
     * @return {@code true} if navigation was triggered, {@code false} if the input was
     *     rejected (caller may then leave the default-action in place)
     */
    function navigateSafe(raw) {
        if (typeof raw !== 'string') return false;
        let match = SAFE_PATH_REGEX.exec(raw);
        if (!match) return false;
        let a = document.createElement('a');
        a.href = window.location.origin;
        a.pathname = match[1];
        if (match[2]) a.search = match[2];
        if (match[3]) a.hash = match[3];
        if (a.origin !== window.location.origin) return false;
        window.location.assign(a.href);
        return true;
    }

    /**
     * Navigate to the URL in {@code data-href}. Delegated to {@link navigateSafe} so the
     * actual navigation sink only ever receives a URL re-built from the structural
     * {@link HTMLAnchorElement} setters.
     */
    on('click', 'navigate-href', function (el, event) {
        if (navigateSafe(el.getAttribute('data-href'))) {
            event.preventDefault();
        }
    });

    /**
     * Navigate to a URL templated against the selected value. Element declares
     * {@code data-url-template} containing a {@code {value}} placeholder; the placeholder is
     * substituted with the input's URL-encoded current value, then handed to
     * {@link navigateSafe} for the structural same-origin guard.
     */
    on('change', 'navigate-select', function (el) {
        if (!el.value) return;
        let template = el.getAttribute('data-url-template');
        if (!template) return;
        navigateSafe(template.replace('{value}', encodeURIComponent(el.value)));
    });

    /**
     * Browser-history back. {@code preventDefault} so the surrounding {@code <a>} with a real
     * href does not also navigate forward — this keeps the "go back" behaviour identical to
     * the historical {@code onclick="history.back(); return false;"} pattern.
     */
    on('click', 'history-back', function (el, event) {
        event.preventDefault();
        window.history.back();
    });

    /**
     * Pure {@link Event#stopPropagation}. Used on inner buttons / forms inside clickable
     * container rows so a nested action does not bubble up to the row's own click handler.
     */
    on('click', 'stop-propagation', function (el, event) {
        event.stopPropagation();
    });

    /**
     * Submit the closest surrounding {@code <form>} whenever the bound input changes. Mirrors
     * the historical {@code onchange="this.form.submit()"} pattern used by filter dropdowns
     * and auto-applying selects.
     */
    on('change', 'submit-form', function (el) {
        if (el.form && typeof el.form.submit === 'function') {
            el.form.submit();
        }
    });

    /**
     * Submit a form by id. Element declares {@code data-form-id} pointing at the form's id.
     * Used by buttons placed outside the form (e.g. in a toolbar) that need to trigger one of
     * the inline forms on the page. Registered on {@code click} (for buttons) and {@code
     * change} (for filter-checkbox / filter-select patterns that historically used
     * {@code onchange="getElementById('…').submit()"}). The duplicate registration is safe
     * because the two event types fire on disjoint sets of elements in practice — buttons
     * fire {@code click}, form controls fire {@code change} (the {@code click} that a mouse
     * also fires on a checkbox is suppressed via {@code event.preventDefault()} below, which
     * leaves the {@code change} handler as the single submit trigger).
     */
    function submitFormByIdHandler(el, event) {
        let id = el.getAttribute('data-form-id');
        if (!id) return;
        let form = document.getElementById(id);
        if (!form || typeof form.submit !== 'function') return;
        if (event && typeof event.preventDefault === 'function' && el.tagName === 'BUTTON') {
            event.preventDefault();
        }
        form.submit();
    }
    on('click', 'submit-form-by-id', submitFormByIdHandler);
    on('change', 'submit-form-by-id', submitFormByIdHandler);

    /**
     * Call the global {@code filterTable(tableId, query)} helper on every keystroke. Element
     * declares {@code data-table-id} naming the target table. Bound to {@code input} (covers
     * paste / autofill) and {@code keyup} (legacy keyboard-only flows).
     */
    function filterTableHandler(el) {
        if (typeof window.filterTable !== 'function') return;
        let tableId = el.getAttribute('data-table-id');
        if (!tableId) return;
        window.filterTable(tableId, el.value);
    }
    on('input', 'filter-table', filterTableHandler);
    on('keyup', 'filter-table', filterTableHandler);

    /**
     * Open a modal by id. Element declares {@code data-modal-id} naming the modal's
     * {@code id}; the modal element receives the {@code .active} class (matches the KRT modal
     * convention from {@code fragments/toast.html} / {@code personal-inventory.html}).
     */
    on('click', 'open-modal', function (el, event) {
        let id = el.getAttribute('data-modal-id');
        if (!id) return;
        let modal = document.getElementById(id);
        if (!modal) return;
        event.preventDefault();
        modal.classList.add('active');
    });

    /**
     * Close a modal. {@code data-modal-id} explicitly names the modal to close; falling back
     * to the nearest {@code .modal-overlay} or {@code .modal} ancestor when omitted (covers
     * close-buttons living inside the modal itself).
     */
    on('click', 'close-modal', function (el, event) {
        event.preventDefault();
        let id = el.getAttribute('data-modal-id');
        if (id) {
            let modal = document.getElementById(id);
            if (modal) modal.classList.remove('active');
            return;
        }
        let ancestor = el.closest('.modal-overlay, .modal, .modal-box');
        if (!ancestor) return;
        // `.modal-box` is the inner element — climb one level to the overlay if needed.
        if (ancestor.classList.contains('modal-box')) {
            ancestor = ancestor.closest('.modal-overlay, .modal') || ancestor;
        }
        ancestor.classList.remove('active');
    });

    /**
     * Toggle a target element's visibility via the {@code krtm-hidden} class ({@code display:none}
     * in inline-migration.css). {@code data-target} carries the target element's id. Migrated from
     * the former inline {@code style.display} toggle so no inline style attribute is written (CSP:
     * {@code style-src-attr} no longer needs {@code 'unsafe-inline'}). Used for collapsible
     * info-boxes and "show details" reveals; the shown state is the element's own CSS display.
     */
    on('click', 'toggle-display', function (el, event) {
        let id = el.getAttribute('data-target');
        if (!id) return;
        let target = document.getElementById(id);
        if (!target) return;
        event.preventDefault();
        target.classList.toggle('krtm-hidden');
    });

    /**
     * Set a target element's visibility from {@code data-display} via classes instead of an inline
     * {@code style.display} (CSP migration). {@code none} hides it ({@code krtm-hidden}); {@code
     * flex} shows it as a flex modal ({@code krtm-modal-open}); any other/empty value clears both so
     * the element falls back to its own CSS display. Legacy trigger predating the {@code .active}
     * modal convention.
     */
    on('click', 'set-display', function (el, event) {
        let id = el.getAttribute('data-target');
        if (!id) return;
        let target = document.getElementById(id);
        if (!target) return;
        event.preventDefault();
        let dv = el.getAttribute('data-display') || '';
        if (dv === 'none') {
            target.classList.add('krtm-hidden');
            target.classList.remove('krtm-modal-open');
        } else if (dv === 'flex') {
            target.classList.add('krtm-modal-open');
            target.classList.remove('krtm-hidden');
        } else {
            target.classList.remove('krtm-hidden');
            target.classList.remove('krtm-modal-open');
        }
    });

    /**
     * "Open this modal" for the display-based modal pattern, migrated to classes (CSP): adds {@code
     * krtm-modal-open} ({@code display:flex}) and clears {@code krtm-hidden}. Works for both modal
     * families because inline-migration.css is loaded last — {@code krtm-modal-open} wins the
     * default-none {@code .krt-modal-overlay} and clearing {@code krtm-hidden} un-hides the
     * default-flex {@code .modal}. {@code data-modal-id} carries the modal's id.
     *
     * <p>Also clears any stale inline {@code style.display} on the modal: an inline display
     * declaration outranks the {@code krtm-modal-open} class rule, so a modal that some page script
     * had closed by writing {@code modal.style.display = 'none'} (rather than via the class toggle)
     * could otherwise never be re-opened without a full page reload. Removing the inline property lets
     * the class win again, hardening this open path against that recurring footgun regardless of how
     * the modal was last closed.
     */
    on('click', 'open-modal-display', function (el, event) {
        let id = el.getAttribute('data-modal-id');
        if (!id) return;
        let modal = document.getElementById(id);
        if (!modal) return;
        event.preventDefault();
        modal.style.removeProperty('display');
        modal.classList.add('krtm-modal-open');
        modal.classList.remove('krtm-hidden');
    });

    /**
     * "Close this modal" for the display-based modal pattern, migrated to classes (CSP): removes
     * {@code krtm-modal-open} and adds {@code krtm-hidden} ({@code display:none}). Adding
     * {@code krtm-hidden} is required to hide the default-flex {@code .modal} family and is a
     * harmless no-op over the default-none {@code .krt-modal-overlay} family.
     *
     * <p>Symmetric with {@code open-modal-display}, it first clears any inline {@code style.display}
     * on the modal. A modal that a page script had OPENED by writing {@code
     * modal.style.display = 'flex'} (rather than via the class) carries an inline {@code display} that
     * outranks the {@code krtm-hidden} class rule — so without this the class-only close would leave
     * the modal on screen (the {@code delete-operation-modal} Cancel button did exactly that: it
     * opens via an inline flex in the page script yet closes only through this trigger). Removing the
     * inline property lets {@code krtm-hidden} take effect regardless of how the modal was opened.
     */
    on('click', 'close-modal-display', function (el, event) {
        let id = el.getAttribute('data-modal-id');
        if (!id) return;
        let modal = document.getElementById(id);
        if (!modal) return;
        event.preventDefault();
        modal.style.removeProperty('display');
        modal.classList.remove('krtm-modal-open');
        modal.classList.add('krtm-hidden');
    });
})();
