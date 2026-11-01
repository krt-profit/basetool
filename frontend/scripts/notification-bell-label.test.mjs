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
 * Regression tests for the notification bell's accessible name: the unread count is part of the
 * name while the visible badge stays out of it.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({});
vm.runInContext(flattenModule('notification/notification-bell-label.js'), context);

const OPEN = 'Open notifications';
const UNREAD = 'Open notifications, {0} unread';

const bellAriaLabel = vm.runInContext('bellAriaLabel', context);
const badgeText = vm.runInContext('badgeText', context);

assert.equal(bellAriaLabel(OPEN, UNREAD, 0), OPEN, 'no count without unread notifications');
assert.equal(bellAriaLabel(OPEN, UNREAD, 3), 'Open notifications, 3 unread');
assert.equal(bellAriaLabel(OPEN, UNREAD, 99), 'Open notifications, 99 unread');
assert.equal(bellAriaLabel(OPEN, UNREAD, 100), 'Open notifications, 99+ unread');
assert.equal(badgeText(7), '7');
assert.equal(badgeText(250), '99+');

console.log('notification-bell-label: all assertions passed');
