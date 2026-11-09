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
 * The language a page shows the game's texts in (REQ-P4K-023): `'en'` shows the English text on
 * every page, `'page'` the German text on a German page where one exists. Mirrors
 * `LocalisedNames.SHOWN` of the server-rendered pages.
 *
 * @typedef {'en' | 'page'} GameTextLanguage
 */

/** @type {GameTextLanguage} */
export const GAME_TEXT_LANGUAGE = 'en';

/**
 * Picks the name a page shows for a catalogue row under a game-text language (REQ-P4K-023).
 * Mirrors `LocalisedNames` of the server-rendered pages; search and matching keep using the name.
 *
 * @param {string | null | undefined} name the row's name
 * @param {string | null | undefined} nameDe the row's German name
 * @param {string} [lang] the page language, the `lang` of the document by default
 * @param {GameTextLanguage} [language] the game-text language, {@link GAME_TEXT_LANGUAGE} by default
 * @returns {string} the name to show, empty when both are absent
 */
export function localisedName(
    name,
    nameDe,
    lang = document.documentElement.lang,
    language = GAME_TEXT_LANGUAGE,
) {
    const german = language === 'page' && (lang || '').toLowerCase().startsWith('de');
    if (german && nameDe && nameDe.trim() !== '') {
        return nameDe;
    }
    return name || '';
}
