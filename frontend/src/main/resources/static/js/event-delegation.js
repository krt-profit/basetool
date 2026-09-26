// @ts-check
(function () {
    'use strict';

    const pendingQueue =
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
        const selector = '[data-trigger="' + actionName + '"]';
        document.addEventListener(eventType, function (event) {
            const target = /** @type {Element | null} */ (event.target);
            if (!target || typeof target.closest !== 'function') return;
            const matched = /** @type {HTMLElement | null} */ (target.closest(selector));
            if (!matched) return;
            if (/** @type {HTMLInputElement} */ (matched).disabled) return;
            handler(matched, event);
        });
    }

    window.krtEvents = { on };

    for (let i = 0; i < pendingQueue.length; i++) {
        on(pendingQueue[i][0], pendingQueue[i][1], pendingQueue[i][2]);
    }

    if (typeof window.setTimeout === 'function') {
        window.setTimeout(function () {
            const stub = window.krtEvents;
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
