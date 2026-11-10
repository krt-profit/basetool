/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const [specPath, outputDirectory] = process.argv.slice(2);
if (!specPath || !outputDirectory)
    throw new Error('Expected OpenAPI input and Java output directory');
const document = JSON.parse(readFileSync(specPath, 'utf8'));
const models = {
    PurchasingVersionRequest: {
        domain: 'purchasing',
        summary: 'Carries the expected optimistic-lock version for a purchasing write.',
        fields: { version: 'the echoed optimistic-lock version' },
    },
};

function fieldType(schema) {
    if (schema.enum || schema.$ref || schema.allOf || schema.oneOf || schema.anyOf) {
        throw new Error('The wire-record pilot requires a flat scalar contract');
    }
    switch (schema.type) {
        case 'integer':
            return schema.format === 'int64' ? 'Long' : 'Integer';
        case 'boolean':
            return 'Boolean';
        case 'number':
            return schema.format === 'float' ? 'Float' : 'Double';
        case 'string':
            if (schema.format) throw new Error(`Unsupported scalar format ${schema.format}`);
            return 'String';
        default:
            throw new Error(`Unsupported scalar type ${schema.type}`);
    }
}

for (const [name, model] of Object.entries(models)) {
    const schema = document.components.schemas[name];
    if (!schema || schema.type !== 'object' || !schema.properties || schema.additionalProperties) {
        throw new Error(`Missing flat object schema ${name}`);
    }
    const properties = Object.entries(schema.properties);
    if (
        properties.some(([field]) => !model.fields[field]) ||
        Object.keys(model.fields).length !== properties.length
    ) {
        throw new Error(`Document the changed fields of ${name}`);
    }
    const required = new Set(schema.required || []);
    const packageName = `de.greluc.krt.profit.basetool.frontend.${model.domain}.model`;
    const components = properties.map(
        ([field, value]) => `${required.has(field) ? '@NotNull ' : ''}${fieldType(value)} ${field}`,
    );
    const source = `/*\n * Profit Basetool - squadron-management web app.\n * Copyright (C) 2026 Lucas Greuloch\n *\n * SPDX-License-Identifier: GPL-3.0-only\n */\n\npackage ${packageName};\n\nimport de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;\nimport jakarta.validation.constraints.NotNull;\n\n/**\n * ${model.summary}\n *\n${properties.map(([field]) => ` * @param ${field} ${model.fields[field]}`).join('\n')}\n */\n@DtoMirror\npublic record ${name}(${components.join(', ')}) {}\n`;
    const directory = path.join(outputDirectory, ...packageName.split('.'));
    mkdirSync(directory, { recursive: true });
    writeFileSync(path.join(directory, name + '.java'), source);
}
