/*
 * Bank area client behaviors (epic #556).
 *
 * Covers every interactive surface of the bank pages:
 *  - modal field priming: `data-field-*` attributes on an open-modal trigger are
 *    copied into the modal form before it opens (row-scoped id/version/name),
 *  - AJAX forms (`form.bank-ajax-form`): JSON POST/PATCH/DELETE against
 *    /api/proxy/bank/** sent through the shared window.krtFetch.write (CSRF +
 *    retry-once-on-403 + X-Reauthenticate redirect; #906 Q13 retired the former
 *    bespoke bankWrite loop), success = in-place server-fragment swap of the region named by the form's
 *    `data-refresh` attribute (accountBody / manageBody / grantsMatrix) so balances,
 *    holder distribution, tab-counts and every @Version stay server-authoritative
 *    without a page reload (#579, REQ-FE-005); errors = localized inline message
 *    next to the field the conflict names,
 *  - grants matrix toggles: one PATCH per flag click with all three flags + version,
 *    applied in place (button pressed state + row data-can-* + synced data-version),
 *  - grants filter selects: navigate to the grouped view on change,
 *  - account-create type switch: org-unit vs. area name dependent fields,
 *  - searchable user selects (holder registration / grant creation).
 */
(function () {
    'use strict';

    /** Form field names serialized as JSON numbers instead of strings. */
    const NUMBER_FIELDS = [
        'amount',
        'version',
        'target',
        'limit',
        'splitPercent',
        'employeeCeiling',
        'areaLeadCeiling',
    ];

    /** Form field names serialized as JSON booleans ("true"/"false" hidden inputs). */
    const BOOLEAN_FIELDS = ['active'];

    /**
     * Maps backend bank conflict codes onto the form field whose inline error slot
     * should show the localized message; unmapped codes land in the form's
     * `_global` slot.
     */
    const CODE_FIELD = {
        BANK_OVERDRAFT: 'amount',
        BANK_HOLDER_OVERDRAFT: 'amount',
        BANK_SELF_TRANSFER: 'destinationAccountId',
        BANK_GRANTEE_MISSING_ROLE: 'userId',
        BANK_HOLDER_INACTIVE: 'holderId',
        // Split deposit conflicts (REQ-BANK-043): surface at the percentage field.
        BANK_SPLIT_NO_TARGETS: 'splitPercent',
        BANK_SPLIT_TOO_SMALL: 'splitPercent',
        // Missing Begründung on a debit from a justification-mandating account (REQ-BANK-045).
        BANK_JUSTIFICATION_REQUIRED: 'justification',
        // Fee-inclusive amount that does not exceed the fee, so nothing would arrive (REQ-BANK-033).
        BANK_FEE_EXCEEDS_AMOUNT: 'amount',
    };

    /**
     * Maps a form's `data-refresh` token to the stable swap container on each bank page and whether
     * the current query string is preserved on the in-place re-render (#579, REQ-FE-005):
     *  - accountBody  -> the account-detail body; query dropped so the booking history resets to
     *                    page 0 and the just-booked row shows newest-first,
     *  - manageBody   -> the manage tab-nav + active panel; `?tab=` preserved,
     *  - grantsMatrix -> the grants matrix; `?view=/accountId=/userId=` filter preserved (#573).
     */
    const REFRESH_TARGETS = {
        accountBody: { container: '#bank-account-results', preserveQuery: false },
        manageBody: { container: '#bank-manage-results', preserveQuery: true },
        grantsMatrix: { container: '#bank-grants-results', preserveQuery: true },
        // Org-unit officer/lead page (#666 F2): balance cards + own-request list re-render after a
        // create/cancel; `?layout=` preserved so the account list keeps the caller's chosen card/
        // table view (REQ-BANK-021), the server re-rendering it in that layout.
        orgUnitBank: { container: '#org-unit-bank-results', preserveQuery: true },
        // Org-unit account detail (REQ-BANK-035/-036): the facts + responsibility settings region
        // re-renders in place after a target/visibility write; query dropped.
        orgUnitBankSettings: { container: '#org-unit-bank-settings-results', preserveQuery: false },
        // Bank-staff confirmation queue (#666 F2): the request table re-renders after a
        // confirm/reject; `?status=` preserved so the staffer stays on the filtered view.
        requestQueue: { container: '#bank-request-queue-results', preserveQuery: true },
        // Bank dashboard (REQ-BANK-016/-023): a direct booking from the header Kontobewegung modal
        // re-renders the switchable account grid so balances + 30-day deltas refresh in place;
        // `?layout=/group=` preserved so the booking keeps the caller's chosen view.
        bankGrid: { container: '#bank-grid-results', preserveQuery: true },
    };

    /**
     * Maps a movement type on the unified "Kontobewegung" modal (#997) onto its backend endpoint and
     * the JSON field the (fixed or chosen) source-account id is submitted under: a deposit/withdrawal
     * books against `accountId`, a transfer against `sourceAccountId`. syncMovementRows switches the
     * form's `data-endpoint` + `data-account-id-field` from this on every type change and on open.
     */
    const MOVEMENT_ENDPOINTS = {
        DEPOSIT: { endpoint: '/api/proxy/bank/deposits', accountField: 'accountId' },
        WITHDRAWAL: { endpoint: '/api/proxy/bank/withdrawals', accountField: 'accountId' },
        TRANSFER: { endpoint: '/api/proxy/bank/transfers', accountField: 'sourceAccountId' },
    };

    /** Maps a grant flag name onto the row attribute the next toggle click reads. */
    const FLAG_ATTR = {
        canDeposit: 'data-can-deposit',
        canWithdraw: 'data-can-withdraw',
        canTransfer: 'data-can-transfer',
    };

    // ---- Live multi-user sync — the Kartellbank rooms (REQ-FE-010 / REQ-FE-015, ADR-0094) --------
    // A peer's booking / request / grant / settings change re-renders the affected bank fragment in
    // place for every other viewer of the same room, over the shared /ws/sync socket. Only opaque
    // section keys cross the wire; each viewer re-pulls its OWN authorization-checked fragment (its
    // own filter/page). The four section maps below are the single source of truth shared with the
    // server LiveSyncTopicClass whitelists (the three-mirror-points rule, build-enforced by
    // LiveSyncSectionMapParityTest). Both sides ship together: the RECEIVE side (these maps + the
    // receivers wired below) and the publish side (publishBankLiveSync, driven by the per-form
    // data-livesync matrix, called from handleBankSuccess).

    // bank:{id} on the STAFF account-detail page: the balance chart + booking history are NESTED
    // inside #bank-account-results (the accountBody fragment), so re-rendering any of the three keys
    // re-renders all of them — hence the shared container. makeBankReceiverRefresh dedupes by
    // container so three inbound keys collapse to a single accountBody swap.
    const BANK_ACCOUNT_SECTIONS = {
        account: { container: '#bank-account-results', fragmentValue: 'accountBody' },
        bookings: { container: '#bank-account-results', fragmentValue: 'accountBody' },
        chart: { container: '#bank-account-results', fragmentValue: 'accountBody' },
    };

    // bank:{id} on the ORG-UNIT account-detail page: separate sibling containers for the
    // facts/settings region, the booking history and the balance chart.
    const ORGUNIT_ACCOUNT_SECTIONS = {
        account: {
            container: '#org-unit-bank-settings-results',
            fragmentValue: 'orgUnitBankSettings',
        },
        bookings: {
            container: '#org-unit-bank-bookings-results',
            fragmentValue: 'orgUnitBankBookings',
        },
        chart: {
            container: '#org-unit-bank-chart-results',
            fragmentValue: 'orgUnitBalanceChart',
        },
    };

    // bank (staff-global): the dashboard grid, the confirmation queue, the management tab and the
    // grants matrix — one per staff page, so each page renders only its own container (the receiver
    // silently skips the absent ones).
    const BANK_STAFF_SECTIONS = {
        grid: { container: '#bank-grid-results', fragmentValue: 'bankGrid' },
        requestQueue: { container: '#bank-request-queue-results', fragmentValue: 'requestQueue' },
        manage: { container: '#bank-manage-results', fragmentValue: 'manageBody' },
        grants: { container: '#bank-grants-results', fragmentValue: 'grantsMatrix' },
    };

    // orgunit-bank (global): the officer/lead overview and the account-detail settings region.
    const ORGUNIT_BANK_SECTIONS = {
        orgUnitBank: { container: '#org-unit-bank-results', fragmentValue: 'orgUnitBank' },
        orgUnitBankSettings: {
            container: '#org-unit-bank-settings-results',
            fragmentValue: 'orgUnitBankSettings',
        },
    };

    /**
     * Reads the localized "updates available" pill label from `<main data-bank-livesync-updates>` so
     * this file stays i18n-free; undefined falls back to the shared receiver default.
     *
     * @returns {string|undefined} the pill label, or undefined
     */
    function bankLiveSyncUpdates() {
        const main = document.querySelector('main[data-bank-livesync-updates]');
        return main ? main.getAttribute('data-bank-livesync-updates') : undefined;
    }

    /**
     * The account id of an account-detail page, read from `<main data-bank-account-id>`, or null on a
     * list/overview page that has no single account.
     *
     * @returns {string|null} the account id, or null
     */
    function bankAccountId() {
        const main = document.querySelector('main[data-bank-account-id]');
        const id = main ? main.getAttribute('data-bank-account-id') : null;
        return id || null;
    }

    /**
     * The dedicated "saved, but reload" refresh-error message (NOT the generic "action failed" text):
     * a peer's follow-up refresh that bounces must tell the user to reload, never that an action
     * failed.
     *
     * @returns {string} the refresh-error message
     */
    function bankRefreshError() {
        const main = document.querySelector('main[data-bank-refresh-error]');
        return main ? main.getAttribute('data-bank-refresh-error') : genericError();
    }

    /**
     * Builds a live-sync receiver refresh closure for one section map: on an inbound peer change it
     * re-renders each present container ONCE (deduped by container, since the staff account-detail
     * collapses account/bookings/chart into a single accountBody swap), re-fetching each viewer's OWN
     * pathname+query so a peer keeps its filter/page rather than adopting the actor's.
     *
     * @param {Object} sectionMap the section → {container, fragmentValue} map for the room
     * @returns {function(string[]): void} the refresh handler passed to createReceiver
     */
    function makeBankReceiverRefresh(sectionMap) {
        return function (keys) {
            if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
                return;
            }
            const url = window.location.pathname + window.location.search;
            const errorMessage = bankRefreshError();
            const done = {};
            keys.forEach(function (key) {
                const cfg = sectionMap[key];
                if (!cfg || done[cfg.container] || !document.querySelector(cfg.container)) {
                    return;
                }
                done[cfg.container] = true;
                window.krtFetch.swap({
                    url: url,
                    container: cfg.container,
                    fragmentValue: cfg.fragmentValue,
                    errorMessage: errorMessage,
                });
            });
        };
    }

    // Subscribe each bank page to the rooms it participates in and re-render present containers on an
    // inbound peer change. A page subscribes to `bank` when it renders any staff container, to
    // `orgunit-bank` when it renders an org-unit container, and to `bank:{id}` on an account-detail
    // page (its section map picked by whether it is the staff or the org-unit account view). All bank
    // receivers share the default pill; each re-fetches only the containers it actually renders.
    (function () {
        if (
            !window.krtLiveSync ||
            typeof window.krtLiveSync.createReceiver !== 'function' ||
            !window.krtFetch ||
            typeof window.krtFetch.swap !== 'function'
        ) {
            return; // no-JS / no-foundation: the classic in-place swaps still run for the actor.
        }
        function pill() {
            return { label: bankLiveSyncUpdates };
        }

        const hasStaffRoom =
            document.querySelector('#bank-grid-results') ||
            document.querySelector('#bank-request-queue-results') ||
            document.querySelector('#bank-manage-results') ||
            document.querySelector('#bank-grants-results');
        if (hasStaffRoom) {
            window.krtLiveSync.createReceiver({
                topic: 'bank',
                sections: BANK_STAFF_SECTIONS,
                // Global room: coalesce longer to flatten the refetch herd when many staffers get the
                // same signal at once (#1125).
                coalesceMs: 1500,
                refresh: makeBankReceiverRefresh(BANK_STAFF_SECTIONS),
                pill: pill(),
            });
        }

        const hasOrgUnitRoom =
            document.querySelector('#org-unit-bank-results') ||
            document.querySelector('#org-unit-bank-settings-results');
        if (hasOrgUnitRoom) {
            window.krtLiveSync.createReceiver({
                topic: 'orgunit-bank',
                sections: ORGUNIT_BANK_SECTIONS,
                coalesceMs: 1500,
                refresh: makeBankReceiverRefresh(ORGUNIT_BANK_SECTIONS),
                pill: pill(),
            });
        }

        const accountId = bankAccountId();
        if (accountId) {
            // The staff detail renders #bank-account-results; the org-unit detail its
            // settings/bookings/chart siblings — pick the section map accordingly.
            const sectionMap = document.querySelector('#bank-account-results')
                ? BANK_ACCOUNT_SECTIONS
                : ORGUNIT_ACCOUNT_SECTIONS;
            window.krtLiveSync.createReceiver({
                topic: 'bank:' + accountId,
                sections: sectionMap,
                refresh: makeBankReceiverRefresh(sectionMap),
                pill: pill(),
            });
        }
    })();

    /**
     * Builds the JSON + CSRF request headers via the shared window.krtCsrf (#579 migration; replaces
     * bank.js's former bespoke meta-tag reader). Degrades to a minimal meta-tag read only if
     * krt-fetch.js is somehow absent, so a write still attempts rather than silently dropping the
     * token. The added X-Requested-With does not change the bank error body — the frontend
     * GlobalExceptionHandler already answers JSON because bank requests send Accept: application/json.
     *
     * @param {Object<string,string>} [base] optional base headers merged under the CSRF header
     * @returns {Object<string,string>} headers for a same-origin JSON fetch
     */
    function csrfHeaders(base) {
        if (window.krtCsrf && typeof window.krtCsrf.headers === 'function') {
            return window.krtCsrf.headers(base);
        }
        const headers = Object.assign(
            { Accept: 'application/json', 'Content-Type': 'application/json' },
            base || {},
        );
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        if (token && header && header !== 'undefined' && token !== 'undefined') {
            headers[header] = token;
        }
        return headers;
    }

    /**
     * The localized fallback error text, sourced from the page (the templates set
     * `data-bank-generic-error` on <main>) so this file stays i18n-free.
     *
     * @returns {string} the generic error message
     */
    function genericError() {
        const main = document.querySelector('main[data-bank-generic-error]');
        return main ? main.getAttribute('data-bank-generic-error') : 'Error';
    }

    /**
     * Hides and empties every inline error slot of a form.
     *
     * @param {HTMLFormElement} form the bank AJAX form
     */
    function clearErrors(form) {
        form.querySelectorAll('.bank-field-error').forEach(function (el) {
            el.textContent = '';
            el.classList.remove('visible');
        });
    }

    /**
     * Shows a localized message in the error slot of the named field, falling back
     * to the form's `_global` slot and finally to an error toast.
     *
     * @param {HTMLFormElement} form the bank AJAX form
     * @param {string} field the field name the message belongs to
     * @param {string} message the localized message
     */
    function showError(form, field, message) {
        let slot = form.querySelector('.bank-field-error[data-error-for="' + field + '"]');
        if (!slot) {
            slot = form.querySelector('.bank-field-error[data-error-for="_global"]');
        }
        if (slot) {
            slot.textContent = message;
            slot.classList.add('visible');
        } else if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message);
        }
    }

    /**
     * Copies the trigger's `data-field-*` attributes into the modal: form controls
     * whose name matches the suffix (case-insensitively — the browser lowercases
     * attribute names) receive the value, `[data-bank-label]` elements receive it
     * as text. Also resets the modal's previous inline errors.
     *
     * @param {HTMLElement} trigger the clicked open-modal button
     * @param {HTMLElement} modal the overlay element being opened
     */
    function primeModal(trigger, modal) {
        const form = modal.querySelector('form.bank-ajax-form');
        if (form) {
            clearErrors(form);
        }
        for (const attr of Array.from(trigger.attributes)) {
            if (!attr.name.startsWith('data-field-')) {
                continue;
            }
            const key = attr.name.slice('data-field-'.length);
            if (form) {
                for (const el of Array.from(form.elements)) {
                    if (el.name && el.name.toLowerCase() === key) {
                        el.value = attr.value;
                    }
                }
            }
            modal.querySelectorAll('[data-bank-label]').forEach(function (el) {
                if (el.getAttribute('data-bank-label').toLowerCase() === key) {
                    el.textContent = attr.value;
                }
            });
        }
        // Reset/refresh the live transfer-fee preview to match the (re)opened modal's amount
        // (REQ-BANK-033); updateFeePreview hides it when there is nothing to show.
        if (form && form.querySelector('[data-fee-preview]')) {
            updateFeePreview(form);
        }
        // Org-unit request modal (REQ-BANK-040/-041): re-apply the transfer-type rows and the live
        // over-limit warning for the (re)opened modal — the CTA carries no per-row data-field-*.
        if (form && form.querySelector('[data-limit-warning]')) {
            const typeSelect = form.querySelector('select[data-role="org-unit-request-type"]');
            if (typeSelect) {
                syncRequestTypeRows(typeSelect);
            }
            updateLimitWarning(form);
        }
        // Split deposit (REQ-BANK-043): re-sync the percentage row + preview to the (re)opened modal's
        // toggle so a reused modal never shows a stale split state. The org-unit request modal's split
        // visibility is additionally driven by the type via syncRequestTypeRows above.
        if (form && form.querySelector('[data-split-row]')) {
            const splitToggle = form.querySelector('[data-role="bank-split-toggle"]');
            if (splitToggle) {
                toggleSplitRow(splitToggle);
            }
        }
        // Unified movement modal (#997): sync the whole modal (rows, endpoint, submit label, live
        // helpers) to its type selector's current value so a (re)opened modal never shows a stale
        // type's fields. Runs last so it owns the final consistent state.
        if (form) {
            const movementType = form.querySelector('[data-role="bank-movement-type"]');
            if (movementType) {
                syncMovementRows(movementType);
            }
        }
    }

    document.addEventListener('click', function (event) {
        const trigger = event.target.closest('[data-trigger="open-modal-display"][data-modal-id]');
        if (!trigger) {
            return;
        }
        const modal = document.getElementById(trigger.getAttribute('data-modal-id'));
        if (modal && modal.classList.contains('krt-modal-overlay')) {
            primeModal(trigger, modal);
            if (modal.id === 'bank-confirm-request-modal') {
                applyConfirmModalState(trigger, modal);
            }
        }
    });

    /**
     * Fills a counterparty org-unit <select> from the chosen counterparty user's
     * memberships (REQ-BANK-044). Resets it to just its placeholder and disables it
     * while loading or when no user is selected; auto-selects when the user has exactly
     * one membership. A read-only same-origin GET, so no CSRF / krtFetch is involved.
     *
     * @param {HTMLElement} userSelect the counterparty user picker — a native {@code <select>}, or
     *     the searchable combobox's hidden value input after enhancement (both expose {@code .value})
     * @param {HTMLSelectElement} orgSelect the dependent org-unit picker
     */
    async function fillCounterpartyOrgUnits(userSelect, orgSelect) {
        const placeholder = orgSelect.querySelector('option[value=""]');
        orgSelect.innerHTML = '';
        if (placeholder) {
            orgSelect.appendChild(placeholder);
        }
        orgSelect.value = '';
        orgSelect.disabled = true;
        const userId = userSelect.value;
        if (!userId) {
            return;
        }
        let memberships = [];
        try {
            const response = await fetch(
                '/users/' + encodeURIComponent(userId) + '/memberships?allKinds=true',
                {
                    headers: { Accept: 'application/json' },
                },
            );
            if (response.ok) {
                memberships = await response.json();
            }
        } catch {
            memberships = [];
        }
        if (!Array.isArray(memberships) || memberships.length === 0) {
            return;
        }
        memberships.forEach(function (membership) {
            const option = document.createElement('option');
            option.value = membership.orgUnitId;
            option.textContent = membership.orgUnitName;
            orgSelect.appendChild(option);
        });
        orgSelect.disabled = false;
        if (memberships.length === 1) {
            orgSelect.value = memberships[0].orgUnitId;
        }
    }

    // Delegated so it survives the accountBody fragment swap (REQ-FE-005): a change on a
    // counterparty user picker (re)loads its paired org-unit select. The picker is a searchable
    // combobox (remote-bank-users, #1193 follow-up), so [data-counterparty-user] resolves to the
    // enhancer's hidden VALUE input (an <input>, not a <select>) — it inherits the marker attributes
    // and re-dispatches a bubbling `change` on commit, which this catches (so match on the attribute,
    // not the tag).
    document.addEventListener('change', function (event) {
        const userSelect = event.target.closest('[data-counterparty-user]');
        if (!userSelect) {
            return;
        }
        const orgSelect = document.getElementById(userSelect.getAttribute('data-orgunit-target'));
        if (orgSelect) {
            fillCounterpartyOrgUnits(userSelect, orgSelect);
        }
    });

    /**
     * Resets a counterparty org-unit select to just its placeholder option and clears the selection,
     * mirroring {@link fillCounterpartyOrgUnits}'s reset before it repopulates.
     *
     * @param {HTMLSelectElement} select the org-unit select
     */
    function resetCounterpartyOrgUnitOptions(select) {
        const placeholder = select.querySelector('option[value=""]');
        select.innerHTML = '';
        if (placeholder) {
            select.appendChild(placeholder);
        }
        select.value = '';
    }

    /**
     * Switches a counterparty block between the registered-tool-user path and the external
     * free-text path (REQ-BANK-044, #994). External: hide + disable the user lookup, show + enable the
     * free-text name, and widen the unit picklist to ALL active org units (cloned from the shared
     * [data-bank-all-orgunits] source) with no membership filter. Registered (default): the reverse —
     * the unit select resets to its placeholder and is disabled until a user is chosen (then
     * {@link fillCounterpartyOrgUnits} fills it from that user's memberships). A no-op for a
     * gated-off (movement-type-inactive) block, since its toggle is disabled.
     *
     * <p>The registered-user lookup is a searchable combobox (remote-bank-users, #1193 follow-up), so
     * enabling/disabling + clearing it routes through {@link setMovementControlActive}: that resets
     * the value via the combobox controller ({@code krtCombobox.setValue('')}) and mirrors the
     * disabled state onto the visible textbox — setting {@code .disabled} on the hidden value input
     * alone would not stop the user typing into the box. The plain free-text name input goes through
     * the same helper (its non-combobox branch), so both alternatives share one code path.
     *
     * @param {HTMLInputElement} toggle the "kein Tool-Account" checkbox
     */
    function toggleCounterpartyExternal(toggle) {
        const section = toggle.closest('[data-counterparty-section]');
        if (!section) {
            return;
        }
        const external = toggle.checked && !toggle.disabled;
        const registeredRow = section.querySelector('[data-cp-registered-row]');
        const externalRow = section.querySelector('[data-cp-external-row]');
        const userSelect = section.querySelector('[data-counterparty-user]');
        const nameInput = section.querySelector('[data-counterparty-name]');
        const orgSelect = section.querySelector('[data-counterparty-orgunit]');
        if (registeredRow) {
            registeredRow.hidden = external;
        }
        if (userSelect) {
            setMovementControlActive(userSelect, !external);
        }
        if (externalRow) {
            externalRow.hidden = !external;
        }
        if (nameInput) {
            setMovementControlActive(nameInput, external);
        }
        if (orgSelect) {
            resetCounterpartyOrgUnitOptions(orgSelect);
            if (external) {
                const form = toggle.closest('form');
                const source = form ? form.querySelector('[data-bank-all-orgunits]') : null;
                if (source) {
                    Array.prototype.forEach.call(source.options, function (option) {
                        if (option.value) {
                            orgSelect.appendChild(option.cloneNode(true));
                        }
                    });
                }
                orgSelect.disabled = false;
            } else {
                orgSelect.disabled = true;
            }
        }
    }

    // Delegated so it survives the accountBody swap: toggling "kein Tool-Account" swaps the block.
    document.addEventListener('change', function (event) {
        const toggle = event.target.closest('[data-role="bank-cp-external-toggle"]');
        if (toggle) {
            toggleCounterpartyExternal(toggle);
        }
    });

    /**
     * Converts one form control into its JSON value: checkboxes to booleans,
     * whitelisted names to numbers/booleans, everything else stays a string.
     *
     * @param {HTMLElement} el the form control
     * @returns {*} the JSON-ready value; '' and null mark "omit this field"
     */
    function fieldValue(el) {
        if (el.type === 'checkbox') {
            return el.checked;
        }
        const value = el.value;
        if (value === '') {
            return '';
        }
        if (NUMBER_FIELDS.includes(el.name)) {
            return Number(value);
        }
        if (BOOLEAN_FIELDS.includes(el.name)) {
            return value === 'true';
        }
        return value;
    }

    /**
     * Serializes and sends one bank AJAX form: `_`-prefixed fields fill the
     * endpoint's `{placeholder}` slots instead of the body, `data-account-id`
     * is injected under `data-account-id-field` (default `accountId`), success
     * runs the in-place refresh ({@link handleBankSuccess}: close modal + swap the
     * `data-refresh` fragment), errors render inline.
     *
     * @param {HTMLFormElement} form the submitted form
     */
    async function submitBankForm(form) {
        clearErrors(form);
        // Unified movement modal (#997): the source-account picker carries no name, so mirror its
        // current value onto data-account-id (submitBankForm injects it under the type-specific
        // data-account-id-field). Its native `required` gates an empty pick before submit even fires;
        // a stray empty value is a backstop the backend rejects with an inline accountId error.
        const movementSource = form.querySelector('[data-role="bank-movement-source"]');
        if (movementSource) {
            form.setAttribute('data-account-id', movementSource.value || '');
        }
        const method = (form.getAttribute('data-method') || 'POST').toUpperCase();
        const body = {};
        const placeholders = {};
        for (const el of Array.from(form.elements)) {
            if (!el.name || el.disabled) {
                continue;
            }
            if (el.name.startsWith('_')) {
                placeholders[el.name.slice(1).toLowerCase()] = el.value;
                continue;
            }
            const value = fieldValue(el);
            if (value === '') {
                continue;
            }
            body[el.name] = value;
        }
        const accountId = form.getAttribute('data-account-id');
        if (accountId) {
            body[form.getAttribute('data-account-id-field') || 'accountId'] = accountId;
        }
        const endpoint = form
            .getAttribute('data-endpoint')
            .replace(/\{([^}]+)\}/g, function (match, name) {
                const filled = placeholders[name.toLowerCase()];
                return filled !== undefined ? encodeURIComponent(filled) : match;
            });

        // Routed through the shared window.krtFetch.write (#906 Q13): it owns the CSRF header,
        // the retry-once-on-403 and the X-Reauthenticate 401 redirect. The submit button is the
        // opts.submitter, so send() disables it for the round-trip and re-enables it in its finally
        // (which runs right after onSuccess schedules handleBankSuccess's async swap, matching the
        // former explicit early re-enable). toast:false keeps the bank's inline field-level error
        // rendering; onError handles every non-ok branch (returning true suppresses the default
        // toast), and onNetworkError reproduces the old null-response inline _global error.
        const submitButton = form.querySelector('button[type="submit"]');
        await window.krtFetch.write({
            method: method,
            url: endpoint,
            payload: method !== 'GET' && method !== 'DELETE' ? body : undefined,
            submitter: submitButton,
            toast: false,
            errorMessage: genericError(),
            onSuccess: function () {
                handleBankSuccess(form);
            },
            onError: function (status, payload) {
                // A backend body flag (not the header-based 401 that krtFetch redirects on) telling
                // us the OAuth2 session is gone: reload so the browser re-runs the login flow.
                if (payload && payload.unauthenticated) {
                    window.location.reload();
                    return true;
                }
                if (
                    payload &&
                    Array.isArray(payload.fieldErrors) &&
                    payload.fieldErrors.length > 0
                ) {
                    payload.fieldErrors.forEach(function (fe) {
                        showError(form, fe.field, fe.message);
                    });
                    return true;
                }
                const message = payload && payload.message ? payload.message : genericError();
                const field =
                    payload && payload.code && CODE_FIELD[payload.code]
                        ? CODE_FIELD[payload.code]
                        : '_global';
                showError(form, field, message);
                return true;
            },
            onNetworkError: function () {
                showError(form, '_global', genericError());
                return true;
            },
        });
    }

    /**
     * Resolves a bank live-sync account placeholder to a concrete account id. `account`: the form's
     * dedicated publish-only `data-livesync-account`, else its submitted `data-account-id` (the
     * movement modal), else a primed `_livesyncAccount` field (the confirm modal, filled per-row by
     * primeModal). `destination`: an enabled non-empty submitted `destinationAccountId` (a movement
     * transfer target), else a primed `_livesyncDestination` field (a confirm of a transfer
     * request). Returns null when the form has no such account (e.g. a deposit / a non-transfer
     * confirm has no destination) so the entry is skipped. None of these are read into the submit
     * body (`data-livesync-account` is an attribute; the `_livesync*` fields are `_`-prefixed, so
     * submitBankForm treats them as unused endpoint placeholders), so tagging a form for publishing
     * never changes its money write.
     *
     * @param {HTMLFormElement} form the form whose write just succeeded
     * @param {string} ref the placeholder name (`account` or `destination`)
     * @returns {string|null} the resolved account id, or null
     */
    function resolveBankLiveSyncAccount(form, ref) {
        if (ref === 'account') {
            const primed = form.querySelector('[name="_livesyncAccount"]');
            return (
                form.getAttribute('data-livesync-account') ||
                form.getAttribute('data-account-id') ||
                (primed && primed.value ? primed.value : null)
            );
        }
        if (ref === 'destination') {
            const el =
                form.querySelector('[name="destinationAccountId"]') ||
                form.querySelector('[name="_livesyncDestination"]');
            return el && !el.disabled && el.value ? el.value : null;
        }
        return null;
    }

    /**
     * Broadcasts a bank form's live-sync publish matrix after a successful write (REQ-FE-015): the
     * form's `data-livesync` attribute is a space-separated list of `topic/section,section` entries,
     * where a `bank:@account` / `bank:@destination` topic resolves its placeholder to the form's
     * account. Publishing needs no subscription (the sanctioned cross-topic case) — the acting page
     * is not subscribed to most of these rooms, yet their viewers must still update. Fully additive
     * and fire-and-forget: it never touches the write or the local swap, duplicate topics are
     * collapsed, and an unresolvable-account entry is simply skipped.
     *
     * @param {HTMLFormElement} form the form whose write just succeeded
     */
    function publishBankLiveSync(form) {
        if (!window.krtLiveSync || typeof window.krtLiveSync.sendChanged !== 'function') {
            return;
        }
        const spec = form.getAttribute('data-livesync');
        if (!spec) {
            return;
        }
        const sent = {};
        spec.trim()
            .split(/\s+/)
            .forEach(function (entry) {
                const slash = entry.indexOf('/');
                if (slash < 0) {
                    return;
                }
                let topic = entry.slice(0, slash);
                const sections = entry
                    .slice(slash + 1)
                    .split(',')
                    .filter(Boolean);
                if (!sections.length) {
                    return;
                }
                const at = topic.indexOf(':@');
                if (at >= 0) {
                    const id = resolveBankLiveSyncAccount(form, topic.slice(at + 2));
                    if (!id) {
                        return; // no such account on this form — skip this entry.
                    }
                    topic = topic.slice(0, at) + ':' + id;
                }
                const key = topic + '|' + sections.join(',');
                if (sent[key]) {
                    return;
                }
                sent[key] = true;
                window.krtLiveSync.sendChanged(topic, sections);
            });
    }

    /**
     * The in-place success path for a bank AJAX form (#579, REQ-FE-005): closes the form's modal,
     * shows the localized success toast and re-renders the server fragment named by the form's
     * `data-refresh` attribute (accountBody / manageBody / grantsMatrix). Re-rendering server-side
     * keeps money aggregates (balance, holder distribution, tab-counts) and every trigger button's
     * fresh `data-field-version` authoritative — the page never recomputes them in JS. The swap is
     * given the dedicated `data-bank-refresh-error` message (NOT the generic "action failed" text):
     * the write already succeeded, so if only the follow-up refresh GET bounces the user must be told
     * "saved, but reload" rather than "action failed, retry" — the latter could prompt a second money
     * booking. Falls back to a full reload if the swap helper or the refresh target is missing.
     *
     * @param {HTMLFormElement} form the form whose write just succeeded
     */
    function handleBankSuccess(form) {
        const modal = form.closest('.krt-modal-overlay');
        if (modal) {
            // Close via the shared class toggle, NOT an inline style.display. The shared
            // open-modal-display handler (common-handlers.js) shows a `.krt-modal-overlay` by adding
            // the `krtm-modal-open` class (display:flex, inline-migration.css). An inline
            // `style="display:none"` outranks that class rule, so a modal closed here with an inline
            // style could never be re-opened by a later trigger without a full page reload — the bank
            // staffer who decided one request and then clicked the confirm/reject button on a second
            // one got a dead button. Mirror the shared close-modal-display handler so the reused
            // modal opens again.
            modal.classList.remove('krtm-modal-open');
            modal.classList.add('krtm-hidden');
        }
        const main = document.querySelector('main[data-bank-saved]');
        const savedMessage = main ? main.getAttribute('data-bank-saved') : null;
        if (savedMessage && typeof window.showFrontendSuccessToast === 'function') {
            window.showFrontendSuccessToast(savedMessage);
        }
        // Tell peers viewing the affected rooms that these sections changed (REQ-FE-015), read from
        // the form's data-livesync matrix. Done before the local swap and independent of it —
        // additive and fire-and-forget, so it never affects the actor's own re-render below.
        publishBankLiveSync(form);
        const spec = REFRESH_TARGETS[form.getAttribute('data-refresh')];
        if (!spec || !window.krtFetch || typeof window.krtFetch.swap !== 'function') {
            window.location.reload();
            return;
        }
        const refreshError = main ? main.getAttribute('data-bank-refresh-error') : null;
        const url = spec.preserveQuery
            ? window.location.pathname + window.location.search
            : window.location.pathname;
        // Freeze the about-to-be-replaced open-modal trigger buttons for the duration of the
        // in-flight swap. The swap is async, so until its fresh DOM lands the old triggers still
        // carry a now-stale data-field-version; a rapid re-open + submit would prime a modal with it
        // and 409 (the manage/holder lifecycle race the old full reload dodged by navigating away).
        // It also blocks an accidental rapid double money booking. On a successful swap they are
        // replaced by fresh enabled buttons; if the swap bails (rare expired-session bounce, DOM left
        // untouched) we re-enable exactly the ones we froze.
        const container = document.querySelector(spec.container);
        const frozen = container
            ? Array.from(
                  container.querySelectorAll(
                      'button[data-trigger="open-modal-display"]:not([disabled])',
                  ),
              )
            : [];
        frozen.forEach(function (button) {
            button.disabled = true;
        });
        window.krtFetch
            .swap({
                url: url,
                container: spec.container,
                fragmentValue: form.getAttribute('data-refresh'),
                errorMessage: refreshError || genericError(),
            })
            .then(function (swapped) {
                if (!swapped) {
                    frozen.forEach(function (button) {
                        button.disabled = false;
                    });
                }
            });
    }

    document.addEventListener('submit', function (event) {
        const form = event.target.closest('form.bank-ajax-form');
        if (!form) {
            return;
        }
        event.preventDefault();
        submitBankForm(form);
    });

    /**
     * Reads the org-wide in-game transfer-fee rate from `<main data-transfer-fee-rate>`
     * (REQ-BANK-033). Returns 0 (no preview) when absent or out of the sane [0, 1) range.
     *
     * @returns {number} the fee rate as a fraction
     */
    function transferFeeRate() {
        const main = document.querySelector('main[data-transfer-fee-rate]');
        const raw = main ? Number(main.getAttribute('data-transfer-fee-rate')) : 0;
        return Number.isFinite(raw) && raw > 0 && raw < 1 ? raw : 0;
    }

    /**
     * Live transfer-fee preview + fee-mode gating (REQ-BANK-033, ADR-0052, #999). The fee is always
     * `round(entered * rate)`; only what the toggle does with it differs. In the default on-top mode
     * the entered amount is what ARRIVES and the source is debited `amount + fee`; in the fee-inclusive
     * mode (`feeInclusive` checkbox on) the entered amount is the gross DEBITED and the recipient gets
     * `amount - fee`. The preview shows the fee, the "wird abgebucht" gross and the "kommt an" net for
     * the selected mode. A fee applies only to a withdrawal and a holder-changing transfer — a deposit
     * and a same-holder transfer are fee-free, where the fee-inclusive toggle is hidden, reset and
     * disabled (so it is omitted from the body) and the preview is suppressed. Guidance only; the
     * authoritative fee is computed server-side at booking time.
     *
     * @param {HTMLFormElement} form the booking form carrying a `[data-fee-preview]` block
     */
    function updateFeePreview(form) {
        const preview = form.querySelector('[data-fee-preview]');
        const amountEl = form.querySelector('input[name="amount"]');
        if (!preview || !amountEl) {
            return;
        }
        const rate = transferFeeRate();
        const typeEl = form.querySelector('[data-role="bank-movement-type"]');
        const type = typeEl ? typeEl.value : null;
        const src = form.querySelector('[name="sourceHolderId"]');
        const dst = form.querySelector('[name="destinationHolderId"]');
        const sameHolder = !!(src && dst && src.value && src.value === dst.value);
        // A fee applies to a withdrawal and to a holder-changing transfer (ADR-0052); a deposit and a
        // same-holder transfer are fee-free.
        const feeApplies = rate > 0 && type !== 'DEPOSIT' && !(type === 'TRANSFER' && sameHolder);
        // The fee-inclusive toggle (#999) is only meaningful where a fee applies: reveal + enable it
        // there, otherwise hide, reset and disable it so `feeInclusive` is omitted from the body.
        const inclusiveRow = form.querySelector('[data-fee-inclusive-row]');
        const inclusiveToggle = inclusiveRow
            ? inclusiveRow.querySelector('input[name="feeInclusive"]')
            : null;
        if (inclusiveRow) {
            inclusiveRow.hidden = !feeApplies;
        }
        if (inclusiveToggle) {
            inclusiveToggle.disabled = !feeApplies;
            if (!feeApplies) {
                inclusiveToggle.checked = false;
            }
        }
        const amount = Number(amountEl.value);
        if (!feeApplies || !Number.isFinite(amount) || amount <= 0) {
            preview.hidden = true;
            return;
        }
        const fee = Math.round(amount * rate);
        const inclusive = !!(inclusiveToggle && inclusiveToggle.checked);
        const debited = inclusive ? amount : amount + fee;
        const arrives = inclusive ? amount - fee : amount;
        const valueEl = preview.querySelector('[data-fee-value]');
        const debitEl = preview.querySelector('[data-fee-debit]');
        const arrivesEl = preview.querySelector('[data-fee-arrives]');
        if (valueEl) {
            valueEl.textContent = fee.toLocaleString('de-DE');
        }
        if (debitEl) {
            debitEl.textContent = debited.toLocaleString('de-DE');
        }
        if (arrivesEl) {
            arrivesEl.textContent = arrives.toLocaleString('de-DE');
        }
        preview.hidden = false;
    }

    function recomputeFeePreviewFrom(target) {
        const form = target && target.closest ? target.closest('form') : null;
        if (form && form.querySelector('[data-fee-preview]')) {
            updateFeePreview(form);
        }
    }

    /**
     * Live owner-approval warning on the org-unit request modal (REQ-BANK-041, amended): shows the
     * warning whenever the withdrawal/transfer will need the responsible holder's approval. The
     * per-account limit rides on each source-account <option> as `data-limit`. Semantics: a configured
     * limit warns only when the entered amount exceeds it; a MISSING limit (`data-limit` absent/empty,
     * i.e. no per-user/role/all-members limit applies) means approval is ALWAYS required, so it warns
     * for any positive amount. Advisory only — it never blocks submission; the request is still filed
     * and then flagged for the responsible holder.
     *
     * @param {HTMLFormElement} form the org-unit request form carrying a `[data-limit-warning]` block
     */
    function updateLimitWarning(form) {
        const warning = form.querySelector('[data-limit-warning]');
        const amountEl = form.querySelector('input[name="amount"]');
        const accountEl = form.querySelector('select[name="sourceAccountId"]');
        if (!warning || !amountEl || !accountEl) {
            return;
        }
        // REQ-BANK-042: a deposit is never subject to an approval limit — never warn.
        const typeEl = form.querySelector('select[data-role="org-unit-request-type"]');
        if (typeEl && typeEl.value === 'DEPOSIT') {
            warning.hidden = true;
            return;
        }
        const amount = Number(amountEl.value);
        if (!Number.isFinite(amount) || amount <= 0) {
            warning.hidden = true;
            return;
        }
        const option = accountEl.options[accountEl.selectedIndex];
        const rawLimit = option ? option.getAttribute('data-limit') : null;
        const limit = rawLimit === null || rawLimit === '' ? null : Number(rawLimit);
        // No configured limit => approval is always required; a configured limit => only above it.
        const needsApproval = limit === null || !Number.isFinite(limit) || amount > limit;
        warning.hidden = !needsApproval;
    }

    /**
     * Recomputes the over-limit warning for the form the edited field belongs to.
     *
     * @param {EventTarget} target the field that fired the event
     */
    function recomputeLimitWarningFrom(target) {
        const form = target && target.closest ? target.closest('form') : null;
        if (form && form.querySelector('[data-limit-warning]')) {
            updateLimitWarning(form);
        }
    }

    /**
     * Recomputes the Begründung field's `required` flag on the ORG-UNIT request modal (REQ-BANK-045):
     * the field is required only for a WITHDRAWAL/TRANSFER whose selected source account mandates a
     * reason — its <option> carries `data-requires-justification="true"` (set for CARTEL / CARTEL_BANK
     * / SPECIAL). For a deposit, or a source account that does not mandate a reason, it is optional.
     * The bank-employee withdraw/transfer modals are NOT touched here — they have no
     * `[data-request-justification-only]` wrapper and carry a server-rendered `required` flag instead.
     *
     * @param {HTMLFormElement} form the org-unit request form
     */
    function updateJustificationRequired(form) {
        const input = form.querySelector('input[name="justification"]');
        if (!input) {
            return;
        }
        const typeEl = form.querySelector('select[data-role="org-unit-request-type"]');
        const type = typeEl ? typeEl.value : null;
        if (type !== 'WITHDRAWAL' && type !== 'TRANSFER') {
            input.required = false;
            return;
        }
        const accountEl = form.querySelector('select[name="sourceAccountId"]');
        const option = accountEl ? accountEl.options[accountEl.selectedIndex] : null;
        input.required = !!option && option.getAttribute('data-requires-justification') === 'true';
    }

    /**
     * Recomputes the org-unit request modal's justification-required flag for the form the edited
     * field belongs to. Scoped to that modal via its `[data-request-justification-only]` wrapper so a
     * field edit in a bank-employee modal never clears its server-set `required`.
     *
     * @param {EventTarget} target the field that fired the event
     */
    function recomputeJustificationRequiredFrom(target) {
        const form = target && target.closest ? target.closest('form') : null;
        if (form && form.querySelector('[data-request-justification-only]')) {
            updateJustificationRequired(form);
        }
    }

    /**
     * Shows/hides and enables/disables a deposit form's split-percentage row to match its toggle
     * checkbox (REQ-BANK-043). The percentage input is disabled while hidden so {@link submitBankForm}
     * omits it entirely (and the backend's "split off ⇒ no percentage" rule is never tripped). Clears
     * the value + preview when the split is switched off, then refreshes the preview.
     *
     * @param {HTMLInputElement} toggle the split toggle checkbox
     */
    function toggleSplitRow(toggle) {
        const form = toggle.closest('form');
        if (!form) {
            return;
        }
        const row = form.querySelector('[data-split-row]');
        const input = form.querySelector('input[name="splitPercent"]');
        const on = toggle.checked && !toggle.disabled;
        if (row) {
            row.hidden = !on;
        }
        if (input) {
            input.disabled = !on;
            if (!on) {
                input.value = '';
            }
        }
        updateSplitPreview(form);
    }

    /**
     * Live split-deposit preview (REQ-BANK-043): the entered amount is the gross; this fills the
     * slice distributed across the squadron accounts (`round(gross * percent / 100)`) and the
     * remainder that stays on the named account (`gross - slice`). Hidden when the split is off or the
     * inputs are incomplete/out of range. Guidance only — the authoritative split (and the exact
     * per-account amounts, which depend on how many squadron accounts are active) is computed
     * server-side at booking time.
     *
     * @param {HTMLFormElement} form the deposit/request form carrying a `[data-split-preview]` block
     */
    function updateSplitPreview(form) {
        const preview = form.querySelector('[data-split-preview]');
        const amountEl = form.querySelector('input[name="amount"]');
        const percentEl = form.querySelector('input[name="splitPercent"]');
        if (!preview || !amountEl || !percentEl) {
            return;
        }
        const gross = Number(amountEl.value);
        const percent = Number(percentEl.value);
        if (
            percentEl.disabled ||
            !Number.isFinite(gross) ||
            gross <= 0 ||
            !Number.isFinite(percent) ||
            percent <= 0 ||
            percent > 100
        ) {
            preview.hidden = true;
            return;
        }
        const slice = Math.round((gross * percent) / 100);
        if (slice <= 0) {
            preview.hidden = true;
            return;
        }
        const sliceEl = preview.querySelector('[data-split-slice]');
        const remainderEl = preview.querySelector('[data-split-remainder]');
        if (sliceEl) {
            sliceEl.textContent = slice.toLocaleString('de-DE');
        }
        if (remainderEl) {
            remainderEl.textContent = (gross - slice).toLocaleString('de-DE');
        }
        preview.hidden = false;
    }

    /**
     * Recomputes the split preview for the form the edited field belongs to (amount or percentage).
     *
     * @param {EventTarget} target the field that fired the event
     */
    function recomputeSplitPreviewFrom(target) {
        const form = target && target.closest ? target.closest('form') : null;
        if (form && form.querySelector('[data-split-preview]')) {
            updateSplitPreview(form);
        }
    }

    /* Split toggle: show/hide the percentage row and refresh the preview as it is ticked/unticked. */
    document.addEventListener('change', function (event) {
        const toggle = event.target.closest('[data-role="bank-split-toggle"]');
        if (toggle) {
            toggleSplitRow(toggle);
        }
    });

    /**
     * Live balance-split calculator on the holder detail page (REQ-BANK-032): given the holder's
     * entered current in-game balance and their server-rendered global custody total
     * (`data-reserved`), shows how much is the holder's own private money (`balance − reserved`).
     * Purely client-side — nothing is stored. A negative own value (physically less than the bank's
     * records say) is flagged; a negative reserved means the bank owes the holder.
     *
     * @param {HTMLInputElement} input the balance input that fired
     */
    function updateBalanceCalc(input) {
        const panel = input.closest('[data-reserved]');
        if (!panel) {
            return;
        }
        const result = panel.querySelector('[data-balance-result]');
        const ownEl = panel.querySelector('[data-balance-own]');
        if (!result || !ownEl) {
            return;
        }
        const balance = Number(input.value);
        if (input.value === '' || !Number.isFinite(balance)) {
            result.hidden = true;
            return;
        }
        const reserved = Number(panel.getAttribute('data-reserved'));
        const own = balance - (Number.isFinite(reserved) ? reserved : 0);
        ownEl.textContent = Math.round(own).toLocaleString('de-DE') + ' aUEC';
        ownEl.classList.toggle('bank-amount--neg', own < 0);
        ownEl.classList.toggle('bank-amount--pos', own > 0);
        result.hidden = false;
    }

    /**
     * Dispatches one field edit to both client-side helpers — the live transfer-fee preview
     * (REQ-BANK-033) and the balance-split calculator (REQ-BANK-032). Registered once for `input`
     * and once for `change`, so a select change (the holder pickers in the transfer modal) refreshes
     * the fee preview too.
     *
     * @param {EventTarget} target the field that fired the event
     */
    function onBankFieldEdit(target) {
        recomputeFeePreviewFrom(target);
        recomputeLimitWarningFrom(target);
        recomputeJustificationRequiredFrom(target);
        recomputeSplitPreviewFrom(target);
        if (target && target.matches && target.matches('[data-balance-input]')) {
            updateBalanceCalc(target);
        }
    }

    document.addEventListener('input', function (event) {
        onBankFieldEdit(event.target);
    });
    document.addEventListener('change', function (event) {
        onBankFieldEdit(event.target);
    });

    /**
     * Applies a successful grant flag PATCH in place (#579): flips the clicked button's pressed
     * state, mirrors the new value onto the row's data-can-<flag> attribute (read by the next click)
     * and syncs the row's data-version from the BankGrantDto response so an immediate second toggle
     * on the same row does not 409.
     *
     * @param {HTMLButtonElement} button the clicked matrix-flag button
     * @param {HTMLTableRowElement} row the grant row carrying the flag + version attributes
     * @param {string} flag the toggled flag name (canDeposit / canWithdraw / canTransfer)
     * @param {boolean} newValue the value the flag was toggled to
     * @param {Object} payload the BankGrantDto response body (carries the fresh version)
     */
    function applyGrantFlagResult(button, row, flag, newValue, payload) {
        button.classList.toggle('on', newValue);
        button.setAttribute('aria-pressed', String(newValue));
        const attr = FLAG_ATTR[flag];
        if (attr) {
            row.setAttribute(attr, String(newValue));
        }
        const version = payload ? payload.version : null;
        if (version == null) {
            return;
        }
        if (window.krtFetch && typeof window.krtFetch.syncVersion === 'function') {
            window.krtFetch.syncVersion(row, version);
        } else {
            row.setAttribute('data-version', String(version));
        }
    }

    /**
     * Grants matrix: clicking a flag cell PATCHes the grant with the toggled flag plus the row's
     * other two flags and its optimistic-lock version, then applies the result IN PLACE (#579) via
     * {@link applyGrantFlagResult}. This is the one isolated single-row write in the bank area (no
     * aggregate, count or structural change on screen), so a precise dom-patch beats a whole-matrix
     * re-render that would reset focus and feel heavy on rapid toggling.
     */
    document.addEventListener('click', async function (event) {
        const flagButton = event.target.closest('button.matrix-flag[data-flag]');
        if (!flagButton) {
            return;
        }
        const row = flagButton.closest('tr[data-user-id][data-account-id]');
        if (!row) {
            return;
        }
        const flag = flagButton.getAttribute('data-flag');
        // The intended new state of the CLICKED flag, captured now. The rest of the payload (the
        // other two flags + the row's optimistic-lock version) is re-read from the row LAZILY at send
        // time (see the payload thunk) and the write is serialized per grant row, so toggling a
        // second flag on the same row while the first is in flight sees the first toggle's applied
        // state + fresh version instead of reverting it or 409-ing (self-collision fix).
        const newValue = row.getAttribute(FLAG_ATTR[flag]) !== 'true';
        const endpoint =
            '/api/proxy/bank/grants/' +
            encodeURIComponent(row.getAttribute('data-user-id')) +
            '/' +
            encodeURIComponent(row.getAttribute('data-account-id'));
        // Routed through window.krtFetch.write (#906 Q13): flagButton is the submitter (disabled
        // for the round-trip, re-enabled in send()'s finally); applyGrantFlagResult receives the
        // parsed success body. toast:false + an explicit onError preserve the bank's payload.message
        // error toast — krtFetch's default error toast reads a problem+json `detail`, but the bank
        // grant error body carries `message`, so the default would downgrade it to the generic text.
        await window.krtFetch.write({
            method: 'PATCH',
            url: endpoint,
            serialize:
                'bank-grant:' +
                row.getAttribute('data-user-id') +
                ':' +
                row.getAttribute('data-account-id'),
            payload: function () {
                const p = {
                    canDeposit: row.getAttribute('data-can-deposit') === 'true',
                    canWithdraw: row.getAttribute('data-can-withdraw') === 'true',
                    canTransfer: row.getAttribute('data-can-transfer') === 'true',
                    version: Number(row.getAttribute('data-version')),
                };
                p[flag] = newValue;
                return p;
            },
            submitter: flagButton,
            toast: false,
            errorMessage: genericError(),
            onSuccess: function (payload) {
                applyGrantFlagResult(flagButton, row, flag, newValue, payload);
                // The grants matrix changed for every other viewer (REQ-FE-015). This isolated
                // DOM-patch write does not go through handleBankSuccess, so broadcast here.
                if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
                    window.krtLiveSync.sendChanged('bank', ['grants']);
                }
            },
            onError: function (status, payload) {
                const message = payload && payload.message ? payload.message : genericError();
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(message);
                }
                return true;
            },
            onNetworkError: function () {
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(genericError());
                }
                return true;
            },
        });
    });

    // Grants filter selects: navigate to the chosen grouping/entity on change. Match on the
    // attribute, NOT the tag: the account filter is a searchable combobox (remote-bank-accounts), so
    // after enhancement [data-role="bank-grants-filter"] resolves to the enhancer's hidden value
    // input (an <input>, not a <select>); the employee filter stays a native <select>. Both inherit
    // the data-view / data-param attributes read below.
    document.addEventListener('change', function (event) {
        const select = event.target.closest('[data-role="bank-grants-filter"]');
        if (!select) {
            return;
        }
        const params = new URLSearchParams();
        params.set('view', select.getAttribute('data-view'));
        if (select.value) {
            params.set(select.getAttribute('data-param'), select.value);
        }
        window.location.assign('/bank/grants?' + params.toString());
    });

    /**
     * The org-unit kinds each account type may own (epic #692 Phase 6, REQ-ORG-019): an ORG_UNIT
     * account is owned by a Staffel/SK, an AREA account by its Bereich, the CARTEL account by the
     * Organisationsleitung. CARTEL_BANK / SPECIAL own no org unit (the picker is hidden).
     */
    const ACCOUNT_TYPE_OWNER_KINDS = {
        ORG_UNIT: ['SQUADRON', 'SPECIAL_COMMAND'],
        AREA: ['BEREICH'],
        CARTEL: ['ORGANISATIONSLEITUNG'],
    };

    /**
     * Account-create modal: a single org-unit picker is shown for the types that carry an owner
     * (ORG_UNIT / AREA / CARTEL) and its options are filtered to the kinds that type may own, so an
     * AREA account can only pick a Bereich and a CARTEL only the Organisationsleitung. The picker is
     * required for ORG_UNIT and AREA (they must be linked) and optional for CARTEL (a CARTEL may
     * predate the OL). Hidden / filtered-out values are cleared so stale ids never reach the backend.
     *
     * @param {HTMLSelectElement} select the account-type select
     */
    function syncAccountTypeRows(select) {
        const form = select.closest('form');
        if (!form) {
            return;
        }
        const type = select.value;
        const allowedKinds = ACCOUNT_TYPE_OWNER_KINDS[type];
        const orgRow = form.querySelector('.bank-row-orgunit');
        if (!orgRow) {
            return;
        }
        orgRow.style.display = allowedKinds ? '' : 'none';
        const control = orgRow.querySelector('select');
        if (!control) {
            return;
        }
        // AREA and ORG_UNIT must be linked; CARTEL link is optional.
        control.required = type === 'ORG_UNIT' || type === 'AREA';
        let currentStillVisible = false;
        Array.prototype.forEach.call(control.options, function (option) {
            if (!option.value) {
                return; // keep the "please choose" placeholder
            }
            const kind = option.getAttribute('data-kind');
            const visible = !!allowedKinds && allowedKinds.indexOf(kind) !== -1;
            option.hidden = !visible;
            option.disabled = !visible;
            if (visible && option.value === control.value) {
                currentStillVisible = true;
            }
        });
        // Reset the selection when the picker is hidden or the current pick is now filtered out.
        if (!allowedKinds || !currentStillVisible) {
            control.value = '';
        }
    }

    document.addEventListener('change', function (event) {
        const select = event.target.closest('select[data-role="bank-account-type"]');
        if (select) {
            syncAccountTypeRows(select);
        }
    });

    document.querySelectorAll('select[data-role="bank-account-type"]').forEach(syncAccountTypeRows);

    /**
     * Toggles a transfer-only row and its inner <select>: shown/enabled/required only for a
     * TRANSFER, otherwise hidden, disabled (so it is omitted from the submitted body) and cleared.
     * No-op when the row is absent. Shared by the request modal and the confirm modal.
     *
     * @param {HTMLElement|null} row the transfer-only row wrapper
     * @param {boolean} isTransfer whether the current request type is TRANSFER
     */
    function toggleTransferOnlyControl(row, isTransfer) {
        if (!row) {
            return;
        }
        row.hidden = !isTransfer;
        // Native <select> before enhancement / no-JS, or the searchable combobox's hidden input
        // afterwards (the holder picker is upgraded into a combobox). Disabling the hidden input
        // keeps it out of the submit for a non-transfer; the visible textbox is disabled to match.
        const control = row.querySelector('select, .krt-combobox input[type="hidden"]');
        if (control) {
            control.disabled = !isTransfer;
            control.required = isTransfer;
            if (!isTransfer) {
                if (control.krtCombobox) {
                    control.krtCombobox.setValue('');
                } else {
                    control.value = '';
                }
            }
            const box = control.closest ? control.closest('.krt-combobox') : null;
            const textbox = box ? box.querySelector('.krt-combobox__input') : null;
            if (textbox) {
                textbox.disabled = !isTransfer;
            }
        }
    }

    /**
     * Org-unit request modal (REQ-BANK-039/-040/-042): adapts the modal to the chosen request type.
     *  - Source picker: a DEPOSIT may target ANY active account (REQ-BANK-042); a WITHDRAWAL/TRANSFER
     *    only the caller's request-capable accounts (the `data-can-debit` options, REQ-BANK-039).
     *    Non-eligible options are hidden + disabled and the selection re-points to the first eligible
     *    one if the current pick fell out (mirrors {@link syncAccountTypeRows}).
     *  - Account hint follows the type ("any active account" vs. "only accounts you may request for").
     *  - Transfer destination row: shown/enabled only for a TRANSFER.
     *  - The over-limit warning is recomputed (and stays hidden for a deposit).
     *
     * @param {HTMLSelectElement} select the request-type select
     */
    function syncRequestTypeRows(select) {
        const form = select.closest('form');
        if (!form) {
            return;
        }
        const type = select.value;
        const isTransfer = type === 'TRANSFER';
        const isDeposit = type === 'DEPOSIT';
        const account = form.querySelector('select[data-role="org-unit-request-account"]');
        if (account) {
            let currentVisible = false;
            Array.prototype.forEach.call(account.options, function (option) {
                const debitable = option.getAttribute('data-can-debit') === 'true';
                const visible = isDeposit || debitable;
                option.hidden = !visible;
                option.disabled = !visible;
                if (visible && option.value === account.value) {
                    currentVisible = true;
                }
            });
            if (!currentVisible) {
                const first = Array.prototype.find.call(account.options, function (o) {
                    return !o.disabled;
                });
                account.value = first ? first.value : '';
            }
        }
        const depositHint = form.querySelector('[data-deposit-hint]');
        const debitHint = form.querySelector('[data-debit-hint]');
        if (depositHint) {
            depositHint.hidden = !isDeposit;
        }
        if (debitHint) {
            debitHint.hidden = isDeposit;
        }
        const row = form.querySelector('[data-request-transfer-only]');
        toggleTransferOnlyControl(row, isTransfer);
        // Split deposit (REQ-BANK-043): the split is DEPOSIT-only. Show the block only for a deposit,
        // and disable its toggle otherwise so splitEnabled is omitted from a withdrawal/transfer body.
        const splitBlock = form.querySelector('[data-split-deposit-only]');
        if (splitBlock) {
            splitBlock.hidden = !isDeposit;
            const splitToggle = splitBlock.querySelector('[data-role="bank-split-toggle"]');
            if (splitToggle) {
                splitToggle.disabled = !isDeposit;
                if (!isDeposit) {
                    splitToggle.checked = false;
                }
                toggleSplitRow(splitToggle);
            }
        }
        // Begründung (REQ-BANK-045): shown + enabled only for a WITHDRAWAL/TRANSFER; disabled while
        // hidden so a deposit request omits it from the body entirely. The required flag is then
        // recomputed from the selected source account's type.
        const justificationRow = form.querySelector('[data-request-justification-only]');
        if (justificationRow) {
            const isDebit = type === 'WITHDRAWAL' || type === 'TRANSFER';
            justificationRow.hidden = !isDebit;
            const input = justificationRow.querySelector('input[name="justification"]');
            if (input) {
                input.disabled = !isDebit;
                if (!isDebit) {
                    input.value = '';
                    input.required = false;
                }
            }
        }
        updateJustificationRequired(form);
        updateLimitWarning(form);
    }

    document.addEventListener('change', function (event) {
        const select = event.target.closest('select[data-role="org-unit-request-type"]');
        if (select) {
            syncRequestTypeRows(select);
        }
    });

    /**
     * Enables or disables one control inside a unified movement-modal row (#997), keeping a gated-off
     * control out of the submitted JSON (submitBankForm skips disabled controls). Clears the value
     * when disabling so a hidden type's field never leaks into another type's booking: a checkbox is
     * unticked, a searchable-select combobox is reset via its controller, any other control blanked;
     * the combobox's visible textbox mirrors the disabled state.
     *
     * @param {HTMLElement} control the form control to toggle
     * @param {boolean} on whether the owning row is active for the current movement type
     */
    function setMovementControlActive(control, on) {
        control.disabled = !on;
        if (control.type === 'checkbox') {
            if (!on) {
                control.checked = false;
            }
        } else if (!on) {
            if (control.krtCombobox) {
                control.krtCombobox.setValue('');
            } else {
                control.value = '';
            }
        }
        const box = control.closest ? control.closest('.krt-combobox') : null;
        const textbox = box ? box.querySelector('.krt-combobox__input') : null;
        if (textbox) {
            textbox.disabled = !on;
        }
    }

    /**
     * Shows/hides a movement-modal row and enables/disables every submittable control inside it, so
     * only the active movement type's fields are visible AND submitted. A combobox replaces its
     * <select> with a hidden value input, so the two selector groups never double-count one control;
     * the visible combobox textbox is excluded (its disabled state rides on its hidden input via
     * {@link setMovementControlActive}).
     *
     * @param {HTMLElement} row the [data-movement-types] wrapper
     * @param {boolean} on whether the row is active for the current movement type
     */
    function setMovementRowActive(row, on) {
        row.hidden = !on;
        row.querySelectorAll(
            'input:not(.krt-combobox__input), select, textarea, .krt-combobox input[type="hidden"]',
        ).forEach(function (control) {
            setMovementControlActive(control, on);
        });
    }

    /**
     * Recomputes the Begründung field's `required` flag on the unified movement modal (REQ-BANK-045):
     * required only for a WITHDRAWAL/TRANSFER whose source account mandates a reason. On the
     * account-detail page the source is fixed, so the mandate rides on the form as
     * data-justification-required; on the requests overview it comes from the chosen source account's
     * <option> data-requires-justification.
     *
     * @param {HTMLFormElement} form the movement form
     */
    function updateMovementJustification(form) {
        const input = form.querySelector('input[name="justification"]');
        const typeEl = form.querySelector('[data-role="bank-movement-type"]');
        if (!input || !typeEl) {
            return;
        }
        const type = typeEl.value;
        if (type !== 'WITHDRAWAL' && type !== 'TRANSFER') {
            input.required = false;
            return;
        }
        const source = form.querySelector('[data-role="bank-movement-source"]');
        if (source) {
            // A native <select> carries the mandate on the chosen <option>; the enhanced
            // remote-bank-accounts combobox is a hidden value input (no <option>s), so read the
            // mandate from window.krtBankAccountMeta — populated by the account-search source as the
            // user searches, keyed by the committed account id (REQ-FE-017, ADR-0104).
            if (source.options) {
                const option = source.options[source.selectedIndex];
                input.required =
                    !!option && option.getAttribute('data-requires-justification') === 'true';
            } else {
                const meta = window.krtBankAccountMeta || {};
                input.required = !!source.value && meta[source.value] === true;
            }
        } else {
            input.required = form.getAttribute('data-justification-required') === 'true';
        }
    }

    /**
     * Adapts the unified "Kontobewegung" modal (#997, REQ-BANK-017/-023) to the chosen movement type:
     * routes the form to the type's endpoint + source-account field name (MOVEMENT_ENDPOINTS), shows
     * only that type's [data-movement-types] rows (disabling the rest so they stay out of the body),
     * swaps the submit-button label, and refreshes the live split / fee / justification helpers. A
     * deposit exposes the split + Einzahler rows; a withdrawal the payer holder + Empfänger + fee
     * preview + Begründung; a transfer the destination account/holder pair + fee preview + Begründung.
     *
     * @param {HTMLSelectElement} typeSelect the movement-type select
     */
    function syncMovementRows(typeSelect) {
        const form = typeSelect.closest('form');
        if (!form) {
            return;
        }
        const type = typeSelect.value;
        const spec = MOVEMENT_ENDPOINTS[type];
        if (spec) {
            form.setAttribute('data-endpoint', spec.endpoint);
            form.setAttribute('data-account-id-field', spec.accountField);
        }
        form.querySelectorAll('[data-movement-types]').forEach(function (row) {
            const types = row.getAttribute('data-movement-types').split(/\s+/);
            setMovementRowActive(row, types.indexOf(type) !== -1);
        });
        // The split toggle sits in a DEPOSIT-only block: re-sync its percentage row + preview after
        // the block's active state changed (it is unchecked + disabled when the block is gated off).
        const splitToggle = form.querySelector('[data-role="bank-split-toggle"]');
        if (splitToggle) {
            toggleSplitRow(splitToggle);
        }
        // Restore each ACTIVE counterparty block's registered-vs-external state after the type gating
        // (#994): a gated-off block's toggle is disabled, so skip it — its controls stay disabled.
        form.querySelectorAll('[data-role="bank-cp-external-toggle"]').forEach(function (t) {
            if (!t.disabled) {
                toggleCounterpartyExternal(t);
            }
        });
        // Submit label follows the type (localized keys carried on the button as data-label-*).
        const submit = form.querySelector('button[type="submit"][data-label-deposit]');
        if (submit) {
            const label = submit.getAttribute('data-label-' + type.toLowerCase());
            if (label) {
                submit.textContent = label;
            }
        }
        // Source-account label follows the type on the selectable (requests / dashboard) variant
        // (REQ-BANK-023): a deposit lands ON the account (Zielkonto — a deposit has no source
        // account, that would be a transfer); a withdrawal/transfer debits it (Quellkonto). Only
        // present when accountSelectable; a no-op on the fixed-account detail modal.
        const accountLabel = form.querySelector('[data-role="bank-movement-account-label"]');
        if (accountLabel) {
            const accountLabelText = accountLabel.getAttribute('data-label-' + type.toLowerCase());
            if (accountLabelText) {
                accountLabel.textContent = accountLabelText;
            }
        }
        updateFeePreview(form);
        updateMovementJustification(form);
    }

    document.addEventListener('change', function (event) {
        const select = event.target.closest('select[data-role="bank-movement-type"]');
        if (select) {
            syncMovementRows(select);
        }
    });

    // The source-account picker (requests / dashboard overview) drives the fixed-account id mirrored
    // onto the form, the per-account Begründung mandate and the fee preview: re-sync them when it
    // changes. Match on the attribute, NOT the tag: the picker is a searchable combobox
    // (remote-bank-accounts), so after enhancement [data-role="bank-movement-source"] resolves to
    // the enhancer's hidden VALUE input (an <input>, not a <select>) that re-dispatches change on
    // commit — a `select[...]` selector would miss it.
    document.addEventListener('change', function (event) {
        const source = event.target.closest('[data-role="bank-movement-source"]');
        if (!source) {
            return;
        }
        const form = source.closest('form');
        if (!form) {
            return;
        }
        form.setAttribute('data-account-id', source.value || '');
        updateMovementJustification(form);
        updateFeePreview(form);
    });

    /**
     * Per-row state for the bank confirmation modal (REQ-BANK-040/-041), read from the trigger's
     * data-field-* attributes (which are NOT form controls, so they are not primed onto inputs):
     *  - the over-limit owner-approval block + its mandatory checkbox (pre-ticked when the
     *    responsible holder already granted in-app), gating the confirm button until it is ticked;
     *  - the transfer destination-holder select (enabled only for a TRANSFER) and the adaptive
     *    source-holder label.
     * Re-applied on every open: the modal is reused across rows and {@link submitBankForm} re-enables
     * the submit button on success.
     *
     * @param {HTMLElement} trigger the clicked confirm button carrying the request's data-field-*
     * @param {HTMLElement} modal the confirm modal overlay
     */
    function applyConfirmModalState(trigger, modal) {
        const form = modal.querySelector('form.bank-ajax-form');
        if (!form) {
            return;
        }
        const requiresApproval =
            trigger.getAttribute('data-field-requiresownerapproval') === 'true';
        const alreadyGranted = trigger.getAttribute('data-field-ownerapprovalgranted') === 'true';
        const isTransfer = trigger.getAttribute('data-field-reqtype') === 'TRANSFER';
        const submit = form.querySelector('button[type="submit"]');

        const block = form.querySelector('[data-owner-approval-block]');
        const checkbox = form.querySelector('[data-owner-approval-check]');
        if (block) {
            block.hidden = !requiresApproval;
        }
        if (checkbox) {
            checkbox.disabled = !requiresApproval;
            checkbox.checked = requiresApproval && alreadyGranted;
        }
        if (submit) {
            submit.disabled = requiresApproval && !(checkbox && checkbox.checked);
        }

        // Split deposit notice (REQ-BANK-043): show the percentage that will be distributed across the
        // squadron accounts, so the staffer knows what they are confirming.
        const splitNotice = form.querySelector('[data-split-confirm]');
        if (splitNotice) {
            const splitEnabled = trigger.getAttribute('data-field-splitenabled') === 'true';
            splitNotice.hidden = !splitEnabled;
            if (splitEnabled) {
                const percentEl = splitNotice.querySelector('[data-split-confirm-percent]');
                if (percentEl) {
                    percentEl.textContent = Math.round(
                        Number(trigger.getAttribute('data-field-splitpercent')),
                    ).toLocaleString('de-DE');
                }
            }
        }

        // Requester's Begründung (REQ-BANK-045): show it read-only when the request carries one. The
        // trigger omits data-field-justification entirely when there is none (a deposit, or an
        // optional reason the requester left blank), so an absent attribute hides the block.
        const justificationNotice = form.querySelector('[data-confirm-justification]');
        if (justificationNotice) {
            const justification = trigger.getAttribute('data-field-justification');
            const present = justification != null && justification.trim() !== '';
            const valueEl = justificationNotice.querySelector('[data-bank-label="justification"]');
            if (valueEl) {
                valueEl.textContent = present ? justification : '';
            }
            justificationNotice.hidden = !present;
        }

        const destRow = form.querySelector('[data-confirm-destination-holder]');
        toggleTransferOnlyControl(destRow, isTransfer);
        const holderLabel = form.querySelector('[data-confirm-holder-label]');
        if (holderLabel) {
            const text = isTransfer
                ? holderLabel.getAttribute('data-label-source')
                : holderLabel.getAttribute('data-label-default');
            if (text) {
                holderLabel.textContent = text;
            }
        }
    }

    /* Over-limit confirmation checkbox: re-arms the confirm button as it is ticked/unticked. */
    document.addEventListener('change', function (event) {
        const checkbox = event.target.closest('[data-owner-approval-check]');
        if (!checkbox) {
            return;
        }
        const form = checkbox.closest('form');
        const submit = form ? form.querySelector('button[type="submit"]') : null;
        if (submit) {
            submit.disabled = !checkbox.checked;
        }
    });

    /*
     * The user pickers (data-role="bank-holder-user") and holder pickers are enhanced into
     * searchable comboboxes by the global krt-searchable-select.js auto-initialiser (they carry the
     * data-krt-combobox marker in the templates); no per-page wiring is needed here (REQ-FE-011).
     */

    /**
     * Fetches one bank PDF with CSRF + the browser's IANA zone and saves it as a file.
     * Reports failures through the given callback (toast or inline error).
     *
     * @param {string} url the proxy URL
     * @param {string} filename the download filename
     * @param {function(string):void} onError receives the localized error message
     */
    async function downloadBankPdf(url, filename, onError) {
        const headers = csrfHeaders();
        delete headers['Content-Type'];
        const userTimeZone =
            window.Intl && Intl.DateTimeFormat
                ? Intl.DateTimeFormat().resolvedOptions().timeZone
                : '';
        if (userTimeZone) {
            headers['X-User-Time-Zone'] = userTimeZone;
        }
        let response;
        try {
            response = await fetch(url, { method: 'GET', headers: headers });
        } catch {
            response = null;
        }
        if (!response || !response.ok) {
            onError(genericError());
            return;
        }
        const blob = await response.blob();
        const objectUrl = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = objectUrl;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(objectUrl);
    }

    /* Direct download buttons (dashboard three-month report). */
    document.addEventListener('click', function (event) {
        const button = event.target.closest('.bank-download-btn[data-download-url]');
        if (!button) {
            return;
        }
        button.disabled = true;
        downloadBankPdf(
            button.getAttribute('data-download-url'),
            button.getAttribute('data-filename') || 'report.pdf',
            function (message) {
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(message);
                }
            },
        ).finally(function () {
            button.disabled = false;
        });
    });

    /**
     * Type-to-confirm hurdle (admin wipe-reset): the named submit button stays disabled until
     * the input value exactly matches the token (case-sensitive), guarding the danger action.
     */
    document.addEventListener('input', function (event) {
        const input = event.target.closest('input[data-confirm-token][data-confirm-submit]');
        if (!input) {
            return;
        }
        const submit = document.getElementById(input.getAttribute('data-confirm-submit'));
        if (submit) {
            submit.disabled = input.value !== input.getAttribute('data-confirm-token');
        }
    });

    /**
     * In-place admin wipe-reset (#582): posts via the AJAX twin and reports the outcome as a toast
     * instead of the former PRG reload. The no-reload win is the failure path (inline toast, modal
     * stays open to retry); on success the danger modal closes and the type-to-confirm hurdle
     * resets. Falls back to the native POST when krtFetch is unavailable (no-JS). The success /
     * no-op / error strings come from data-* attributes on the form so bank.js stays i18n-free.
     */
    document.addEventListener('submit', function (event) {
        const form = event.target.closest('form[data-bank-wipe]');
        if (!form) {
            return;
        }
        event.preventDefault();
        if (!window.krtFetch) {
            form.submit();
            return;
        }
        const confirmInput = form.querySelector('input[name="confirm"]');
        const confirmValue = confirmInput ? confirmInput.value : '';
        const submitBtn = form.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        const url = form.getAttribute('action') + '?confirm=' + encodeURIComponent(confirmValue);
        window.krtFetch
            .write({
                method: 'POST',
                url: url,
                toast: false,
                errorMessage: form.getAttribute('data-wipe-error') || genericError(),
                onSuccess: function (body) {
                    const accountsReset =
                        body && body.accountsReset != null ? body.accountsReset : 0;
                    const zeroed =
                        body && body.holderStashesZeroed != null ? body.holderStashesZeroed : 0;
                    if (typeof window.showFrontendSuccessToast === 'function') {
                        const message =
                            accountsReset === 0
                                ? form.getAttribute('data-wipe-noop') || ''
                                : (form.getAttribute('data-wipe-success') || '')
                                      .replace('{0}', accountsReset)
                                      .replace('{1}', zeroed);
                        window.showFrontendSuccessToast(message);
                    }
                    // Close the danger modal and clear the confirm input so a re-open requires
                    // re-typing the WIPE token (the hurdle is re-armed in finally below).
                    const overlay = form.closest('.krt-modal-overlay');
                    const closeBtn = overlay
                        ? overlay.querySelector('[data-trigger="close-modal-display"]')
                        : null;
                    if (closeBtn) {
                        closeBtn.click();
                    }
                    if (confirmInput) {
                        confirmInput.value = '';
                    }
                },
            })
            .finally(function () {
                if (submitBtn && confirmInput) {
                    submitBtn.disabled =
                        confirmInput.value !== confirmInput.getAttribute('data-confirm-token');
                } else if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    });

    /**
     * Statement export form: the datetime-splitter keeps the hidden from/to fields as UTC
     * ISO instants; both are required, then the PDF is fetched as a blob download.
     */
    document.addEventListener('submit', function (event) {
        const form = event.target.closest('form.bank-download-form');
        if (!form) {
            return;
        }
        event.preventDefault();
        clearErrors(form);
        const from = form.querySelector('input[name="from"]');
        const to = form.querySelector('input[name="to"]');
        const required = form.getAttribute('data-period-required') || genericError();
        let valid = true;
        if (!from || !from.value) {
            showError(form, 'from', required);
            valid = false;
        }
        if (!to || !to.value) {
            showError(form, 'to', required);
            valid = false;
        }
        if (!valid) {
            return;
        }
        const url =
            form.getAttribute('data-endpoint') +
            '?from=' +
            encodeURIComponent(from.value) +
            '&to=' +
            encodeURIComponent(to.value);
        downloadBankPdf(
            url,
            form.getAttribute('data-filename') || 'kontoauszug.pdf',
            function (message) {
                showError(form, '_global', message);
            },
        );
    });

    /**
     * Client-side account-name live filter shared by the bank dashboard cards (D1) and the org-unit
     * account list (REQ-BANK-046). Purely visual and read-only: as the user types into a
     * `[data-bank-acc-filter]` search box, every account item inside the container named by the box's
     * `data-filter-scope` selector whose `data-filter-name` does not contain the entered term
     * (case-insensitive substring) is hidden via the `hidden` attribute. This is a pure DOM toggle
     * with no server round-trip — so no krtFetch, no reload and no CSRF — which is why the live-update
     * standard is met by construction (REQ-FE-001). The optional `[data-filter-empty]` note named by
     * the box's `data-filter-empty` selector is revealed only when a non-empty account set is filtered
     * down to nothing (never on a genuinely empty list, which shows its own server-rendered empty
     * state).
     *
     * @param {HTMLInputElement} input the account-filter search box that fired the event
     */
    function applyAccountNameFilter(input) {
        const scope = document.querySelector(input.getAttribute('data-filter-scope'));
        if (!scope) {
            return;
        }
        const term = input.value.trim().toLowerCase();
        const items = scope.querySelectorAll('[data-filter-name]');
        let visible = 0;
        items.forEach(function (item) {
            const name = (item.getAttribute('data-filter-name') || '').toLowerCase();
            const match = term === '' || name.indexOf(term) !== -1;
            item.hidden = !match;
            if (match) {
                visible += 1;
            }
        });
        // By-Bereich view (REQ-BANK-016): hide a group whose accounts are all filtered out so no
        // empty coloured header lingers. A no-op on the flat views (no groups present).
        scope.querySelectorAll('[data-bank-acc-group]').forEach(function (groupEl) {
            const anyVisible = Array.prototype.some.call(
                groupEl.querySelectorAll('[data-filter-name]'),
                function (item) {
                    return !item.hidden;
                },
            );
            groupEl.hidden = !anyVisible;
        });
        const emptySelector = input.getAttribute('data-filter-empty');
        const empty = emptySelector ? document.querySelector(emptySelector) : null;
        if (empty) {
            empty.hidden = items.length === 0 || visible > 0;
        }
    }

    // Delegated so the single handler serves both the dashboard filter and the org-unit filter, and
    // survives the org-unit `orgUnitBank` fragment swap (after a swap the input is re-rendered empty,
    // which naturally resets the filter to "show all").
    document.addEventListener('input', function (event) {
        const input = event.target.closest ? event.target.closest('[data-bank-acc-filter]') : null;
        if (input) {
            applyAccountNameFilter(input);
        }
    });

    /**
     * Toggles a collapsible region driven by an aria-expanded control and an aria-controls target:
     * flips aria-expanded on the control (the CSS rotates the chevron) and shows/hides the target
     * element. Shared by the booking-row detail sub-row (REQ-BANK-045) and the account-detail
     * Konto-Info tile (REQ-BANK-017).
     *
     * @param {HTMLElement} control the button carrying aria-expanded + aria-controls
     */
    function toggleAriaRegion(control) {
        const expanded = control.getAttribute('aria-expanded') === 'true';
        control.setAttribute('aria-expanded', expanded ? 'false' : 'true');
        const id = control.getAttribute('aria-controls');
        const target = id ? document.getElementById(id) : null;
        if (target) {
            target.hidden = expanded;
        }
    }

    // Booking-row expand (REQ-BANK-045): the chevron button toggles the detail sub-row directly; a
    // click anywhere else on an expandable row toggles it too — but not when it lands on another
    // control (the reverse button, a link, an input), so the whole row is the click target the design
    // asks for while the button stays the keyboard control. Document-delegated so it survives the
    // booking-history fragment swap (REQ-FE-002), on both the bank-staff and org-unit detail pages.
    document.addEventListener('click', function (event) {
        const toggle = event.target.closest
            ? event.target.closest('[data-trigger="bank-row-expand"]')
            : null;
        if (toggle) {
            toggleAriaRegion(toggle);
            return;
        }
        if (event.target.closest('a, button, input, select, textarea, label')) {
            return;
        }
        const row = event.target.closest('tr.bank-booking-row.is-expandable');
        if (row) {
            const btn = row.querySelector('[data-trigger="bank-row-expand"]');
            if (btn) {
                toggleAriaRegion(btn);
            }
        }
    });

    // Collapsible Konto-Info tile (REQ-BANK-017): the head button toggles its body. Document-delegated
    // so it survives the accountBody fragment swap; a swap re-renders it collapsed (the default).
    document.addEventListener('click', function (event) {
        const head = event.target.closest
            ? event.target.closest('[data-trigger="bank-info-collapse"]')
            : null;
        if (head) {
            toggleAriaRegion(head);
        }
    });
})();

