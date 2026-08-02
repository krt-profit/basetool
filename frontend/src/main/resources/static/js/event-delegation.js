// @ts-check
/*
 * Lightweight event-delegation helper.
 *
 * Replaces the historical pattern of inline HTML event handlers
 * (`onclick="myFn(this)"`, `onchange="…"`, …) with a CSP-safe alternative:
 * elements declare a logical action via `data-trigger="<actionName>"`, and
 * modules register a delegated handler via `krtEvents.on(eventType, actionName, handler)`.
 *
 * Why a single delegated listener per (eventType, actionName) rather than
 * `el.addEventListener` per node? Two reasons:
 *   1. Works out of the box for DOM elements that are added dynamically *after*
 *      DOMContentLoaded (modals, search results, paginated tables) — no need to
 *      re-bind on every render.
 *   2. Keeps the binding code in one place per module, so the CSP migration
 *      (removing `script-src-attr 'unsafe-inline'`) does not multiply churn
 *      across every template.
 *
 * Naming: actions use a short kebab-case prefix per feature to avoid collisions
 * (e.g. `pi-open-create` for personal-inventory, `members-toggle-logistician`
 * for the members admin page). The handler signature is
 * `(matchedElement, event) => void`. The matched element is the closest
 * `[data-trigger="<actionName>"]` ancestor of the event target, which mirrors
 * the `this`/`event.currentTarget` semantics that the old inline handlers had.
 *
 * The handler is not called if the matched element is disabled (form fields)
 * — that prevents the typical "click-on-disabled-button still fires" footgun
 * for buttons that get disabled while a request is in flight.
 */
(function () {
    'use strict';

    // Pick up registrations queued by the bootstrap stub in `fragments/head.html`. The
    // stub installs a tiny `window.krtEvents.on(...)` that buffers calls until this
    // file loads, so any inline `<script>` block in a body template that registers
    // handlers cannot lose its registration to a load-order race — even if this
    // script is deferred, slowed by the network or blocked by a stale browser cache.
    // See the comment in `fragments/head.html` for why the stub exists.
    let pendingQueue =
        (window.krtEvents &&
            window.krtEvents._isBootstrapStub &&
            window.krtEvents._queuedRegistrations) ||
        [];

    /**
     * Register a delegated handler.
     *
     * @param {string} eventType  - DOM event name (`click`, `change`, `input`, `submit`, …).
     * @param {string} actionName - value of the `data-trigger` attribute to match.
     * @param {(element: HTMLElement, event: Event) => void} handler
     */
    function on(eventType, actionName, handler) {
        if (typeof handler !== 'function') return;
        let selector = '[data-trigger="' + actionName + '"]';
        document.addEventListener(eventType, function (event) {
            // event.target is an EventTarget: it is only an Element for events
            // originating in the document tree, which is why the closest() probe
            // below is a capability check rather than a formality.
            let target = /** @type {Element | null} */ (event.target);
            if (!target || typeof target.closest !== 'function') return;
            let matched = /** @type {HTMLElement | null} */ (target.closest(selector));
            if (!matched) return;
            if (/** @type {HTMLInputElement} */ (matched).disabled) return;
            handler(matched, event);
        });
    }

    window.krtEvents = { on: on };

    // Drain everything that was queued before this script ran. Order is preserved so
    // a template that registered handler A then handler B for the same event sees A
    // fire first — same as if both calls had hit the real `on(...)` directly.
    for (let i = 0; i < pendingQueue.length; i++) {
        on(pendingQueue[i][0], pendingQueue[i][1], pendingQueue[i][2]);
    }

    // Watchdog: if some future change accidentally drops the head.html stub or this
    // file fails to load entirely (CSP block, 404, broken proxy), inline registration
    // calls would silently queue forever. Five seconds after load is enough for any
    // realistic deployment to settle; if the stub is still active by then the page is
    // broken and we want a console error so the operator notices instead of users
    // reporting "the dropdown does nothing" a fourth time. No-op once `on` is installed.
    if (typeof window.setTimeout === 'function') {
        window.setTimeout(function () {
            let stub = window.krtEvents;
            if (stub && stub._isBootstrapStub) {
                if (typeof console !== 'undefined' && typeof console.error === 'function') {
                    console.error(
                        '[krtEvents] event-delegation.js did not install the real handler ' +
                            'registry — ' +
                            (stub._queuedRegistrations || []).length +
                            ' registration(s) are queued but inactive. Check network/CSP/cache.',
                    );
                }
            }
        }, 5000);
    }
})();
