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
 * Regression tests for the bank forms' publish matrix (REQ-FE-015): a `data-livesync` entry whose
 * topic names a placeholder resolves it from the form that just wrote, and an unresolvable entry is
 * skipped.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const STUBS = {
    '/js/bank/bank-messages.js': 'function bankRefreshError() { return ""; }',
    '/js/kernel/krt-page-sections.js':
        'const krtPageSections = { create() { return {}; }, publish(topic, keys) { __published.push({ topic, keys }); } };',
};

const published = [];
const context = vm.createContext({ __published: published });
vm.runInContext(flattenModule('bank/bank-livesync.js', STUBS), context);
const publishBankLiveSync = vm.runInContext('publishBankLiveSync', context);

/**
 * A stand-in for a bank form.
 *
 * @param {string} spec the form's `data-livesync` value
 * @param {Record<string, { value: string, disabled?: boolean }>} fields the form's named fields
 * @returns {object} the fake form
 */
function fakeForm(spec, fields) {
    return {
        getAttribute: (name) => (name === 'data-livesync' ? spec : null),
        querySelector: (selector) => {
            const match = /^\[name="([^"]+)"\]$/.exec(selector);
            return match && fields[match[1]] ? fields[match[1]] : null;
        },
    };
}

const memberId = '11111111-2222-4333-8444-555555555555';

publishBankLiveSync(
    fakeForm('bank/grants,manage member:@user/roles', { _userId: { value: memberId } }),
);
assert.deepEqual(
    JSON.parse(JSON.stringify(published)),
    [
        { topic: 'bank', keys: ['grants', 'manage'] },
        { topic: `member:${memberId}`, keys: ['roles'] },
    ],
    'a role form pokes the bank staff room and the room of the member it named',
);

published.length = 0;
publishBankLiveSync(fakeForm('bank/grants,manage member:@user/roles', { _userId: { value: '' } }));
assert.deepEqual(
    JSON.parse(JSON.stringify(published)),
    [{ topic: 'bank', keys: ['grants', 'manage'] }],
    'a form without a picked member skips the member room',
);

console.log('bank-livesync tests passed');
