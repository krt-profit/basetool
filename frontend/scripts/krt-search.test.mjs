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
 * Regression tests for the tolerant menu search: case, umlauts and their spelled-out forms, other
 * diacritics, and the hit range mapped back onto the original label (REQ-UI-026).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({});
vm.runInContext(flattenModule('kernel/krt-search.js'), context);
const fold = vm.runInContext('krtSearchFold', context);
const find = vm.runInContext('krtSearchFind', context);

const cases = [
    ['Übersicht', 'ubersicht'],
    ['Uebersicht', 'ubersicht'],
    ['Qualitätsstufen', 'qualitatsstufen'],
    ['Löschanträge', 'loschantrage'],
    ['Schließen', 'schliessen'],
    ['Café', 'cafe'],
    ['HANGAR', 'hangar'],
];
for (const [input, expected] of cases) {
    assert.equal(fold(input), expected, `fold(${input})`);
}

assert.equal(fold('Material-Übersicht'), fold('material-uebersicht'));
assert.ok(fold('Beförderungssystem').includes(fold('befoerderung')));

const hit = find('Material-Übersicht', fold('uebers'));
assert.deepEqual({ ...hit }, { start: 9, end: 14 });
assert.equal('Material-Übersicht'.slice(hit.start, hit.end), 'Übers');

const spelled = find('Uebersicht', fold('über'));
assert.equal('Uebersicht'.slice(spelled.start, spelled.end), 'Ueber');

const tail = find('Hangar', fold('gar'));
assert.equal('Hangar'.slice(tail.start, tail.end), 'gar');

const sharp = find('Schließen', fold('schliess'));
assert.equal('Schließen'.slice(sharp.start, sharp.end), 'Schließ');

assert.equal(find('Hangar', fold('lager')), null);
assert.equal(find('Hangar', ''), null);

console.log('krt-search: all assertions passed');
