(function (root) {
    'use strict';

    const SELECTOR = 'input[data-scu-decimal]';
    const MAX_DECIMALS = 3;

    /**
     * Canonicalises a user-entered amount to a dot-decimal string: trims, turns every comma into a
     * dot and keeps only the first dot ("1,2.3" -> "1.23"). Does not round.
     *
     * @param {*} raw the raw field value (or anything String()-able)
     * @returns {string} the canonical dot value, or "" when there is no number
     */
    function normalize(raw) {
        if (raw === null || raw === undefined) {
            return '';
        }
        let s = String(raw).trim().replace(/,/g, '.');
        if (!/[0-9]/.test(s)) {
            return '';
        }
        const dot = s.indexOf('.');
        if (dot !== -1) {
            s = s.slice(0, dot + 1) + s.slice(dot + 1).replace(/\./g, '');
        }
        return s;
    }

    /**
     * Adds 1 to a non-negative integer string, propagating the carry ("999" -> "1000"), without
     * converting to a float.
     *
     * @param {string} s a string of decimal digits
     * @returns {string} the incremented digit string
     */
    function incrementDigits(s) {
        const a = s.split('');
        let i = a.length - 1;
        while (i >= 0) {
            if (a[i] === '9') {
                a[i] = '0';
                i--;
            } else {
                a[i] = String.fromCharCode(a[i].charCodeAt(0) + 1);
                break;
            }
        }
        if (i < 0) {
            a.unshift('1');
        }
        return a.join('');
    }

    /**
     * Rounds a canonical, non-negative dot string half up to three decimals on the digit string and
     * strips trailing zeros. Values with at most three decimals are returned unchanged.
     *
     * @param {string} s a canonical dot string from {@link normalize}
     * @returns {string} the value rounded to three decimals
     */
    function round(s) {
        if (!s) {
            return s;
        }
        const dot = s.indexOf('.');
        if (dot === -1) {
            return s;
        }
        const frac = s.slice(dot + 1);
        if (frac.length <= MAX_DECIMALS) {
            return s;
        }
        const intPart = s.slice(0, dot) || '0';
        let scaled = intPart + frac.slice(0, MAX_DECIMALS);
        if (frac.charCodeAt(MAX_DECIMALS) - 48 >= 5) {
            scaled = incrementDigits(scaled);
        }
        scaled = scaled.replace(/^0+(?=\d)/, '');
        while (scaled.length < MAX_DECIMALS + 1) {
            scaled = '0' + scaled;
        }
        const newInt = scaled.slice(0, scaled.length - MAX_DECIMALS);
        const newFrac = scaled.slice(scaled.length - MAX_DECIMALS).replace(/0+$/, '');
        return newFrac ? newInt + '.' + newFrac : newInt;
    }

    /**
     * Reduces a canonical dot string to a non-negative integer string, dropping any fractional part
     * and leading zeros ("007" -> "7", "12.9" -> "12").
     *
     * @param {string} s a canonical dot string from {@link normalize}
     * @returns {string} the integer string, or "" when there is no number
     */
    function toInteger(s) {
        if (!s) {
            return s;
        }
        const dot = s.indexOf('.');
        const intPart = (dot === -1 ? s : s.slice(0, dot)).replace(/^0+(?=\d)/, '');
        return intPart === '' ? '0' : intPart;
    }

    /**
     * Parses a user-entered amount via {@link normalize}, accepting "." and "," as the decimal
     * separator.
     *
     * @param {*} raw the raw field value
     * @returns {number} the parsed amount, or NaN
     */
    function parse(raw) {
        const n = parseFloat(normalize(raw));
        return Number.isFinite(n) ? n : NaN;
    }

    /**
     * Reports whether a field is in integer (PIECE) mode, inferred from an integral `step`
     * attribute; a missing or "any" step counts as decimal.
     *
     * @param {HTMLInputElement} el the amount field
     * @returns {boolean} true when only whole digits are allowed
     */
    function isIntegerMode(el) {
        const step = el.getAttribute('step');
        if (!step || step === 'any') {
            return false;
        }
        const n = parseFloat(step.replace(',', '.'));
        return Number.isInteger(n);
    }

    /**
     * The canonical value a field should hold: an integer string in PIECE mode, or
     * a dot string rounded to three decimals in SCU mode.
     *
     * @param {HTMLInputElement} el the amount field
     * @returns {string} the canonical value
     */
    function canonicalValue(el) {
        const v = normalize(el.value);
        return isIntegerMode(el) ? toInteger(v) : round(v);
    }

    /**
     * Computes the field's custom validity message from its canonical value: it must be positive, or
     * zero or more with data-scu-allow-zero. Empty fields are left to the native `required` constraint.
     *
     * @param {HTMLInputElement} el the amount field
     * @returns {string} the validity message, or "" when valid
     */
    function validityMessage(el) {
        if (normalize(el.value) === '') {
            return '';
        }
        const n = parseFloat(canonicalValue(el));
        const allowZero = el.hasAttribute('data-scu-allow-zero');
        if (Number.isFinite(n) && (allowZero ? n >= 0 : n > 0)) {
            return '';
        }
        const i18n = root.krtScuI18n || {};
        return isIntegerMode(el)
            ? window.krtI18nText(i18n.piece, 'krtScuI18n.piece')
            : window.krtI18nText(i18n.scu, 'krtScuI18n.scu');
    }

    /**
     * Refreshes a field's constraint-validation state from its current value.
     *
     * @param {HTMLInputElement} el the amount field
     */
    function validate(el) {
        el.setCustomValidity(validityMessage(el));
    }

    /**
     * Strips every character not allowed in the field's current mode, preserving the caret, and
     * refreshes its validity.
     *
     * @param {Event} e the input event
     */
    function onInput(e) {
        const el = e.target;
        if (!el || typeof el.matches !== 'function' || !el.matches(SELECTOR)) {
            return;
        }
        const disallowed = isIntegerMode(el) ? /[^0-9]/g : /[^0-9.,]/g;
        const before = el.value;
        const cleaned = before.replace(disallowed, '');
        if (cleaned !== before) {
            const caret = el.selectionStart;
            el.value = cleaned;
            if (caret !== null && caret !== undefined) {
                const pos = Math.max(0, caret - (before.length - cleaned.length));
                try {
                    el.setSelectionRange(pos, pos);
                } catch (_e) {}
            }
        }
        validate(el);
    }

    /**
     * Canonicalises a field on change (SCU rounded to three decimals, PIECE reduced to an integer)
     * and refreshes its validity.
     *
     * @param {Event} e the change event
     */
    function onCommit(e) {
        const el = e.target;
        if (!el || typeof el.matches !== 'function' || !el.matches(SELECTOR)) {
            return;
        }
        el.value = canonicalValue(el);
        validate(el);
    }

    /**
     * On submit, rewrites every managed field to its canonical value and re-validates it; an invalid
     * field blocks the submit and shows the native message.
     *
     * @param {Event} e the submit event
     */
    function canonicaliseForm(e) {
        const form = e.target;
        if (!form || typeof form.querySelectorAll !== 'function') {
            return;
        }
        let blocked = false;
        form.querySelectorAll(SELECTOR).forEach((el) => {
            el.value = canonicalValue(el);
            validate(el);
            if (!el.checkValidity()) {
                blocked = true;
            }
        });
        if (blocked) {
            e.preventDefault();
            if (typeof form.reportValidity === 'function') {
                form.reportValidity();
            }
        }
    }

    document.addEventListener('input', onInput, true);
    document.addEventListener('change', onCommit, true);
    document.addEventListener('submit', canonicaliseForm, true);

    root.krtScuInput = { normalize, parse, round };
})(window);
