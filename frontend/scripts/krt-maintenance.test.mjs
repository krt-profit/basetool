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
 * Regression tests for the client side of the restart window (REQ-FE-040): the maintenance banner
 * and its probe, the transport's calls into it, and the error toast it silences; and of a release that
 * replaced the page's version, which the banner offers to reload (REQ-FE-042). Each part runs in
 * a vm context with a fake page, a scripted fetch and a recording clock.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule, UI_STUBS } from './flatten-module.mjs';

const MODULE_SOURCE = flattenModule('kernel/krt-maintenance.js', {
    '/js/kernel/krt-html.js':
        'function krtHtml(strings, ...values) { return { strings: [...strings], values }; }\nkrtHtml.set = (el, markup) => { el.markup = markup; };',
    '/js/kernel/krt-i18n.js': 'function krtI18nText(value, key) { return value || key; }',
    '/js/kernel/krt-modal.js': 'const krtModal = { layerRoot() { return window.layer; } };',
});

const RECORDING_MAINTENANCE =
    'const krtMaintenance = { calls: [], enter() { this.calls.push("enter"); }, observe(response, problem) { this.calls.push(["observe", response.status, problem]); return false; }, isActive() { return false; }, isMaintenanceResponse() { return false; } };';

const FETCH_SOURCE = flattenModule('kernel/krt-fetch.js', {
    ...UI_STUBS,
    '/js/kernel/krt-i18n.js': 'function krtI18nText(value, key) { return value || key; }',
    '/js/kernel/krt-maintenance.js': RECORDING_MAINTENANCE,
});

const TOAST_SOURCE = flattenModule('kernel/toast.js', {
    '/js/kernel/krt-modal.js': 'const krtModal = { layerRoot() { return document.body; } };',
    '/js/kernel/krt-maintenance.js':
        'const krtMaintenance = { active: true, isActive() { return this.active; } };',
});

const MAINTENANCE_HEADERS = { 'X-Basetool-Maintenance': '1' };

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
 * Builds a page with a fake body, a scripted fetch and a recording clock, and loads the module.
 *
 * @param {Array<unknown>} answers what each fetch answers, a Response or a function returning one
 * @param {{pageVersion?: string}} [options] the version the page names in its meta tag, if any
 */
function page(answers = [], options = {}) {
    const timers = [];
    const elements = new Map();
    const body = {
        children: [],
        appendChild(el) {
            this.children = this.children.filter((c) => c !== el);
            this.children.push(el);
            elements.set(el.id, el);
        },
    };
    const createElement = (tag) => {
        const button = {
            listeners: {},
            addEventListener(type, fn) {
                this.listeners[type] = fn;
            },
        };
        const reloadButton = {
            listeners: {},
            addEventListener(type, fn) {
                this.listeners[type] = fn;
            },
        };
        return {
            tag,
            id: '',
            className: '',
            dataset: {},
            attributes: {},
            markup: null,
            button,
            reloadButton,
            setAttribute(name, value) {
                this.attributes[name] = value;
            },
            querySelector(selector) {
                if (selector === '.krt-maintenance-banner__close') {
                    return button;
                }
                if (selector === '.krt-maintenance-banner__reload') {
                    return this.dataset.state === 'updated' ? reloadButton : null;
                }
                return null;
            },
            remove() {
                elements.delete(this.id);
                body.children = body.children.filter((c) => c !== this);
            },
        };
    };
    const fetches = [];
    const math = Object.create(Math);
    math.random = () => 0.5;
    const context = {
        console,
        Math: math,
        Promise,
        Response,
        Headers,
        document: {
            getElementById: (id) => elements.get(id) || null,
            createElement,
            querySelector: (selector) =>
                selector === 'meta[name="krt-app-version"]' && options.pageVersion
                    ? { getAttribute: () => options.pageVersion }
                    : null,
        },
        location: {
            reloads: 0,
            reload() {
                this.reloads += 1;
            },
        },
        fetch(url, init) {
            fetches.push({ url, init });
            const next = answers.shift();
            return typeof next === 'function' ? next() : Promise.resolve(next);
        },
    };
    context.window = context;
    context.layer = body;
    context.krtMaintenanceI18n = {
        unreachable: 'DOWN',
        back: 'BACK',
        dismiss: 'CLOSE',
        updated: 'UPDATED',
        reload: 'RELOAD',
    };
    context.setTimeout = (fn, ms) => {
        const timer = { fn, ms, live: true };
        timers.push(timer);
        return timer;
    };
    context.clearTimeout = (timer) => {
        if (timer) {
            timer.live = false;
        }
    };
    vm.createContext(context);
    vm.runInContext(MODULE_SOURCE, context);
    return {
        maintenance: vm.runInContext('krtMaintenance', context),
        banner: () => elements.get('krt-maintenance-banner') || null,
        live: () => timers.filter((t) => t.live),
        fetches,
        reloads: () => context.location.reloads,
        /** Fires a timer and waits for the work it started. */
        async fire(timer) {
            timer.live = false;
            await timer.fn();
        },
    };
}

