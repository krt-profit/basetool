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

function filesBelow(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(directory, entry.name);
        return entry.isDirectory() ? filesBelow(full) : [full];
    });
}

function contentsOf(view) {
    const seen = new Set();
    const pending = [view];
    const parts = [];
    const domain = view.split('/')[0];
    while (pending.length) {
        const item = pending.pop();
        if (seen.has(item) || item === 'kernel/fragments/layout') continue;
        seen.add(item);
        const filename = item.startsWith('/js/')
            ? path.join(resources, 'static', item)
            : path.join(templates, `${item}.html`);
        const source = readFileSync(filename, 'utf8');
        parts.push(source);
        if (item.startsWith('/js/')) {
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
            const scripts = Array.from(
                source.matchAll(/th:src="@\{(\/js\/[^}]+\.js)\}"/g),
                (match) => match[1],
            );
            pending.push(...scripts);
            if (scripts.length) {
                pending.push(
                    ...filesBelow(path.join(templates, domain))
                        .filter(
                            (file) =>
                                file.endsWith('.html') &&
                                file.includes(`${path.sep}fragments${path.sep}`),
                        )
                        .map((file) =>
                            path.relative(templates, file).replaceAll(path.sep, '/').slice(0, -5),
                        ),
                );
            }
        }
    }
    return parts.join('\n');
}

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
