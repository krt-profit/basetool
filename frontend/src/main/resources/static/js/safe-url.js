// @ts-check
/*
 * Same-origin URL guard for form-action and navigation sinks.
 *
 * CodeQL's `js/xss-through-dom` rule flags every
 *   form.action = btn.getAttribute('data-action');
 *   window.location.href = someValue;
 * because the source is "DOM text reinterpreted as a sink that can execute
 * code if the scheme is javascript: / data: / vbscript:". In this codebase
 * those values come from server-rendered `data-*` attributes that
 * Thymeleaf builds from controller-known paths (`/missions/{id}/...`,
 * `/inventory/{id}/...`, etc.), but we still want defence-in-depth so a
 * future template change can't accidentally widen the attack surface.
 *
 * Available as:
 *   window.safeSameOriginUrl(url)            -> url if same-origin path, null otherwise
 *   window.safeSameOriginUrl(url, fallback)  -> url if same-origin path, fallback otherwise
 *
 * "Same-origin path" =
 *   - non-empty string
 *   - starts with a single '/'
 *   - second character is NOT '/' or '\\' (rejects protocol-relative `//host`
 *     and the Windows `\\host\share` style)
 *
 * Anything starting with a scheme (`javascript:`, `data:`, `vbscript:`,
 * `http:`, `https:`, ...) or with `#`, `?`, or `..` is rejected — every
 * controller endpoint in this app is reached via an absolute path under
 * `/`, so this is sufficient for the actual call sites.
 *
 * The explicit `startsWith('/')` check is also what CodeQL recognises as a
 * sanitizer for `js/xss-through-dom`, so wrapping the existing call sites
 * with this helper closes the alert in the Security tab.
 */
(function (root) {
    function safeSameOriginUrl(url, fallback) {
        if (typeof url !== 'string' || url.length < 2) {
            return fallback === undefined ? null : fallback;
        }
        if (url.charAt(0) !== '/') {
            return fallback === undefined ? null : fallback;
        }
        let second = url.charAt(1);
        if (second === '/' || second === '\\') {
            return fallback === undefined ? null : fallback;
        }
        return url;
    }

    root.safeSameOriginUrl = safeSameOriginUrl;
})(window);
