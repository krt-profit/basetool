/*
 * SCU / piece material-amount input normalisation and validation.
 *
 * Star-Citizen cargo amounts are entered in SCU and may be fractional
 * (cSCU = 0.01 SCU, microSCU = 0.001 SCU). A native <input type="number"> only
 * accepts the decimal separator of the *browser* locale (German Chrome takes
 * "0,01" but rejects "0.01", English Chrome the reverse), so a value typed with
 * the "wrong" separator is silently dropped. Every material-amount field is
 * therefore a plain <input type="text" inputmode="decimal" data-scu-decimal ...>
 * and this module both lets operators type EITHER "." or "," and enforces the
 * project's amount rules. The field's mode is read from its live `step` attribute,
 * which the quantity-type-aware page scripts toggle ("1" = PIECE, "0.001" = SCU):
 *
 *   - SCU (decimal) fields: a positive value (> 0) with at most three decimals,
 *     in steps of 0.001. A value typed with more decimals is rounded to three
 *     places with commercial rounding (round half up) when the field is committed
 *     (on blur) and again before its form is submitted.
 *   - PIECE (integer) fields: a positive whole number (>= 1) only - separators are
 *     stripped as they are typed.
 *
 * Positivity is enforced through the Constraint Validation API, so the browser
 * blocks the surrounding form's submit and shows the localised message from
 * window.krtScuI18n (populated in fragments/head.html). Fields that legitimately
 * accept 0 - the book-out target stock - opt out with data-scu-allow-zero.
 *
 * The backend binds Double via the locale-independent Double.valueOf (expects a
 * dot), and "normalise internally" means: the operator keeps seeing the separator
 * they typed while editing; only the value that leaves the field (on commit, on
 * submit, or via parse()) is canonicalised to a rounded dot value.
 *
 * window.krtScuInput.{normalize,parse,round} is exposed for the few inline page
 * scripts that read these fields live (book-out target<->amount sync, the AJAX
 * material claim, the handover amount check). fragments/head.html defines a
 * minimal inline stub so those stay callable even if this file is slow to load.
 */
(function (root) {
    'use strict';

    const SELECTOR = 'input[data-scu-decimal]';
    const MAX_DECIMALS = 3;

    /**
     * Canonicalises a user-entered amount to a dot-decimal string: trims, turns
     * every comma into a dot and keeps only the first dot (so "1,2.3" -> "1.23").
     * Returns "" when the input holds no digit (null, blank or a lone separator),
     * which lets the backend's @NotNull report a clean "required" error rather
     * than a number-format failure. Does NOT round - see {@link round}.
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
     * Adds 1 to a non-negative integer string, propagating the carry (so "199"
     * becomes "200" and "999" becomes "1000"). Used by {@link round} to round up
     * without ever converting to a float, which would reintroduce the precision
     * errors that make naive *1000/Math.round rounding wrong for values like
     * 1.2345.
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
     * Commercially rounds a canonical, non-negative dot string to three decimals
     * (round half up: a 4th decimal of 5..9 rounds the 3rd up). Operates purely on
     * the digit string to avoid binary-float rounding errors, then strips trailing
     * zeros ("1.2000" -> "1.2", "1.0000" -> "1"). Values with <= 3 decimals and
     * plain integers are returned unchanged.
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
     * Reduces a canonical dot string to a non-negative integer string, dropping any
     * fractional part and leading zeros ("007" -> "7", "12.9" -> "12"). PIECE fields
     * never hold a separator (the sanitiser strips it), so this normally just trims
     * leading zeros.
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
     * Parses a user-entered amount to a finite Number via {@link normalize},
     * accepting both "." and "," as the decimal separator. Returns NaN when the
     * field holds no finite number; callers already guard with `> 0` style checks.
     *
     * @param {*} raw the raw field value
     * @returns {number} the parsed amount, or NaN
     */
    function parse(raw) {
        const n = parseFloat(normalize(raw));
        return Number.isFinite(n) ? n : NaN;
    }

    /**
     * Reports whether a field is currently in integer ("Stueck"/PIECE) mode and
     * must reject decimal separators. Inferred from the live `step` attribute,
     * which the quantity-type-aware page scripts toggle between "1" (PIECE) and
     * "0.001" (SCU); a missing or "any" step counts as decimal.
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
     * Computes the field's custom validity message: "" when the (canonicalised)
     * value is acceptable, otherwise the localised positivity message. Empty fields
     * are deferred to the native `required` constraint. A value must be > 0 unless
     * the field carries data-scu-allow-zero (the book-out target stock), in which
     * case 0 is also accepted. A value that rounds down to 0 (e.g. 0.0004) is
     * rejected, because validation runs on the canonical (rounded) value.
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
            ? i18n.piece || 'Please enter a whole number greater than 0.'
            : i18n.scu || 'Please enter an amount greater than 0.';
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
     * Strips every character not allowed in the field's current mode (preserving
     * the caret) and refreshes its validity. Runs on every input event - typing,
     * paste and IME - so a stray letter, sign or separator-in-integer-mode never
     * survives in the field.
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
                } catch (_e) {
                    // setSelectionRange throws on some input types; the caret is cosmetic.
                }
            }
        }
        validate(el);
    }

    /**
     * Canonicalises a field (rounds SCU to three decimals / reduces PIECE to an
     * integer) when it loses focus, so the operator sees the value the backend will
     * receive, then refreshes its validity.
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
     * Just before the surrounding form serialises, rewrites every managed field to
     * its canonical (rounded, dot) value and re-validates it. This also catches
     * values set programmatically (e.g. the book-out target<->amount sync) that
     * never fired an input/change event; if any field is then invalid the submit is
     * blocked and the native message is shown.
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

    // Capture phase so these run before any page-level handler (the book-out sync
    // reads the value on input; the handover validator reads it on submit) - those
    // consumers then see an already-sanitised / canonical value.
    document.addEventListener('input', onInput, true);
    document.addEventListener('change', onCommit, true);
    document.addEventListener('submit', canonicaliseForm, true);

    root.krtScuInput = { normalize, parse, round };
})(window);
