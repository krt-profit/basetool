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
 * Derives the frontend's TypeScript view of the backend DTOs from `openapi.json`.
 *
 * Emits `components['schemas'][<Name>]` declarations that `types/dto.d.ts` lifts into the global
 * `ApiDto<'…'>` alias, so a backend field rename fails `:frontend:typecheckJs` instead of silently
 * emptying a picker at runtime (REQ-FE-018, ADR-0125/ADR-0130).
 *
 * WHY THIS IS HAND-ROLLED RATHER THAN `openapi-typescript`
 * -------------------------------------------------------
 * Emitting a `.d.ts` is printing text. `openapi-typescript` printed the same text through the
 * TypeScript compiler API (`ts.factory`, `ts.createPrinter`), and TypeScript 7's native compiler
 * removed that API — `require('typescript')` now resolves to `lib/version.cjs` and exposes nothing
 * else. That made a maintained third-party generator the single thing pinning the whole repo to
 * the TypeScript 5.x line, for output we consume three annotations of. Printing the declarations
 * ourselves costs ~90 lines, has no dependency to pin, and drops the generated file from 54 334
 * lines to ~3 000 because we emit only `components.schemas` — `paths` and `operations` had zero
 * usages (ADR-0130 records the full reasoning and the measurements).
 *
 * SCOPE — WHAT THIS DELIBERATELY DOES NOT HANDLE
 * ----------------------------------------------
 * springdoc emits flat object schemas: at the time of writing all 398 schemas are `type: object`
 * and the spec contains **no** `allOf` / `oneOf` / `anyOf` at all. So this generator handles
 * exactly what the spec uses — `$ref`, `items`, `enum`, `additionalProperties`, `nullable` and the
 * primitive types — and nothing more. If a DTO ever gains polymorphism (a `@JsonSubTypes` on a
 * backend model is the realistic trigger), those keywords will appear in the spec and this
 * generator must be extended; it will emit `unknown` for the unhandled node rather than fail, so
 * the symptom is a suddenly-untyped DTO rather than a broken build. The `assertNoPolymorphism`
 * guard below turns that silent degradation into a loud one.
 *
 * Usage: node scripts/gen-api-types.mjs <openapi.json> <out.d.ts>
 */

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const [, , specPath, outPath] = process.argv;
if (!specPath || !outPath) {
  console.error("usage: gen-api-types.mjs <openapi.json> <out.d.ts>");
  process.exit(2);
}

const spec = JSON.parse(readFileSync(specPath, "utf8"));
const schemas = spec.components?.schemas ?? {};
if (Object.keys(schemas).length === 0) {
  console.error(`no components.schemas found in ${specPath}`);
  process.exit(1);
}

/**
 * Fails the build when the spec grows a construct this generator cannot express.
 *
 * Without it an unhandled keyword degrades the affected DTO to `unknown`, which type-checks
 * everywhere and silently removes exactly the drift protection the file exists to provide.
 */
function assertNoPolymorphism(node, path) {
  if (!node || typeof node !== "object") return;
  if (Array.isArray(node)) {
    node.forEach((v, i) => assertNoPolymorphism(v, `${path}[${i}]`));
    return;
  }
  for (const kw of ["allOf", "oneOf", "anyOf", "not", "discriminator"]) {
    if (kw in node) {
      console.error(
        `${specPath}: unsupported OpenAPI construct '${kw}' at ${path}.\n` +
          "scripts/gen-api-types.mjs emits only the flat-object subset springdoc used to produce.\n" +
          "Extend the generator (see its header comment) — do not silently drop the type.",
      );
      process.exit(1);
    }
  }
  for (const [k, v] of Object.entries(node)) assertNoPolymorphism(v, `${path}.${k}`);
}
assertNoPolymorphism(schemas, "components.schemas");

/** Renders a property name: bare when it is a valid TS identifier, quoted otherwise. */
const key = (k) => (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(k) ? k : JSON.stringify(k));

/** Resolves a local `$ref` into an indexed access, which keeps self-referential DTOs legal. */
function fromRef(ref) {
  const name = ref.replace("#/components/schemas/", "");
  if (!(name in schemas)) {
    console.error(`${specPath}: dangling $ref '${ref}' — no such schema.`);
    process.exit(1);
  }
  return `components['schemas'][${JSON.stringify(name)}]`;
}

/** Maps one OpenAPI schema node to a TypeScript type expression. */
function toType(schema, indent) {
  if (!schema || typeof schema !== "object") return "unknown";
  if (schema.$ref) return fromRef(schema.$ref);
  if (Array.isArray(schema.enum)) {
    return schema.enum.map((v) => JSON.stringify(v)).join(" | ") || "never";
  }
  switch (schema.type) {
    case "string":
      return "string";
    case "integer":
    case "number":
      return "number";
    case "boolean":
      return "boolean";
    case "array":
      return `(${toType(schema.items, indent)})[]`;
    default: {
      if (schema.additionalProperties && typeof schema.additionalProperties === "object") {
        return `Record<string, ${toType(schema.additionalProperties, indent)}>`;
      }
      if (!schema.properties) {
        return schema.type === "object" ? "Record<string, unknown>" : "unknown";
      }
      const required = new Set(schema.required ?? []);
      const pad = "  ".repeat(indent + 1);
      const body = Object.entries(schema.properties)
        .map(([name, prop]) => {
          const optional = required.has(name) ? "" : "?";
          const nullable = prop.nullable ? " | null" : "";
          return `${pad}${key(name)}${optional}: ${toType(prop, indent + 1)}${nullable};`;
        })
        .join("\n");
      return `{\n${body}\n${"  ".repeat(indent)}}`;
    }
  }
}

const entries = Object.entries(schemas)
  .sort(([a], [b]) => a.localeCompare(b))
  .map(([name, schema]) => `    ${key(name)}: ${toType(schema, 2)};`)
  .join("\n");

const out = `/*
 * AUTO-GENERATED from openapi.json by scripts/gen-api-types.mjs — do not edit, do not commit.
 * Regenerate with: ./gradlew :frontend:generateApiTypes
 */
/* eslint-disable */

export interface components {
  schemas: {
${entries}
  };
}

/*
 * Declared for source compatibility with \`types/dto.d.ts\`, which imports all three names.
 * \`ApiPaths\` / \`ApiOperations\` have no usages today; emitting the full path and operation maps
 * cost 51 000 lines of output for nothing (ADR-0130).
 */
export interface paths {}
export interface operations {}
`;

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, out, "utf8");
console.log(
  `gen-api-types: ${Object.keys(schemas).length} schemas -> ${outPath} (${out.split("\n").length} lines)`,
);
