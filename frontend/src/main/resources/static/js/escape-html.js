// @ts-check
(function (root) {
    const ENTITY = {
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
