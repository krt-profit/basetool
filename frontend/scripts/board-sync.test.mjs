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
 * Regression tests for the dashboard board reconcile: what counts as another window's change and
 * when the page may swap its board (REQ-UI-032, REQ-UI-034).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({});
vm.runInContext(flattenModule('dashboard/board-sync.js'), context);
const readBoard = vm.runInContext('readBoard', context);
const boardSignature = vm.runInContext('boardSignature', context);
const shouldSwapBoard = vm.runInContext('shouldSwapBoard', context);

/**
 * A stand-in for an instance element.
 *
 * @param {Record<string, string>} dataset the instance's data attributes in camel case
 * @param {string} column the column the instance sits in
 * @returns {object} the fake element
 */
function item(dataset, column) {
    return {
        dataset,
        closest: () => ({ getAttribute: () => column }),
    };
}

/**
 * A stand-in for the board element.
 *
 * @param {Record<string, string>} dataset the board's data attributes
 * @param {object[]} items the instances in document order
 * @returns {object} the fake board
 */
function fakeBoard(dataset, items) {
    return { dataset, querySelectorAll: () => items };
}

const rows3 = item(
    { widgetId: 'a', widgetType: 'LOW_STOCK', optionsVersion: '1', rows: '3', available: 'true' },
    'NARROW',
);
const rows10 = item(
    { widgetId: 'a', widgetType: 'LOW_STOCK', optionsVersion: '2', rows: '10', available: 'true' },
    'NARROW',
);
const base = readBoard(fakeBoard({ version: '4', loaded: 'true' }, [rows3]));
const swapSituation = (fresh, extra = {}) => ({
    current: base,
    fresh,
    editing: false,
    writing: false,
    visible: true,
    ...extra,
});

assert.equal(
    boardSignature(base),
    boardSignature(readBoard(fakeBoard({ version: '4', loaded: 'true' }, [rows3]))),
    'the same saved state has the same signature',
);
assert.equal(
    shouldSwapBoard(swapSituation(readBoard(fakeBoard({ version: '4', loaded: 'true' }, [rows3])))),
    false,
    'an unchanged board is not swapped',
);
assert.equal(
    shouldSwapBoard(
        swapSituation(readBoard(fakeBoard({ version: '4', loaded: 'true' }, [rows10]))),
    ),
    true,
    'another window changed the rows of an instance',
);
assert.equal(
    shouldSwapBoard(swapSituation(readBoard(fakeBoard({ version: '5', loaded: 'true' }, [rows3])))),
    true,
    'another window changed the layout version',
);
assert.equal(
    shouldSwapBoard(swapSituation(readBoard(fakeBoard({ version: '4', loaded: 'true' }, [])))),
    true,
    'another window removed the instance',
);
assert.equal(
    shouldSwapBoard(
        swapSituation(
            readBoard(
                fakeBoard({ version: '4', loaded: 'true' }, [item({ ...rows3.dataset }, 'WIDE')]),
            ),
        ),
    ),
    true,
    'another window moved the instance to the other column',
);

const changed = readBoard(fakeBoard({ version: '5', loaded: 'true' }, [rows10]));
assert.equal(
    shouldSwapBoard(swapSituation(changed, { editing: true })),
    false,
    'no swap while customising',
);
assert.equal(
    shouldSwapBoard(swapSituation(changed, { writing: true })),
    false,
    'no swap during a local write',
);
assert.equal(
    shouldSwapBoard(swapSituation(changed, { visible: false })),
    false,
    'no swap in a hidden tab',
);
assert.equal(
    shouldSwapBoard(swapSituation(readBoard(fakeBoard({ version: '9', loaded: 'false' }, [])))),
    false,
    'a board that could not be read never replaces a good one',
);
console.log('board-sync tests passed');
