// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * The tagged-template HTML builder behind the `krt-html` Trusted Types policy (ADR-0239,
 * REQ-FE-022): `krtHtml` escapes every interpolated value unless the value was itself built by
 * `krtHtml`, and `krtHtml.set` is the one way page scripts write markup into an element.
 */
(function (root) {
    'use strict';

    const POLICY_NAME = 'krt-html';

    /** @type {Record<string, string>} */
    const ENTITY = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;',
        '/': '&#x2F;',
    };

    /**
     * Escapes a value for an HTML text or quoted attribute position, like `escapeHtml`.
     *
     * @param {unknown} value the value
     * @returns {string} the escaped text
     */
    function escape(value) {
        return String(value).replace(/[&<>"'/]/g, (c) => ENTITY[c]);
    }

    /**
     * Markup built by `krtHtml` where the browser has no Trusted Types.
     */
    class KrtHtmlValue {
        /** @param {string} markup the built markup */
        constructor(markup) {
            /** @type {string} */
            this.markup = markup;
        }

        /** @returns {string} the built markup */
        toString() {
            return this.markup;
        }
    }

    const factory = root.trustedTypes;
    const policy =
        factory && typeof factory.createPolicy === 'function'
            ? factory.createPolicy(POLICY_NAME, { createHTML: (markup) => markup })
            : null;

    /** @type {WeakSet<object>} */
    const minted = new WeakSet();

    /**
     * Wraps built markup as the value the sinks accept.
     *
     * @param {string} markup markup whose every interpolation was escaped or itself minted
     * @returns {KrtHtml} a `TrustedHTML` where the browser has Trusted Types, else a wrapper
     */
    function mint(markup) {
        const value = policy ? policy.createHTML(markup) : new KrtHtmlValue(markup);
        minted.add(value);
        return /** @type {KrtHtml} */ (/** @type {unknown} */ (value));
    }

    /**
     * Whether a value was built by `krtHtml`.
     *
     * @param {unknown} value the value
     * @returns {boolean} true for a minted value
     */
    function isHtml(value) {
        return typeof value === 'object' && value !== null && minted.has(value);
    }

    /**
     * Renders one interpolated value: minted markup as is, an array element by element, null and
     * undefined as nothing, and everything else escaped.
     *
     * @param {unknown} value the interpolated value
     * @returns {string} its markup
     */
    function part(value) {
        if (value === null || value === undefined) {
            return '';
        }
        if (isHtml(value)) {
            return String(value);
        }
        if (Array.isArray(value)) {
            return value.map(part).join('');
        }
        return escape(value);
    }

    /**
     * Builds markup from a template literal, escaping every interpolated value that is not itself
     * `krtHtml` markup. Only valid as a tag: a call with a non-template array throws.
     *
     * @param {TemplateStringsArray} strings the literal's static parts
     * @param {...unknown} values the interpolated values
     * @returns {KrtHtml} the markup
     */
    function krtHtml(strings, ...values) {
        if (!Array.isArray(strings) || !Object.isFrozen(strings) || !Array.isArray(strings.raw)) {
            throw new TypeError('krtHtml is a template tag');
        }
        let markup = strings[0];
        for (let i = 0; i < values.length; i++) {
            markup += part(values[i]) + strings[i + 1];
        }
        return mint(markup);
    }

    /**
     * Joins values into one piece of markup, escaping each one and the separator that is not
     * `krtHtml` markup.
     *
     * @param {Iterable<unknown>} values the values
     * @param {unknown} [separator] what goes between two values; nothing when omitted
     * @returns {KrtHtml} the markup
     */
    function join(values, separator) {
        return mint(Array.from(values, part).join(part(separator)));
    }

    /**
     * Replaces an element's content with `krtHtml` markup, or with an array of values joined as
     * `krtHtml` interpolates one; any other value is written as text, so a raw string can never
     * reach the HTML parser.
     *
     * @param {Element | null | undefined} el the element; no-op when absent
     * @param {unknown} value the markup, an array of pieces, or a value shown as text
     */
    function set(el, value) {
        if (!el) {
            return;
        }
        const markup = Array.isArray(value) ? join(value) : value;
        if (isHtml(markup)) {
            // eslint-disable-next-line no-unsanitized/property
            el.innerHTML = /** @type {string} */ (/** @type {unknown} */ (markup));
        } else {
            el.textContent = value === null || value === undefined ? '' : String(value);
        }
    }

    krtHtml.join = join;
    krtHtml.set = set;
    krtHtml.isHtml = isHtml;
    krtHtml.policyName = POLICY_NAME;

    root.krtHtml = /** @type {KrtHtmlApi} */ (krtHtml);
})(window);
