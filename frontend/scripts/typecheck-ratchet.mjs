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
 * The type-check ratchet (REQ-FE-018, ADR-0125): every script under static/js, subfolders included,
 * belongs to exactly one domain of typecheck-ratchet.json, every domain is migrated, and a migrated
 * domain holds only files that opt into the type check. The set of migrated domains and the number
 * of checked files may only grow.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The migrated domains that may never leave the manifest's "migrated" list. */
export const MIGRATED_FLOOR = [
    'audit',
    'bank',
    'blueprint',
    'catalogue',
    'dashboard',
    'exchange',
    'hangar',
    'identity',
    'inventory',
    'joborder',
    'kernel',
    'leadership',
    'materialexchange',
    'mission',
    'notification',
    'operation',
    'orgchart',
    'orgunit',
    'personalinventory',
    'promotion',
    'refinery',
    'settings',
];

/** The number of scripts carrying the `// @ts-check` pragma that may never fall. */
export const CHECKED_FILES_FLOOR = 142;

const ALLOWED_EXCLUDES = ['src/main/resources/static/js/vendor/**', 'node_modules', 'build'];
const EXCLUDED_DIRECTORIES = ['vendor/', 'node_modules/'];
const SUPPRESSION = /@ts-(?:nocheck|ignore|expect-error)\b/;
const DOMAIN_NAME = /^[a-z][a-z0-9-]*$/;

/**
 * Tells whether a script opts into the type check: a `// @ts-check` line in its leading comments.
 *
 * @param {string} source the script's text
 * @returns {boolean} true when the pragma precedes the first statement
 */
export function isChecked(source) {
    let pos = 0;
    for (;;) {
        while (pos < source.length && /\s/.test(source[pos])) {
            pos += 1;
        }
        if (source.startsWith('//', pos)) {
            let end = source.indexOf('\n', pos);
            if (end === -1) {
                end = source.length;
            }
            if (source.slice(pos, end).trim() === '// @ts-check') {
                return true;
            }
            pos = end;
        } else if (source.startsWith('/*', pos)) {
            const end = source.indexOf('*/', pos + 2);
            if (end === -1) {
                return false;
            }
            pos = end + 2;
        } else {
            return false;
        }
    }
}

/**
 * Evaluates the ratchet over a set of scripts.
 *
 * @param {object} input the tree to judge
 * @param {Map<string, string>} input.scripts script file name to its text, vendor excluded
 * @param {{domains: Record<string, string[]>, migrated: string[]}} input.manifest typecheck-ratchet.json
 * @param {{compilerOptions?: {strict?: boolean, checkJs?: boolean}, include?: string[], exclude?: string[]}} input.tsconfig tsconfig.json
 * @param {string[]} input.migratedFloor the migrated domains that must stay migrated
 * @param {number} input.checkedFloor the least number of checked scripts
 * @returns {string[]} one message per violation, empty when the ratchet holds
 */
