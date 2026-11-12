/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The item picker entries the Materialbörse offer and request dialogs share (ADR-0372).
 */

import { krtHtml } from '/js/kernel/krt-html.js';
import { localisedName } from '/js/kernel/krt-localised-name.js';

/**
 * One entry of an item picker: a blueprint product or a stocked item no blueprint produces. Exactly
 * one of the two identities is set.
 *
 * @typedef {object} ExchangeItemOption
 * @property {string | null} productKey the blueprint product, or null
 * @property {string | null} gameItemId the catalogue row, or null
 * @property {string} name the item name
 * @property {string | null} [manufacturerName] the manufacturer
 * @property {string | null} [manufacturerNameDe] the manufacturer's German name
 */

/**
 * The identity a picked entry names its item by, the body fields of an item offer or request.
 *
 * @typedef {object} ExchangeItemIdentity
 * @property {string | null} productKey the blueprint product, or null
 * @property {string | null} gameItemId the catalogue row, or null
 */

/**
 * Builds the relay URL the item pickers search.
 *
 * @param {string} query the search text; empty for the first items
 * @returns {string} the URL
 */
export function itemOptionsUrl(query) {
    return `/materialboerse/item-options${query ? `?q=${encodeURIComponent(query)}` : ''}`;
}

/**
 * Renders one picker entry with the item's identity on the element.
 *
 * @param {ExchangeItemOption} option the entry
 * @returns {KrtHtml} the option markup
 */
export function renderItemOption(option) {
    const manufacturer = localisedName(option.manufacturerName, option.manufacturerNameDe);
    return krtHtml`<li class="krt-combobox__option" role="option" data-product-key="${
        option.productKey ?? ''
    }" data-game-item-id="${option.gameItemId ?? ''}" data-name="${option.name}"><strong>${
        option.name
    }</strong>${manufacturer ? krtHtml` <small>${manufacturer}</small>` : ''}</li>`;
}

/**
 * Reads the identity a rendered entry carries.
 *
 * @param {Element} li the picked entry
 * @returns {ExchangeItemIdentity} the identity
 */
export function itemIdentityOf(li) {
    return {
        productKey: li.getAttribute('data-product-key') || null,
        gameItemId: li.getAttribute('data-game-item-id') || null,
    };
}
