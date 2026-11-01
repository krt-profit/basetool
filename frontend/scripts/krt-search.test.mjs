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
 * prefixes and the mixed ranking of pages and server hits, the often-used decay and the empty
 * state's de-duplication (REQ-UI-033).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({});
vm.runInContext(flattenModule('kernel/krt-search.js'), context);
vm.runInContext(flattenModule('kernel/krt-frequent.js'), context);
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

const hitHref = vm.runInContext('krtSearchHitHref', context);
const memberHit = { href: '/org-chart?highlight=1', adminHref: '/members/1' };
assert.equal(hitHref(memberHit, true), '/members/1');
assert.equal(hitHref(memberHit, false), '/org-chart?highlight=1');
assert.equal(
    hitHref({ href: '/org-chart?highlight=2', adminHref: null }, true),
    '/org-chart?highlight=2',
);
assert.equal(hitHref({ href: '/orders/3' }, true), '/orders/3');
assert.equal(
    hitHref({ href: '/org-chart', adminHref: 'https://evil.example' }, true),
    '/org-chart',
);

const ranked2 = rank(
    [
        { ...item('Hangar Alpha', '', false), frequency: 0 },
        { ...item('Hangar Beta', '', false), frequency: 3 },
        { ...item('Hangar Gamma', '', false), frequency: 1 },
        { ...item('Hangar-Check', '', true), frequency: 9 },
        { ...item('Mein Hangar', '', false), frequency: 99 },
    ],
    fold('hangar'),
);
assert.deepEqual(
    Array.from(ranked2, (r) => r.label),
    ['Hangar Beta', 'Hangar Gamma', 'Hangar Alpha', 'Hangar-Check', 'Mein Hangar'],
    'a more used page leads within its match quality, a server hit stays behind pages',
);

const DAY = 24 * 60 * 60 * 1000;
const NOW = Date.UTC(2026, 10, 1);
const frequentParse = vm.runInContext('krtFrequentParse', context);
const frequentRecord = vm.runInContext('krtFrequentRecord', context);
const frequentScore = vm.runInContext('krtFrequentScore', context);
const frequentScores = vm.runInContext('krtFrequentScores', context);
const frequentTop = vm.runInContext('krtFrequentTop', context);
const emptyState = vm.runInContext('krtEmptyState', context);
const plainStore = (store) => JSON.parse(JSON.stringify(store));

assert.equal(frequentScore([NOW], NOW), 1, 'a visit right now weighs 1');
assert.equal(frequentScore([NOW - 15 * DAY], NOW), 0.5, 'half the window leaves half the weight');
assert.equal(frequentScore([NOW - 30 * DAY], NOW), 0, 'a 30-day-old visit weighs nothing');
assert.equal(frequentScore([NOW - 45 * DAY], NOW), 0, 'an older visit weighs nothing');
assert.equal(frequentScore([NOW + DAY], NOW), 1, 'a visit in the future is clamped to 1');
assert.equal(frequentScore([NOW, NOW - 15 * DAY], NOW), 1.5, 'visits add up');

assert.deepEqual(plainStore(frequentRecord({}, 'a', NOW)), { a: [NOW] });
assert.deepEqual(plainStore(frequentRecord({ a: [NOW - DAY] }, 'a', NOW)), { a: [NOW - DAY, NOW] });
const decayed = frequentRecord({ gone: [NOW - 31 * DAY], kept: [NOW - 29 * DAY] }, 'x', NOW);
assert.deepEqual(
    Object.keys(plainStore(decayed)).sort(),
    ['kept', 'x'],
    'dead entries are dropped',
);
const before = { a: [NOW - DAY] };
frequentRecord(before, 'a', NOW);
assert.deepEqual(plainStore(before), { a: [NOW - DAY] }, 'recording leaves its argument alone');
let busy = {};
for (let i = 0; i < 50; i++) busy = frequentRecord(busy, 'busy', NOW - (50 - i) * 1000);
assert.equal(busy.busy.length, 30, 'visits per entry are capped');
let crowded = { strong: [NOW - 2000, NOW - 1000, NOW] };
for (let i = 0; i < 205; i++) crowded = frequentRecord(crowded, `k${i}`, NOW);
assert.equal(Object.keys(crowded).length, 200, 'entries are capped');
assert.ok('strong' in crowded, 'the weakest entries go first');

assert.deepEqual(plainStore(frequentParse(null)), {});
assert.deepEqual(plainStore(frequentParse('not json')), {});
assert.deepEqual(plainStore(frequentParse('[1,2]')), {});
assert.deepEqual(plainStore(frequentParse('{"a":[3,"x",1],"b":"no","c":[]}')), { a: [1, 3] });

const scores = frequentScores({ a: [NOW], b: [NOW - 40 * DAY] }, NOW);
assert.deepEqual(Array.from(scores.keys()), ['a'], 'a fully decayed entry has no score');

const used = {
    twice: [NOW - DAY, NOW],
    steady: [NOW - 2 * DAY, NOW - DAY, NOW],
    often: [NOW, NOW - 1000, NOW - 2000],
    oldOften: [NOW - 25 * DAY, NOW - 26 * DAY, NOW - 27 * DAY],
    once: [NOW],
};
assert.deepEqual(
    Array.from(frequentTop(used, NOW)),
    ['often', 'steady'],
    'only entries above the minimum score count, best first; decayed ones fall out',
);

const limits = { frequent: 2, recent: 2 };
const state = emptyState(
    {
        favourites: ['fav', 'both', 'fav'],
        frequent: ['both', 'f1', 'f2', 'f3'],
        recent: ['fav', 'f1', 'r1', 'r2', 'r3'],
    },
    limits,
);
assert.deepEqual(plainStore(state), {
    favourites: ['fav', 'both'],
    frequent: ['f1', 'f2'],
    recent: ['r1', 'r2'],
});
assert.deepEqual(
    plainStore(emptyState({ favourites: [], frequent: ['a', 'a'], recent: ['a', 'b'] }, limits)),
    { favourites: [], frequent: ['a'], recent: ['b'] },
    'an entry appears once, in the highest section',
);
assert.deepEqual(plainStore(emptyState({ favourites: [], frequent: [], recent: [] }, limits)), {
    favourites: [],
    frequent: [],
    recent: [],
});

console.log('krt-search: all assertions passed');
