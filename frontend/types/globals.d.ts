/**
 * Ambient declarations for the cross-file runtime contract of the static browser scripts.
 *
 * The scripts under `static/js` share one global scope (ADR-0069); this file types the surface
 * shared across pages. Per-page bootstrap constants live in `thymeleaf-bootstrap.d.ts`, backend
 * DTO shapes in `dto.d.ts` (REQ-FE-018).
 */

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
    /**
     * Set on a 2xx only: true when fetch followed a redirect, so an HTML-swapping caller can refuse
     * a whole-document answer instead of a fragment.
     */
    redirected?: boolean;
}

/**
 * A localized message dictionary handed from a Thymeleaf bootstrap block to a page module.
 * Keys are dotted message keys; values are the resolved translations.
 */
type KrtI18nDict = Record<string, string>;

/**
 * An element or selector accepted wherever the helpers take "container or selector". `null` is
 * allowed: every consumer bails out when the reference resolves to nothing.
 */
type KrtElementRef = Element | string | null;

/**
 * The open/close contract for every `.krt-modal-overlay` dialog, installed by `krt-modal.js`.
 * `open` shows the native `<dialog>` modally and moves focus in; `close` restores focus.
 */
interface KrtModalApi {
    /** Opens an overlay (element or id); `focus` overrides the default focus target. */
    open(
        ref: Element | string | null | undefined,
        options?: { focus?: Element | null },
    ): HTMLElement | null;
    /** Closes an overlay (element or id) and restores the focus it took. */
    close(ref: Element | string | null | undefined): HTMLElement | null;
    /** Whether an overlay is currently displayed, however it was opened. */
    isOpen(ref: Element | string | null | undefined): boolean;
    /** The topmost open overlay, or null. */
    topmost(): HTMLElement | null;
    /** Where toasts, confirms and download links are appended: the open modal, else the body. */
    layerRoot(): HTMLElement;
}

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

/** CSRF token access and refresh, installed by `krt-fetch.js`. */
interface KrtCsrfApi {
    /** The current CSRF token from the `_csrf` meta tag, or null when absent. */
    token(): string | null;
    /** The header name the token must be sent under, or null when absent. */
    headerName(): string | null;
    /** Merges the CSRF header into `base` and returns the merged header map. */
    headers(base?: Record<string, string>): Record<string, string>;
    /**
     * Re-reads the CSRF token from `GET /csrf` and updates the meta tags. Resolves the fresh
     * token pair, or null when the refresh failed; concurrent calls share one request.
     */
    refresh(): Promise<{ token: string; headerName: string } | null>;
}

/** Localized strings for the 409 optimistic-lock conflict dialog. */
interface KrtConflictStrings {
    /** Dialog title; defaults to `krtFetchI18n.conflictTitle`. */
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
     * Container (or selector) whose `[data-version]` descendants receive the response's fresh
     * version on success.
     */
    containerSelector?: KrtElementRef;
    /** Already-localized prefix for the success toast. */
    sectionLabel?: string;
    /** Already-localized prefix for the error and conflict toasts. */
    conflictSectionLabel?: string;
    /** Already-localized success text; defaults to `krtFetchI18n.saved`. */
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
     * Accept header value; defaults to `application/json`. A non-JSON 2xx body reaches `onSuccess`
     * as text.
     */
    accept?: string;
    /** `'blob'` reads a 2xx body as a Blob; an error body is still parsed as problem+json. */
    responseType?: 'blob';
    /**
     * Runs after a 2xx with the parsed body. A returned thenable is awaited before the next
     * serialized write starts.
     */
    onSuccess?: (body: any) => void | Promise<unknown>;
    /**
     * Runs on a non-ok, non-reauth response before the default problem handling. Returning truthy
     * skips the default toast and conflict dialog.
     */
    onError?: (status: number, body: any, response: Response) => unknown;
    /**
     * Runs when the request failed at the transport layer (no response, so no `onError`).
     * Returning truthy suppresses the default network-error toast.
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
    /** Sends the payload with a DELETE as well; off by default, so a DELETE sends no body. */
    bodyOnDelete?: boolean;
    /**
     * Lock-scope key: writes sharing it run one at a time, in order. Pair it with thunk
     * `url`/`payload` so a queued write re-reads its version.
     */
    serialize?: string;
    /** Section key resolved against the dictionary by a `sectionWrite` wrapper. */
    sectionKey?: string;
}

