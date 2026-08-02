// @ts-check
/*
 * Global HTML-escape helpers used by inline templates and other static
 * scripts to safely interpolate user/admin-controlled strings into
 * innerHTML / template literals.
 *
 * Available as:
 *   window.escapeHtml(value)   -> escapes &, <, >, ", ', /
 *   window.escapeAttr(value)   -> alias of escapeHtml; use for attribute values
 *
 * Whenever possible, prefer DOM APIs (textContent, createElement) over
 * concatenating into innerHTML; these helpers are the fallback when a
 * template literal genuinely needs to mix markup with dynamic strings.
 */
(function (root) {
    let ENTITY = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;',
        '/': '&#x2F;',
    };

    function escapeHtml(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/[&<>"'/]/g, function (c) {
            return ENTITY[c];
        });
    }

    root.escapeHtml = escapeHtml;
    root.escapeAttr = escapeHtml;
})(window);
