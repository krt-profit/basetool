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
 * Regression tests for safeSameOriginUrl, the one validator between a URL read from the DOM and the
 * `action` of a form: only a same-origin absolute path passes (REQ-FE-035).
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const ORIGIN = 'https://app.example';
const context = vm.createContext({ window: { location: { origin: ORIGIN } }, URL });
vm.runInContext(flattenModule('kernel/safe-url.js'), context);
const safeSameOriginUrl = vm.runInContext('safeSameOriginUrl', context);

let failures = 0;

/** Runs one named case, recording rather than throwing so every case reports. */
function test(name, fn) {
    try {
        fn();
        console.log(`ok - ${name}`);
    } catch (e) {
        failures += 1;
        console.error(`not ok - ${name}\n${e.stack}`);
    }
}

test('a same-origin absolute path passes unchanged, markup characters included', () => {
    for (const path of ['/orders/1', '/bank?view=a&x="1"', '/a<b>c', '/p?q=%3Cscript%3E']) {
        assert.equal(safeSameOriginUrl(path), path);
        assert.equal(safeSameOriginUrl(path, '/fallback'), path);
    }
});

test('another origin, another scheme and a relative path are rejected', () => {
    for (const url of [
        '//evil.example/x',
        '/\\evil.example',
        'https://evil.example/x',
        'javascript:alert(1)',
        'data:text/html,<b>',
        'orders/1',
        '',
        '/',
        'x',
    ]) {
        assert.equal(safeSameOriginUrl(url), null, url);
        assert.equal(safeSameOriginUrl(url, '/fallback'), '/fallback', url);
    }
});

test('whitespace the URL parser would drop cannot turn a path into another origin', () => {
    for (const url of [
        '/\t/evil.example',
        '/\n/evil.example',
        '/\r/evil.example',
        '/ /evil.example',
        '/a b',
    ]) {
        assert.equal(safeSameOriginUrl(url, '/fallback'), '/fallback', JSON.stringify(url));
    }
});

test('a value that is not a string is rejected', () => {
    for (const value of [null, undefined, 42, {}, ['/a']]) {
        assert.equal(safeSameOriginUrl(value, '/fallback'), '/fallback');
    }
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}
