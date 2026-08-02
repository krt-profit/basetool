/**
 * Backend DTO shapes, published as global type aliases.
 *
 * The types come from `build/generated/ts/api.d.ts`, which the
 * `:frontend:generateApiTypes` Gradle task derives from the backend's OpenAPI
 * spec on every build. Nothing here is hand-maintained and the generated file
 * is never committed, so the frontend's idea of a DTO cannot drift away from
 * the contract the backend actually publishes: rename a field in a backend DTO
 * and the JSDoc annotation that reads it stops compiling.
 *
 * `declare global` is what makes this usable from the static scripts. Those are
 * classic non-module scripts sharing one global scope (ADR-0069), and a bare
 * `import` in one of them would turn it into an ES module and change that
 * scope. Publishing the aliases globally lets a page module annotate with
 * `ApiDto<'MaterialDto'>` and no import at all.
 *
 * See ADR-0125 and REQ-FE-018.
 */

import type { components, operations, paths } from '../build/generated/ts/api';

declare global {
    /** Every schema the backend publishes, keyed by its OpenAPI name. */
    type ApiSchemas = components['schemas'];

    /** One backend DTO by name — e.g. `ApiDto<'MaterialDto'>`. */
    type ApiDto<K extends keyof ApiSchemas> = ApiSchemas[K];

    /**
     * The paged envelope wrapping a DTO — `ApiPage<'BankBookingDto'>` resolves
     * to `PageResponseBankBookingDto`. Valid only for DTOs the backend really
     * exposes a paged endpoint for; anything else resolves to `never`, which is
     * the point.
     */
    type ApiPage<K extends string & keyof ApiSchemas> = `PageResponse${K}` extends keyof ApiSchemas
        ? ApiSchemas[`PageResponse${K}`]
        : never;

    /** The full path map, for typing a URL against the published routes. */
    type ApiPaths = paths;

    /** The full operation map, keyed by `operationId`. */
    type ApiOperations = operations;

    /** RFC 7807 problem body returned by every error path (REQ-API-*). */
    type ApiProblem = {
        /** URI identifying the problem type. */
        type?: string;
        /** Short human-readable summary. */
        title?: string;
        /** The HTTP status, repeated in the body. */
        status?: number;
        /** Human-readable explanation specific to this occurrence. */
        detail?: string;
        /** URI identifying the specific occurrence. */
        instance?: string;
    };
}

export {};
