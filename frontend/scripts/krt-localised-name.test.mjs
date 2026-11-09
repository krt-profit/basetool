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
 * Regression tests for the name a script shows for a catalogue row under the game-text language
 * policy (REQ-P4K-023).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({});
vm.runInContext(flattenModule('kernel/krt-localised-name.js'), context);

const localisedName = vm.runInContext('localisedName', context);
const shown = vm.runInContext('GAME_TEXT_LANGUAGE', context);

assert.equal(shown, 'en', 'every page shows the English game text');
assert.equal(localisedName('Aslarite', 'Aslarit', 'de'), 'Aslarite');
assert.equal(localisedName('Aslarite', 'Aslarit', 'en'), 'Aslarite');
assert.equal(localisedName(null, 'Aslarit', 'de'), '');

assert.equal(localisedName('Aslarite', 'Aslarit', 'de-DE', 'page'), 'Aslarit');
assert.equal(localisedName('Aslarite', 'Aslarit', 'en', 'page'), 'Aslarite');
assert.equal(localisedName('Aslarite', ' ', 'de', 'page'), 'Aslarite');
assert.equal(localisedName('Aslarite', null, 'de', 'page'), 'Aslarite');

console.log('krt-localised-name: all assertions passed');