export function evaluate({ scripts, manifest, tsconfig, migratedFloor, checkedFloor }) {
    const violations = [];
    const owner = new Map();

    for (const [domain, files] of Object.entries(manifest.domains)) {
        if (!DOMAIN_NAME.test(domain)) {
            violations.push(`domain name "${domain}" is not lower-case kebab`);
        }
        for (const file of files) {
            if (owner.has(file)) {
                violations.push(`${file} is listed under both ${owner.get(file)} and ${domain}`);
            }
            owner.set(file, domain);
            if (!scripts.has(file)) {
                violations.push(`${domain} lists ${file}, which does not exist`);
            }
        }
    }
    for (const file of scripts.keys()) {
        if (!owner.has(file)) {
            violations.push(`${file} belongs to no domain of typecheck-ratchet.json`);
        }
    }

    const migrated = new Set(manifest.migrated);
    if (migrated.size !== manifest.migrated.length) {
        violations.push('"migrated" lists a domain twice');
    }
    for (const domain of migrated) {
        if (!(domain in manifest.domains)) {
            violations.push(`"migrated" names ${domain}, which is not a domain`);
        }
    }
    for (const domain of migratedFloor) {
        if (!migrated.has(domain)) {
            violations.push(`${domain} was migrated and left the "migrated" list`);
        }
    }
    for (const domain of Object.keys(manifest.domains)) {
        if (!migrated.has(domain)) {
            violations.push(`${domain} is a domain but is not migrated; every domain must be`);
        }
    }
    for (const domain of migrated) {
        for (const file of manifest.domains[domain] ?? []) {
            const source = scripts.get(file);
            if (source !== undefined && !isChecked(source)) {
                violations.push(
                    `${file} is in the migrated domain ${domain} but has no // @ts-check`,
                );
            }
        }
    }

    let checked = 0;
    for (const [file, source] of scripts) {
        if (isChecked(source)) {
            checked += 1;
        }
        if (SUPPRESSION.test(source)) {
            violations.push(
                `${file} suppresses the type check (@ts-nocheck, @ts-ignore, @ts-expect-error)`,
            );
        }
    }
    if (checked < checkedFloor) {
        violations.push(
            `${checked} scripts are type-checked, fewer than the floor of ${checkedFloor}`,
        );
    }

    const options = tsconfig.compilerOptions ?? {};
    if (options.strict !== true) {
        violations.push('tsconfig.json must keep "strict": true');
    }
    if (options.checkJs === undefined) {
        violations.push('tsconfig.json must state "checkJs"');
    }
    for (const entry of tsconfig.exclude ?? []) {
        if (!ALLOWED_EXCLUDES.includes(entry)) {
            violations.push(`tsconfig.json excludes ${entry}, which would hide checked scripts`);
        }
    }
    if (!(tsconfig.include ?? []).includes('src/main/resources/static/js/**/*.js')) {
        violations.push('tsconfig.json must include src/main/resources/static/js/**/*.js');
    }
    return violations;
}

/**
 * Checks that every listed script lives in the folder of the domain it is listed under.
 *
 * @param {{domains: Record<string, string[]>}} manifest typecheck-ratchet.json
 * @returns {string[]} one message per script outside its domain's folder
 */
export function layoutViolations(manifest) {
    const violations = [];
    for (const [domain, files] of Object.entries(manifest.domains)) {
        for (const file of files) {
            if (!file.startsWith(`${domain}/`) || file.indexOf('/', domain.length + 1) !== -1) {
                violations.push(
                    `${file} is listed under ${domain} but is not in static/js/${domain}/`,
                );
            }
        }
    }
    return violations;
}

/**
 * Reads the real tree below the frontend module.
 *
 * @param {string} root the frontend module directory, with a trailing slash
 * @returns {{scripts: Map<string, string>, manifest: object, tsconfig: object}} what evaluate needs
 */
export function loadTree(root) {
    const jsDir = `${root}src/main/resources/static/js/`;
    const scripts = new Map();
    for (const entry of readdirSync(jsDir, { withFileTypes: true, recursive: true })) {
        if (!entry.isFile() || !entry.name.endsWith('.js')) {
            continue;
        }
        const absolute = join(entry.parentPath, entry.name);
        const script = relative(jsDir, absolute).replaceAll('\\', '/');
        if (EXCLUDED_DIRECTORIES.some((directory) => script.startsWith(directory))) {
            continue;
        }
        scripts.set(script, readFileSync(absolute, 'utf8'));
    }
    const manifest = JSON.parse(readFileSync(`${root}typecheck-ratchet.json`, 'utf8'));
    const tsconfig = JSON.parse(readFileSync(`${root}tsconfig.json`, 'utf8'));
    return { scripts, manifest, tsconfig };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const root = fileURLToPath(new URL('..', import.meta.url));
    const tree = loadTree(root);
    const violations = [
        ...evaluate({ ...tree, migratedFloor: MIGRATED_FLOOR, checkedFloor: CHECKED_FILES_FLOOR }),
        ...layoutViolations(tree.manifest),
    ];
    for (const violation of violations) {
        console.error(`type-check ratchet: ${violation}`);
    }
    process.exit(violations.length === 0 ? 0 : 1);
}
