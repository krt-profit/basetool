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
 * Regression tests for the one read path, krtFetch.get / getJson (REQ-FE-031), and the problem
 * wording helper page-local handlers read: the transport is run in a vm context with a scripted
 * fetch and a recording location.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule, UI_STUBS } from './flatten-module.mjs';

const SOURCE = flattenModule('kernel/krt-fetch.js', {
    ...UI_STUBS,
    '/js/kernel/krt-i18n.js': 'function krtI18nText(value, key) { return value || key; }',
});

let failures = 0;

/** Runs one named async case, recording rather than throwing so every case reports. */
async function test(name, fn) {
    try {
        await fn();
        console.log(`ok - ${name}`);
    } catch (e) {
        failures += 1;
        console.error(`not ok - ${name}\n${e.stack}`);
    }
}

/**
 * Builds a response the scripted fetch hands back.
 *
 * @param {number} status the HTTP status
 * @param {string | null} body the body text
 * @param {Record<string, string>} [headers] response headers
 * @param {boolean} [redirected] whether fetch followed a redirect
 */
function reply(status, body, headers = {}, redirected = false) {
    const res = new Response(body, { status, headers });
    if (redirected) {
        Object.defineProperty(res, 'redirected', { value: true });
    }
    return res;
}

const JSON_TYPE = { 'Content-Type': 'application/json' };

/** Builds a fresh script context whose fetch answers from `script`, one entry per call. */
function harness(script = []) {
    const calls = [];
    const navigations = [];
    const storage = new Map();
    const errors = [];
    const context = {
        console: { warn() {}, log() {}, error() {} },
        Response,
        Headers,
        AbortController,
        AbortSignal,
        URL,
        Promise,
        Map,
        Error,
        ErrorEvent: class {
            constructor(type, options) {
                this.type = type;
                Object.assign(this, options);
            }
        },
        CustomEvent: class {},
        document: {
            querySelector: () => null,
            querySelectorAll: () => [],
            addEventListener() {},
            dispatchEvent() {},
            createElement: () => ({ setAttribute() {} }),
            head: { appendChild() {} },
        },
        fetch(url, init) {
            calls.push({ url, init });
            const next = script.shift();
            if (typeof next === 'function') {
                return next(init);
            }
            return Promise.resolve(next);
        },
    };
    context.window = context;
    context.dispatchEvent = (event) => {
        errors.push(event);
    };
    context.location = {
        origin: 'https://example.test',
        assign(target) {
            navigations.push(target);
        },
        reload() {},
    };
    context.window.location = context.location;
    context.window.sessionStorage = {
        getItem: (k) => (storage.has(k) ? storage.get(k) : null),
        setItem: (k, v) => storage.set(k, v),
    };
    context.window.krtFetchI18n = {
        itemRetired: 'Item retired wording',
        blueprintRetired: 'Blueprint retired wording',
    };
    vm.createContext(context);
    vm.runInContext(SOURCE, context);
    return {
        krtFetch: context.krtFetch,
        reauth: vm.runInContext('krtReauth', context),
        terms: vm.runInContext('krtTermsGate', context),
        calls,
        navigations,
        errors,
    };
}

await test('get marks the read as background traffic and sends the caller headers', async () => {
    const h = harness([reply(200, 'x')]);
    const res = await h.krtFetch.get('/a', { accept: 'text/html', headers: { 'X-One': '1' } });
    assert.equal(res.status, 200);
    const { init } = h.calls[0];
    assert.equal(init.method, 'GET');
    assert.equal(init.credentials, 'same-origin');
    assert.equal(init.headers['X-Requested-With'], 'XMLHttpRequest');
    assert.equal(init.headers.Accept, 'text/html');
    assert.equal(init.headers['X-One'], '1');
});

await test('a caller cannot drop the background marker', async () => {
    const h = harness([reply(200, 'x')]);
    await h.krtFetch.get('/a', { headers: { 'X-Requested-With': 'nope' } });
    assert.equal(h.calls[0].init.headers['X-Requested-With'], 'XMLHttpRequest');
});

await test('a 401 with X-Reauthenticate sends the browser to the login and yields null', async () => {
    const h = harness([reply(401, '{}', { ...JSON_TYPE, 'X-Reauthenticate': '/oauth2/login' })]);
    assert.equal(await h.krtFetch.get('/a'), null);
    assert.deepEqual(h.navigations, ['/oauth2/login']);
});

await test('getJson on session loss navigates to the login and rejects as refused', async () => {
    const h = harness([reply(401, '{}', { ...JSON_TYPE, 'X-Reauthenticate': '/oauth2/login' })]);
    await assert.rejects(h.krtFetch.getJson('/picker'), (e) => {
        assert.equal(e.name, 'KrtReadError');
        assert.equal(e.reason, 'refused');
        return true;
    });
    assert.deepEqual(h.navigations, ['/oauth2/login']);
});

await test('the terms gate takes over a gated read', async () => {
    const h = harness([reply(403, '', { 'X-Terms-Acceptance-Required': '/terms' })]);
    assert.equal(await h.krtFetch.get('/a'), null);
    assert.deepEqual(h.navigations, ['/terms']);
});

await test('a redirected answer is refused, not parsed', async () => {
    const h = harness([reply(200, '<html>login</html>', { 'Content-Type': 'text/html' }, true)]);
    assert.equal(await h.krtFetch.get('/a'), null);
    assert.deepEqual(h.navigations, []);
});

await test('getJson parses a 2xx JSON answer and asks for JSON', async () => {
    const h = harness([reply(200, '[1,2]', JSON_TYPE)]);
    assert.deepEqual(await h.krtFetch.getJson('/a'), [1, 2]);
    assert.equal(h.calls[0].init.headers.Accept, 'application/json');
});

