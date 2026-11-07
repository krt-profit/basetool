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
const isAdditive = fn('isAdditive');
const formatSummand = fn('formatSummand');
const formatModifierChange = fn('formatModifierChange');

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

assert.equal(isAdditive({ valueKind: 'ADDITIVE' }), true);
assert.equal(isAdditive({ valueKind: 'MULTIPLIER' }), false);
assert.equal(isAdditive({ valueKind: null }), false, 'a Wiki modifier carries factors');
assert.equal(statValueAt(3, 1, true), 4, 'Power Pips 3 with an additive +1 is 4');
assert.equal(statValueAt(3, -1, true), 2);
assert.equal(formatSummand(1, 'de'), '+1');
assert.equal(formatSummand(-0.5, 'de'), '-0,5');
assert.equal(formatSummand(0, 'en'), '±0');
assert.equal(formatSummand(2, 'en', '°C'), '+2 °C');
const pips = { propertyKey: 'itemresource_powergeneration', baseValue: 3, unit: 'NONE' };
assert.equal(formatModifierChange(1, true, pips, 'en', UNITS), '+1', 'not „+0 %" nor „×1"');
assert.equal(formatModifierChange(-1, true, null, 'de', UNITS), '-1');
assert.equal(formatModifierChange(1.1, false, pips, 'de', UNITS), '+10 %');
assert.equal(formatModifierChange(2, true, minTemp, 'de', UNITS), '+2 °C');
assert.equal(formatModifierChange(0.05, true, mitigation, 'en', UNITS), '+5 %');
assert.equal(formatStatRange(pips, 1, 'en', UNITS, true), '3 → 4');
assert.equal(formatStatRange(minTemp, -2, 'de', UNITS, true), '-61 → -63 °C');

const index = indexBaseStats([
    integrity,
    { propertyKey: 'weapon_damage', baseValue: 97.2, unit: 'NONE' },
    { propertyKey: '', baseValue: 1, unit: 'NONE' },
    null,
]);
assert.equal(index.size, 2);
assert.equal(index.get('health_maxhealth'), integrity);
assert.equal(indexBaseStats(undefined).size, 0);

class MetadataElement {
    constructor(tag) {
        this.tagName = tag;
        this.children = [];
        this.text = '';
    }
    set textContent(value) {
        this.text = String(value);
        this.children = [];
    }
    get textContent() {
        return this.text + this.children.map((child) => child.textContent).join('');
    }
    appendChild(child) {
        this.children.push(child);
        return child;
    }
}

