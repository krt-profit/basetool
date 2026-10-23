/**
 * @typedef {ApiDto<'BlueprintRequirementModifierDto'>} BpStatModifier
 */

/**
 * @typedef {ApiDto<'BlueprintStatBaseDto'>} BpStatBase
 */

/**
 * Clamps a value to the unit interval.
 *
 * @param {number} t the value
 * @returns {number} the value between 0 and 1
 */
function clamp01(t) {
    return t < 0 ? 0 : t > 1 ? 1 : t;
}

/**
 * Linear interpolation.
 *
 * @param {number} a the value at 0
 * @param {number} b the value at 1
 * @param {number} t the position between 0 and 1
 * @returns {number} the interpolated value
 */
function lerp(a, b, t) {
    return a + (b - a) * t;
}

/**
 * Evaluates a quality modifier at a quality, over its segments or its min/max range.
 *
 * @param {BpStatModifier} m the modifier
 * @param {number} q the quality
 * @returns {number | null | undefined} the modifier value, null when the quality is out of range
 */
export function computeModifierValue(m, q) {
    const segs = m.segments || [];
    if (segs.length > 0) {
        const stepped = (m.valueRangeType || 'linear').toLowerCase() !== 'linear';
        for (let i = 0; i < segs.length; i++) {
            const a = segs[i].qualityMin;
            const b = segs[i].qualityMax;
            const vs = segs[i].modifierAtStart;
            const ve = segs[i].modifierAtEnd;
            if (a == null || b == null) {
                continue;
            }
            if (q <= b || i === segs.length - 1) {
                if (stepped) {
                    return vs == null ? ve : vs;
                }
                const t = b === a ? 0 : clamp01((q - a) / (b - a));
                return vs == null || ve == null ? vs : lerp(vs, ve, t);
            }
        }
        return null;
    }
    const qmin = m.effectiveQualityMin;
    const qmax = m.effectiveQualityMax;
    const vmin = m.modifierAtMinQuality;
    const vmax = m.modifierAtMaxQuality;
    if (qmin != null && qmax != null && vmin != null && vmax != null) {
        const tt = qmax === qmin ? 0 : clamp01((q - qmin) / (qmax - qmin));
        return lerp(vmin, vmax, tt);
    }
    return null;
}

/**
 * Indexes a recipe's stat base values by their property key.
 *
 * @param {BpStatBase[] | null | undefined} baseStats the recipe's base values
 * @returns {Map<string, BpStatBase>} the base value per property key
 */
export function indexBaseStats(baseStats) {
    /** @type {Map<string, BpStatBase>} */
    const byKey = new Map();
    (baseStats || []).forEach((s) => {
        if (s && s.propertyKey && typeof s.baseValue === 'number') {
            byKey.set(s.propertyKey, s);
        }
    });
    return byKey;
}

/**
 * The stat value a quality factor yields: the base value times the factor, for every unit alike.
 * A negative base, such as a minimum temperature, moves away from zero when the factor grows.
 *
 * @param {number} base the base value
 * @param {number | null | undefined} factor the modifier factor at the chosen quality
 * @returns {number | null} the value, null when the factor is unknown
 */
export function statValueAt(base, factor) {
    if (factor == null || !Number.isFinite(factor)) {
        return null;
    }
    return base * factor;
}

/**
 * Formats a stat number for display: whole numbers from 100, one decimal from 10, two from 1, and
 * three significant digits below.
 *
 * @param {number} value the value, a percentage already scaled to 0–100
 * @param {string} lang a BCP 47 language tag such as `de` or `en`
 * @returns {string} the formatted number
 */
export function formatStatNumber(value, lang) {
    const abs = Math.abs(value);
    /** @type {Intl.NumberFormatOptions} */
    const options =
        abs >= 100
            ? { maximumFractionDigits: 0 }
            : abs >= 10
              ? { maximumFractionDigits: 1 }
              : abs >= 1 || abs === 0
                ? { maximumFractionDigits: 2 }
                : { maximumSignificantDigits: 3 };
    return value.toLocaleString(lang || undefined, options);
}

/**
 * Formats a stat's base value and its value at a quality as „550 → 605", with the unit once at
 * the end; a percentage is shown scaled to 0–100.
 *
 * @param {BpStatBase} base the stat's base value and unit
 * @param {number | null | undefined} factor the modifier factor at the chosen quality
 * @param {string} lang a BCP 47 language tag such as `de` or `en`
 * @param {Record<string, string>} unitLabels the unit label per unit name
 * @returns {string} the text, with „?" for the value when the factor is unknown
 */
export function formatStatRange(base, factor, lang, unitLabels) {
    const baseValue = base.baseValue ?? 0;
    const scale = base.unit === 'PERCENT' ? 100 : 1;
    const at = statValueAt(baseValue, factor);
    const from = formatStatNumber(baseValue * scale, lang);
    const to = at == null ? '?' : formatStatNumber(at * scale, lang);
    const unit = base.unit && base.unit !== 'NONE' ? unitLabels[base.unit] || '' : '';
    return `${from} → ${to}${unit ? ` ${unit}` : ''}`;
}