/** Options for {@linkcode KrtFetchApi.submitForm}. */
interface KrtSubmitFormOpts extends KrtSendOpts {
    /**
     * The form element or a selector for it; supplies action, method and FormData. Optional when
     * `url` and `formData` are passed explicitly.
     */
    form?: HTMLFormElement | string;
    /** Target URL; defaults to the form's `action` attribute. */
    url?: string | (() => string);
    /** HTTP method; defaults to the form's `method`, else POST. */
    method?: string;
    /** Explicit body; defaults to `new FormData(form)`. A `URLSearchParams` is sent urlencoded. */
    formData?: FormData | URLSearchParams;
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
     * When true, the address-bar URL is kept in sync via `history.replaceState`, minus the
     * fragment param.
     */
    history?: boolean;
    /** Unless false, the scroll position is restored after the swap. */
    preserveScroll?: boolean;
    /**
     * Already-localized error toast shown when the response was redirected or not OK; omit to fail
     * silently. The container is left untouched either way.
     */
    errorMessage?: string;
}

/** Configuration for {@linkcode KrtFetchApi.sectionWrite}. */
interface KrtSectionWriteConfig {
    /** Late-bound accessor for the page's i18n dictionary. */
    dict(): KrtI18nDict | null | undefined;
    /** The dictionary's global name, used to report a missing string as `NAME[key]`. */
    dictName?: string;
    /** Message-key prefixes used to derive the per-section localized strings. */
    keys: Record<string, string>;
    /** Maps a section key to its swap container and Thymeleaf fragment value. */
    sections: Record<string, { container: string; fragmentValue: string }>;
    /**
     * Late-bound accessor for the URL the section fragments are fetched from. Null while the
     * entity has no id; `refresh()` then resolves false instead of fetching.
     */
    pageUrl(): string | null;
    /**
     * Late-bound peer-notification closure called by `refresh`/`notify` with the changed section
     * keys, to publish them to the page's live-sync room (REQ-FE-010).
     */
    broadcast?(sectionKeys: string[]): void;
}

/** Options for {@linkcode KrtSectionWriter.refresh}. */
interface KrtSectionRefreshOpts {
    /**
     * `false` suppresses the peer broadcast; required when applying a peer's inbound signal, to
     * avoid an echo loop. Defaults to broadcasting.
     */
    broadcast?: boolean;
}

/** The `{write, refresh, notify}` trio returned by {@linkcode KrtFetchApi.sectionWrite}. */
interface KrtSectionWriter {
    /** {@linkcode KrtFetchApi.write} with the section's localized strings applied. */
    write(opts: KrtWriteOpts): Promise<KrtWriteResult>;
    /**
     * Re-renders one or more sections in place and broadcasts the change to peers. Resolves with
     * one success flag per section, in the order given.
     */
    refresh(sectionKeys: string | string[], opts?: KrtSectionRefreshOpts): Promise<boolean[]>;
    /** Broadcast-only sibling of `refresh` for handlers that already patched the DOM. */
    notify(sectionKeys: string | string[]): void;
}

/**
 * The shared AJAX-mutation foundation installed by `krt-fetch.js`: CSRF, optimistic-lock version
 * sync, re-auth, RFC 7807 problem handling and toasts for every write (REQ-FE-001…010).
 */
interface KrtFetchApi {
    /** Sends a JSON write and handles the response, returning the parsed outcome. */
    write(opts: KrtWriteOpts): Promise<KrtWriteResult>;
    /** Submits a form as multipart/form-encoded data through the same pipeline. */
    submitForm(opts: KrtSubmitFormOpts): Promise<KrtWriteResult>;
    /**
     * Fetches a rendered fragment and replaces the container's content in place. Resolves true
     * when the swap happened, false otherwise.
     */
    swap(opts: KrtSwapOpts): Promise<boolean>;
    /** Binds in-container pagination/sort anchor interception without an initial fetch. */
    bindSwap(opts: KrtSwapOpts): void;
    /**
     * Replaces `el`'s content with a same-origin Thymeleaf fragment response — the one sanctioned
     * innerHTML sink for unescaped markup; never for markup assembled in script. No-op when `el`
     * is absent; null/undefined `html` clears it.
     */
    setTrustedHtml(el: Element | null | undefined, html: string | null | undefined): void;
    /**
     * The outerHTML twin of `setTrustedHtml`: replaces `el` itself with the fragment, under the
     * same trust contract. No-op when `el` is absent or detached; null/undefined `html` removes it.
     */
    replaceWithTrustedHtml(el: Element | null | undefined, html: string | null | undefined): void;
    /** Writes `newVersion` to the container and to every `[data-version]` descendant. */
    syncVersion(container: KrtElementRef, newVersion: number | string | null): void;
    /** Handles an RFC 7807 `problem+json` response, showing the appropriate toast. */
    handleProblem(response: Response, problem: any, opts?: KrtSendOpts): Promise<void>;

