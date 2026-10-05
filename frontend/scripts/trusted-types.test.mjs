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
 * Regression tests for the two Trusted Types policies (ADR-0239): the `krtHtml` builder of
 * krt-html.js and the `krt-fragment` policy of krt-fetch.js, run in a vm context with and without a
 * scripted `trustedTypes` factory.
 */

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const source = (name) =>
    readFileSync(
        fileURLToPath(new URL(`../src/main/resources/static/js/${name}`, import.meta.url)),
        'utf8',
    );

const KRT_HTML = source('krt-html.js');
const KRT_FETCH = source('krt-fetch.js');

let failures = 0;

/** Runs one named case, recording rather than throwing so every case reports. */
async function test(name, fn) {
    try {
        await fn();
        console.log(`ok - ${name}`);
    } catch (e) {
        failures += 1;
        console.error(`not ok - ${name}\n${e.stack}`);
    }
}

/** A stand-in for the browser's TrustedHTML: an object that only the factory mints. */
class FakeTrustedHtml {
    constructor(markup) {
        this.markup = markup;
    }

    toString() {
        return this.markup;
    }
}

/** A scripted `window.trustedTypes` recording the policies created and the markup approved. */
function factory() {
    const policies = [];
    const approved = [];
    return {
        policies,
        approved,
        createPolicy(name, rules) {
            policies.push(name);
            return {
                name,
                createHTML(input) {
                    const markup = rules.createHTML(input);
                    approved.push(markup);
                    return new FakeTrustedHtml(markup);
                },
            };
        },
        isHTML: (value) => value instanceof FakeTrustedHtml,
    };
}

/** An element stand-in recording what the sinks receive. */
function element() {
    return { innerHTML: undefined, textContent: undefined };
}

/** Loads krt-html.js into a fresh context, optionally with a Trusted Types factory. */
function loadKrtHtml(trustedTypes) {
    const context = { Array, Object, String, WeakSet, TypeError };
    context.window = context;
    if (trustedTypes) {
        context.trustedTypes = trustedTypes;
    }
    vm.createContext(context);
    vm.runInContext(KRT_HTML, context);
    return context.krtHtml;
}

/** Loads krt-fetch.js into a fresh context, optionally with a Trusted Types factory. */
function loadKrtFetch(trustedTypes) {
    const parsed = [];
    const context = {
        console: { warn() {}, log() {}, error() {} },
        URL,
        Promise,
        Map,
        Error,
        CustomEvent: class {},
        DOMParser: class {
            parseFromString(markup, type) {
                parsed.push({ markup, type });
                return { parsed: true };
            }
        },
        document: {
            querySelector: () => null,
            querySelectorAll: () => [],
            addEventListener() {},
            dispatchEvent() {},
            createElement: () => ({ setAttribute() {} }),
            head: { appendChild() {} },
        },
    };
    context.window = context;
    context.location = { origin: 'https://example.test' };
    if (trustedTypes) {
        context.trustedTypes = trustedTypes;
    }
    vm.createContext(context);
    vm.runInContext(KRT_FETCH, context);
    return { krtFetch: context.krtFetch, parsed };
}

await test('every interpolated value is escaped like the retired escapeHtml', () => {
    const krtHtml = loadKrtHtml();
    const value = `<img src=x onerror="a('b')">&/`;
    assert.equal(
        String(krtHtml`<li title="${value}">${value}</li>`),
        '<li title="&lt;img src=x onerror=&quot;a(&#39;b&#39;)&quot;&gt;&amp;&#x2F;">' +
            '&lt;img src=x onerror=&quot;a(&#39;b&#39;)&quot;&gt;&amp;&#x2F;</li>',
    );
});

await test('null and undefined render as nothing, numbers and booleans as text', () => {
    const krtHtml = loadKrtHtml();
    assert.equal(String(krtHtml`${null}|${undefined}|${0}|${false}|${1.5}`), '||0|false|1.5');
});