// ------------------------------------------------------------------------------------------------
// Booking-request queue status filter (REQ-BANK-023): parallel checkboxes whose selection is
// persisted per user in localStorage and replayed through the `status` query parameter. The
// checkboxes live outside the swapped `requestQueue` fragment, so this module owns their checked
// state and re-fetches the table via the shared krtFetch.swap on every change. Default (no saved
// preference) is Ausstehend only, rendered server-side; deselecting all shows the "no filter" hint.
// ------------------------------------------------------------------------------------------------
(function () {
    const STATUSES = ['PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED'];

    function filterBar() {
        return document.querySelector('[data-bank-status-filter-bar]');
    }

    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return 'bank_request_status_filter_' + uid;
    }

    function checkboxFor(status) {
        return document.querySelector('input[data-bank-status-filter="' + status + '"]');
    }

    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            if (raw === null) {
                return null;
            }
            const arr = JSON.parse(raw);
            return Array.isArray(arr)
                ? STATUSES.filter(function (s) {
                      return arr.indexOf(s) !== -1;
                  })
                : null;
        } catch {
            return null;
        }
    }

    function writeSaved(list) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify(list));
        } catch {
            /* localStorage unavailable (private mode): the filter simply will not persist. */
        }
    }

    function renderedSelection() {
        return STATUSES.filter(function (s) {
            const c = checkboxFor(s);
            return c && c.checked;
        });
    }

    function applyCheckStates(list) {
        STATUSES.forEach(function (s) {
            const c = checkboxFor(s);
            if (c) {
                c.checked = list.indexOf(s) !== -1;
            }
        });
    }

    // A blank query value collapses to null via the frontend's global emptyAsNull string binder,
    // which the controller reads as "no selection" and defaults to PENDING. So an empty selection is
    // sent as the non-empty `NONE` sentinel (an unknown status the controller filters out to an empty
    // set), keeping deselect-all distinct from a first, unfiltered load.
    function refetch(list) {
        if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
            window.location.reload();
            return;
        }
        window.krtFetch.swap({
            url: '/bank/requests?status=' + (list.length ? list.join(',') : 'NONE'),
            container: '#bank-request-queue-results',
            fragmentValue: 'requestQueue',
            history: true,
        });
    }

    // On load, reconcile the saved per-user selection with the server-rendered checkboxes. An
    // explicit `status` in the address bar (deep link / back-forward) is authoritative and is
    // persisted; otherwise a saved selection that differs from the server default (PENDING) is
    // applied and the queue re-fetched to match.
    document.addEventListener('DOMContentLoaded', function () {
        if (!filterBar()) {
            return;
        }
        if (/[?&]status=/.test(window.location.search)) {
            writeSaved(renderedSelection());
            return;
        }
        const saved = readSaved();
        if (saved === null) {
            writeSaved(renderedSelection());
            return;
        }
        if (saved.join(',') !== renderedSelection().join(',')) {
            applyCheckStates(saved);
            refetch(saved);
        }
    });

    // A checkbox toggle fires `change`; its own `checked` state is already the new one, so the
    // current selection is read straight off the DOM and persisted + replayed.
    document.addEventListener('change', function (event) {
        const checkbox = event.target.closest
            ? event.target.closest('input[data-bank-status-filter]')
            : null;
        if (!checkbox) {
            return;
        }
        const ordered = renderedSelection();
        writeSaved(ordered);
        refetch(ordered);
    });
})();

