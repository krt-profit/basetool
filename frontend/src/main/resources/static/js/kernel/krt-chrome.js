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
 * The navigation drawer, the command palette and the shortcuts dialog, registered by sidebar.js,
 * krt-palette.js and nav-shortcuts.js when they initialise. Any may be absent (a bare page has no
 * drawer, an anonymous visitor no palette), so they reach each other through this registry instead
 * of importing each other.
 *
 * @type {{
 *     nav: KrtNavApi | null,
 *     palette: KrtPaletteApi | null,
 *     shortcuts: KrtShortcutsApi | null,
 * }}
 */
export const krtChrome = { nav: null, palette: null, shortcuts: null };
