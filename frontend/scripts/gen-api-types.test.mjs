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
 * Regression tests for scripts/gen-api-types.mjs (ADR-0130).
 *
 * Two things are worth testing here, and they are not the happy path. First, the type mapping:
 * this emitter replaced a maintained package, so a wrong `$ref` or a dropped `required` would
 * quietly mistype a DTO and the drift protection of REQ-FE-018 would pass on a lie. Second — and
 * more important — the guard: an OpenAPI construct the emitter cannot express must FAIL the build,
 * because the alternative is a DTO silently degraded to `unknown`, which type-checks everywhere.
 *
 * Runs the real script as a subprocess against synthetic specs in a temp directory. No network,
 * no Gradle, no npm packages — a couple of hundred milliseconds.
 */

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("./gen-api-types.mjs", import.meta.url));
const workdir = mkdtempSync(join(tmpdir(), "gen-api-types-test-"));

let failures = 0;

/** Runs one named assertion, recording rather than throwing so every case reports. */
function test(name, fn) {
  try {
    fn();
    console.log(`  ok  ${name}`);
  } catch (err) {
    failures += 1;
    console.error(`  FAIL ${name}\n       ${err.message}`);
  }
}

/** Runs the generator on `spec`; returns `{ ok, stderr, out }` with `out` the emitted text. */
function run(spec, label) {
  const specPath = join(workdir, `${label}.json`);
  const outPath = join(workdir, `${label}.d.ts`);
  writeFileSync(specPath, JSON.stringify(spec), "utf8");
  try {
    execFileSync(process.execPath, [SCRIPT, specPath, outPath], { stdio: "pipe" });
    return { ok: true, stderr: "", out: readFileSync(outPath, "utf8") };
  } catch (err) {
    return { ok: false, stderr: String(err.stderr ?? ""), out: "" };
  }
}

// --- type mapping -----------------------------------------------------------------------------

test("maps primitives, honours `required` for optionality", () => {
  const { ok, out } = run(
    {
      components: {
        schemas: {
          Thing: {
            type: "object",
            required: ["id"],
            properties: {
              id: { type: "string" },
              count: { type: "integer" },
              ratio: { type: "number" },
              active: { type: "boolean" },
            },
          },
        },
      },
    },
    "primitives",
  );
  assert.ok(ok, "generator should succeed");
  assert.match(out, /id: string;/, "required property must NOT be optional");
  assert.match(out, /count\?: number;/, "integer maps to number and stays optional");
  assert.match(out, /ratio\?: number;/);
  assert.match(out, /active\?: boolean;/);
});

test("resolves $ref through an indexed access, so self-reference stays legal", () => {
  const { ok, out } = run(
    {
      components: {
        schemas: {
          Node: {
            type: "object",
            properties: { parent: { $ref: "#/components/schemas/Node" } },
          },
        },
      },
    },
    "selfref",
  );
  assert.ok(ok, "self-referential schema must not break the emitter");
  assert.match(out, /parent\?: components\['schemas'\]\["Node"\];/);
});

test("emits arrays, enums, additionalProperties and nullable", () => {
  const { ok, out } = run(
    {
      components: {
        schemas: {
          Bag: {
            type: "object",
            properties: {
              tags: { type: "array", items: { type: "string" } },
              state: { type: "string", enum: ["OPEN", "DONE"] },
              meta: { type: "object", additionalProperties: { type: "number" } },
              note: { type: "string", nullable: true },
            },
          },
        },
      },
    },
    "shapes",
  );
  assert.ok(ok);
  assert.match(out, /tags\?: \(string\)\[\];/);
  assert.match(out, /state\?: "OPEN" \| "DONE";/);
  assert.match(out, /meta\?: Record<string, number>;/);
  assert.match(out, /note\?: string \| null;/);
});

test("quotes property and schema names that are not valid identifiers", () => {
  const { ok, out } = run(
    {
      components: {
        schemas: { "Odd.Name": { type: "object", properties: { "x-total": { type: "string" } } } },
      },
    },
    "quoting",
  );
  assert.ok(ok);
  assert.match(out, /"Odd\.Name":/);
  assert.match(out, /"x-total"\?: string;/);
});

test("declares paths and operations for dto.d.ts source compatibility", () => {
  const { ok, out } = run(
    { components: { schemas: { A: { type: "object", properties: {} } } } },
    "compat",
  );
  assert.ok(ok);
  assert.match(out, /export interface paths \{\}/);
  assert.match(out, /export interface operations \{\}/);
});

// --- the guard --------------------------------------------------------------------------------

for (const keyword of ["allOf", "oneOf", "anyOf", "not", "discriminator"]) {
  test(`FAILS the build on '${keyword}' instead of degrading the DTO to unknown`, () => {
    const { ok, stderr } = run(
      {
        components: {
          schemas: {
            Poly: { type: "object", properties: { v: { [keyword]: [{ type: "string" }] } } },
          },
        },
      },
      `guard-${keyword}`,
    );
    assert.equal(ok, false, `'${keyword}' must be a hard failure`);
    assert.match(stderr, new RegExp(`unsupported OpenAPI construct '${keyword}'`));
    assert.match(stderr, /components\.schemas/, "the error must name the JSON path");
  });
}

test("FAILS on a dangling $ref rather than emitting a broken reference", () => {
  const { ok, stderr } = run(
    {
      components: {
        schemas: { A: { type: "object", properties: { b: { $ref: "#/components/schemas/Gone" } } } },
      },
    },
    "dangling",
  );
  assert.equal(ok, false);
  assert.match(stderr, /dangling \$ref/);
});

test("FAILS on a spec with no schemas, rather than emitting an empty map", () => {
  const { ok, stderr } = run({ components: {} }, "empty");
  assert.equal(ok, false, "an empty emit would type-check everywhere and hide the breakage");
  assert.match(stderr, /no components\.schemas/);
});

rmSync(workdir, { recursive: true, force: true });

if (failures > 0) {
  console.error(`\ngen-api-types.test.mjs: ${failures} failure(s)`);
  process.exit(1);
}
console.log("\ngen-api-types.test.mjs: all assertions passed");