await test('nested markup and arrays of markup are not escaped twice', () => {
    const krtHtml = loadKrtHtml();
    const items = ['a<b', 'c'].map((t) => krtHtml`<li>${t}</li>`);
    assert.equal(
        String(krtHtml`<ul>${items}${krtHtml`<li>&times;</li>`}</ul>`),
        '<ul><li>a&lt;b</li><li>c</li><li>&times;</li></ul>',
    );
});

await test('a plain string in an array is still escaped', () => {
    const krtHtml = loadKrtHtml();
    assert.equal(String(krtHtml`${['<b>', krtHtml`<i></i>`]}`), '&lt;b&gt;<i></i>');
});

await test('join escapes the values and the separator that are not markup', () => {
    const krtHtml = loadKrtHtml();
    assert.equal(String(krtHtml.join(['<a>', krtHtml`<b></b>`], ' & ')), '&lt;a&gt; &amp; <b></b>');
});

await test('set writes markup as HTML, an array joined, and anything else as text', () => {
    const krtHtml = loadKrtHtml();
    const el = element();
    krtHtml.set(el, krtHtml`<b>${'x'}</b>`);
    assert.equal(String(el.innerHTML), '<b>x</b>');
    const list = element();
    krtHtml.set(list, [krtHtml`<li>1</li>`, '<li>2</li>']);
    assert.equal(String(list.innerHTML), '<li>1</li>&lt;li&gt;2&lt;&#x2F;li&gt;');
    const text = element();
    krtHtml.set(text, '<script>alert(1)</script>');
    assert.equal(text.innerHTML, undefined);
    assert.equal(text.textContent, '<script>alert(1)</script>');
    krtHtml.set(null, krtHtml`<b></b>`);
});

await test('krtHtml refuses to be called with a forged strings array', () => {
    const krtHtml = loadKrtHtml();
    assert.throws(() => krtHtml(['<img src=x onerror=alert(1)>']), TypeError);
});

await test('with Trusted Types the builder creates only the krt-html policy and mints through it', () => {
    const tt = factory();
    const krtHtml = loadKrtHtml(tt);
    assert.deepEqual(tt.policies, ['krt-html']);
    const value = krtHtml`<b>${'<i>'}</b>`;
    assert.ok(value instanceof FakeTrustedHtml);
    assert.deepEqual(tt.approved, ['<b>&lt;i&gt;</b>']);
    const el = element();
    krtHtml.set(el, value);
    assert.equal(el.innerHTML, value);
});

await test('a TrustedHTML the builder did not mint is escaped, not trusted', () => {
    const tt = factory();
    const krtHtml = loadKrtHtml(tt);
    const foreign = new FakeTrustedHtml('<img src=x onerror=alert(1)>');
    assert.equal(String(krtHtml`${foreign}`), '&lt;img src=x onerror=alert(1)&gt;');
    const el = element();
    krtHtml.set(el, foreign);
    assert.equal(el.innerHTML, undefined);
});

await test('the transport writes server fragments through the krt-fragment policy', () => {
    const tt = factory();
    const { krtFetch, parsed } = loadKrtFetch(tt);
    assert.deepEqual(tt.policies, ['krt-fragment']);
    const el = element();
    krtFetch.setTrustedHtml(el, '<tr><td>1</td></tr>');
    assert.ok(el.innerHTML instanceof FakeTrustedHtml);
    assert.equal(String(el.innerHTML), '<tr><td>1</td></tr>');
    krtFetch.parseTrustedDocument('<html></html>');
    assert.ok(parsed[0].markup instanceof FakeTrustedHtml);
    assert.equal(parsed[0].type, 'text/html');
});

await test('without Trusted Types the transport writes the plain text', () => {
    const { krtFetch } = loadKrtFetch();
    const el = element();
    krtFetch.setTrustedHtml(el, '<b>1</b>');
    assert.equal(el.innerHTML, '<b>1</b>');
    krtFetch.setTrustedHtml(el, null);
    assert.equal(el.innerHTML, '');
});

if (failures > 0) {
    console.error(`${failures} failed`);
    process.exit(1);
}