// ------------------------------------------------------------------------------------------------
// Bank dashboard view options (REQ-BANK-016): two independent checkboxes — a table-view toggle and
// a by-Bereich grouping toggle — each persisted per user in localStorage and replayed through the
// `layout` / `group` query parameters. The checkboxes live outside the swapped `bankGrid` fragment,
// so this module owns their checked state and re-fetches just the grid via krtFetch.swap on every
// change, re-applying the account-name filter afterwards. Default (both unchecked, no saved
// preference) is the card grid ordered A→Z, rendered server-side.
// ------------------------------------------------------------------------------------------------
(function () {
    function viewToggles() {
        return document.querySelector('[data-bank-view-toggles]');
    }

    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return 'bank_dashboard_view_' + uid;
    }

    function layoutCheckbox() {
        return document.querySelector('input[data-bank-view-layout]');
    }

    function groupCheckbox() {
        return document.querySelector('input[data-bank-view-group]');
    }

    // The checked checkbox maps to the non-default value (table / bereich); unchecked = the default
    // (card / alpha), so the pair encodes the same {layout, group} state the query params carry.
    function renderedState() {
        const layoutEl = layoutCheckbox();
        const groupEl = groupCheckbox();
        return {
            layout: layoutEl && layoutEl.checked ? 'table' : 'card',
            group: groupEl && groupEl.checked ? 'bereich' : 'alpha',
        };
    }

    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            if (raw === null) {
                return null;
            }
            const parsed = JSON.parse(raw) || {};
            return {
                layout: parsed.layout === 'table' ? 'table' : 'card',
                group: parsed.group === 'bereich' ? 'bereich' : 'alpha',
            };
        } catch {
            return null;
        }
    }

    function writeSaved(state) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify(state));
        } catch {
            /* localStorage unavailable (private mode): the choice simply will not persist. */
        }
    }

    function applyCheckboxStates(state) {
        const layoutEl = layoutCheckbox();
        const groupEl = groupCheckbox();
        if (layoutEl) {
            layoutEl.checked = state.layout === 'table';
        }
        if (groupEl) {
            groupEl.checked = state.group === 'bereich';
        }
    }

    function reapplyNameFilter() {
        const input = document.querySelector('[data-bank-acc-filter]');
        if (input && input.value) {
            input.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    function fetchView(state) {
        if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
            window.location.reload();
            return;
        }
        window.krtFetch
            .swap({
                url: '/bank?layout=' + state.layout + '&group=' + state.group,
                container: '#bank-grid-results',
                fragmentValue: 'bankGrid',
                history: true,
            })
            .then(function () {
                reapplyNameFilter();
            });
    }

    // On load, reconcile the saved per-user view with the server-rendered checkboxes. An explicit
    // `layout`/`group` in the address bar is authoritative and is persisted; otherwise a saved view
    // that differs from the server default (card + A→Z) is applied and the grid re-fetched.
    document.addEventListener('DOMContentLoaded', function () {
        if (!viewToggles()) {
            return;
        }
        if (/[?&](layout|group)=/.test(window.location.search)) {
            writeSaved(renderedState());
            return;
        }
        const saved = readSaved();
        if (saved === null) {
            writeSaved(renderedState());
            return;
        }
        const current = renderedState();
        if (saved.layout !== current.layout || saved.group !== current.group) {
            applyCheckboxStates(saved);
            fetchView(saved);
        }
    });

    // A checkbox toggle fires `change`; its own `checked` state is already the new one, so the
    // current {layout, group} is read straight off the DOM, persisted and replayed onto the grid.
    document.addEventListener('change', function (event) {
        const checkbox = event.target.closest
            ? event.target.closest('input[data-bank-view-layout],input[data-bank-view-group]')
            : null;
        if (!checkbox) {
            return;
        }
        const state = renderedState();
        writeSaved(state);
        fetchView(state);
    });
})();

