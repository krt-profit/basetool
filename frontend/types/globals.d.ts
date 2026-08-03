/**
 * Ambient declarations for the cross-file runtime contract of the static
 * browser scripts.
 *
 * The scripts under `static/js` are loaded as classic <script> tags and share
 * ONE global lexical environment (ADR-0069). A file therefore consumes helpers
 * and API objects that a *different* file installed, with nothing in the source
 * stating the shape of what it consumes — until now that contract lived only in
 * the `/* global ... *\/` ESLint headers, which carry names but no types.
 *
 * This file is that contract, typed. It declares only the surface that is
 * genuinely shared across pages; per-page constants injected by a Thymeleaf
 * bootstrap block live in `thymeleaf-bootstrap.d.ts`, and backend DTO shapes
 * come from the generated `dto.d.ts`.
 *
 * See ADR-0125 and REQ-FE-018.
 */

// ---------------------------------------------------------------- primitives

/** Outcome of a `krtFetch` write: the parsed body plus the raw HTTP status. */
interface KrtWriteResult {
    /** True when the response status was 2xx and the body parsed without error. */
    ok: boolean;
    /** The HTTP status code; 0 when the request failed before a response arrived. */
    status: number;
    /**
     * The parsed response body — a DTO on success, an RFC 7807 `problem+json`
     * object on a handled error, or null when the response carried no body.
     */
    body: any;
}

/**
 * A localized message dictionary handed from a Thymeleaf bootstrap block to a
 * page module (the `window.MISSION_SUBRES_I18N` handoff pattern of #574).
 * Keys are dotted message keys; values are the resolved translations.
 */
type KrtI18nDict = Record<string, string>;

/**
 * An element reference accepted wherever the helpers take "container or
 * selector". `null` is included deliberately: every consumer resolves the
 * reference and bails out when nothing was found, so passing the unchecked
 * result of `getElementById` / `querySelector` straight through is the intended
 * usage rather than an oversight the type should forbid.
 */
type KrtElementRef = Element | string | null;

// -------------------------------------------------------- cross-file helpers

/**
 * Escapes `&`, `<`, `>`, `"`, `'` and `/` so a value can be interpolated into
 * `innerHTML` or a template literal. Returns the empty string for null and
 * undefined. Installed by `escape-html.js`.
 */
declare function escapeHtml(value: unknown): string;

/**
 * Alias of {@linkcode escapeHtml} used at attribute-value interpolation sites,
 * where the distinct name documents the intent. Installed by `escape-html.js`.
 */
declare function escapeAttr(value: unknown): string;

/** Shows a transient error toast. Installed by the layout's toast module. */
declare function showFrontendErrorToast(message: string): void;

/** Shows a transient success toast. Installed by the layout's toast module. */
declare function showFrontendSuccessToast(message: string): void;

// ------------------------------------------------------------ the krt* APIs

/** CSRF token access and refresh, installed by `krt-fetch.js`. */
interface KrtCsrfApi {
    /** The current CSRF token from the `_csrf` meta tag, or null when absent. */
    token(): string | null;
    /** The header name the token must be sent under, or null when absent. */
    headerName(): string | null;
    /** Merges the CSRF header into `base` and returns the merged header map. */
    headers(base?: Record<string, string>): Record<string, string>;
    /**
     * Re-reads the CSRF token from `GET /csrf` and updates the meta tags, so a
     * page whose token rotated can keep writing without a reload. Resolves the
     * fresh token pair, or null when the refresh failed — call sites test the
     * result to decide whether retrying the write is worthwhile. Concurrent
     * calls share one in-flight request.
     */
    refresh(): Promise<{ token: string; headerName: string } | null>;
}

/** Localized strings for the 409 optimistic-lock conflict dialog. */
interface KrtConflictStrings {
    /** Dialog title; defaults to "Konflikt". */
    title?: string;
    /** The question asked before reloading the current values. */
    reloadQuestion?: string;
    /** Confirm-button label for reloading. */
    reloadLabel?: string;
    /** Dismiss-button label. */
    dismissLabel?: string;
    /** Detail text used when the problem response carried none. */
    reloadDetailFallback?: string;
}

