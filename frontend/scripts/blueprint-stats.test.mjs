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
 * Regression tests for the blueprint recipe's stat values (REQ-INV-069): the factor at a quality,
 * the value it yields from the crafted item's base value, and the „550 → 605" text per unit.
 */

import assert from 'node:assert/strict';
import vm from 'node:vm';
import { flattenModule } from './flatten-module.mjs';

const context = vm.createContext({ Intl, Map, Number, Math });
vm.runInContext(flattenModule('personalinventory/personal-inventory-blueprint-stats.js'), context);
const fn = (/** @type {string} */ name) => vm.runInContext(name, context);
const computeModifierValue = fn('computeModifierValue');
const indexBaseStats = fn('indexBaseStats');
const statValueAt = fn('statValueAt');
const formatStatNumber = fn('formatStatNumber');
const formatStatRange = fn('formatStatRange');
const formatFactorPercent = fn('formatFactorPercent');

const UNITS = {
    PERCENT: '%',
    CELSIUS: '°C',
    METER: 'm',
    METER_PER_SECOND: 'm/s',
    ROUNDS_PER_MINUTE: 'rpm',
};

const linear = {
    effectiveQualityMin: 0,
    effectiveQualityMax: 1000,
    modifierAtMinQuality: 0.9,
    modifierAtMaxQuality: 1.1,
    segments: [],
};

assert.equal(computeModifierValue(linear, 0), 0.9);
assert.equal(computeModifierValue(linear, 1000), 1.1);
assert.ok(Math.abs(computeModifierValue(linear, 500) - 1.0) < 1e-12);
assert.equal(computeModifierValue(linear, 2000), 1.1, 'a quality above the band clamps');

const stepped = {
    valueRangeType: 'stepped',
    segments: [
        { qualityMin: 0, qualityMax: 500, modifierAtStart: 0.95, modifierAtEnd: 0.95 },
        { qualityMin: 500, qualityMax: 1000, modifierAtStart: 1.05, modifierAtEnd: 1.05 },
    ],
};
assert.equal(computeModifierValue(stepped, 400), 0.95);
assert.equal(computeModifierValue(stepped, 900), 1.05);

assert.ok(Math.abs(statValueAt(550, 1.1) - 605) < 1e-9, 'Integrity 550 at ×1.10 is 605');
assert.ok(Math.abs(statValueAt(-61, 1.1) - -67.1) < 1e-9, 'a minimum temperature widens');
assert.equal(statValueAt(550, null), null);
assert.equal(statValueAt(550, Number.NaN), null);

assert.equal(formatStatNumber(605, 'en'), '605');
assert.equal(formatStatNumber(102.06, 'en'), '102');
assert.equal(formatStatNumber(97.2, 'de'), '97,2');
assert.equal(formatStatNumber(5.26, 'en'), '5.26');
assert.equal(formatStatNumber(0.00588, 'en'), '0.00588');
assert.equal(formatStatNumber(263040000, 'de'), '263.040.000');

assert.equal(formatFactorPercent(1.1, 'de'), '+10 %');
assert.equal(formatFactorPercent(0.95, 'de'), '-5 %');
assert.equal(formatFactorPercent(1, 'de'), '±0 %');
assert.equal(formatFactorPercent(1.025, 'de'), '+2,5 %');
assert.equal(formatFactorPercent(1.025, 'en'), '+2.5 %');
assert.equal(formatFactorPercent(0.9, 'en'), '-10 %');

const integrity = { propertyKey: 'health_maxhealth', baseValue: 550, unit: 'NONE' };
assert.equal(formatStatRange(integrity, 1.1, 'en', UNITS), '550 → 605');
assert.equal(formatStatRange(integrity, null, 'en', UNITS), '550 → ?');

const mitigation = { propertyKey: 'armor_damagemitigation', baseValue: 0.3, unit: 'PERCENT' };
assert.equal(formatStatRange(mitigation, 1.2, 'de', UNITS), '30 → 36 %');

const minTemp = { propertyKey: 'armor_temperaturemin', baseValue: -61, unit: 'CELSIUS' };
assert.equal(formatStatRange(minTemp, 1.1, 'de', UNITS), '-61 → -67,1 °C');

const fireRate = { propertyKey: 'weapon_firerate', baseValue: 650, unit: 'ROUNDS_PER_MINUTE' };
assert.equal(formatStatRange(fireRate, 1.05, 'en', UNITS), '650 → 683 rpm');

const index = indexBaseStats([
    integrity,
    { propertyKey: 'weapon_damage', baseValue: 97.2, unit: 'NONE' },
    { propertyKey: '', baseValue: 1, unit: 'NONE' },
    null,
]);
assert.equal(index.size, 2);
assert.equal(index.get('health_maxhealth'), integrity);
assert.equal(indexBaseStats(undefined).size, 0);

console.log('blueprint-stats: all assertions passed');
