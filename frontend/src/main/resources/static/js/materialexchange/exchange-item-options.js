/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * The item picker search the Materialbörse offer and request dialogs share (ADR-0372).
 */

import { localisedName } from '/js/kernel/krt-localised-name.js';

/** @import { ExchangePickerSource } from '/js/materialexchange/exchange-picker.js' */

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
 * The item relay search; an option is labelled with the item name and its manufacturer, and its
 * value is prefixed by the identity kind so a product key and a catalogue id never collide.
 *
 * @type {ExchangePickerSource<ExchangeItemOption>}
 */
export const exchangeItemSource = {
    url: (query) => `/materialboerse/item-options${query ? `?q=${encodeURIComponent(query)}` : ''}`,
    rows: (body) => (Array.isArray(body) ? body : []),
    value: (option) =>
        option.productKey ? `product:${option.productKey}` : `item:${option.gameItemId ?? ''}`,
    label: (option) => {
        const manufacturer = localisedName(option.manufacturerName, option.manufacturerNameDe);
        return manufacturer ? `${option.name} · ${manufacturer}` : option.name;
    },
};

/**
 * Reads the identity a picked entry carries.
 *
 * @param {ExchangeItemOption | null} option the picked entry, or null when none is picked
 * @returns {ExchangeItemIdentity} the identity; both fields null without a pick
 */
export function itemIdentityOf(option) {
    return {
        productKey: option?.productKey || null,
        gameItemId: option?.gameItemId || null,
    };
}