/** Options shared by every `krtFetch` request helper. */
interface KrtSendOpts {
    /**
     * Container (or selector) whose `[data-version]` descendants receive the
     * fresh version from the response body on success, so the next write on the
     * same aggregate does not 409 on a stale version.
     */
    containerSelector?: KrtElementRef;
    /** Already-localized prefix for the success toast. */
    sectionLabel?: string;
    /** Already-localized prefix for the error and conflict toasts. */
    conflictSectionLabel?: string;
    /** Already-localized success text; defaults to "Gespeichert.". */
    successMessage?: string;
    /** Set false to suppress the success toast entirely. */
    toast?: boolean;
    /** Already-localized generic error text. */
    errorMessage?: string;
    /** Localized strings for the optimistic-lock conflict dialog. */
    conflict?: KrtConflictStrings;
    /** The submit button disabled for the in-flight request (double-submit guard). */
    submitter?: KrtElementRef;
    /**
     * Runs after a 2xx with the parsed body. If it returns a thenable the write
     * awaits it, so a serialized chain waits for the caller's fragment refresh
     * — which rewrites the version holder — before the next queued write starts.
     */
    onSuccess?: (body: any) => void | Promise<unknown>;
    /**
     * Runs on a non-ok, non-reauth response BEFORE the default problem
     * handling. Return truthy to signal "handled" (e.g. after rendering 422
     * field errors) and skip the default toast and conflict dialog.
     */
    onError?: (status: number, body: any, response: Response) => unknown;
    /**
     * Runs when the request failed at the transport layer, so no response ever
     * arrived and `onError` never fires. Return truthy to signal that it
     * surfaced its own error UI and suppress the default network-error toast.
     */
    onNetworkError?: (error: unknown) => unknown;
}

/** Options for {@linkcode KrtFetchApi.write}. */
interface KrtWriteOpts extends KrtSendOpts {
    /** HTTP method; defaults to PATCH. */
    method?: string;
    /** Target URL, or a thunk resolved at send time so a queued write re-reads it. */
    url: string | (() => string);
    /** JSON payload, or a thunk resolved at send time; omitted for GET and DELETE. */
    payload?: unknown | (() => unknown);
    /**
     * Lock-scope key: writes sharing it run one at a time, in order. Pair it
     * with thunk `url`/`payload` so a queued write re-reads its optimistic-lock
     * version after the preceding same-key write refreshed the version holder.
     */
    serialize?: string;
    /** Section key resolved against the dictionary by a `sectionWrite` wrapper. */
    sectionKey?: string;
}

/** Options for {@linkcode KrtFetchApi.submitForm}. */
interface KrtSubmitFormOpts extends KrtSendOpts {
    /** The form element or a selector for it; supplies action, method and FormData. */
    form: HTMLFormElement | string;
    /** Target URL; defaults to the form's `action` attribute. */
    url?: string | (() => string);
    /** HTTP method; defaults to the form's `method`, else POST. */
    method?: string;
    /** Explicit FormData; defaults to `new FormData(form)`. */
    formData?: FormData;
    /** Lock-scope key, as on {@linkcode KrtWriteOpts.serialize}. */
    serialize?: string;
    /** Section key resolved against the dictionary by a `sectionWrite` wrapper. */
    sectionKey?: string;
}

/** Options for the fragment-swap helpers. */
interface KrtSwapOpts {
    /** The container to replace in place, or a selector for it. */
    container: KrtElementRef;
    /** Source URL; the fragment query param is appended when missing. */
    url?: string;
    /** Loading element (or selector) toggled for the duration of the fetch. */
    indicator?: KrtElementRef;
    /** Name of the fragment query param; defaults to "fragment". */
    fragmentParam?: string;
    /** Value of the fragment query param; defaults to "results". */
    fragmentValue?: string;
    /**
     * When true the address-bar URL is kept in sync via `history.replaceState`
     * (minus the internal fragment param) so a refresh or deep link re-renders
     * the same state. Deliberately replaceState, not pushState, so a debounced
     * filter does not flood the back-stack with intermediate keystrokes.
     */
    history?: boolean;
    /** Unless false, the scroll position is restored after the swap. */
    preserveScroll?: boolean;
    /**
     * Already-localized error toast shown when the swap bails because the
     * response was redirected or not OK. Omit to fail silently; the stale
     * container is left untouched either way.
     */
    errorMessage?: string;
}

