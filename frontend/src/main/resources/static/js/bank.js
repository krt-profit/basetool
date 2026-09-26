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
        BANK_SPLIT_NO_TARGETS: 'splitPercent',
        BANK_SPLIT_TOO_SMALL: 'splitPercent',
        BANK_JUSTIFICATION_REQUIRED: 'justification',
        BANK_FEE_EXCEEDS_AMOUNT: 'amount',
    };

    /**
     * Maps a form's `data-refresh` token to its swap container and whether the current query string
     * is preserved on the in-place re-render (REQ-FE-005). `accountBody` drops the query so the
     * booking history resets to page 0.
     */
    const REFRESH_TARGETS = {
        accountBody: { container: '#bank-account-results', preserveQuery: false },
        manageBody: { container: '#bank-manage-results', preserveQuery: true },
        grantsMatrix: { container: '#bank-grants-results', preserveQuery: true },
        orgUnitBank: { container: '#org-unit-bank-results', preserveQuery: true },
        orgUnitBankSettings: { container: '#org-unit-bank-settings-results', preserveQuery: false },
        requestQueue: { container: '#bank-request-queue-results', preserveQuery: true },
        bankGrid: { container: '#bank-grid-results', preserveQuery: true },
    };

    /**
     * Maps a movement type on the unified "Kontobewegung" modal onto its backend endpoint and the
     * JSON field the source-account id is submitted under.
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

    const BANK_ACCOUNT_SECTIONS = {
        account: { container: '#bank-account-results', fragmentValue: 'accountBody' },
        bookings: { container: '#bank-account-results', fragmentValue: 'accountBody' },
        chart: { container: '#bank-account-results', fragmentValue: 'accountBody' },
    };

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

    const BANK_STAFF_SECTIONS = {
        grid: { container: '#bank-grid-results', fragmentValue: 'bankGrid' },
        requestQueue: { container: '#bank-request-queue-results', fragmentValue: 'requestQueue' },
        manage: { container: '#bank-manage-results', fragmentValue: 'manageBody' },
        grants: { container: '#bank-grants-results', fragmentValue: 'grantsMatrix' },
    };

    const ORGUNIT_BANK_SECTIONS = {
        orgUnitBank: { container: '#org-unit-bank-results', fragmentValue: 'orgUnitBank' },
        orgUnitBankSettings: {
            container: '#org-unit-bank-settings-results',
            fragmentValue: 'orgUnitBankSettings',
        },
    };

    /**
     * Reads the localized "updates available" pill label from `<main data-bank-livesync-updates>`.
     *
     * @returns {string|undefined} the pill label, or undefined for the receiver default
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
     * Reads the localized "saved, but reload" refresh-error message from `<main>`, falling back to
     * the generic error text.
     *
     * @returns {string} the refresh-error message
     */
    function bankRefreshError() {
        const main = document.querySelector('main[data-bank-refresh-error]');
        return main ? main.getAttribute('data-bank-refresh-error') : genericError();
    }

    /**
     * Builds a live-sync receiver refresh closure for one section map. It re-renders each present
     * container once (deduplicated by container) from the viewer's own pathname and query.
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
                    url,
                    container: cfg.container,
                    fragmentValue: cfg.fragmentValue,
                    errorMessage,
                });
            });
        };
    }

    (function () {
        if (
            !window.krtLiveSync ||
            typeof window.krtLiveSync.createReceiver !== 'function' ||
            !window.krtFetch ||
            typeof window.krtFetch.swap !== 'function'
        ) {
            return;
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
     * Builds the JSON + CSRF request headers via the shared window.krtCsrf, falling back to reading
     * the CSRF meta tags when krt-fetch.js is absent.
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
     * Reads the localized fallback error text from `<main data-bank-generic-error>`.
     *
     * @returns {string} the generic error message
     */
    function genericError() {
        const main = document.querySelector('main[data-bank-generic-error]');
        return main ? main.getAttribute('data-bank-generic-error') : 'Error';
    }

    /**
     * Reads the localized "booking was filed as an approval request" notice from `<main>`, falling
     * back to the generic error text (REQ-BANK-047).
     *
     * @returns {string} the approval-request-filed notice
     */
    function approvalRequestFiledMessage() {
        const main = document.querySelector('main[data-bank-approval-request-filed]');
        return main ? main.getAttribute('data-bank-approval-request-filed') : genericError();
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
     * Copies the trigger's `data-field-*` attributes into the modal's matching form controls
     * (case-insensitive) and `[data-bank-label]` elements, then resets errors and re-syncs the
     * modal's dependent rows and previews.
     *
     * @param {HTMLElement} trigger the clicked open-modal button
     * @param {HTMLElement} modal the overlay element being opened
     */
    function primeModal(trigger, modal) {
        const form = modal.querySelector('form.bank-ajax-form');
        if (form) {
            clearErrors(form);
            const staffNote = form.querySelector('[name="staffNote"]');
            if (staffNote) {
                staffNote.value = '';
            }
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
        if (form && form.querySelector('[data-fee-preview]')) {
            updateFeePreview(form);
        }
        if (form && form.querySelector('[data-limit-warning]')) {
            const typeSelect = form.querySelector('select[data-role="org-unit-request-type"]');
            if (typeSelect) {
                syncRequestTypeRows(typeSelect);
            }
            updateLimitWarning(form);
        }
        if (form && form.querySelector('[data-split-row]')) {
            const splitToggle = form.querySelector('[data-role="bank-split-toggle"]');
            if (splitToggle) {
                toggleSplitRow(splitToggle);
            }
        }
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
     * Fills a counterparty org-unit <select> from the chosen user's memberships (REQ-BANK-044). It
     * stays disabled while loading or without a user, and auto-selects a single membership.
     *
     * @param {HTMLElement} userSelect the counterparty user picker (native select or combobox value
     *     input)
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
     * Resets a counterparty org-unit select to just its placeholder option and clears the selection.
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
     * Switches a counterparty block between a registered tool user and an external free-text name
     * (REQ-BANK-044). External enables the name input and offers all active org units from
     * `[data-bank-all-orgunits]`; registered enables the user lookup and disables the unit select
     * until a user is chosen.
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
     * Serializes and sends one bank AJAX form. `_`-prefixed fields fill the endpoint's
     * `{placeholder}` slots instead of the body, `data-account-id` is sent under
     * `data-account-id-field` (default `accountId`); success refreshes in place, errors render inline.
     *
     * @param {HTMLFormElement} form the submitted form
     */
    async function submitBankForm(form) {
        clearErrors(form);
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

        const submitButton = form.querySelector('button[type="submit"]');
        await window.krtFetch.write({
            method,
            url: endpoint,
            payload: method !== 'GET' && method !== 'DELETE' ? body : undefined,
            submitter: submitButton,
            toast: false,
            errorMessage: genericError(),
            onSuccess(payload) {
                handleBankSuccess(form);
                if (payload && payload.pendingRequest) {
                    if (typeof window.showFrontendSuccessToast === 'function') {
                        window.showFrontendSuccessToast(approvalRequestFiledMessage());
                    }
                }
            },
            onError(status, payload) {
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
            onNetworkError() {
                showError(form, '_global', genericError());
                return true;
            },
        });
    }

    /**
     * Resolves a bank live-sync account placeholder to a concrete account id. `account` reads
     * `data-livesync-account`, `data-account-id` or a primed `_livesyncAccount` field; `destination`
     * reads an enabled `destinationAccountId` or a primed `_livesyncDestination` field.
     *
     * @param {HTMLFormElement} form the form whose write just succeeded
     * @param {string} ref the placeholder name (`account` or `destination`)
     * @returns {string|null} the resolved account id, or null when the form has none
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
     * Broadcasts a bank form's live-sync publish matrix after a successful write (REQ-FE-015). The
     * `data-livesync` attribute lists space-separated `topic/section,section` entries; a
     * `bank:@account` / `bank:@destination` topic resolves to the form's account, and entries that
     * are duplicate or unresolvable are skipped.
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
                        return;
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
     * Handles a successful bank AJAX form write in place (REQ-FE-005): closes the modal, shows the
     * success toast, publishes live sync and re-renders the fragment named by `data-refresh`. A failed
     * refresh shows the "saved, but reload" message; without a swap target the page reloads.
     *
     * @param {HTMLFormElement} form the form whose write just succeeded
     */
    function handleBankSuccess(form) {
        const modal = form.closest('.krt-modal-overlay');
        if (modal) {
            window.krtModal.close(modal);
        }
        const main = document.querySelector('main[data-bank-saved]');
        const savedMessage = main ? main.getAttribute('data-bank-saved') : null;
        if (savedMessage && typeof window.showFrontendSuccessToast === 'function') {
            window.showFrontendSuccessToast(savedMessage);
        }
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
                url,
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
     * (REQ-BANK-033).
     *
     * @returns {number} the fee rate as a fraction, or 0 when absent or outside [0, 1)
     */
    function transferFeeRate() {
        const main = document.querySelector('main[data-transfer-fee-rate]');
        const raw = main ? Number(main.getAttribute('data-transfer-fee-rate')) : 0;
        return Number.isFinite(raw) && raw > 0 && raw < 1 ? raw : 0;
    }

    /**
     * Updates the live transfer-fee preview and the fee-inclusive toggle (REQ-BANK-033). The fee is
     * `round(amount * rate)`, added on top by default or deducted from the amount when `feeInclusive`
     * is checked. Deposits and same-holder transfers are fee-free and hide the toggle and preview.
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
        const feeApplies = rate > 0 && type !== 'DEPOSIT' && !(type === 'TRANSFER' && sameHolder);
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
     * Shows the advisory owner-approval warning on the org-unit request modal when a withdrawal or
     * transfer exceeds the source option's `data-limit` (REQ-BANK-041). A missing limit always warns;
     * a `data-exempt="true"` account never does.
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
        if (option && option.getAttribute('data-exempt') === 'true') {
            warning.hidden = true;
            return;
        }
        const rawLimit = option ? option.getAttribute('data-limit') : null;
        const limit = rawLimit === null || rawLimit === '' ? null : Number(rawLimit);
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
     * Recomputes the Begründung field's `required` flag on the org-unit request modal (REQ-BANK-045):
     * required only for a withdrawal or transfer whose source option carries
     * `data-requires-justification="true"`.
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
     * Recomputes the justification-required flag for the edited field's form, only when it has a
     * `[data-request-justification-only]` wrapper.
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
     * Shows and enables a deposit form's split-percentage row to match its toggle (REQ-BANK-043).
     * When off, the input is disabled and cleared so it is not submitted; the preview is refreshed.
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
     * Updates the live split-deposit preview (REQ-BANK-043): the slice distributed to the squadron
     * accounts (`round(gross * percent / 100)`) and the remainder kept on the named account. Hidden
     * when the split is off or the inputs are out of range.
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

    document.addEventListener('change', function (event) {
        const toggle = event.target.closest('[data-role="bank-split-toggle"]');
        if (toggle) {
            toggleSplitRow(toggle);
        }
    });

    /**
     * Updates the client-side balance-split calculator on the holder detail page (REQ-BANK-032): the
     * holder's own money is the entered in-game balance minus the panel's `data-reserved` total.
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
     * Dispatches one field edit to the fee preview, limit warning, justification flag, split preview
     * and balance calculator.
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
     * Applies a successful grant flag PATCH in place: sets the button's pressed state, the row's
     * `data-can-<flag>` attribute and the row's `data-version` from the response.
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
     * Grants matrix: clicking a flag cell PATCHes the grant with all three flags and the row's
     * version, then patches the row in place.
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
        const newValue = row.getAttribute(FLAG_ATTR[flag]) !== 'true';
        const endpoint =
            '/api/proxy/bank/grants/' +
            encodeURIComponent(row.getAttribute('data-user-id')) +
            '/' +
            encodeURIComponent(row.getAttribute('data-account-id'));
        await window.krtFetch.write({
            method: 'PATCH',
            url: endpoint,
            serialize:
                'bank-grant:' +
                row.getAttribute('data-user-id') +
                ':' +
                row.getAttribute('data-account-id'),
            payload() {
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
            onSuccess(payload) {
                applyGrantFlagResult(flagButton, row, flag, newValue, payload);
                if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
                    window.krtLiveSync.sendChanged('bank', ['grants']);
                }
            },
            onError(status, payload) {
                const message = payload && payload.message ? payload.message : genericError();
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(message);
                }
                return true;
            },
            onNetworkError() {
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(genericError());
                }
                return true;
            },
        });
    });

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
     * The org-unit kinds each account type may be owned by (REQ-ORG-019); types absent here own no
     * org unit.
     */
    const ACCOUNT_TYPE_OWNER_KINDS = {
        ORG_UNIT: ['SQUADRON', 'SPECIAL_COMMAND'],
        AREA: ['BEREICH'],
        CARTEL: ['ORGANISATIONSLEITUNG'],
    };

    /**
     * Shows the account-create modal's org-unit picker for owner-carrying account types and filters
     * its options to the allowed kinds. It is required for ORG_UNIT and AREA; a hidden or filtered-out
     * selection is cleared.
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
        control.required = type === 'ORG_UNIT' || type === 'AREA';
        let currentStillVisible = false;
        Array.prototype.forEach.call(control.options, function (option) {
            if (!option.value) {
                return;
            }
            const kind = option.getAttribute('data-kind');
            const visible = !!allowedKinds && allowedKinds.indexOf(kind) !== -1;
            option.hidden = !visible;
            option.disabled = !visible;
            if (visible && option.value === control.value) {
                currentStillVisible = true;
            }
        });
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
     * Toggles a transfer-only row and its select: shown, enabled and required only for a transfer,
     * otherwise hidden, disabled and cleared.
     *
     * @param {HTMLElement|null} row the transfer-only row wrapper
     * @param {boolean} isTransfer whether the current request type is TRANSFER
     */
    function toggleTransferOnlyControl(row, isTransfer) {
        if (!row) {
            return;
        }
        row.hidden = !isTransfer;
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
     * Adapts the org-unit request modal to the chosen request type (REQ-BANK-039, REQ-BANK-042):
     * filters the source accounts (any for a deposit, `data-can-debit` otherwise), and toggles the
     * hints, transfer, split, justification and counterparty rows before recomputing the warnings.
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
        syncRequestCounterparty(form, type === 'WITHDRAWAL');
        updateJustificationRequired(form);
        updateLimitWarning(form);
    }

    /**
     * Enables the request's Empfänger block only for a withdrawal (REQ-BANK-055); otherwise its
     * controls are disabled but keep their values. On first activation it dispatches one `change` on
     * a pre-filled user picker so its org units load.
     *
     * @param {HTMLFormElement} form the request form
     * @param {boolean} on whether the block is active (the request is a withdrawal)
     */
    function syncRequestCounterparty(form, on) {
        const block = form.querySelector('[data-request-withdrawal-only]');
        if (!block) {
            return;
        }
        block.hidden = !on;
        block
            .querySelectorAll(
                'select, .krt-combobox input[type="hidden"], input:not(.krt-combobox__input)',
            )
            .forEach(function (control) {
                control.disabled = !on;
                const box = control.closest ? control.closest('.krt-combobox') : null;
                const textbox = box ? box.querySelector('.krt-combobox__input') : null;
                if (textbox) {
                    textbox.disabled = !on;
                }
            });
        if (!on) {
            return;
        }
        const picker = block.querySelector('[data-counterparty-user]');
        if (picker && picker.value && !picker.dataset.cpUnitsPrimed) {
            picker.dataset.cpUnitsPrimed = 'true';
            picker.dispatchEvent(new Event('change', { bubbles: true }));
        }
    }

    document.addEventListener('change', function (event) {
        const select = event.target.closest('select[data-role="org-unit-request-type"]');
        if (select) {
            syncRequestTypeRows(select);
        }
    });

    /**
     * Enables or disables one movement-modal control, clearing its value (or combobox selection) when
     * disabled; a combobox's visible textbox mirrors the disabled state.
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
     * Shows or hides a movement-modal row and enables or disables every submittable control in it.
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
     * required only for a withdrawal or transfer whose source account mandates a reason, read from
     * the source option, `window.krtBankAccountMeta` or the form's `data-justification-required`.
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
     * Adapts the unified "Kontobewegung" modal to the chosen movement type (REQ-BANK-017): sets the
     * endpoint and source-account field, activates only that type's `[data-movement-types]` rows,
     * swaps the labels and refreshes the split, fee and justification helpers.
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
        const splitToggle = form.querySelector('[data-role="bank-split-toggle"]');
        if (splitToggle) {
            toggleSplitRow(splitToggle);
        }
        form.querySelectorAll('[data-role="bank-cp-external-toggle"]').forEach(function (t) {
            if (!t.disabled) {
                toggleCounterpartyExternal(t);
            }
        });
        const submit = form.querySelector('button[type="submit"][data-label-deposit]');
        if (submit) {
            const label = submit.getAttribute('data-label-' + type.toLowerCase());
            if (label) {
                submit.textContent = label;
            }
        }
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
     * Applies one request row's state to the reused bank confirmation modal on every open
     * (REQ-BANK-040, REQ-BANK-041): the owner-approval checkbox gating the submit button, the split
     * and justification notices, the transfer destination-holder row and the holder label.
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

    /**
     * Fetches one bank PDF with CSRF and the browser's IANA time zone and saves it as a file.
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
            response = await fetch(url, { method: 'GET', headers });
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
        window.krtModal.layerRoot().appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(objectUrl);
    }

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
     * Type-to-confirm hurdle: the named submit button stays disabled until the input exactly matches
     * `data-confirm-token`.
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
     * Admin wipe-reset: posts in place and reports the outcome as a toast; on success the modal
     * closes and the confirm input resets. Without krtFetch the form submits natively.
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
                url,
                toast: false,
                errorMessage: form.getAttribute('data-wipe-error') || genericError(),
                onSuccess(body) {
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
     * Statement export form: requires the hidden UTC `from`/`to` instants, then downloads the PDF.
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
     * Client-side account-name filter (REQ-BANK-046): hides every `[data-filter-name]` item in the
     * `data-filter-scope` container that does not contain the term (case-insensitive), hides empty
     * groups, and shows the `data-filter-empty` note when a non-empty list is filtered to nothing.
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

    document.addEventListener('input', function (event) {
        const input = event.target.closest ? event.target.closest('[data-bank-acc-filter]') : null;
        if (input) {
            applyAccountNameFilter(input);
        }
    });

    /**
     * Toggles a collapsible region: flips the control's `aria-expanded` and shows or hides its
     * `aria-controls` target.
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

    document.addEventListener('click', function (event) {
        const head = event.target.closest
            ? event.target.closest('[data-trigger="bank-info-collapse"]')
            : null;
        if (head) {
            toggleAriaRegion(head);
        }
    });
})();

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
        } catch {}
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
        } catch {}
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
            localStorage.setItem(storageKey(), JSON.stringify({ layout }));
        } catch {}
    }

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
        } catch {}
    }

    function setCollapsed(head, collapsed) {
        head.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        const id = head.getAttribute('aria-controls');
        const target = id ? document.getElementById(id) : null;
        if (target) {
            target.hidden = collapsed;
        }
    }

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

(function () {
    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return 'bank_grants_view_' + uid;
    }

    function onGrantsPage() {
        return !!document.querySelector('[data-role="bank-grants-filter"]');
    }

    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            if (raw === null) {
                return null;
            }
            const parsed = JSON.parse(raw) || {};
            return {
                view: parsed.view === 'employee' ? 'employee' : 'account',
                accountId: typeof parsed.accountId === 'string' ? parsed.accountId : null,
                userId: typeof parsed.userId === 'string' ? parsed.userId : null,
            };
        } catch {
            return null;
        }
    }

    function writeSaved(state) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify(state));
        } catch {}
    }

    function urlState() {
        const params = new URLSearchParams(window.location.search);
        const view =
            (params.get('view') || '').toLowerCase() === 'employee' ? 'employee' : 'account';
        return {
            view,
            accountId: view === 'account' ? params.get('accountId') : null,
            userId: view === 'employee' ? params.get('userId') : null,
        };
    }

    document.addEventListener('DOMContentLoaded', function () {
        if (!onGrantsPage()) {
            return;
        }
        if (/[?&](view|accountId|userId)=/.test(window.location.search)) {
            writeSaved(urlState());
            return;
        }
        const saved = readSaved();
        if (saved === null) {
            writeSaved(urlState());
            return;
        }
        if (saved.view === 'employee' || saved.accountId || saved.userId) {
            const params = new URLSearchParams();
            params.set('view', saved.view);
            if (saved.view === 'account' && saved.accountId) {
                params.set('accountId', saved.accountId);
            }
            if (saved.view === 'employee' && saved.userId) {
                params.set('userId', saved.userId);
            }
            window.location.replace('/bank/grants?' + params.toString());
        }
    });

    document.addEventListener('change', function (event) {
        const select = event.target.closest
            ? event.target.closest('[data-role="bank-grants-filter"]')
            : null;
        if (!select) {
            return;
        }
        const view = select.getAttribute('data-view') === 'employee' ? 'employee' : 'account';
        writeSaved({
            view,
            accountId: view === 'account' && select.value ? select.value : null,
            userId: view === 'employee' && select.value ? select.value : null,
        });
    });
})();

(function () {
    const CHART_CONTAINER_IDS = ['bank-chart-results', 'org-unit-bank-chart-results'];

    function storageKey() {
        const main = document.querySelector('main[data-user-id]');
        const uid = main ? main.getAttribute('data-user-id') : 'unknown';
        return 'bank_chart_range_' + uid;
    }

    function chartContainer() {
        for (let i = 0; i < CHART_CONTAINER_IDS.length; i++) {
            const el = document.getElementById(CHART_CONTAINER_IDS[i]);
            if (el) {
                return el;
            }
        }
        return null;
    }

    function renderedRange() {
        const active = document.querySelector('.bank-chart-range-btn.is-active[data-range]');
        return active ? active.getAttribute('data-range') : null;
    }

    function readSaved() {
        try {
            const raw = localStorage.getItem(storageKey());
            if (raw === null) {
                return null;
            }
            const parsed = JSON.parse(raw) || {};
            return typeof parsed.range === 'string' ? parsed.range : null;
        } catch {
            return null;
        }
    }

    function writeSaved(range) {
        try {
            localStorage.setItem(storageKey(), JSON.stringify({ range }));
        } catch {}
    }

    document.addEventListener('DOMContentLoaded', function () {
        const container = chartContainer();
        if (!container) {
            return;
        }
        if (/[?&]chartRange=/.test(window.location.search)) {
            const rendered = renderedRange();
            if (rendered) {
                writeSaved(rendered);
            }
            return;
        }
        const saved = readSaved();
        if (saved === null || saved === renderedRange()) {
            return;
        }
        const link = container.querySelector('a.bank-chart-range-btn[data-range="' + saved + '"]');
        if (link) {
            link.click();
        }
    });

    document.addEventListener('krt:swapped', function (event) {
        const container = event.detail && event.detail.container;
        if (!container || CHART_CONTAINER_IDS.indexOf(container.id) === -1) {
            return;
        }
        const rendered = renderedRange();
        if (rendered) {
            writeSaved(rendered);
        }
    });
})();