await test('getJson refuses a 2xx answer that is not JSON', async () => {
    const h = harness([reply(200, '<html></html>', { 'Content-Type': 'text/html' })]);
    await assert.rejects(
        h.krtFetch.getJson('/a'),
        (e) => e.reason === 'not-json' && e.status === 200,
    );
});

await test('getJson rejects a non-2xx answer with its status and problem', async () => {
    const h = harness([reply(409, '{"code":"X"}', { 'Content-Type': 'application/problem+json' })]);
    await assert.rejects(h.krtFetch.getJson('/a'), (e) => {
        assert.equal(e.reason, 'status');
        assert.equal(e.status, 409);
        assert.equal(e.problem.code, 'X');
        return true;
    });
});

await test('getJson resolves null for a 204', async () => {
    const h = harness([reply(204, null)]);
    assert.equal(await h.krtFetch.getJson('/a'), null);
});

await test('a transport failure rejects with the browser error', async () => {
    const h = harness([() => Promise.reject(new TypeError('offline'))]);
    await assert.rejects(h.krtFetch.getJson('/a'), TypeError);
});

/** A fetch that never answers but rejects with an AbortError when its signal fires. */
function hanging(init) {
    return new Promise((_resolve, reject) => {
        init.signal.addEventListener('abort', () => {
            reject(new DOMException('aborted', 'AbortError'));
        });
    });
}

await test('a later read with the same key aborts the earlier one', async () => {
    const h = harness([hanging, reply(200, '[]', JSON_TYPE)]);
    const first = h.krtFetch.getJson('/a', { key: 'picker' });
    const second = h.krtFetch.getJson('/a', { key: 'picker' });
    await assert.rejects(first, (e) => e.name === 'AbortError');
    assert.deepEqual(await second, []);
});

await test('reads under different keys do not abort each other', async () => {
    let release;
    const h = harness([
        (init) =>
            new Promise((resolve, reject) => {
                init.signal.addEventListener('abort', () => reject(new Error('aborted')));
                release = () => resolve(reply(200, '1', JSON_TYPE));
            }),
        reply(200, '2', JSON_TYPE),
    ]);
    const first = h.krtFetch.getJson('/a', { key: 'one' });
    assert.equal(await h.krtFetch.getJson('/b', { key: 'two' }), 2);
    release();
    assert.equal(await first, 1);
});

await test('the caller signal aborts the read', async () => {
    const h = harness([hanging]);
    const controller = new AbortController();
    const read = h.krtFetch.get('/a', { signal: controller.signal });
    controller.abort();
    await assert.rejects(read, (e) => e.name === 'AbortError');
});

await test('a retired-row refusal reads its page-wide wording and no other problem does', () => {
    const h = harness();
    assert.equal(
        h.krtFetch.catalogueRetiredMessage({ code: 'CATALOGUE_ITEM_RETIRED', detail: 'backend' }),
        'Item retired wording',
    );
    assert.equal(
        h.krtFetch.catalogueRetiredMessage({ code: 'CATALOGUE_BLUEPRINT_RETIRED', detail: 'x' }),
        'Blueprint retired wording',
    );
    assert.equal(h.krtFetch.catalogueRetiredMessage({ code: 'VALIDATION_FAILED' }), null);
    assert.equal(h.krtFetch.catalogueRetiredMessage({ code: 'toString' }), null);
    assert.equal(h.krtFetch.catalogueRetiredMessage(null), null);
});

await test('unsafe login targets fall back and unsafe consent targets never navigate', async () => {
    for (const target of [
        '//outside.test/path',
        '/\\outside.test/path',
        '/\t/outside.test/path',
        ' /terms',
    ]) {
        const login = harness();
        login.reauth.redirect(target);
        assert.deepEqual(login.navigations, ['/oauth2/authorization/keycloak']);
        const consent = harness();
        consent.terms.redirect(target);
        assert.deepEqual(consent.navigations, []);
    }
});

await test('a keyed JSON read remains supersedable while its body is pending', async () => {
    let finishBody;
    const delayed = reply(200, '{}', JSON_TYPE);
    delayed.json = () =>
        new Promise((resolve) => {
            finishBody = resolve;
        });
    const h = harness([delayed, reply(200, '{"new":true}', JSON_TYPE)]);
    const first = h.krtFetch.getJson('/old', { key: 'search' });
    await Promise.resolve();
    await Promise.resolve();
    const rejected = assert.rejects(first, (error) => error.name === 'AbortError');
    const newest = await h.krtFetch.getJson('/new', { key: 'search' });
    finishBody({ old: true });
    await rejected;
    assert.deepEqual(newest, { new: true });
});

await test('failed success callbacks expose saved data and never resend the mutation', async () => {
    for (const callback of [
        () => {
            throw new Error('sync');
        },
        async () => {
            throw new Error('async');
        },
    ]) {
        const h = harness([reply(200, '{"id":1}', JSON_TYPE)]);
        const submitter = { disabled: false };
        const result = await h.krtFetch.write({
            url: '/save',
            method: 'POST',
            body: {},
            onSuccess: callback,
            submitter,
        });
        assert.equal(result.ok, true);
        assert.equal(result.refreshFailed, true);
        assert.equal(result.body.id, 1);
        assert.equal(h.calls.length, 1);
        assert.equal(h.errors.length, 1);
        assert.equal(submitter.disabled, false);
    }
});

await test('rejected error callbacks are reported and do not suppress default handling', async () => {
    const h = harness([reply(400, '{}', JSON_TYPE)]);
    const result = await h.krtFetch.write({
        url: '/save',
        body: {},
        onError: async () => {
            throw new Error('error callback');
        },
    });
    assert.equal(result.ok, false);
    assert.equal(h.errors.length, 1);
    assert.equal(h.calls.length, 1);
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}
