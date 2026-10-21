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
 * @param {string} s a canonical dot string from `normalize`
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
        scaled = `0${scaled}`;
    }
    const newInt = scaled.slice(0, scaled.length - MAX_DECIMALS);
    const newFrac = scaled.slice(scaled.length - MAX_DECIMALS).replace(/0+$/, '');
    return newFrac ? `${newInt}.${newFrac}` : newInt;
}

/**
 * Parses a user-entered amount via `normalize`, accepting "." and "," as the decimal
 * separator.
 *
 * @param {*} raw the raw field value
 * @returns {number} the parsed amount, or NaN
 */
function parse(raw) {
    const n = parseFloat(normalize(raw));
    return Number.isFinite(n) ? n : NaN;
}

export const krtScuInput = /** @type {KrtScuInputApi} */ ({ normalize, parse, round });