/** Configuration for {@linkcode KrtFetchApi.sectionWrite}. */
interface KrtSectionWriteConfig {
    /**
     * Late-bound accessor for the page's i18n dictionary — late because the
     * bootstrap block that defines it runs after this factory is instantiated.
     */
    dict(): KrtI18nDict | null | undefined;
    /** Message-key prefixes used to derive the per-section localized strings. */
    keys: Record<string, string>;
    /** Maps a section key to its swap container and Thymeleaf fragment value. */
    sections: Record<string, { container: string; fragmentValue: string }>;
    /** Late-bound accessor for the URL the section fragments are fetched from. */
    pageUrl(): string;
    /** Late-bound accessor for the peer-broadcast channel, when the page has one. */
    broadcast?(): { send(section: string): void } | null | undefined;
}

/** The `{write, refresh, notify}` trio returned by {@linkcode KrtFetchApi.sectionWrite}. */
interface KrtSectionWriter {
    /** {@linkcode KrtFetchApi.write} with the section's localized strings applied. */
    write(opts: KrtWriteOpts): Promise<KrtWriteResult>;
    /**
     * Re-renders one or more sections in place and broadcasts the change to
     * peers. Resolves once every swap completed — with one flag per section, in
     * the order given — so callers can then close a modal.
     */
    refresh(sectionKeys: string | string[], opts?: KrtSwapOpts): Promise<boolean[]>;
    /** Broadcast-only sibling of `refresh` for handlers that already patched the DOM. */
    notify(sectionKeys: string | string[]): void;
}

/**
 * The shared AJAX-mutation foundation installed by `krt-fetch.js`: every
 * create/update/delete on every page routes through it so CSRF, optimistic-lock
 * version sync, re-auth, RFC 7807 problem handling and toasts stay uniform
 * (REQ-FE-001…010, ADR-0012/0013).
 */
interface KrtFetchApi {
    /** Sends a JSON write and handles the response, returning the parsed outcome. */
    write(opts: KrtWriteOpts): Promise<KrtWriteResult>;
    /** Submits a form as multipart/form-encoded data through the same pipeline. */
    submitForm(opts: KrtSubmitFormOpts): Promise<KrtWriteResult>;
    /**
     * Fetches a rendered fragment and replaces the container's content in
     * place. Resolves false when the container could not be resolved or the
     * fetch failed, true when the swap happened.
     */
    swap(opts: KrtSwapOpts): Promise<boolean>;
    /** Binds in-container pagination/sort anchor interception without an initial fetch. */
    bindSwap(opts: KrtSwapOpts): void;
    /**
     * Writes `newVersion` to the container and to every `[data-version]`
     * descendant, so the next write on the same aggregate sends a fresh version.
     */
    syncVersion(container: KrtElementRef, newVersion: number | string | null): void;
    /** Handles an RFC 7807 `problem+json` response, showing the appropriate toast. */
    handleProblem(response: Response, problem: any, opts?: KrtSendOpts): Promise<void>;
    /** Redirects to the re-auth path when the response carries `X-Reauthenticate`. */
    maybeReauthenticate(response: Response): boolean;
    /** Redirects the window to a same-origin re-authentication path. */
    reauthRedirect(url?: string): void;
    /** Builds a section-scoped `{write, refresh, notify}` wrapper for a page. */
    sectionWrite(config: KrtSectionWriteConfig): KrtSectionWriter;
    /** Runs `task` after the previous task with the same key settled. */
    serialize<T>(key: string, task: () => Promise<T>): Promise<T>;
    /** The CSRF helpers, re-exported for call sites that already hold `krtFetch`. */
    csrf: KrtCsrfApi;
}

/** Delegated event registration installed by `event-delegation.js`. */
interface KrtEventsApi {
    /**
     * Registers a delegated handler for `eventType` on elements carrying
     * `data-trigger="<actionName>"`. Delegation is anchored at `document`, so
     * the registration survives the fragment swaps that replace container
     * contents. Disabled form controls never reach the handler.
     */
    on(
        eventType: string,
        actionName: string,
        handler: (element: HTMLElement, event: Event) => void,
    ): void;
    /** Set on the head.html bootstrap stub only; the real registry never carries it. */
    _isBootstrapStub?: boolean;
    /** Registrations the stub buffered before this module loaded, drained on install. */
    _queuedRegistrations?: Array<[string, string, (element: HTMLElement, event: Event) => void]>;
}

/** The live multi-user sync channel installed by `krt-live-sync.js` (ADR-0092). */
interface KrtLiveSyncApi {
    /** Builds a receiver that applies inbound peer changes to the local DOM. */
    createReceiver(config: unknown): unknown;
    /** Subscribes to a topic room and returns the subscription handle. */
    subscribe(topic: string, handler: (message: any) => void): unknown;
    /** Broadcasts that a section changed, so peers re-render it. */
    sendChanged(topic: string, section: string): void;
    /** Broadcasts a presence event (focus/blur/heartbeat) for a section. */
    sendPresence(topic: string, kind: string, section: string): void;
    /** The topics currently subscribed on this connection. */
    subscribedTopics(): string[];
}

