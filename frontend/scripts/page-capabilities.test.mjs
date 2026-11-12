/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const resources = fileURLToPath(new URL('../src/main/resources/', import.meta.url));
const templates = path.join(resources, 'templates');
const capabilities = {
    pagePickers:
        /data-krt-combobox|krtCombobox|krt-searchable-select|krt-user-search|krt-catalog-search|krt-bank-account-search/,
    pageNumbers:
        /data-number-input|data-scu-decimal|krtNumberInput|krtScu|scu-decimal-input|krt-number-input/,
    pageDates: /datetime-split-group|datetime-splitter|krtDateTime/,
    pageFilters:
        /data-filter-panel|data-filter-chips|data-list-count-for|filter-popover|filter-chips|filter-toggle|filter-count/,
    pageOverflow: /data-overflow-menu|overflow-menu/,
    pageFocus: /data-focus-id|krt-focus-target/,
};

// A module can load controller-rendered fragments absent from its page markup.
const fetchedFragments = new Map([
    [
        '/js/dashboard/dashboard-widgets.js',
        [
            'dashboard/fragments/admin-widgets',
            'dashboard/fragments/order-widgets',
            'dashboard/fragments/trade-widgets',
            'dashboard/fragments/widgets',
            'dashboard/fragments/work-widgets',
        ],
    ],
    ['/js/inventory/inventory-common.js', ['inventory/fragments/inventory-stack-entries']],
    ['/js/inventory/inventory-provision.js', ['joborder/fragments/provision-options']],
    ['/js/joborder/orders-contribution-history.js', ['joborder/fragments/contribution-history']],
]);

function filesBelow(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(directory, entry.name);
        return entry.isDirectory() ? filesBelow(full) : [full];
    });
}

function sourceOf(item) {
    const filename = item.startsWith('/js/')
        ? path.join(resources, 'static', item)
        : path.join(templates, `${item}.html`);
    return readFileSync(filename, 'utf8');
}

function contentsOf(view, readSource = sourceOf) {
    const seen = new Set();
    const pending = [view];
    const parts = [];
    while (pending.length) {
        const item = pending.pop();
        if (seen.has(item) || item === 'kernel/fragments/layout') continue;
        seen.add(item);
        const source = readSource(item);
        parts.push(source);
        if (item.startsWith('/js/')) {
            pending.push(...(fetchedFragments.get(item) ?? []));
            pending.push(
                ...Array.from(
                    source.matchAll(/(?:from\s*|import\s*)['"](\/js\/[^'"]+)['"]/g),
                    (match) => match[1],
                ),
            );
        } else {
            pending.push(
                ...Array.from(
                    source.matchAll(/~\{\s*([A-Za-z][A-Za-z0-9_/-]*)\s*(?:::|\})/g),
                    (match) => match[1],
                ),
            );
            pending.push(
                ...Array.from(
                    source.matchAll(/th:src="@\{(\/js\/[^}]+\.js)\}"/g),
                    (match) => match[1],
                ),
            );
        }
    }
    return parts.join('\n');
}

const testViewSources = new Map([
    [
        'joborder/example',
        '<div th:replace="~{joborder/fragments/included :: fields}"></div>' +
            '<script th:src="@{/js/joborder/example.js}"></script>',
    ],
    [
        'joborder/fragments/included',
        '<div th:insert="~{joborder/fragments/nested :: fields}"></div>',
    ],
    ['joborder/fragments/nested', '<select data-krt-combobox></select>'],
    ['joborder/fragments/unrelated', '<input data-number-input>'],
    ['/js/joborder/example.js', "import '/js/joborder/orders-contribution-history.js';"],
    ['/js/joborder/orders-contribution-history.js', ''],
    ['joborder/fragments/contribution-history', '<input data-focus-id>'],
]);
const testContents = contentsOf('joborder/example', (item) => {
    assert.ok(testViewSources.has(item), item);
    return testViewSources.get(item);
});
assert.ok(capabilities.pagePickers.test(testContents));
assert.ok(capabilities.pageFocus.test(testContents));
assert.ok(!capabilities.pageNumbers.test(testContents));

let checked = 0;
for (const filename of filesBelow(templates).filter((file) => file.endsWith('.html'))) {
    const source = readFileSync(filename, 'utf8');
    if (!source.includes('kernel/fragments/layout :: page(')) continue;
    const view = path.relative(templates, filename).replaceAll(path.sep, '/').slice(0, -5);
    const contents = contentsOf(view);
    for (const [capability, marker] of Object.entries(capabilities)) {
        if (source.includes(`${capability}=false`)) {
            assert.equal(
                marker.test(contents),
                false,
                `${view} disables ${capability} used by its fragments or modules`,
            );
        }
    }
    checked += 1;
}
assert.ok(checked >= 100, 'The capability inventory covers the layout pages');
console.log(`Verified optional assets across ${checked} pages and their fragment/module trees.`);
