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
 * belongs to exactly one domain of typecheck-ratchet.json and lives in that domain's folder, no
 * script suppresses the type check, and tsconfig.json keeps every script checked with the strict
 * options on.
 */

import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The compiler options that must stay on, with the value each must hold. */
export const REQUIRED_OPTIONS = {
    strict: true,
    checkJs: true,
    noImplicitAny: true,
    useUnknownInCatchVariables: true,
};

const ALLOWED_EXCLUDES = ['src/main/resources/static/js/vendor/**', 'node_modules', 'build'];
const EXCLUDED_DIRECTORIES = ['vendor/', 'node_modules/'];
const SUPPRESSION = /@ts-(?:nocheck|ignore|expect-error)\b/;
const DOMAIN_NAME = /^[a-z][a-z0-9-]*$/;

/**
 * Evaluates the ratchet over a set of scripts.
 *
 * @param {object} input the tree to judge
 * @param {Map<string, string>} input.scripts script file name to its text, vendor excluded
 * @param {{domains: Record<string, string[]>}} input.manifest typecheck-ratchet.json
 * @param {{compilerOptions?: Record<string, boolean>, include?: string[], exclude?: string[]}} input.tsconfig tsconfig.json
 * @returns {string[]} one message per violation, empty when the ratchet holds
 */
export function evaluate({ scripts, manifest, tsconfig }) {
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
    for (const [file, source] of scripts) {
        if (!owner.has(file)) {
            violations.push(`${file} belongs to no domain of typecheck-ratchet.json`);
        }
        if (SUPPRESSION.test(source)) {
            violations.push(
                `${file} suppresses the type check (@ts-nocheck, @ts-ignore, @ts-expect-error)`,
            );
        }
    }

    const options = tsconfig.compilerOptions ?? {};
    for (const [option, required] of Object.entries(REQUIRED_OPTIONS)) {
        if (options[option] !== required) {
            violations.push(`tsconfig.json must keep "${option}": ${required}`);
        }
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
    const violations = [...evaluate(tree), ...layoutViolations(tree.manifest)];
    for (const violation of violations) {
        console.error(`type-check ratchet: ${violation}`);
    }
    process.exit(violations.length === 0 ? 0 : 1);
}
