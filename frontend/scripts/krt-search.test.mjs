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
 * diacritics, and the hit range mapped back onto the original label (REQ-UI-026); the quick-access
 * prefixes and the mixed ranking of pages and server hits (REQ-UI-033).
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

const parse = vm.runInContext('krtSearchParse', context);
const plain = (value) => ({ ...value });

assert.deepEqual(plain(parse('hangar')), { kind: null, term: 'hangar', help: false });
assert.deepEqual(plain(parse('  hangar  ')), { kind: null, term: 'hangar', help: false });
assert.deepEqual(plain(parse('')), { kind: null, term: '', help: false });
assert.deepEqual(plain(parse('?')), { kind: null, term: '', help: true });
assert.deepEqual(plain(parse(' ? ')), { kind: null, term: '', help: true });
assert.deepEqual(plain(parse('?x')), { kind: null, term: '?x', help: false });

const symbols = [
    ['>neu', 'action', 'neu'],
    ['/ hangar', 'page', 'hangar'],
    ['#123', 'job_order', '123'],
    ['@Lucas', 'member', 'Lucas'],
    ['>', 'action', ''],
];
for (const [input, kind, term] of symbols) {
    assert.deepEqual(plain(parse(input)), { kind, term, help: false }, `parse(${input})`);
}

const words = [
    ['aktion: neu', 'action'],
    ['action:neu', 'action'],
    ['seite: neu', 'page'],
    ['page: neu', 'page'],
    ['auftrag: neu', 'job_order'],
    ['order: neu', 'job_order'],
    ['mitglied: neu', 'member'],
    ['member: neu', 'member'],
    ['mission: neu', 'mission'],
    ['operation: neu', 'operation'],
    ['material: neu', 'material'],
    ['blueprint: neu', 'blueprint'],
    ['schiff: neu', 'ship'],
    ['ship: neu', 'ship'],
    ['einheit: neu', 'org_unit'],
    ['unit: neu', 'org_unit'],
    ['MISSION: neu', 'mission'],
    ['Schiff:neu', 'ship'],
];
for (const [input, kind] of words) {
    assert.deepEqual(plain(parse(input)), { kind, term: 'neu', help: false }, `parse(${input})`);
}

assert.deepEqual(plain(parse('mission:')), { kind: 'mission', term: '', help: false });
assert.deepEqual(plain(parse('foo: bar')), { kind: null, term: 'foo: bar', help: false });
assert.deepEqual(plain(parse('mission control')), {
    kind: null,
    term: 'mission control',
    help: false,
});

const prefixes = vm.runInContext('KRT_SEARCH_PREFIXES', context);
assert.deepEqual(
    Array.from(prefixes, (p) => p.kind),
    [
        'action',
        'page',
        'job_order',
        'member',
        'mission',
        'operation',
        'material',
        'blueprint',
        'ship',
        'org_unit',
    ],
);
for (const p of prefixes) {
    const prefix = p.symbol || `${p.words[0]}:`;
    assert.equal(parse(`${prefix}x`).kind, p.kind, `prefix ${prefix} parses back`);
}

const score = vm.runInContext('krtSearchScore', context);
assert.equal(score(fold('Hangar'), '', fold('han')), 0);
assert.equal(score(fold('Mein Hangar'), '', fold('han')), 1);
assert.equal(score(fold('Lager'), fold('Logistik Bestand'), fold('best')), 2);
assert.equal(score(fold('Lager'), fold('Logistik'), fold('han')), -1);

const rank = vm.runInContext('krtSearchRank', context);
const item = (label, rest, fromServer) => ({
    label,
    foldLabel: fold(label),
    foldRest: fold(rest),
    fromServer,
});
const ranked = rank(
    [
        item('Mein Hangar', '', false),
        item('Lager', 'Hangar Bestand', false),
        item('Hangar-Check', '', true),
        item('Ankerpunkt', '', true),
        item('Hangar', '', false),
    ],
    fold('hangar'),
);
assert.deepEqual(
    Array.from(ranked, (r) => r.label),
    ['Hangar', 'Hangar-Check', 'Mein Hangar', 'Lager', 'Ankerpunkt'],
);
assert.equal(rank([item('Lager', '', false)], fold('hangar')).length, 0);

console.log('krt-search: all assertions passed');
