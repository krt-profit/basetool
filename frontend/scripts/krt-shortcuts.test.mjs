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
 * Regression tests for the navigation shortcuts Alt+1 to Alt+9 and Alt+0: the digit row matched on
 * the physical key and nothing else, and the decision per slot for the switch, administration
 * mode, an open dialog, an empty slot and a greyed favourite (REQ-UI-035).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({});
vm.runInContext(flattenModule('kernel/krt-shortcuts.js'), context);
const shortcutSlot = vm.runInContext('shortcutSlot', context);
const decideShortcut = vm.runInContext('decideShortcut', context);
const ariaKeys = vm.runInContext('ariaKeys', context);
const keyLabel = vm.runInContext('keyLabel', context);

const alt = (code, extra = {}) => ({
    altKey: true,
    ctrlKey: false,
    metaKey: false,
    shiftKey: false,
    repeat: false,
    code,
    ...extra,
});

for (let digit = 0; digit <= 9; digit++) {
    assert.equal(shortcutSlot(alt(`Digit${digit}`)), digit, `Alt+${digit}`);
}

assert.equal(shortcutSlot(alt('Numpad1')), null, 'the numpad is Alt-code entry');
assert.equal(shortcutSlot(alt('KeyA')), null, 'letters are no shortcut');
assert.equal(shortcutSlot(alt(undefined)), null, 'a press without a code');
assert.equal(shortcutSlot(alt('Digit1', { altKey: false })), null, 'a bare digit types');
assert.equal(shortcutSlot(alt('Digit1', { ctrlKey: true })), null, 'Ctrl+Alt is AltGr');
assert.equal(shortcutSlot(alt('Digit1', { metaKey: true })), null, 'Meta+Alt');
assert.equal(shortcutSlot(alt('Digit1', { shiftKey: true })), null, 'Alt+Shift+digit');
assert.equal(shortcutSlot(alt('Digit1', { repeat: true })), null, 'auto-repeat');

const ready = {
    enabled: true,
    adminMode: false,
    dialogOpen: false,
    favourites: [true, false, true],
};
const decide = (slot, change = {}) => ({ ...decideShortcut(slot, { ...ready, ...change }) });

assert.deepEqual(decide(1), { action: 'open', slot: 1 });
assert.deepEqual(decide(3), { action: 'open', slot: 3 });
assert.deepEqual(decide(2), { action: 'unavailable', slot: 2 }, 'a greyed favourite');
assert.deepEqual(decide(4), { action: 'empty', slot: 4 }, 'no favourite in the slot');
assert.deepEqual(decide(9), { action: 'empty', slot: 9 });
assert.deepEqual(decide(0), { action: 'dashboard' });
assert.deepEqual(
    decide(0, { favourites: [] }),
    { action: 'dashboard' },
    'Alt+0 needs no favourite',
);

for (const slot of [0, 1, 2, 4]) {
    assert.deepEqual(decide(slot, { enabled: false }), { action: 'ignore' }, `switch off, ${slot}`);
    assert.deepEqual(decide(slot, { adminMode: true }), { action: 'ignore' }, `admin, ${slot}`);
    assert.deepEqual(decide(slot, { dialogOpen: true }), { action: 'ignore' }, `dialog, ${slot}`);
}

assert.equal(ariaKeys(0), 'Alt+0');
assert.equal(ariaKeys(7), 'Alt+7');
assert.equal(keyLabel(3, false), 'Alt+3');
assert.equal(keyLabel(3, true), '⌥ 3');

console.log('krt-shortcuts: all assertions passed');