/** Builds a response with the given status and headers. */
function reply(status, headers = {}, body = null) {
    return new Response(body, { status, headers });
}

await test('a 502, a 504 and a 503 with the edge header or problem are maintenance answers', () => {
    const { maintenance } = page();
    assert.equal(maintenance.isMaintenanceResponse(reply(502)), true);
    assert.equal(maintenance.isMaintenanceResponse(reply(504)), true);
    assert.equal(maintenance.isMaintenanceResponse(reply(503, MAINTENANCE_HEADERS)), true);
    assert.equal(
        maintenance.isMaintenanceResponse(reply(503), {
            type: 'https://profit-base.online/problems/maintenance',
        }),
        true,
    );
});

await test('a plain 503, a 500 and a 200 are not', () => {
    const { maintenance } = page();
    assert.equal(maintenance.isMaintenanceResponse(reply(503), { type: 'about:blank' }), false);
    assert.equal(maintenance.isMaintenanceResponse(reply(500)), false);
    assert.equal(maintenance.isMaintenanceResponse(reply(200, MAINTENANCE_HEADERS)), false);
});

await test('entering shows the unreachable banner and schedules a jittered probe', () => {
    const p = page();
    p.maintenance.enter();

    const banner = p.banner();
    assert.ok(banner, 'the banner is on the layer');
    assert.equal(banner.dataset.state, 'down');
    assert.equal(banner.attributes.role, 'status');
    assert.equal(banner.attributes['aria-live'], 'polite');
    assert.ok(banner.markup.values.includes('DOWN'));
    assert.ok(banner.markup.values.includes('CLOSE'));
    assert.equal(p.maintenance.isActive(), true);
    const [probe] = p.live();
    assert.ok(probe.ms >= 5000 && probe.ms < 7500, `probe delay ${probe.ms}`);
});

await test('entering twice keeps one banner and one pending probe', () => {
    const p = page();
    p.maintenance.enter();
    p.maintenance.enter();

    assert.equal(p.live().length, 1);
});

await test('the probe asks the favicon without a cookie and keeps waiting on a maintenance answer', async () => {
    const p = page([reply(503, MAINTENANCE_HEADERS)]);
    p.maintenance.enter();

    await p.fire(p.live()[0]);

    assert.equal(p.fetches.length, 1);
    assert.equal(p.fetches[0].url, '/favicon.ico');
    assert.equal(p.fetches[0].init.method, 'HEAD');
    assert.equal(p.fetches[0].init.credentials, 'omit');
    assert.equal(p.fetches[0].init.cache, 'no-store');
    assert.equal(p.maintenance.isActive(), true);
    assert.equal(p.banner().dataset.state, 'down');
    assert.equal(p.live().length, 1, 'the next probe is scheduled');
});

await test('a failed probe keeps waiting', async () => {
    const p = page([() => Promise.reject(new TypeError('Failed to fetch'))]);
    p.maintenance.enter();

    await p.fire(p.live()[0]);

    assert.equal(p.maintenance.isActive(), true);
    assert.equal(p.live().length, 1);
});

await test('an answering probe says the tool is back, then hides the banner', async () => {
    const p = page([reply(200)]);
    p.maintenance.enter();

    await p.fire(p.live()[0]);

    assert.equal(p.maintenance.isActive(), false);
    assert.equal(p.banner().dataset.state, 'back');
    assert.ok(p.banner().markup.values.includes('BACK'));
    const [hide] = p.live();
    assert.equal(hide.ms, 8000);
    await p.fire(hide);
    assert.equal(p.banner(), null);
});