    /**
     * The localized message for an `OWNER_ORG_UNIT_REQUIRED` problem, or `null` for any other
     * problem; for page-local `onError` handlers (REQ-ORG-023).
     */
    ownerOrgUnitRequiredMessage(problem: any): string | null;
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
     * Registers a handler for `eventType` on elements carrying `data-trigger="<actionName>"`,
     * delegated from `document` so it survives fragment swaps. Disabled controls never reach it.
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
    /**
     * Broadcasts that one or more sections changed, so peers re-render them; the relay drops keys
     * outside the topic's whitelist. Needs no subscription to the room (ADR-0094).
     */
    sendChanged(topic: string, sections: string | string[]): void;
    /** Broadcasts a presence event (focus/blur/heartbeat) for a section. */
    sendPresence(topic: string, kind: string, section: string): void;
    /** The topics currently subscribed on this connection. */
    subscribedTopics(): string[];
}

/** Re-authentication helpers installed by `krt-fetch.js` (REQ-SEC-012). */
interface KrtReauthApi {
    /** Redirects the window to a same-origin re-authentication path. */
    redirect(url?: string): void;
    /** Returns true when the response demanded re-authentication. */
    check(response: Response): boolean;
}

/**
 * Terms-of-Use consent-gate helper installed by `krt-fetch.js` (REQ-SEC-028); navigates the
 * browser when the gate appears mid-session.
 */
interface KrtTermsGateApi {
    /** Navigates to the consent page and returns true when the response demanded consent. */
    check(response: Response): boolean;
    /**
     * Navigates the window to a same-origin consent path, for gate signals without a Response
     * (the `terms-gate` SSE event, the `/ws/sync` close reason).
     */
    redirect(url?: string | null): boolean;
}

/**
 * A control the refinery-order forms address by id: the raw `<select>`, or the hidden `<input>`
 * carrying its id after combobox enhancement (REQ-FE-016). Only `id` and `value` are read.
 */
type KrtRefineryControl = HTMLInputElement | HTMLSelectElement;

/**
 * The material-row yield-badge manager installed by `refinery-yield-badge.js` for both
 * refinery-order forms. Holds the current refinery's `{materialId -> bonusPercent}` map in memory.
 */
interface KrtRefineryYieldApi {
    /**
     * Replaces the yield map and the badge tooltip text; a nullish map means no UEX data and
     * collapses every badge.
     */
    init(
        initialMap: Record<string, number> | null | undefined,
        helpText: string | null | undefined,
    ): void;
    /**
     * Sets, updates or removes the badge on material row `rowIndex`. A nullish `bonus` removes it;
     * 0 renders a neutral "0%" badge.
     */
    setBadge(rowIndex: string | number, bonus: number | null | undefined): void;
    /**
     * Re-renders the badge of the row owning `control`, identified by its trailing `_<n>` id
     * suffix; a control without one is ignored.
     */
    refreshFor(control: KrtRefineryControl | null | undefined): void;
    /** Re-renders every material row's badge against the current map. */
    refreshAll(): void;
    /**
     * Refetches the selected refinery's yield map and re-renders every badge. Never rejects: a
     * nullish location, a 4xx or a network failure fall back to an empty map.
     */
    onLocationChange(control: KrtRefineryControl | null | undefined): Promise<void>;
}

/** One tag's share of a book-out / transfer deduction plan. */
interface KrtHerkunftReduction {
    /** The job-order or mission id the amount is deducted from. */
    targetId: string;
    /** The amount taken from that target, already rounded to the material's unit. */
    amount: number;
}

/**
 * The "Herkunft" (provenance) picker installed by `inventory-herkunft.js`, splitting a book-out or
 * transfer deduction across a stock entry's earmarks. Each call is scoped by the modal's `prefix`
 * (`'bookout'` / `'umbuchen'`).
 */
