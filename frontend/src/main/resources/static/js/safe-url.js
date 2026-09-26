// @ts-check
(function (root) {
    function safeSameOriginUrl(url, fallback) {
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

    root.safeSameOriginUrl = safeSameOriginUrl;
})(window);
