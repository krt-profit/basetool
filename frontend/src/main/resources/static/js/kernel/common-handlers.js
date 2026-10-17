import { krtModal } from '/js/kernel/krt-modal.js';
import { krtEvents } from '/js/kernel/event-delegation.js';
import { krtHooks } from '/js/kernel/krt-hooks.js';

function initCommonHandlers() {
    window.addEventListener('pageshow', (event) => {
        if (event.persisted) {
            window.location.reload();
        }
    });

    /**
     * Finds the nearest ancestor that clips its overflow.
     *
     * @param {Element} el the element whose ancestors are inspected
     * @returns {HTMLElement | null} the clipping ancestor, or null when none is below the body
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

    /**
     * Shifts a hint bubble horizontally so it stays inside the viewport and its clipping ancestor.
     *
     * @param {Element} hint the `.scu-hint` element
     */
    function positionHintBubble(hint) {
        const bubble = /** @type {HTMLElement | null} */ (hint.querySelector('.scu-hint__bubble'));
        if (!bubble) {
            return;
        }
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
            bubble.style.setProperty('--hint-shift', `${Math.round(shift)}px`);
        }
    }

    /**
     * Repositions the bubble of the hint containing `target`, if any.
     *
     * @param {Element | null} target the event target
     */
    function repositionHintFrom(target) {
        const hint = target && target.closest ? target.closest('.scu-hint') : null;
        if (hint) {
            positionHintBubble(hint);
        }
    }

    document.addEventListener('mouseover', (event) => {
        repositionHintFrom(/** @type {Element | null} */ (event.target));
    });
    document.addEventListener('focusin', (event) => {
        repositionHintFrom(/** @type {Element | null} */ (event.target));
    });

    /**
     * Publishes the sticky page header's height as `--krt-header-height` on the root element, so
     * sticky content below it can stop at its lower edge.
     */
    function trackHeaderHeight() {
        const header = document.querySelector('body > header');
        if (!header) return;
        const root = document.documentElement;
        const publish = () => {
            root.style.setProperty(
                '--krt-header-height',
                `${Math.round(header.getBoundingClientRect().height)}px`,
            );
        };
        publish();
        new ResizeObserver(publish).observe(header);
    }
    trackHeaderHeight();

    const on = krtEvents.on;

    /**
     * Whitelist for same-origin path URLs: a leading `/` not followed by `/` or a backslash, and no
     * whitespace, angle brackets, quotes or backticks; the groups capture path, search and hash.
     */
    const SAFE_PATH_REGEX = /^(\/[^/\\][^?#\s<>"'`]*)(\?[^#\s<>"'`]*)?(#[^\s<>"'`]*)?$/;

    /**
     * Navigates to a same-origin path, rebuilding the URL through the path, search and hash setters
     * of an anchor at the current origin so the scheme can never change.
     *
     * @param {string | null} raw the candidate URL, typically a `data-*` attribute value
     * @returns {boolean} `true` if navigation was triggered, `false` if the input was rejected
     */
    function navigateSafe(raw) {
        if (typeof raw !== 'string') return false;
        const match = SAFE_PATH_REGEX.exec(raw);
        if (!match) return false;
        const a = document.createElement('a');
        a.href = window.location.origin;
        a.pathname = match[1];
        if (match[2]) a.search = match[2];
        if (match[3]) a.hash = match[3];
        if (a.origin !== window.location.origin) return false;
        window.location.assign(a.href);
        return true;
    }

    /**
     * Navigates to the same-origin URL in `data-href` through `navigateSafe`.
     */
    on('click', 'navigate-href', (el, event) => {
        if (navigateSafe(el.getAttribute('data-href'))) {
            event.preventDefault();
        }
    });

    /**
     * Navigates to `data-url-template` with its `{value}` placeholder replaced by the URL-encoded
     * selected value, through `navigateSafe`.
     */
    on('change', 'navigate-select', (el) => {
        const select = /** @type {HTMLSelectElement} */ (el);
        if (!select.value) return;
        const template = select.getAttribute('data-url-template');
        if (!template) return;
        navigateSafe(template.replace('{value}', encodeURIComponent(select.value)));
    });

    /**
     * Goes back in browser history, preventing the element's own default navigation.
     */
    on('click', 'history-back', (el, event) => {
        event.preventDefault();
        window.history.back();
    });

    /**
     * Stops click propagation so a nested action does not trigger a clickable row's handler.
     */
    on('click', 'stop-propagation', (el, event) => {
        event.stopPropagation();
    });

    /**
     * Submits the control's form whenever the control changes.
     */
    on('change', 'submit-form', (el) => {
        const control = /** @type {HTMLInputElement} */ (el);
        if (control.form && typeof control.form.submit === 'function') {
            control.form.submit();
        }
    });

    /**
     * Submits the form named by `data-form-id`; bound to `click` for buttons (whose default action
     * is prevented) and to `change` for form controls.
     *
     * @param {HTMLElement} el the control carrying `data-form-id`
     * @param {Event} event the triggering event
     */
    function submitFormByIdHandler(el, event) {
        const id = el.getAttribute('data-form-id');
        if (!id) return;
        const form = /** @type {HTMLFormElement | null} */ (document.getElementById(id));
        if (!form || typeof form.submit !== 'function') return;
        if (event && typeof event.preventDefault === 'function' && el.tagName === 'BUTTON') {
            event.preventDefault();
        }
        form.submit();
    }
    on('click', 'submit-form-by-id', submitFormByIdHandler);
    on('change', 'submit-form-by-id', submitFormByIdHandler);

    /**
     * Calls the global `filterTable` with the table named by `data-table-id` and the control's
     * value; bound to `input` and `keyup`.
     *
     * @param {HTMLElement} el the filter input carrying `data-table-id`
     */
    function filterTableHandler(el) {
        if (typeof krtHooks.filterTable !== 'function') return;
        const tableId = el.getAttribute('data-table-id');
        if (!tableId) return;
        krtHooks.filterTable(tableId, /** @type {HTMLInputElement} */ (el).value);
    }
    on('input', 'filter-table', filterTableHandler);
    on('keyup', 'filter-table', filterTableHandler);

    /**
     * Toggles the `is-hidden` class on the element whose id is in `data-target`, without writing
     * an inline style.
     */
    on('click', 'toggle-display', (el, event) => {
        const id = el.getAttribute('data-target');
        if (!id) return;
        const target = document.getElementById(id);
        if (!target) return;
        event.preventDefault();
        target.classList.toggle('is-hidden');
    });

    /**
     * Opens the dialog named by `data-modal-id` through `window.krtModal.open`.
     */
    on('click', 'open-modal-display', (el, event) => {
        const id = el.getAttribute('data-modal-id');
        if (!id || !document.getElementById(id)) return;
        event.preventDefault();
        krtModal.open(id);
    });

    /**
     * Closes the dialog named by `data-modal-id` through `window.krtModal.close`.
     */
    on('click', 'close-modal-display', (el, event) => {
        const id = el.getAttribute('data-modal-id');
        if (!id || !document.getElementById(id)) return;
        event.preventDefault();
        krtModal.close(id);
    });

    revealActiveTabs();
}

/**
 * Scrolls every horizontally scrolling tab strip so its current tab is in view, without moving the
 * page itself.
 */
function revealActiveTabs() {
    const current = document.querySelectorAll(
        '.tab-nav > .active, .tab-nav > [aria-current="page"], .krt-segmented > [aria-current="page"]',
    );
    for (const tab of current) {
        const strip = tab.parentElement;
        if (!(tab instanceof HTMLElement) || !strip || strip.scrollWidth <= strip.clientWidth) {
            continue;
        }
        const tabRect = tab.getBoundingClientRect();
        const stripRect = strip.getBoundingClientRect();
        if (tabRect.left >= stripRect.left && tabRect.right <= stripRect.right) {
            continue;
        }
        strip.scrollLeft += tabRect.left - stripRect.left - (stripRect.width - tabRect.width) / 2;
    }
}

initCommonHandlers();