const metadataContext = vm.createContext({
    Intl,
    Map,
    Number,
    Math,
    URL,
    document: {
        documentElement: { lang: 'en' },
        createElement(tag) {
            return new MetadataElement(tag);
        },
    },
    window: {
        krtI18nText(value, key) {
            return value || key;
        },
    },
});
vm.runInContext(
    flattenModule('personalinventory/personal-inventory-blueprint-metadata.js'),
    metadataContext,
);
const renderBlueprintMetadata = vm.runInContext('renderBlueprintMetadata', metadataContext);
const metadataLabels = {
    metadataTitle: 'Recipe source',
    metadataBuild: 'SC Wiki · game build {0}',
    metadataSource: 'SC Wiki',
    metadataMissions: 'Unlock missions',
    metadataIndividualMissions: 'Individual unlock missions',
    metadataMissionGroups: 'Mission groups',
    metadataMissionGroup: 'Mission group',
    metadataAspects: 'Source inputs and choices',
    metadataEmpty: '{0}: no entries.',
    metadataNotLoaded: '{0}: not loaded.',
    metadataMission: 'Mission',
    metadataChance: '{0}% chance',
    metadataTiers: 'Crafting tiers and requirements',
    metadataTier: 'Crafting tier {0}',
    metadataTime: 'Crafting time: {0} seconds',
    metadataInputs: 'Inputs',
    metadataChoices: '{0} of {1} inputs required',
    minQuality: 'min. quality',
    unitScu: 'SCU',
    unitPiece: 'pieces',
};
assert.equal(renderBlueprintMetadata(null, metadataLabels), null);
const sourcePanel = renderBlueprintMetadata(
    {
        gameVersion: '4.10.2-LIVE.12881860',
        loadedSections: ['MISSIONS', 'ASPECTS', 'TIERS'],
        unlockingMissions: [
            {
                title: '<img src=x onerror=alert(1)>',
                chance: 0.25,
                webUrl: 'javascript:alert(1)',
            },
        ],
        aspects: {
            aspectGroups: [{ choiceGroup: true, name: 'Frame', requiredCount: 1, optionCount: 2 }],
        },
        tiers: [
            {
                tierIndex: 0,
                craftTimeSeconds: 540,
                requirements: {
                    name: 'Frame',
                    requiredCount: 1,
                    children: [
                        { name: 'Agricium', quantityScu: 0.36, minQuality: 1 },
                        { name: 'Iron', quantityScu: 0.5, minQuality: 1 },
                    ],
                },
            },
        ],
    },
    metadataLabels,
);
assert.ok(sourcePanel.textContent.includes('4.10.2-LIVE.12881860'));
assert.ok(sourcePanel.textContent.includes('25.0% chance'));
assert.ok(sourcePanel.textContent.includes('1 of 2 inputs required'));
assert.ok(sourcePanel.textContent.includes('Crafting time: 540 seconds'));
assert.ok(sourcePanel.textContent.includes('0.360 SCU'));
const descendants = (element) => [
    element,
    ...element.children.flatMap((child) => descendants(child)),
];
assert.equal(descendants(sourcePanel).filter((element) => element.tagName === 'img').length, 0);
assert.equal(descendants(sourcePanel).filter((element) => element.tagName === 'a').length, 0);
const linkedPanel = renderBlueprintMetadata(
    {
        loadedSections: ['MISSIONS'],
        unlockingMissions: [
            {
                title: 'Tactical Strike Group Needed',
                webUrl: 'https://api.star-citizen.wiki/missions/24ac3c24-ed16-43f5-bb1c-626f0261fcb5?version=4.10.2-LIVE.12881860',
            },
        ],
    },
    metadataLabels,
);
const missionLink = descendants(linkedPanel).find((element) => element.tagName === 'a');
assert.ok(missionLink);
assert.equal(missionLink.rel, 'noopener noreferrer');
assert.equal(missionLink.target, '_blank');
const unknownPanel = renderBlueprintMetadata(
    {
        loadedSections: [],
        unlockingMissions: [{ title: 'Unconfirmed mission payload' }],
        tiers: [{ craftTimeSeconds: 999 }],
    },
    metadataLabels,
);
assert.ok(unknownPanel.textContent.includes('Individual unlock missions: not loaded.'));
assert.ok(unknownPanel.textContent.includes('Mission groups: not loaded.'));
assert.ok(unknownPanel.textContent.includes('Source inputs and choices: not loaded.'));
assert.ok(unknownPanel.textContent.includes('Crafting tiers and requirements: not loaded.'));
assert.ok(!unknownPanel.textContent.includes('no entries.'));
assert.ok(!unknownPanel.textContent.includes('Unconfirmed mission payload'));
assert.ok(!unknownPanel.textContent.includes('999'));

const emptyPanel = renderBlueprintMetadata(
    {
        loadedSections: ['MISSIONS', 'MISSION_GROUPS', 'ASPECTS', 'TIERS'],
        unlockingMissions: [],
        unlockingMissionGroups: [],
        aspects: { aspects: [], aspectGroups: [] },
        tiers: [],
    },
    metadataLabels,
);
assert.ok(emptyPanel.textContent.includes('Individual unlock missions: no entries.'));
assert.ok(emptyPanel.textContent.includes('Mission groups: no entries.'));
assert.ok(emptyPanel.textContent.includes('Source inputs and choices: no entries.'));
assert.ok(emptyPanel.textContent.includes('Crafting tiers and requirements: no entries.'));
assert.ok(!emptyPanel.textContent.includes('not loaded.'));