await test('any regular answer of the transport ends the mode at once', () => {
    const p = page();
    p.maintenance.enter();

    assert.equal(p.maintenance.observe(reply(409)), false);

    assert.equal(p.maintenance.isActive(), false);
    assert.equal(p.banner().dataset.state, 'back');
    assert.equal(p.live().length, 1, 'only the hide timer is left, the probe is cancelled');
    assert.equal(p.live()[0].ms, 8000);
});

await test('a maintenance answer of the transport enters the mode', () => {
    const p = page();

    assert.equal(p.maintenance.observe(reply(503, MAINTENANCE_HEADERS)), true);

    assert.equal(p.maintenance.isActive(), true);
    assert.equal(p.banner().dataset.state, 'down');
});

await test('a regular answer outside the mode shows nothing', () => {
    const p = page();

    p.maintenance.observe(reply(200));

    assert.equal(p.banner(), null);
});

await test('the close button hides the banner, and the next failure shows it again', () => {
    const p = page();
    p.maintenance.enter();

    p.banner().button.listeners.click();
    assert.equal(p.banner(), null);
    assert.equal(p.maintenance.isActive(), true, 'hiding the banner does not end the mode');

    p.maintenance.enter();
    assert.equal(p.banner().dataset.state, 'down');
});

await test('a failure while the back notice shows turns it back and cancels the hide', async () => {
    const p = page([reply(200)]);
    p.maintenance.enter();
    await p.fire(p.live()[0]);
    const hide = p.live()[0];

    p.maintenance.enter();

    assert.equal(hide.live, false);
    assert.equal(p.banner().dataset.state, 'down');
});

const NEXT_VERSION = { 'X-Basetool-Version': '1.1.0' };

await test('an answer from another version offers a reload that stays until the member acts', () => {
    const p = page([], { pageVersion: '1.0.0' });

    assert.equal(p.maintenance.observe(reply(200, NEXT_VERSION)), false);

    const banner = p.banner();
    assert.equal(banner.dataset.state, 'updated');
    assert.ok(banner.markup.values.includes('UPDATED'));
    assert.equal(p.live().length, 0, 'the notice never hides itself');
    banner.reloadButton.listeners.click();
    assert.equal(p.reloads(), 1);
});

await test('the same version, a missing header or a page without a version offer nothing', () => {
    const p = page([], { pageVersion: '1.0.0' });
    p.maintenance.observe(reply(200, { 'X-Basetool-Version': '1.0.0' }));
    p.maintenance.observe(reply(200));
    assert.equal(p.banner(), null);

    const unnamed = page();
    unnamed.maintenance.observe(reply(200, NEXT_VERSION));
    assert.equal(unnamed.banner(), null);
});

await test('a closed notice of a new version stays closed on this page', () => {
    const p = page([], { pageVersion: '1.0.0' });
    p.maintenance.observe(reply(200, NEXT_VERSION));

    p.banner().button.listeners.click();
    p.maintenance.observe(reply(200, NEXT_VERSION));

    assert.equal(p.banner(), null);
    assert.equal(p.reloads(), 0);
});

await test('a window that ends on another version offers the reload instead of the back notice', async () => {
    const p = page([reply(200, NEXT_VERSION)], { pageVersion: '1.0.0' });
    p.maintenance.enter();

    await p.fire(p.live()[0]);

    assert.equal(p.maintenance.isActive(), false);
    assert.equal(p.banner().dataset.state, 'updated');
    assert.equal(p.live().length, 0);
});

await test('a transport answer from another version ends the mode with the reload offer', () => {
    const p = page([], { pageVersion: '1.0.0' });
    p.maintenance.enter();

    p.maintenance.observe(reply(201, NEXT_VERSION));

    assert.equal(p.maintenance.isActive(), false);
    assert.equal(p.banner().dataset.state, 'updated');
    assert.equal(p.live().length, 0, 'the probe is cancelled and nothing hides the notice');
});