// ------------------------------------------------------------------------------------------------
// Org-unit bank account-list view toggle (REQ-BANK-021/-046): a SINGLE per-user "Tabellenansicht"
// checkbox that switches the "Konten" list between the card grid (default) and the dense table —
// there is NO by-Bereich grouping here (unlike the dashboard, REQ-BANK-016). Mirrors the dashboard
// view-options module above: the choice is persisted per user in localStorage and replayed through
// the `layout` query parameter on an `orgUnitBankAccounts` fragment swap (`#ou-acc-results`, no
// reload, REQ-FE-005), and the layout rides in the address bar (history:true) so it survives the
// `orgUnitBank` fragment swap after a request create/cancel (that refresh preserves the query, so
// the server re-renders the chosen layout). The checkbox lives OUTSIDE the swapped `#ou-acc-results`
// container, so it keeps its state across a layout swap. Distinct `data-ou-view-*` attributes keep
// it clear of the dashboard's `data-bank-view-*` module.
// ------------------------------------------------------------------------------------------------
(function () {
    function toggleGroup() {
        return document.querySelector('[data-ou-view-toggles]');
    }

    function layoutCheckbox() {
        return document.querySelector('input[data-ou-view-layout]');
    }

    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return 'org_unit_bank_view_' + uid;
    }

    // The checkbox maps to the non-default layout (table); unchecked = the default card grid, so the
    // checkbox encodes the same layout the server rendered / the query param carries.
    function renderedLayout() {
        const checkbox = layoutCheckbox();
        return checkbox && checkbox.checked ? 'table' : 'card';
    }

    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            if (raw === null) {
                return null;
            }
            const parsed = JSON.parse(raw) || {};
            return parsed.layout === 'table' ? 'table' : 'card';
        } catch {
            return null;
        }
    }

    function writeSaved(layout) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify({ layout: layout }));
        } catch {
            /* localStorage unavailable (private mode): the choice simply will not persist. */
        }
    }

    // The name filter box lives outside the swapped #ou-acc-results, so a layout swap leaves its term
    // applied only to the OLD items; re-dispatch its input event to re-filter the freshly swapped set.
    function reapplyNameFilter() {
        const input = document.querySelector('#ou-panel-konten [data-bank-acc-filter]');
        if (input && input.value) {
            input.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    function fetchView(layout) {
        if (!window.krtFetch || typeof window.krtFetch.swap !== 'function') {
            window.location.reload();
            return;
        }
        window.krtFetch
            .swap({
                url: '/org-unit-bank?layout=' + layout,
                container: '#ou-acc-results',
                fragmentValue: 'orgUnitBankAccounts',
                history: true,
            })
            .then(function () {
                reapplyNameFilter();
            });
    }

    // On load, reconcile the saved per-user layout with the server-rendered checkbox. An explicit
    // `layout=` in the address bar is authoritative and is persisted; otherwise a saved layout that
    // differs from the server default (card) is applied and the list re-fetched. No krt:swapped
    // listener is needed: the layout rides in the query, so every swap re-renders it server-side.
    document.addEventListener('DOMContentLoaded', function () {
        if (!toggleGroup()) {
            return;
        }
        if (/[?&]layout=/.test(window.location.search)) {
            writeSaved(renderedLayout());
            return;
        }
        const saved = readSaved();
        if (saved === null) {
            writeSaved(renderedLayout());
            return;
        }
        if (saved !== renderedLayout()) {
            const checkbox = layoutCheckbox();
            if (checkbox) {
                checkbox.checked = saved === 'table';
            }
            fetchView(saved);
        }
    });

    // A checkbox toggle fires `change`; its own `checked` state is already the new one, so read the
    // layout straight off the DOM, persist it and replay it onto the account list.
    document.addEventListener('change', function (event) {
        const checkbox = event.target.closest
            ? event.target.closest('input[data-ou-view-layout]')
            : null;
        if (!checkbox) {
            return;
        }
        const layout = checkbox.checked ? 'table' : 'card';
        writeSaved(layout);
        fetchView(layout);
    });
})();

