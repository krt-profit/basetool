// @ts-check

/** Whitespace, which a URL parser drops (tab, line feed) or encodes: `/<tab>/host` would resolve as `//host`. */
const WHITESPACE = /\s/;

/**
 * Returns the URL when it is a same-origin absolute path.
 *
 * @overload
 * @param {unknown} url the URL to check
 * @returns {string | null} the URL, or null when it is not a same-origin path
 */
/**
 * Returns the URL when it is a same-origin absolute path, else the fallback.
 *
 * @template T
 * @overload
 * @param {unknown} url the URL to check
 * @param {T} fallback the value returned for an unsafe URL
 * @returns {string | T} the URL, or the fallback
 */
/**
 * @param {unknown} url the URL to check
 * @param {unknown} [fallback] the value returned for an unsafe URL; null when omitted
 * @returns {unknown} the URL, or the fallback
 */
export function safeSameOriginUrl(url, fallback) {
    const rejected = fallback === undefined ? null : fallback;
    if (typeof url !== 'string' || url.length < 2 || url.charAt(0) !== '/') {
        return rejected;
    }
    const second = url.charAt(1);
    if (second === '/' || second === '\\') {
        return rejected;
    }
    if (WHITESPACE.test(url)) {
        return rejected;
    }
    try {
        if (new URL(url, window.location.origin).origin !== window.location.origin) {
            return rejected;
        }
    } catch {
        return rejected;
    }
    return url;
}