interface KrtHerkunftApi {
    /** Renders the picker for the given modal from the source entry's leaf row. */
    populate(prefix: string, itemId: string): void;
    /** Recomputes the picker's derived amounts; returns whether the plan is submittable. */
    recompute(prefix: string): boolean;
    /**
     * Reads the current plan for submission; both lists are null when the picker contributes no
     * split, so the backend applies its default deduction order.
     */
    collect(prefix: string): {
        jobOrderReductions: KrtHerkunftReduction[] | null;
        missionReductions: KrtHerkunftReduction[] | null;
    };
    /** Recomputes and reports whether the plan is currently submittable. */
    isValid(prefix: string): boolean;
    /** Clears and hides the picker, e.g. when its modal closes. */
    reset(prefix: string): void;
}

/**
 * The per-page settings of the two Lager pages sharing `inventory-common.js`: the global Lager
 * (`/inventory/all`) and the personal one (`/inventory/my`).
 */
interface KrtInventoryLagerConfig {
    /** The page's data-trigger prefix: `inv-admin` or `inv-my`. */
    triggerPrefix: string;
    /** The route root the stack-entries endpoints hang off: `/inventory/all` or `/inventory/my`. */
    basePath: string;
    /** The global Lager's stacks are per owner: its stack-entries URL carries `userId`. */
    stackPerOwner: boolean;
    /** The personal Lager's stack key carries the personal flag. */
    stackPersonalFlag: boolean;
    /** Re-pulls the page's grouped table in place after a write. */
    refreshTable: () => void;
    /** Tells peers viewing the page's own Lager room that the stock changed. */
    notifyInventoryChanged: () => void;
    /** Runs after a stack's entries were injected (the personal page re-applies its bulk selection). */
    onStackEntriesLoaded?: (content: HTMLElement) => void;
}

/** One page's instance of the shared Lager behaviour, from `krtInventory.createLager`. */
interface KrtInventoryLager {
    /** Whether the Items (game-item) view is active rather than the Material view. */
    lagerIsItemsView(): boolean;
    /** Re-applies the persisted group and stack expansion after a grouped-table re-swap. */
    restoreExpandedTree(): void;
    /** The job-order ids earmarked on an entry's leaf row, read before a stock write. */
    collectLeafOrderIds(itemId: string | null): string[];
    /** Pokes the affected job orders' rooms and the cross-order demand overview. */
    broadcastOrdersChanged(orderIds: Array<string | null | undefined> | null | undefined): void;
    /** Pokes the Materialbörse board after a stock-reducing write. */
    broadcastBoardChanged(): void;
    /** Closes the Umbuchen modal and resets its Herkunft picker. */
    closeUmbuchenModal(): void;
    /** Records the Umbuchen row's owning org unit the target-OrgUnit picker is preset to. */
    setUmbuchenCurrentOwningOrgUnit(orgUnitId: string | null): void;
    /** Fills the Umbuchen target-OrgUnit picker from the selected target user's memberships. */
    refreshUmbuchenTransferOrgUnitPicker(): void;
    /** Opens one multi-select filter dropdown and closes the others; a second click closes it. */
    toggleMultiSelect(id: string | null): void;
    /** Applies a family's select-all box to all its checkboxes and rewrites the header summary. */
    toggleSelectAll(allId: string | null, checkClass: string | null, headerId: string | null): void;
    /**
     * Re-syncs a family's select-all box and header summary: "all" when none or every box is
     * ticked, the one ticked label, or "<n> <selected>".
     */
    updateSelectState(
        allId: string | null,
        checkClass: string | null,
        headerId: string | null,
    ): void;
    /** The values of a family's ticked checkboxes. */
    collectChecked(className: string): string[];
    /** Installs the shared delegated handlers, the book-out submit and the initial tree restore. */
    bind(): void;
}

/** The shared Lager module installed by `inventory-common.js`. */
interface KrtInventoryApi {
    /** Builds one page's instance of the shared Lager behaviour. */
    createLager(config: KrtInventoryLagerConfig): KrtInventoryLager;
}

/** The SCU amount helpers installed by `scu-decimal-input.js`. */
interface KrtScuInputApi {
    /** Canonicalises a typed amount (commas become dots, first dot kept); "" when there is no digit. */
    normalize(raw: unknown): string;
    /** Parses a typed amount, accepting either decimal separator; NaN when it is not a number. */
    parse(raw: unknown): number;
    /** Rounds a canonical dot string half-up to three decimals, stripping trailing zeros. */
    round(value: string): string;
}

