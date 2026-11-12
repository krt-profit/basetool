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

import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

class FakePicker {
    children = [];
    value = '';

    replaceChildren() {
        this.children = [];
        this.value = '';
    }

    appendChild(child) {
        this.children.push(child);
    }

    get options() {
        return this.children.flatMap((child) => child.options || [child]);
    }
}

function setup(activeId = null) {
    const options = [];
    const context = vm.createContext({
        AbortController,
        options,
        document: {
            createElement(tag) {
                return tag === 'optgroup' ? new FakePicker() : {};
            },
        },
        window: {
            krtOwnerPickerI18n: {
                placeholder: 'Please choose',
                BEREICH: 'Area',
                SQUADRON: 'Squadron',
                loading: 'Loading',
                unavailable: 'Unavailable',
                required: 'Required',
            },
        },
    });
    vm.runInContext(
        flattenModule('kernel/krt-owner-picker.js', {
            '/js/kernel/krt-fetch.js':
                'const krtFetch = { getJson(url) { return new Promise((resolve, reject) => options.push({ url, resolve, reject })); } };',
        }),
        context,
    );
    return {
        picker: new FakePicker(),
        wrapper: { hidden: false, getAttribute: () => activeId },
        reads: options,
        render: context.renderOwnerPicker,
        refresh: context.refreshOwnerPicker,
        error: context.ownerPickerError,
    };
}

const area = { orgUnitId: 'area', orgUnitName: 'Profit', kind: 'BEREICH' };
const squadron = { orgUnitId: 'squadron', orgUnitName: 'Hel', kind: 'SQUADRON' };

test('an eligible active unit is selected and the options group by kind', () => {
    const h = setup();
    assert.equal(h.render(h.picker, [squadron, area], 'squadron'), 2);
    assert.equal(h.picker.value, 'squadron');
    assert.deepEqual(
        h.picker.children.map((group) => group.label),
        ['Area', 'Squadron'],
    );
    assert.deepEqual(
        h.picker.options.map((o) => o.textContent),
        ['Profit', 'Hel'],
    );
});

test('the only unit is selected when the active unit is unavailable', () => {
    const h = setup();
    assert.equal(h.render(h.picker, [squadron], 'foreign'), 1);
    assert.equal(h.picker.value, 'squadron');
    assert.equal(h.error(h.picker), null);
});

test('multiple units without an eligible active unit require a choice', () => {
    const h = setup();
    assert.equal(h.render(h.picker, [area, squadron], 'foreign'), 2);
    assert.equal(h.picker.value, '');
    assert.equal(h.picker.options[0].textContent, 'Please choose');
    assert.equal(h.error(h.picker), 'Required');
    h.picker.value = 'area';
    assert.equal(h.error(h.picker), null);
});

test('a late answer for the prior owner cannot replace the current options', async () => {
    const h = setup('area');
    const prior = h.refresh(h.picker, h.wrapper, '/prior');
    const current = h.refresh(h.picker, h.wrapper, '/current');
    assert.equal(h.error(h.picker), 'Loading');
    h.reads[1].resolve([area]);
    await current;
    h.reads[0].resolve([squadron]);
    await prior;
    assert.equal(h.picker.value, 'area');
    assert.equal(h.wrapper.hidden, false);
});

test('a failed membership read keeps the booking from using an implicit destination', async () => {
    const h = setup();
    const read = h.refresh(h.picker, h.wrapper, '/failed');
    h.reads[0].reject(new Error('Offline'));
    await read;
    assert.equal(h.error(h.picker), 'Unavailable');
    await h.refresh(h.picker, h.wrapper, null);
    assert.equal(h.error(h.picker), null);
    assert.equal(h.wrapper.hidden, true);
});
