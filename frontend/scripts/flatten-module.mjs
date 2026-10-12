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
 * Turns a browser ES module and the modules it imports into one classic script, so the node tests
 * can run it in a `vm` context with fake browser globals (REQ-FE-035). Imports are inlined
 * dependencies-first, `import` statements and the `export` keyword are dropped, which is valid
 * because the modules of one graph share no top-level name.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * Stand-ins for the modules that touch the DOM when they load: the transport only calls them, so a
 * node test replaces them with inert functions of the same names.
 */
export const UI_STUBS = {
    '/js/kernel/krt-modal.js':
        'const krtModal = { open() {}, close() {}, isOpen() { return false; }, topmost() { return null; }, layerRoot() { return null; } };',
    '/js/kernel/toast.js':
        'function showFrontendErrorToast() {}\nfunction showFrontendSuccessToast() {}\nfunction showKrtConfirm() { return Promise.resolve(true); }',
};

const ROOT = fileURLToPath(new URL('../src/main/resources/static/', import.meta.url));

const FROM_IMPORT = /^import\b[^;'"]*?\bfrom\s*(['"])(\/js\/[^'"]+)\1;?[ \t]*\n/gm;
const BARE_IMPORT = /^import\s*(['"])(\/js\/[^'"]+)\1;?[ \t]*\n/gm;
const EXPORT_LIST = /^export\s*\{[^}]*\};?[ \t]*\n/gm;
const EXPORT_DECLARATION = /^export\s+(async\s+function|function|const|let|class)\b/gm;

/**
 * Reads a script below `static/`.
 *
 * @param {string} path the asset path such as `/js/kernel/krt-fetch.js`
 * @returns {string} the file's text
 */
function read(path) {
    return readFileSync(`${ROOT}${path.replace(/^\//, '')}`, 'utf8');
}

/**
 * Lists the asset paths a module imports statically.
 *
 * @param {string} source the module's text
 * @returns {string[]} the imported `/js/...` paths in source order
 */
function importsOf(source) {
    const found = [];
    for (const pattern of [FROM_IMPORT, BARE_IMPORT]) {
        for (const match of source.matchAll(pattern)) {
            found.push({ at: match.index, path: match[2] });
        }
    }
    return found.sort((a, b) => a.at - b.at).map((entry) => entry.path);
}

/**
 * Flattens a module and its imports into one classic script.
 *
 * @param {string} entry the entry module, relative to `static/js`, such as `kernel/krt-fetch.js`
 * @param {Record<string, string>} [stubs] source text to use instead of a module, keyed by its path
 * @returns {string} the script text, dependencies first
 */
export function flattenModule(entry, stubs = {}) {
    const seen = new Set();
    const parts = [];
    const visit = (path) => {
        if (seen.has(path)) {
            return;
        }
        seen.add(path);
        if (path in stubs) {
            parts.push(stubs[path]);
            return;
        }
        const source = read(path);
        importsOf(source).forEach(visit);
        parts.push(
            source
                .replace(FROM_IMPORT, '')
                .replace(BARE_IMPORT, '')
                .replace(EXPORT_LIST, '')
                .replace(EXPORT_DECLARATION, '$1'),
        );
    };
    visit(`/js/${entry}`);
    return parts.join('\n');
}
