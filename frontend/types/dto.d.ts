/**
 * Backend DTO shapes, published as global type aliases.
 *
 * The types come from `build/generated/ts/api.d.ts`, which `:frontend:generateApiTypes` derives
 * from the backend's OpenAPI spec. `declare global` lets the classic non-module scripts use them
 * without an import (REQ-FE-018).
 */

import type { components, operations, paths } from '../build/generated/ts/api';

declare global {
    /** Every schema the backend publishes, keyed by its OpenAPI name. */
    type ApiSchemas = components['schemas'];

    /** One backend DTO by name — e.g. `ApiDto<'MaterialDto'>`. */
    type ApiDto<K extends keyof ApiSchemas> = ApiSchemas[K];

    /**
     * The paged envelope wrapping a DTO — `ApiPage<'BankBookingDto'>` resolves to
     * `PageResponseBankBookingDto`; `never` for a DTO without a paged endpoint.
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
