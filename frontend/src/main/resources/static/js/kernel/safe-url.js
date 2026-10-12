// @ts-check

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
    if (typeof url !== 'string' || url.length < 2) {
        return fallback === undefined ? null : fallback;
    }
    if (url.charAt(0) !== '/') {
        return fallback === undefined ? null : fallback;
    }
    const second = url.charAt(1);
    if (second === '/' || second === '\\') {
        return fallback === undefined ? null : fallback;
    }
    return url;
}
