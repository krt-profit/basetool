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
 * Regression tests for the merge of a reordered favourites group into the stored list of both
 * modes (REQ-UI-032).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({});
vm.runInContext(flattenModule('kernel/nav-favourite-order.js'), context);
const mergeFavouriteOrder = vm.runInContext('mergeFavouriteOrder', context);

assert.deepEqual(
    Array.from(mergeFavouriteOrder(['a', 'b', 'c'], ['c', 'a', 'b'])),
    ['c', 'a', 'b'],
    'a full permutation replaces the stored order',
);
assert.deepEqual(
    Array.from(mergeFavouriteOrder(['a', 'admin.x', 'b', 'admin.y'], ['b', 'a'])),
    ['b', 'admin.x', 'a', 'admin.y'],
    'the keys of the other mode keep their places',
);
assert.deepEqual(
    Array.from(mergeFavouriteOrder(['a', 'b'], [])),
    ['a', 'b'],
    'an empty move changes nothing',
);
console.log('nav-favourite-order tests passed');
