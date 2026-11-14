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
 * Regression tests for the member's home-location page (REQ-PATCH-022, REQ-FE-001): the page
 * script runs in a vm context over a fake form, with the transport's write recorded, so the
 * relocation's summary, the way on into the tool and a refusal are proven at the script's own seam.
 * A relocation never navigates the window by itself.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const STUBS = {
    '/js/kernel/krt-fetch.js': 'const krtFetch = { write(opts) { __ui.writes.push(opts); } };',
};

class FakeElement {
    constructor(attrs = {}) {
        this.dataset = { ...attrs };
        this.hidden = false;
        this.textContent = '';
        this.disabled = false;
        this.focused = false;
        this.listeners = {};
    }

    addEventListener(type, fn) {
        (this.listeners[type] ||= []).push(fn);
    }

    focus() {
        this.focused = true;
    }
}

class FakeButton extends FakeElement {}

class FakeInput extends FakeElement {
    constructor(value, name) {
        super({ locationName: name });
        this.value = value;
        this.checked = false;
    }
}

class FakeForm extends FakeElement {
    constructor(radios) {
        super();
        this.radios = radios;
    }

    querySelector(selector) {
        if (selector === 'input[name="locationId"]:checked') {
            return this.radios.find((radio) => radio.checked) || null;
        }
        return null;
    }

    querySelectorAll(selector) {
        return selector === 'input[name="locationId"]' ? this.radios : [];
    }
}

/**
 * Loads the page script over a fresh fake page with two curated home locations.
 *
 * @returns {object} the harness: the recorded writes, the elements, the radios, choose and submit
 */
function load() {
    const radios = [new FakeInput('loc-1', 'Lorville'), new FakeInput('loc-2', 'Levski')];
    const el = {
        'hl-form': new FakeForm(radios),
        'hl-submit': new FakeButton(),
        'hl-error': new FakeElement(),
        'hl-success': new FakeElement(),
        'hl-pending': new FakeElement(),
        'hl-done': new FakeElement(),
        'hl-continue': new FakeElement(),
        'hl-i18n': new FakeElement({
            cta: 'Move to {0}',
            ctaChoose: 'Choose a home location',
            success: 'Moved to {0}: {1}, {2}.',
            rowsOne: '{0} item',
            rowsOther: '{0} items',
            shipsOne: '{0} ship',
            shipsOther: '{0} ships',
            error: 'The home location could not be saved.',
        }),
    };
    el['hl-error'].hidden = true;
    el['hl-done'].hidden = true;
    const ui = { writes: [], navigations: [] };
    const context = vm.createContext({
        __ui: ui,
        HTMLElement: FakeElement,
        HTMLFormElement: FakeForm,
        HTMLButtonElement: FakeButton,
        HTMLInputElement: FakeInput,
        Intl,
        document: {
            documentElement: { lang: 'en' },
            getElementById: (id) => el[id] || null,
        },
        window: {
            krtI18nText: (value, key) => value || key,
            location: { assign: (url) => ui.navigations.push(url) },
        },
    });
    vm.runInContext(flattenModule('patchchange/home-location.js', STUBS), context);
    const form = el['hl-form'];
    /**
     * Picks a card as a click would.
     *
     * @param {FakeInput} radio the card's radio
     */
    function choose(radio) {
        radios.forEach((other) => {
            other.checked = other === radio;
        });
        form.listeners.change.forEach((fn) => fn({ target: radio }));
    }
    /** Submits the form as the call to action would. */
    function submit() {
        form.listeners.submit.forEach((fn) => fn({ preventDefault() {} }));
    }
    return { ui, el, radios, choose, submit };
}

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

test('the call to action names the chosen location', () => {
    const { el, radios, choose } = load();
    assert.equal(el['hl-submit'].disabled, true);
    choose(radios[1]);
    assert.equal(el['hl-submit'].disabled, false);
    assert.equal(el['hl-submit'].textContent, 'Move to Levski');
});

test('a relocation shows what moved and offers the way on without leaving the page', () => {
    const { ui, el, radios, choose, submit } = load();
    choose(radios[0]);
    submit();
    assert.equal(ui.writes.length, 1);
    assert.equal(ui.writes[0].url, '/home-location');
    assert.deepEqual(JSON.parse(JSON.stringify(ui.writes[0].payload)), { locationId: 'loc-1' });

    ui.writes[0].onSuccess({ location: { name: 'Lorville' }, rows: 3, ships: 1 });

    assert.equal(el['hl-success'].textContent, 'Moved to Lorville: 3 items, 1 ship.');
    assert.equal(el['hl-done'].hidden, false, 'the summary and the way on show');
    assert.equal(el['hl-pending'].hidden, true, 'the call to action and the note go');
    assert.equal(el['hl-continue'].focused, true, 'the way on takes the focus');
    assert.ok(
        radios.every((radio) => radio.disabled),
        'the final choice cannot be changed',
    );
    assert.deepEqual(ui.navigations, [], 'the page never navigates by itself');
});

test('the summary counts one item and several ships in their own forms', () => {
    const { ui, el, radios, choose, submit } = load();
    choose(radios[1]);
    submit();

    ui.writes[0].onSuccess({ location: { name: 'Levski' }, rows: 1, ships: 2 });

    assert.equal(el['hl-success'].textContent, 'Moved to Levski: 1 item, 2 ships.');
});

test('a refused choice shows the reason and keeps the choice open', () => {
    const { ui, el, radios, choose, submit } = load();
    choose(radios[0]);
    submit();

    assert.equal(ui.writes[0].onError(409, { detail: 'Already chosen.' }), true);

    assert.equal(el['hl-error'].textContent, 'Already chosen.');
    assert.equal(el['hl-error'].hidden, false);
    assert.equal(el['hl-done'].hidden, true);
    assert.equal(el['hl-pending'].hidden, false);
    assert.ok(radios.every((radio) => !radio.disabled));
    assert.deepEqual(ui.navigations, []);
});

test('a refusal without a reason and a network failure show the fallback', () => {
    const { ui, el, radios, choose, submit } = load();
    choose(radios[0]);
    submit();

    assert.equal(ui.writes[0].onError(500, null), true);
    assert.equal(el['hl-error'].textContent, 'The home location could not be saved.');
    el['hl-error'].textContent = '';
    assert.equal(ui.writes[0].onNetworkError(), true);
    assert.equal(el['hl-error'].textContent, 'The home location could not be saved.');
});

if (failures) {
    process.exitCode = 1;
} else {
    console.log('home-location tests passed');
}
