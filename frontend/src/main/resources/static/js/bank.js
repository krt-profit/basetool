/*
 * Bank area client behaviors (epic #556).
 *
 * Covers every interactive surface of the bank pages:
 *  - modal field priming: `data-field-*` attributes on an open-modal trigger are
 *    copied into the modal form before it opens (row-scoped id/version/name),
 *  - AJAX forms (`form.bank-ajax-form`): JSON POST/PATCH/DELETE against
 *    /api/proxy/bank/**, CSRF via the shared window.krtCsrf with retry-once-on-403,
 *    success = in-place server-fragment swap of the region named by the form's
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
        // create/cancel; query dropped (the page carries no list filter).
        orgUnitBank: { container: '#org-unit-bank-results', preserveQuery: false },
        // Org-unit account detail (REQ-BANK-035/-036): the facts + responsibility settings region
        // re-renders in place after a target/visibility write; query dropped.
        orgUnitBankSettings: { container: '#org-unit-bank-settings-results', preserveQuery: false },
        // Bank-staff confirmation queue (#666 F2): the request table re-renders after a
        // confirm/reject; `?status=` preserved so the staffer stays on the filtered view.
        requestQueue: { container: '#bank-request-queue-results', preserveQuery: true },
    };

    /** Maps a grant flag name onto the row attribute the next toggle click reads. */
    const FLAG_ATTR = {
        canDeposit: 'data-can-deposit',
        canWithdraw: 'data-can-withdraw',
        canTransfer: 'data-can-transfer',
    };

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
     * Sends a bank write with the shared CSRF headers and the epic's retry-once-on-403 semantics: a
     * bare 403 means the CSRF token went stale (post-re-login session rotation, maximumSessions
     * eviction), so the token is refreshed from GET /csrf via window.krtCsrf.refresh() and the write
     * retried exactly once. Headers are rebuilt each attempt so the retry picks up the fresh token.
     * Returns the final Response, or null on a network error — matching the legacy behavior so the
     * caller's generic-error branch still fires.
     *
     * @param {string} url the request URL
     * @param {RequestInit} baseInit method + optional body (headers are built here, not by the caller)
     * @returns {Promise<Response|null>} the response, or null when the network call threw
     */
    async function bankWrite(url, baseInit) {
        function withHeaders() {
            return Object.assign({}, baseInit, { headers: csrfHeaders() });
        }
        let response;
        try {
            response = await fetch(url, withHeaders());
            if (
                response.status === 403 &&
                window.krtCsrf &&
                typeof window.krtCsrf.refresh === 'function'
            ) {
                const refreshed = await window.krtCsrf.refresh();
                if (refreshed) {
                    response = await fetch(url, withHeaders());
                }
            }
        } catch {
            response = null;
        }
        return response;
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
     * @param {HTMLSelectElement} userSelect the counterparty user picker
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
    // counterparty user picker (re)loads its paired org-unit select.
    document.addEventListener('change', function (event) {
        const userSelect = event.target.closest('select[data-counterparty-user]');
        if (!userSelect) {
            return;
        }
        const orgSelect = document.getElementById(userSelect.getAttribute('data-orgunit-target'));
        if (orgSelect) {
            fillCounterpartyOrgUnits(userSelect, orgSelect);
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

        const init = { method: method };
        if (method !== 'GET' && method !== 'DELETE') {
            init.body = JSON.stringify(body);
        }
        const submitButton = form.querySelector('button[type="submit"]');
        if (submitButton) {
            submitButton.disabled = true;
        }
        const response = await bankWrite(endpoint, init);
        if (response && response.ok) {
            // Re-enable before the swap: the manage/grants modals live OUTSIDE the swapped region and
            // are reused per row, so a left-disabled submit button would break the next open. On the
            // detail page the button sits inside the swapped accountBody and is replaced anyway.
            if (submitButton) {
                submitButton.disabled = false;
            }
            handleBankSuccess(form);
            return;
        }
        if (submitButton) {
            submitButton.disabled = false;
        }
        if (!response) {
            showError(form, '_global', genericError());
            return;
        }
        let payload;
        try {
            payload = await response.json();
        } catch {
            payload = null;
        }
        if (payload && payload.unauthenticated) {
            window.location.reload();
            return;
        }
        if (payload && Array.isArray(payload.fieldErrors) && payload.fieldErrors.length > 0) {
            payload.fieldErrors.forEach(function (fe) {
                showError(form, fe.field, fe.message);
            });
            return;
        }
        const message = payload && payload.message ? payload.message : genericError();
        const field =
            payload && payload.code && CODE_FIELD[payload.code]
                ? CODE_FIELD[payload.code]
                : '_global';
        showError(form, field, message);
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
            modal.style.display = 'none';
        }
        const main = document.querySelector('main[data-bank-saved]');
        const savedMessage = main ? main.getAttribute('data-bank-saved') : null;
        if (savedMessage && typeof window.showFrontendSuccessToast === 'function') {
            window.showFrontendSuccessToast(savedMessage);
        }
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
     * Live transfer-fee preview (REQ-BANK-033, ADR-0052): the entered amount is what must ARRIVE at
     * the destination, so this fills the on-top fee (`round(amount * rate)`) and the gross actually
     * debited from the source (`amount + fee`). Hidden when the amount is non-positive, the rate is
     * zero, or — for an account transfer — the source and destination holder match (a same-holder
     * transfer is fee-free). Guidance only; the authoritative fee is computed server-side at booking
     * time.
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
        const amount = Number(amountEl.value);
        const src = form.querySelector('[name="sourceHolderId"]');
        const dst = form.querySelector('[name="destinationHolderId"]');
        const sameHolder = !!(src && dst && src.value && src.value === dst.value);
        if (!Number.isFinite(amount) || amount <= 0 || rate <= 0 || sameHolder) {
            preview.hidden = true;
            return;
        }
        const fee = Math.round(amount * rate);
        const valueEl = preview.querySelector('[data-fee-value]');
        const debitEl = preview.querySelector('[data-fee-debit]');
        if (valueEl) {
            valueEl.textContent = fee.toLocaleString('de-DE');
        }
        if (debitEl) {
            debitEl.textContent = (amount + fee).toLocaleString('de-DE');
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
        const flags = {
            canDeposit: row.getAttribute('data-can-deposit') === 'true',
            canWithdraw: row.getAttribute('data-can-withdraw') === 'true',
            canTransfer: row.getAttribute('data-can-transfer') === 'true',
        };
        const flag = flagButton.getAttribute('data-flag');
        flags[flag] = !flags[flag];
        flags.version = Number(row.getAttribute('data-version'));
        flagButton.disabled = true;
        const endpoint =
            '/api/proxy/bank/grants/' +
            encodeURIComponent(row.getAttribute('data-user-id')) +
            '/' +
            encodeURIComponent(row.getAttribute('data-account-id'));
        const response = await bankWrite(endpoint, {
            method: 'PATCH',
            body: JSON.stringify(flags),
        });
        flagButton.disabled = false;
        if (response && response.ok) {
            let payload = null;
            try {
                payload = await response.json();
            } catch {
                // a malformed/empty body leaves payload null; applyGrantFlagResult tolerates it
            }
            applyGrantFlagResult(flagButton, row, flag, flags[flag], payload);
            return;
        }
        let message = genericError();
        if (response) {
            try {
                const payload = await response.json();
                if (payload && payload.message) {
                    message = payload.message;
                }
            } catch {
                // keep the generic message
            }
        }
        if (typeof window.showFrontendErrorToast === 'function') {
            window.showFrontendErrorToast(message);
        }
    });

    /** Grants filter selects: navigate to the chosen grouping/entity on change. */
    document.addEventListener('change', function (event) {
        const select = event.target.closest('select[data-role="bank-grants-filter"]');
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
})();
