(function () {
    'use strict';

    window.addEventListener('pageshow', function (event) {
        if (event.persisted) {
            window.location.reload();
        }
    });

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
        return;
    }
    const on = window.krtEvents.on;

    /**
     * Whitelist for same-origin path URLs: a leading `/` not followed by `/` or a backslash, and no
     * whitespace, angle brackets, quotes or backticks; the groups capture path, search and hash.
     */
    const SAFE_PATH_REGEX = /^(\/[^/\\][^?#\s<>"'`]*)(\?[^#\s<>"'`]*)?(#[^\s<>"'`]*)?$/;

    /**
     * Navigates to a same-origin path, rebuilding the URL through the path, search and hash setters
     * of an anchor at the current origin so the scheme can never change.
     *
     * @param raw  the candidate URL, typically a `data-*` attribute value
     * @return `true` if navigation was triggered, `false` if the input was rejected
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
     * Navigates to the same-origin URL in `data-href` through {@link navigateSafe}.
     */
    on('click', 'navigate-href', function (el, event) {
        if (navigateSafe(el.getAttribute('data-href'))) {
            event.preventDefault();
        }
    });

    /**
     * Navigates to `data-url-template` with its `{value}` placeholder replaced by the URL-encoded
     * selected value, through {@link navigateSafe}.
     */
    on('change', 'navigate-select', function (el) {
        if (!el.value) return;
        const template = el.getAttribute('data-url-template');
        if (!template) return;
        navigateSafe(template.replace('{value}', encodeURIComponent(el.value)));
    });

    /**
     * Goes back in browser history, preventing the element's own default navigation.
     */
    on('click', 'history-back', function (el, event) {
        event.preventDefault();
        window.history.back();
    });

    /**
     * Stops click propagation so a nested action does not trigger a clickable row's handler.
     */
    on('click', 'stop-propagation', function (el, event) {
        event.stopPropagation();
    });

    /**
     * Submits the control's form whenever the control changes.
     */
    on('change', 'submit-form', function (el) {
        if (el.form && typeof el.form.submit === 'function') {
            el.form.submit();
        }
    });

    /**
     * Submits the form named by `data-form-id`; bound to `click` for buttons (whose default action
     * is prevented) and to `change` for form controls.
     */
    function submitFormByIdHandler(el, event) {
        const id = el.getAttribute('data-form-id');
        if (!id) return;
        const form = document.getElementById(id);
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
     */
    function filterTableHandler(el) {
        if (typeof window.filterTable !== 'function') return;
        const tableId = el.getAttribute('data-table-id');
        if (!tableId) return;
        window.filterTable(tableId, el.value);
    }
    on('input', 'filter-table', filterTableHandler);
    on('keyup', 'filter-table', filterTableHandler);

    /**
     * Toggles the `krtm-hidden` class on the element whose id is in `data-target`, without writing
     * an inline style.
     */
    on('click', 'toggle-display', function (el, event) {
        const id = el.getAttribute('data-target');
        if (!id) return;
        const target = document.getElementById(id);
        if (!target) return;
        event.preventDefault();
        target.classList.toggle('krtm-hidden');
    });

    /**
     * Opens the dialog named by `data-modal-id` through `window.krtModal.open`.
     */
    on('click', 'open-modal-display', function (el, event) {
        const id = el.getAttribute('data-modal-id');
        if (!id || !document.getElementById(id)) return;
        event.preventDefault();
        window.krtModal.open(id);
    });

    /**
     * Closes the dialog named by `data-modal-id` through `window.krtModal.close`.
     */
    on('click', 'close-modal-display', function (el, event) {
        const id = el.getAttribute('data-modal-id');
        if (!id || !document.getElementById(id)) return;
        event.preventDefault();
        window.krtModal.close(id);
    });
})();