// ------------------------------------------------------------------------------------------------
// Account-detail collapsible panels (REQ-BANK-050): the balance chart and the booking history are
// each collapsible, DEFAULT EXPANDED (the server renders aria-expanded="true"), with the
// collapsed/expanded state persisted per user in localStorage and replayed on load AND on every
// krt:swapped — an accountBody / settings re-render restores the server default (expanded), so a
// user who collapsed a panel keeps it collapsed after a money/settings write. The toggle button
// carries data-collapse-key ("chart" / "history"); the store is a { key: collapsed } object under
// bank_panel_collapse_<uid>. Distinct from the always-default-collapsed, unpersisted Konto-Info tile
// (data-trigger="bank-info-collapse"). Document-delegated so it survives every fragment swap.
// ------------------------------------------------------------------------------------------------
(function () {
    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return 'bank_panel_collapse_' + uid;
    }

    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            return raw === null ? {} : JSON.parse(raw) || {};
        } catch {
            return {};
        }
    }

    function writeSaved(state) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify(state));
        } catch {
            /* localStorage unavailable (private mode): the choice simply will not persist. */
        }
    }

    // Reflects the collapsed flag onto a toggle head + its aria-controls body (the CSS rotates the
    // chevron off aria-expanded).
    function setCollapsed(head, collapsed) {
        head.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        const id = head.getAttribute('aria-controls');
        const target = id ? document.getElementById(id) : null;
        if (target) {
            target.hidden = collapsed;
        }
    }

    // Replays the saved collapsed state onto every panel that has one; panels the user never toggled
    // keep the server default (expanded).
    function apply() {
        const saved = readSaved();
        document
            .querySelectorAll('[data-trigger="bank-panel-collapse"][data-collapse-key]')
            .forEach(function (head) {
                const key = head.getAttribute('data-collapse-key');
                if (Object.prototype.hasOwnProperty.call(saved, key)) {
                    setCollapsed(head, saved[key] === true);
                }
            });
    }

    document.addEventListener('click', function (event) {
        const head = event.target.closest
            ? event.target.closest('[data-trigger="bank-panel-collapse"][data-collapse-key]')
            : null;
        if (!head) {
            return;
        }
        // Currently expanded => this click collapses it (and vice versa).
        const collapsed = head.getAttribute('aria-expanded') === 'true';
        setCollapsed(head, collapsed);
        const saved = readSaved();
        saved[head.getAttribute('data-collapse-key')] = collapsed;
        writeSaved(saved);
    });

    document.addEventListener('DOMContentLoaded', apply);
    document.addEventListener('krt:swapped', apply);
    apply();
})();