await test('a later answer from another version turns the back notice into the reload offer', () => {
    const p = page([], { pageVersion: '1.0.0' });
    p.maintenance.enter();
    p.maintenance.observe(reply(200, { 'X-Basetool-Version': '1.0.0' }));
    const hide = p.live()[0];

    p.maintenance.observe(reply(200, NEXT_VERSION));

    assert.equal(hide.live, false);
    assert.equal(p.banner().dataset.state, 'updated');
});

/**
 * Builds a page context for the transport with a scripted fetch.
 *
 * @param {Array<unknown>} answers what each fetch answers
 */
function transport(answers) {
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
        ErrorEvent: class {},
        CustomEvent: class {},
        addEventListener() {},
        dispatchEvent() {},
        document: {
            querySelector: () => null,
            querySelectorAll: () => [],
            addEventListener() {},
            dispatchEvent() {},
            createElement: () => ({ setAttribute() {} }),
            head: { appendChild() {} },
            body: { dataset: {} },
        },
        fetch() {
            const next = answers.shift();
            return typeof next === 'function' ? next() : Promise.resolve(next);
        },
    };
    context.window = context;
    context.location = { origin: 'https://example.test', assign() {}, reload() {} };
    context.window.sessionStorage = { getItem: () => null, setItem() {} };
    context.window.krtFetchI18n = {};
    vm.createContext(context);
    vm.runInContext(FETCH_SOURCE, context);
    const maintenance = vm.runInContext('krtMaintenance', context);
    return {
        krtFetch: context.krtFetch,
        /** The recorded calls as plain data of this realm, so deepEqual compares values only. */
        calls: () => JSON.parse(JSON.stringify(maintenance.calls)),
    };
}

await test('a write that cannot reach the server enters the maintenance mode', async () => {
    const t = transport([() => Promise.reject(new TypeError('Failed to fetch'))]);

    const result = await t.krtFetch.write({ method: 'POST', url: '/x', payload: {} });

    assert.equal(result.ok, false);
    assert.deepEqual(t.calls(), ['enter']);
});

await test('a write hands every answer and its parsed body to the maintenance check', async () => {
    const problem = { type: 'https://profit-base.online/problems/maintenance', status: 503 };
    const t = transport([
        reply(503, { 'Content-Type': 'application/problem+json' }, JSON.stringify(problem)),
    ]);

    await t.krtFetch.write({ method: 'POST', url: '/x', payload: {} });

    assert.deepEqual(t.calls(), [['observe', 503, problem]]);
});

await test('a read that cannot reach the server enters the mode and still rejects', async () => {
    const t = transport([() => Promise.reject(new TypeError('Failed to fetch'))]);

    await assert.rejects(t.krtFetch.get('/a'));

    assert.deepEqual(t.calls(), ['enter']);
});

await test('an aborted read is no outage', async () => {
    const abort = Object.assign(new Error('aborted'), { name: 'AbortError' });
    const t = transport([() => Promise.reject(abort)]);

    await assert.rejects(t.krtFetch.get('/a'));

    assert.deepEqual(t.calls(), []);
});

await test('a read hands its answer to the maintenance check', async () => {
    const t = transport([reply(200, {}, 'x')]);

    await t.krtFetch.get('/a');

    assert.deepEqual(t.calls(), [['observe', 200, null]]);
});

await test('the error toast stays silent while the maintenance banner explains the failure', () => {
    let created = 0;
    const context = {
        console,
        setTimeout() {},
        document: {
            addEventListener() {},
            querySelectorAll: () => [],
            createElement: () => {
                created += 1;
                return { setAttribute() {}, appendChild() {}, classList: { add() {} } };
            },
            body: { appendChild() {} },
        },
    };
    context.window = context;
    context.krtToastI18n = { errorTitle: 'Error' };
    vm.createContext(context);
    vm.runInContext(TOAST_SOURCE, context);
    const showFrontendErrorToast = vm.runInContext('showFrontendErrorToast', context);
    const maintenance = vm.runInContext('krtMaintenance', context);

    showFrontendErrorToast('Save failed.');
    assert.equal(created, 0);

    maintenance.active = false;
    showFrontendErrorToast('Save failed.');
    assert.ok(created > 0, 'outside the mode the toast is built');
});

if (failures > 0) {
    console.error(`${failures} test(s) failed`);
    process.exit(1);
}