/** Per-participant guest edit tokens, installed by `krt-fetch.js` (REQ-SEC). */
interface KrtGuestTokenApi {
    /** Persists the guest edit token for a participant in localStorage. */
    store(participantId: string, token: string): void;
    /** Reads a stored guest edit token, or null when none is held. */
    read(participantId: string): string | null;
}

/** Re-authentication helpers installed by `krt-fetch.js` (REQ-SEC-012). */
interface KrtReauthApi {
    /** Redirects the window to a same-origin re-authentication path. */
    redirect(url?: string): void;
    /** Returns true when the response demanded re-authentication. */
    check(response: Response): boolean;
}

/**
 * Combobox labels handed to `krt-searchable-select.js` by the layout bootstrap.
 * The flat entries are the defaults; `kinds` overrides them per remote source
 * (keyed by the element's `data-krt-combobox` value), so a user picker and a
 * location picker can say different things while sharing one implementation.
 */
interface KrtComboboxI18n extends Partial<Record<string, unknown>> {
    /** Placeholder shown in the search input. */
    placeholder?: string;
    /** Shown when the query matched nothing. */
    noResults?: string;
    /** Shown while more typing is needed to narrow a capped remote list. */
    hint?: string;
    /** Validation message when the field was left on a free-text value. */
    invalid?: string;
    /** Shown while a remote query is in flight. */
    loading?: string;
    /** Per-remote-source label overrides, keyed by combobox kind. */
    kinds?: Record<string, Partial<Omit<KrtComboboxI18n, 'kinds'>>>;
}

// -------------------------------------------------------- custom DOM events

/**
 * The application's own DOM events, registered so `addEventListener` infers a
 * `CustomEvent` and its `detail` shape instead of the bare `Event` that carries
 * no detail at all. This is the contract between whoever dispatches and whoever
 * listens — keep it in step when a new custom event is introduced.
 */
interface DocumentEventMap {
    /**
     * Fired after `krtFetch.swap` replaced a container's content, so
     * progressive enhancement (comboboxes, date localisation, …) can re-run on
     * the freshly injected markup.
     */
    'krt:swapped': CustomEvent<{ container: Element | null }>;
    /** Fired when a mission section changed locally and peers must re-render it. */
    'krt:mission-changed': CustomEvent<{ section?: string }>;
    /** Fired when the mission page must drop its local state and re-fetch. */
    'krt:mission-resync': CustomEvent<unknown>;
}

// ------------------------------------------------------ element augmentation

interface HTMLElement {
    /**
     * The combobox controller `krt-searchable-select.js` attaches to both the
     * hidden input and the wrapper, so later code can drive an already-enhanced
     * field without re-querying the DOM.
     */
    krtCombobox?: unknown;
}

// ------------------------------------------------------- window augmentation

interface Window {
    // --- installed by krt-fetch.js
    krtFetch: KrtFetchApi;
    krtCsrf: KrtCsrfApi;
    krtGuestToken: KrtGuestTokenApi;
    krtReauth: KrtReauthApi;

    // --- installed by the shared foundation modules
    krtEvents: KrtEventsApi;
    krtLiveSync: KrtLiveSyncApi;
    escapeHtml: typeof escapeHtml;
    escapeAttr: typeof escapeAttr;

    // --- toasts and dialogs from the layout
    showFrontendErrorToast?: (message: string) => void;
    showFrontendSuccessToast?: (message: string) => void;
    showInventoryToast?: (message: string, isError?: boolean) => void;
    /**
     * KRT-styled confirmation modal — the sanctioned replacement for the native
     * `window.confirm` the design system forbids. Resolves true when the user
     * confirmed. Each label falls back to its `krtToastI18n` default when
     * omitted, and the whole dialog falls back to a minimal on-the-fly modal
     * when the optional confirm fragment is not on the page.
     */
    showKrtConfirm?: (
        title: string | null,
        message: string,
        confirmLabel?: string,
        cancelLabel?: string,
    ) => Promise<boolean>;

    // --- modal open/close (class-based visibility, ADR-0093)
    krtModalOpen?: (overlay: HTMLElement | null) => void;
    krtModalClose?: (overlay: HTMLElement | null) => void;
    openModal?: (...args: any[]) => void;

