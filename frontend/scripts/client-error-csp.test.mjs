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
 * Regression tests for the client-error beacon's CSP violation report (REQ-SEC-064, REQ-FE-022):
 * the classic script runs in a vm context, a violation event is dispatched, and the posted payload
 * is read back.
 */

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const SOURCE = readFileSync(
    fileURLToPath(
        new URL('../src/main/resources/static/js/kernel/krt-client-error.js', import.meta.url),
    ),
    'utf8',
);

const PAGE_ORIGIN = 'https://profit-base.example';

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

/**
 * Loads the beacon into a fresh context, dispatches one `securitypolicyviolation` event and returns
 * the payload it posted.
 *
 * @param {Record<string, unknown>} event the violation event's fields
 * @returns {Record<string, unknown>} the posted JSON payload
 */
function reportFor(event) {
    const listeners = new Map();
    const bodies = [];
    const meta = { _csrf: 'token', _csrf_header: 'X-CSRF-TOKEN' };
    const context = {
        URL,
        JSON,
        Math,
        Date,
        Set,
        String,
        isFinite,
        Promise,
        document: {
            readyState: 'complete',
            querySelector: (selector) => {
                const name = /name="([^"]+)"/.exec(selector)[1];
                return { getAttribute: () => meta[name] };
            },
            addEventListener: (type, fn) => listeners.set(type, fn),
        },
        window: null,
    };
    context.window = {
        location: { origin: PAGE_ORIGIN },
        sessionStorage: {
            getItem: () => null,
            setItem: () => {},
        },
        addEventListener: () => {},
        fetch: (_url, init) => {
            bodies.push(JSON.parse(init.body));
            return new Promise(() => {});
        },
    };
    vm.runInNewContext(SOURCE, context);
    listeners.get('securitypolicyviolation')(event);
    assert.equal(bodies.length, 1, 'exactly one beacon');
    return bodies[0];
}

test('a trusted-types violation names the attempted policy and its code origin', () => {
    const payload = reportFor({
        effectiveDirective: 'trusted-types',
        sample: 'dompurify',
        blockedURI: 'trusted-types-policy',
        sourceFile: 'chrome-extension://abcdef/content.js',
    });
    assert.equal(payload.kind, 'csp_violation');
    assert.equal(payload.message, 'trusted-types dompurify');
    assert.equal(payload.source, 'trusted-types-policy');
    assert.equal(payload.codeOrigin, 'extension');
});

test('a duplicate of our own policy is named and attributed to the page origin', () => {
    const payload = reportFor({
        effectiveDirective: 'trusted-types',
        sample: 'krt-html',
        blockedURI: 'trusted-types-policy',
        sourceFile: `${PAGE_ORIGIN}/js/kernel/krt-html-0123abcd.js`,
    });
    assert.equal(payload.message, 'trusted-types krt-html');
    assert.equal(payload.codeOrigin, 'self');
});

test('a policy name is capped and reduced to safe characters', () => {
    const payload = reportFor({
        effectiveDirective: 'trusted-types',
        sample: `<img src=x onerror=alert(1)>/path ${'a'.repeat(80)}`,
        sourceFile: '',
    });
    const name = String(payload.message).slice('trusted-types '.length);
    assert.equal(name.length, 40);
    assert.match(name, /^[A-Za-z0-9_.#=@?-]+$/);
    assert.equal(payload.codeOrigin, 'inline');
});

test('a sink violation keeps the sink and drops the markup', () => {
    const payload = reportFor({
        effectiveDirective: 'require-trusted-types-for',
        sample: 'Element innerHTML|<b>member name</b>',
        blockedURI: 'trusted-types-sink',
        sourceFile: `${PAGE_ORIGIN}/js/bank/bank.js`,
    });
    assert.equal(payload.message, 'require-trusted-types-for Element innerHTML');
    assert.equal(payload.codeOrigin, 'self');
});

test('every extension scheme counts as an extension', () => {
    for (const file of [
        'moz-extension://uuid/inject.js',
        'safari-web-extension://uuid/inject.js',
        'webkit-masked-url://hidden/',
        'CHROME-EXTENSION://abc/x.js',
    ]) {
        const payload = reportFor({ effectiveDirective: 'style-src-elem', sourceFile: file });
        assert.equal(payload.codeOrigin, 'extension', file);
    }
});

test('a style-src-elem violation keeps only the directive and a foreign file is other', () => {
    const payload = reportFor({
        effectiveDirective: 'style-src-elem',
        sample: 'body { color: red }',
        blockedURI: 'inline',
        sourceFile: 'https://cdn.example/lib/widget.js',
    });
    assert.equal(payload.message, 'style-src-elem');
    assert.equal(payload.source, 'inline');
    assert.equal(payload.codeOrigin, 'other');
});

test('a missing or unparsable source file is inline or other, never the file itself', () => {
    assert.equal(reportFor({ effectiveDirective: 'img-src' }).codeOrigin, 'inline');
    assert.equal(
        reportFor({ effectiveDirective: 'img-src', sourceFile: 'not a url' }).codeOrigin,
        'other',
    );
});

if (failures > 0) {
    console.error(`${failures} test(s) failed`);
    process.exit(1);
}