const groupedMission = {
    title: 'Tactical Strike Group Needed',
    webUrl: 'https://api.star-citizen.wiki/missions/24ac3c24-ed16-43f5-bb1c-626f0261fcb5?version=4.10.2-LIVE.12881860',
};
const groupedPanel = renderBlueprintMetadata(
    {
        loadedSections: ['MISSIONS', 'MISSION_GROUPS'],
        unlockingMissions: [
            { ...groupedMission, debugName: 'InterSec_Nyx_TSG_FullStrikeOnStation', chance: 1 },
        ],
        unlockingMissionGroups: [{ label: 'Guaranteed', chance: 1, missions: [groupedMission] }],
    },
    metadataLabels,
);
assert.ok(groupedPanel.textContent.includes('Guaranteed · 100.0% chance'));
assert.equal(groupedPanel.textContent.split(groupedMission.title).length - 1, 1);
assert.equal(descendants(groupedPanel).filter((element) => element.tagName === 'a').length, 1);
assert.ok(!groupedPanel.textContent.includes('Individual unlock missions: no entries.'));

const groupsOnlyPanel = renderBlueprintMetadata(
    {
        loadedSections: ['MISSION_GROUPS'],
        unlockingMissionGroups: [
            { label: 'Rare rewards', chance: 0.15, missions: [groupedMission] },
        ],
    },
    metadataLabels,
);
assert.ok(groupsOnlyPanel.textContent.includes('Rare rewards · 15.0% chance'));
assert.ok(groupsOnlyPanel.textContent.includes(groupedMission.title));
assert.ok(groupsOnlyPanel.textContent.includes('Individual unlock missions: not loaded.'));
assert.ok(!groupsOnlyPanel.textContent.includes('Mission groups: no entries.'));

const distinctMissionPanel = renderBlueprintMetadata(
    {
        loadedSections: ['MISSIONS', 'MISSION_GROUPS'],
        unlockingMissions: [{ title: 'Shared title', debugName: 'one' }],
        unlockingMissionGroups: [
            { chance: 0, missions: [{ title: 'Shared title', debugName: 'two' }] },
        ],
    },
    metadataLabels,
);
assert.equal(distinctMissionPanel.textContent.split('Shared title').length - 1, 2);
assert.ok(distinctMissionPanel.textContent.includes('0.0% chance'));

const hostilePanel = renderBlueprintMetadata(
    {
        loadedSections: ['MISSIONS', 'MISSION_GROUPS'],
        unlockingMissions: [
            {
                title: '<script>alert(1)</script>',
                webUrl: 'https://api.star-citizen.wiki.evil/missions/a',
            },
            { title: 'Malformed address', webUrl: 'https://[not-an-address' },
            {
                title: 'Credential address',
                webUrl: 'https://name:secret@api.star-citizen.wiki/missions/a',
            },
            { title: 'Executable address', webUrl: 'javascript:alert(1)', chance: Number.NaN },
        ],
        unlockingMissionGroups: [
            {
                label: '<img src=x onerror=alert(1)>',
                chance: 3,
                missions: [
                    {
                        title: 'Grouped unsafe link',
                        webUrl: 'data:text/html,<script>alert(1)</script>',
                    },
                ],
            },
        ],
    },
    metadataLabels,
);
assert.equal(descendants(hostilePanel).filter((element) => element.tagName === 'script').length, 0);
assert.equal(descendants(hostilePanel).filter((element) => element.tagName === 'img').length, 0);
assert.equal(descendants(hostilePanel).filter((element) => element.tagName === 'a').length, 0);
assert.ok(hostilePanel.textContent.includes('<script>alert(1)</script>'));
assert.ok(!hostilePanel.textContent.includes('NaN'));
assert.ok(!hostilePanel.textContent.includes('300.0%'));
console.log('blueprint-stats and metadata: all assertions passed');
