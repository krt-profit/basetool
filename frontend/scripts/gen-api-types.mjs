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
 * `ApiDto<'…'>` alias (REQ-FE-018, ADR-0130).
 *
 * Handles the flat-object subset springdoc emits: `$ref`, `items`, `enum`, `additionalProperties`,
 * `nullable` and the primitive types. Polymorphic constructs fail the build.
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
 * Fails the build when the spec contains a construct this generator cannot express
 * (`allOf`, `oneOf`, `anyOf`, `not`, `discriminator`).
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