    // --- progressive enhancement applied to freshly swapped fragments
    krtEnhanceComboboxes?: (root: ParentNode) => void;
    krtLocalizeDates?: (root: ParentNode) => void;
    krtInitDatetimeSplitGroup?: (root: ParentNode) => void;
    krtSyncDatetimeSplitGroup?: (root: ParentNode) => void;
    krtSearchableSelect?: unknown;
    krtComboboxRemoteSources?: Record<string, unknown>;
    krtSegSet?: (...args: any[]) => void;

    // --- localized label dictionaries injected by the layout bootstrap
    krtComboboxI18n?: KrtComboboxI18n;
    krtProfileI18n?: KrtI18nDict;
    krtP4kImportI18n?: KrtI18nDict;
    krtBlueprintsImportI18n?: KrtI18nDict;
    krtBlueprintsRecipeI18n?: KrtI18nDict;

    // --- per-page endpoint maps injected alongside the label dictionaries, so
    //     the module never hardcodes a URL the controller owns
    krtP4kImportEndpoints?: Record<string, string>;
    krtBlueprintsEndpoints?: Record<string, string>;
    /**
     * Toast and confirm-dialog labels from `fragments/toast.html`. Declared
     * required, not optional: `toast.js` dereferences it unguarded, so a page
     * that renders a toast without the fragment is already broken — the type
     * should not paper over that with an optional-chaining obligation at every
     * call site.
     */
    krtToastI18n: KrtI18nDict;
    krtLiveSyncI18n?: KrtI18nDict;

    /**
     * Returns `url` when it is a same-origin absolute path, otherwise
     * `fallback` (null when omitted). Guards every `data-*`-driven navigation
     * against protocol-relative and `javascript:` URLs. From `safe-url.js`.
     */
    safeSameOriginUrl?: {
        (url: unknown): string | null;
        <T>(url: unknown, fallback: T): string | T;
    };

    // --- page-scoped hooks published for cross-module reuse
    /**
     * Per-account flag telling the bank booking form whether a justification is
     * mandatory, keyed by account id. Populated as accounts are resolved by the
     * remote picker so the form can react without a second round trip.
     */
    krtBankAccountMeta?: Record<string, boolean>;
    /** Server-rendered configuration for the blueprint overview page module. */
    krtBlueprintOverview?: {
        /** Localized labels for the owners panel. */
        i18n?: KrtI18nDict;
        /** Endpoint the owners panel queries, with its own query string already applied. */
        ownersUrl?: string;
    };
    krtHerkunft?: unknown;
    krtMaterialRelease?: unknown;
    krtMaterialRequest?: unknown;
    krtRefineryYield?: unknown;
    krtOpenEditCrewModal?: (...args: any[]) => void;
    krtOperationsReload?: (...args: any[]) => void;
    krtRefreshOrdersQueue?: (...args: any[]) => void;

    // --- mission page section-write trio (produced by sectionWrite)
    krtMissionWrite?: KrtSectionWriter['write'];
    krtRefreshMissionSection?: KrtSectionWriter['refresh'];
    krtNotifyMissionChanged?: KrtSectionWriter['notify'];
    MissionPresence?: unknown;

    // --- orders / inventory / operations equivalents
    krtRefreshOrderSection?: (...args: any[]) => void;
    krtNotifyOrderChanged?: (...args: any[]) => void;
    krtNotifyInventoryChanged?: (...args: any[]) => void;
    opRefreshSection?: (...args: any[]) => void;
    opNotifyChanged?: (...args: any[]) => void;

    // --- page-local state flags and legacy inline-template callbacks
    /**
     * The refinery order the detail page is showing — keys its `refinery-order:{id}` live-sync room
     * and the base URL its `?fragment=` section refreshes are pulled from. Set by the page bootstrap
     * of refinery-orders-details.html; absent on every other page.
     */
    refineryOrderId?: string | null;
    __unsavedChangesInitialized?: boolean;
    __ordersDragging?: boolean;
    resetUnsavedChanges?: () => void;
    filterTable?: (...args: any[]) => void;
    openNoteModal?: (...args: any[]) => void;
    closeNoteModal?: (...args: any[]) => void;
    saveNote?: (...args: any[]) => void;
    removeNote?: (...args: any[]) => void;
    updateNoteCounter?: (...args: any[]) => void;
    confirmRemoveMaterial?: (...args: any[]) => void;
    materialRowToDelete?: Element | null;
}