/**
 * A Materialbörse create/edit dialog: the offer side (`krtMaterialRelease`) or the request side
 * (`krtMaterialRequest`).
 */
interface KrtMaterialDialogApi {
    /**
     * Opens the dialog in `mode` (`'new'`, `'edit'`, or the offer dialog's `'lager'` / `'item'`),
     * seeded by `ctx` (absent or empty for a blank entry). `doneOrOpts` is a success callback
     * receiving the response body, or an `{onDone, onCancel}` pair.
     */
    open(
        mode: string,
        ctx?: Record<string, unknown> | null,
        doneOrOpts?:
            ((body: any) => void) | { onDone?: (body: any) => void; onCancel?: () => void } | null,
    ): void;
    /** Closes the dialog and discards its in-progress state. */
    close(): void;
}

/**
 * Combobox labels handed to `krt-searchable-select.js` by the layout bootstrap. The flat entries
 * are defaults; `kinds` overrides them per remote source (the element's `data-krt-combobox` value).
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

/**
 * The application's custom DOM events, so `addEventListener` infers the `CustomEvent` and its
 * `detail` shape.
 */
interface DocumentEventMap {
    /**
     * Fired after `krtFetch.swap` replaced a container's content, so progressive enhancement can
     * re-run on the new markup.
     */
    'krt:swapped': CustomEvent<{ container: Element | null }>;
    /** Fired when a mission section changed locally and peers must re-render it. */
    'krt:mission-changed': CustomEvent<{ section?: string }>;
    /** Fired when the mission page must drop its local state and re-fetch. */
    'krt:mission-resync': CustomEvent<unknown>;
}

/** The controller `krt-searchable-select.js` attaches to an enhanced combobox. */
interface KrtComboboxController {
    /**
     * Selects `value` (empty or unknown clears) without firing `change`. `label` names a value
     * outside the loaded items; `data` is option metadata mirrored onto the hidden input.
     */
    setValue(value: string, label?: string, data?: object): void;
}

interface HTMLElement {
    /** The combobox controller `krt-searchable-select.js` attaches to the hidden input and wrapper. */
    krtCombobox?: KrtComboboxController;
}

/**
 * Collapsible filter panels (`krt-filter-panel.js`, REQ-FE-021), which wire themselves from markup.
 * This API supplies a custom active-filter count and re-renders the count after an AJAX swap.
 */
interface KrtFilterPanelApi {
    /**
     * Replaces the generic active-filter count for one panel.
     *
     * @param panelId the panel's element id
     * @param counter returns how many filters are currently active
     */
    registerCounter(panelId: string, counter: () => number): void;

    /**
     * Re-renders the count chips.
     *
     * @param panelId a single panel, or every panel when omitted
     */
    refresh(panelId?: string): void;
}

interface Window {
    /**
     * Returns `value` when it is a non-empty string; otherwise reports `key` once per page view as
     * an `i18n_missing` client error and returns the key name. `key` is `DICTIONARY.property` or
     * `data-attribute`.
     */
    krtI18nText(value: unknown, key: string): string;

    krtFetch: KrtFetchApi;
    krtCsrf: KrtCsrfApi;
    krtReauth: KrtReauthApi;
    krtTermsGate: KrtTermsGateApi;

    krtEvents: KrtEventsApi;
    krtLiveSync: KrtLiveSyncApi;
    krtFilterPanel: KrtFilterPanelApi;
    escapeHtml: typeof escapeHtml;
    escapeAttr: typeof escapeAttr;

    showFrontendErrorToast?: (message: string) => void;
    showFrontendSuccessToast?: (message: string) => void;
    showInventoryToast?: (message: string, isError?: boolean) => void;
    /**
     * KRT-styled confirmation modal replacing the native `window.confirm`; resolves true when the
     * user confirmed. Omitted labels fall back to their `krtToastI18n` defaults.
     */
    showKrtConfirm?: (
        title: string | null,
        message: string,
        confirmLabel?: string,
        cancelLabel?: string,
    ) => Promise<boolean>;

    krtModal: KrtModalApi;
    /** Alias of `krtModal.open` for the mission page. */
    krtModalOpen?: (overlay: HTMLElement | null) => void;
    krtModalClose?: (overlay: HTMLElement | null) => void;
    openModal?: (...args: any[]) => void;

    krtEnhanceComboboxes?: (root: ParentNode) => void;
    krtLocalizeDates?: (root: ParentNode) => void;
    krtInitDatetimeSplitGroup?: (root: ParentNode) => void;
    krtSyncDatetimeSplitGroup?: (root: ParentNode) => void;
    krtSearchableSelect?: unknown;
    krtComboboxRemoteSources?: Record<string, unknown>;
    krtSegSet?: (...args: any[]) => void;

    krtComboboxI18n?: KrtComboboxI18n;
    krtProfileI18n?: KrtI18nDict;
    krtDeletionRequestsI18n?: KrtI18nDict;
    krtP4kImportI18n?: KrtI18nDict;
    krtBlueprintsImportI18n?: KrtI18nDict;
    krtBlueprintsRecipeI18n?: KrtI18nDict;

    krtP4kImportEndpoints?: Record<string, string>;
    krtBlueprintsEndpoints?: Record<string, string>;
    /**
     * Toast and confirm-dialog labels from `fragments/toast.html`; required because `toast.js`
     * dereferences it unguarded.
     */
    krtToastI18n: KrtI18nDict;
    krtLiveSyncI18n?: KrtI18nDict;

    /**
     * Wording for the `OWNER_ORG_UNIT_REQUIRED` rejection from `fragments/head.html`, read by
     * `krt-fetch.js` (REQ-ORG-023).
     */
    krtOwnerPickerI18n?: KrtI18nDict;
    /**
     * The page-wide krtFetch toast and conflict-dialog defaults from `fragments/head.html`, used
     * when a caller passes no string of its own.
     */
    krtFetchI18n?: KrtI18nDict;

    /**
     * Returns `url` when it is a same-origin absolute path, otherwise `fallback` (null when
     * omitted). Installed by `safe-url.js`.
     */
    safeSameOriginUrl?: {
        (url: unknown): string | null;
        <T>(url: unknown, fallback: T): string | T;
    };

    /**
     * Whether a bank booking needs a justification, keyed by account id; filled as the remote
     * picker resolves accounts.
     */
    krtBankAccountMeta?: Record<string, boolean>;
    /** Server-rendered configuration for the admin default-blueprints page module. */
    krtDefaultBlueprints?: {
        /** Endpoint the product type-ahead queries. */
        searchUrl?: string;
        /** Page URL whose `fragment=rows` read re-renders the list after a write. */
        listUrl?: string;
        /** Localized labels for the type-ahead, the modal and the toasts. */
        i18n?: KrtI18nDict;
    };
    /** Server-rendered configuration for the blueprint overview page module. */
    krtBlueprintOverview?: {
        /** Localized labels for the owners panel. */
        i18n?: KrtI18nDict;
        /** Endpoint the owners panel queries, with its own query string already applied. */
        ownersUrl?: string;
    };
    krtHerkunft?: KrtHerkunftApi;
    /** The shared Lager behaviour of the two inventory pages (`inventory-common.js`). */
    krtInventory?: KrtInventoryApi;
    krtScuInput?: KrtScuInputApi;
    krtMaterialRelease?: KrtMaterialDialogApi;
    krtMaterialRequest?: KrtMaterialDialogApi;
    /**
     * The refinery yield-badge manager; required because both refinery-order modules call it
     * unguarded at parse time.
     */
    krtRefineryYield: KrtRefineryYieldApi;
    krtOpenEditCrewModal?: (...args: any[]) => void;
    krtOperationsReload?: (...args: any[]) => void;
    krtRefreshOrdersQueue?: (...args: any[]) => void;
    /**
     * Re-swaps the /members roster for the viewer's own filter and page, so the live-sync receiver
     * can apply a peer's change in place.
     */
    krtRefreshMembersResults?: () => void;

    krtMissionWrite?: KrtSectionWriter['write'];
    krtRefreshMissionSection?: KrtSectionWriter['refresh'];
    krtNotifyMissionChanged?: KrtSectionWriter['notify'];
    MissionPresence?: unknown;

    krtRefreshOrderSection?: (...args: any[]) => void;
    krtNotifyOrderChanged?: (...args: any[]) => void;
    krtNotifyInventoryChanged?: (...args: any[]) => void;
    opRefreshSection?: (...args: any[]) => void;
    opNotifyChanged?: (...args: any[]) => void;

    /**
     * The id of the refinery order on refinery-orders-details.html, keying its live-sync room and
     * section refreshes; absent on every other page.
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
