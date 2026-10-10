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

// @ts-check

const EXCHANGE_CLIENTS_SECTIONS = {
    registry: { container: '#xc-registry-host', fragmentValue: 'registry' },
    undoRuns: { container: '#xc-undo-runs-host', fragmentValue: 'undoRuns' },
};

(function () {
    const BASE = '/admin/exchange-clients';
    const RUN_POLL_MS = 3000;

    wireUndo();

    /** Tells the other admins' open pages that the switch or the client table changed. */
    function broadcastRegistry() {
        if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
            window.krtLiveSync.sendChanged('exchange-clients', ['registry']);
        }
    }

    /** Tells the other admins' open pages that a bulk undo started and suspended its client. */
    function broadcastUndoStart() {
        if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
            window.krtLiveSync.sendChanged('exchange-clients', ['registry', 'undoRuns']);
        }
    }

    const form = document.getElementById('xc-form');
    const title = document.getElementById('xc-form-title');
    const version = document.getElementById('xc-version');
    const clientId = document.getElementById('xc-clientId');
    const displayName = document.getElementById('xc-displayName');
    const minVersion = document.getElementById('xc-minVersion');
    const contactUrl = document.getElementById('xc-contactUrl');
    const requestsPerMinute = document.getElementById('xc-requestsPerMinute');
    const writesPerDay = document.getElementById('xc-writesPerDay');
    if (
        !(form instanceof HTMLFormElement) ||
        !title ||
        !(version instanceof HTMLInputElement) ||
        !(clientId instanceof HTMLInputElement) ||
        !(displayName instanceof HTMLInputElement) ||
        !(minVersion instanceof HTMLInputElement) ||
        !(contactUrl instanceof HTMLInputElement) ||
        !(requestsPerMinute instanceof HTMLInputElement) ||
        !(writesPerDay instanceof HTMLInputElement)
    ) {
        return;
    }
    wire({
        form,
        title,
        version,
        clientId,
        displayName,
        minVersion,
        contactUrl,
        requestsPerMinute,
        writesPerDay,
    });

    /**
     * The registry form's controls, resolved and type-checked once.
     * @typedef {object} ClientFormElements
     * @property {HTMLFormElement} form
     * @property {HTMLElement} title the form heading, retitled between create and edit
     * @property {HTMLInputElement} version the optimistic-lock version of the client being edited
     * @property {HTMLInputElement} clientId
     * @property {HTMLInputElement} displayName
     * @property {HTMLInputElement} minVersion
     * @property {HTMLInputElement} contactUrl
     * @property {HTMLInputElement} requestsPerMinute
     * @property {HTMLInputElement} writesPerDay
     */

    /**
     * Installs the registry editor on the resolved controls.
     * @param {ClientFormElements} el
     */
    function wire(el) {
        const i18n = readMessages();

        /** The capabilities the client under edit held when it was loaded. */
        /** @type {string[]} */
        let loadedCapabilities = [];

        function readMessages() {
            const holder = document.getElementById('xc-i18n');
            const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
            /** @param {string} key */
            const text = function (key) {
                const attribute = `data-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
                return window.krtI18nText(data[key], attribute);
            };
            return {
                saved: text('saved'),
                suspended: text('suspended'),
                activated: text('activated'),
                switched: text('switched'),
                error: text('error'),
                confirmOk: text('confirmOk'),
                confirmCancel: text('confirmCancel'),
                confirmSuspendTitle: text('confirmSuspendTitle'),
                confirmSuspendBody: text('confirmSuspendBody'),
                confirmWidenTitle: text('confirmWidenTitle'),
                confirmWidenBody: text('confirmWidenBody'),
                confirmOffTitle: text('confirmOffTitle'),
                confirmOffBody: text('confirmOffBody'),
                confirmOnTitle: text('confirmOnTitle'),
                confirmOnBody: text('confirmOnBody'),
                formCreate: text('formCreate'),
                formEdit: text('formEdit'),
            };
        }

        /** @returns {HTMLInputElement[]} */
        function capabilityBoxes() {
            return Array.from(el.form.querySelectorAll('input[name="capability"]')).filter(
                (box) => box instanceof HTMLInputElement,
            );
        }

        /** @returns {string[]} the checked capabilities, the required one always included */
        function checkedCapabilities() {
            return capabilityBoxes()
                .filter((box) => box.checked || box.dataset.required === 'true')
                .map((box) => box.value);
        }

        /**
         * @param {HTMLInputElement} input
         * @returns {string | null}
         */
        function textOrNull(input) {
            const value = input.value.trim();
            return value === '' ? null : value;
        }

        /**
         * @param {HTMLInputElement} input
         * @returns {number | null}
         */
        function numberOrNull(input) {
            const value = input.value.trim();
            return value === '' ? null : Number(value);
        }

        function buildPayload() {
            /** @type {Record<string, unknown>} */
            const payload = {
                displayName: el.displayName.value.trim(),
                capabilities: checkedCapabilities(),
                minClientVersion: textOrNull(el.minVersion),
                contactUrl: textOrNull(el.contactUrl),
                requestsPerMinute: numberOrNull(el.requestsPerMinute),
                writesPerDay: numberOrNull(el.writesPerDay),
            };
            if (editedId()) {
                payload.version = el.version.value === '' ? null : Number(el.version.value);
            } else {
                payload.clientId = el.clientId.value.trim();
            }
            return payload;
        }

        /** @returns {string} the id of the client under edit, or '' in create mode */
        function editedId() {
            return el.form.getAttribute('data-client-id') || '';
        }

        /** Puts the form back into create mode. */
        function resetForm() {
            el.form.setAttribute('data-client-id', '');
            el.form.reset();
            el.version.value = '';
            el.clientId.disabled = false;
            el.title.textContent = i18n.formCreate;
            loadedCapabilities = [];
            capabilityBoxes().forEach((box) => {
                box.checked = box.dataset.required === 'true';
            });
        }

        /** @param {ApiDto<'ExchangeClientDto'>} client */
        function prefillForm(client) {
            el.form.setAttribute('data-client-id', client.id || '');
            el.version.value = client.version != null ? String(client.version) : '';
            el.clientId.value = client.clientId || '';
            el.clientId.disabled = true;
            el.displayName.value = client.displayName || '';
            el.minVersion.value = client.minClientVersion || '';
            el.contactUrl.value = client.contactUrl || '';
            el.requestsPerMinute.value =
                client.requestsPerMinute != null ? String(client.requestsPerMinute) : '';
            el.writesPerDay.value = client.writesPerDay != null ? String(client.writesPerDay) : '';
            loadedCapabilities = Array.isArray(client.capabilities)
                ? client.capabilities.map(String)
                : [];
            capabilityBoxes().forEach((box) => {
                box.checked =
                    box.dataset.required === 'true' || loadedCapabilities.includes(box.value);
            });
            el.title.textContent = i18n.formEdit;
            el.form.scrollIntoView({ behavior: 'smooth' });
        }

        /** @param {string} id */
        function editClient(id) {
            window.krtFetch
                .getJson(`${BASE}/${encodeURIComponent(id)}`)
                .then((client) => {
                    if (client) {
                        prefillForm(client);
                    }
                })
                .catch(() => {
                    if (typeof window.showFrontendErrorToast === 'function') {
                        window.showFrontendErrorToast(i18n.error);
                    }
                });
        }

        /** Re-renders the switch and the client table in place from the `registry` fragment. */
        function refreshRegistry() {
            return window.krtFetch.swap({
                url: BASE,
                container: '#xc-registry-host',
                fragmentValue: 'registry',
                errorMessage: i18n.error,
            });
        }

        /**
         * Runs `action` after the member confirmed, or at once where no confirm dialog exists.
         * @param {string} titleText
         * @param {string} bodyText
         * @param {() => void} action
         */
        function confirmThen(titleText, bodyText, action) {
            if (typeof window.showKrtConfirm !== 'function') {
                action();
                return;
            }
            window
                .showKrtConfirm(titleText, bodyText, i18n.confirmOk, i18n.confirmCancel)
                .then((ok) => {
                    if (ok) {
                        action();
                    }
                });
        }

        /** @param {SubmitEvent} event */
        function onSubmit(event) {
            event.preventDefault();
            if (!window.krtFetch || !el.form.reportValidity()) {
                return;
            }
            const id = editedId();
            const submitter = el.form.querySelector('button[type="submit"]');
            const save = function () {
                window.krtFetch.write({
                    method: id ? 'PUT' : 'POST',
                    url: BASE + (id ? `/${encodeURIComponent(id)}` : ''),
                    payload: buildPayload(),
                    successMessage: i18n.saved,
                    errorMessage: i18n.error,
                    submitter,
                    onSuccess() {
                        resetForm();
                        broadcastRegistry();
                        return refreshRegistry();
                    },
                });
            };
            const widened = checkedCapabilities().some((cap) => !loadedCapabilities.includes(cap));
            if (id && widened) {
                confirmThen(i18n.confirmWidenTitle, i18n.confirmWidenBody, save);
            } else {
                save();
            }
        }

        /**
         * @param {Element} row the client's table row
         * @param {'suspend' | 'activate'} action
         * @param {Element} submitter
         */
        function changeStatus(row, action, submitter) {
            const id = row.getAttribute('data-client-id');
            const rowVersion = row.getAttribute('data-version');
            if (!id || !window.krtFetch) {
                return;
            }
            const send = function () {
                window.krtFetch.write({
                    method: 'POST',
                    url: `${BASE}/${encodeURIComponent(id)}/${action}`,
                    payload: { version: rowVersion == null ? null : Number(rowVersion) },
                    successMessage: action === 'suspend' ? i18n.suspended : i18n.activated,
                    errorMessage: i18n.error,
                    submitter,
                    onSuccess() {
                        broadcastRegistry();
                        return refreshRegistry();
                    },
                });
            };
            if (action === 'suspend') {
                confirmThen(i18n.confirmSuspendTitle, i18n.confirmSuspendBody, send);
            } else {
                send();
            }
        }

        /** @param {Element} submitter */
        function toggleSwitch(submitter) {
            const box = document.getElementById('xc-switch');
            if (!box || !window.krtFetch) {
                return;
            }
            const enabled = box.getAttribute('data-enabled') === 'true';
            const boxVersion = box.getAttribute('data-version');
            confirmThen(
                enabled ? i18n.confirmOffTitle : i18n.confirmOnTitle,
                enabled ? i18n.confirmOffBody : i18n.confirmOnBody,
                () => {
                    window.krtFetch.write({
                        method: 'PUT',
                        url: `${BASE}/settings`,
                        payload: {
                            enabled: !enabled,
                            version: boxVersion == null ? null : Number(boxVersion),
                        },
                        successMessage: i18n.switched,
                        errorMessage: i18n.error,
                        submitter,
                        onSuccess() {
                            broadcastRegistry();
                            return refreshRegistry();
                        },
                    });
                },
            );
        }

        el.form.addEventListener('submit', onSubmit);
        const cancel = document.getElementById('xc-cancel');
        if (cancel) {
            cancel.addEventListener('click', resetForm);
        }

        document.addEventListener('click', (event) => {
            const target = event.target;
            if (!(target instanceof Element)) {
                return;
            }
            const switchBtn = target.closest('[data-xc-switch]');
            if (switchBtn) {
                toggleSwitch(switchBtn);
                return;
            }
            if (target.closest('[data-xc-new]')) {
                event.preventDefault();
                resetForm();
                el.form.scrollIntoView({ behavior: 'smooth' });
                el.clientId.focus({ preventScroll: true });
                return;
            }
            const row = target.closest('[data-client-id]');
            if (!row || row === el.form) {
                return;
            }
            const editBtn = target.closest('[data-xc-edit]');
            if (editBtn) {
                const id = row.getAttribute('data-client-id');
                if (id) {
                    editClient(id);
                }
                return;
            }
            const suspendBtn = target.closest('[data-xc-suspend]');
            if (suspendBtn) {
                changeStatus(row, 'suspend', suspendBtn);
                return;
            }
            const activateBtn = target.closest('[data-xc-activate]');
            if (activateBtn) {
                changeStatus(row, 'activate', activateBtn);
            }
        });

        resetForm();
    }

    /**
     * Installs the admin's bulk undo: the dialog with its check-then-confirm steps, the run list
     * that refreshes itself while a run is going, the run detail dialog (REQ-XCH-034), and the
     * page's live-sync receiver, so another admin's write or start refreshes this page too.
     */
    function wireUndo() {
        const formEl = document.getElementById('xc-undo-form');
        const sinceEl = document.getElementById('xc-undo-since');
        const resourceEl = document.getElementById('xc-undo-resource');
        const installationEl = document.getElementById('xc-undo-installation');
        const previewEl = document.getElementById('xc-undo-preview');
        const checkEl = document.getElementById('xc-undo-check');
        const submitEl = document.getElementById('xc-undo-submit');
        const clientNameEl = document.getElementById('xc-undo-client');
        if (
            !(formEl instanceof HTMLFormElement) ||
            !(sinceEl instanceof HTMLInputElement) ||
            !(resourceEl instanceof HTMLSelectElement) ||
            !(installationEl instanceof HTMLSelectElement) ||
            !previewEl ||
            !(checkEl instanceof HTMLButtonElement) ||
            !(submitEl instanceof HTMLButtonElement) ||
            !clientNameEl
        ) {
            return;
        }
        /** @type {HTMLFormElement} */
        const form = formEl;
        /** @type {HTMLInputElement} */
        const since = sinceEl;
        /** @type {HTMLSelectElement} */
        const resource = resourceEl;
        /** @type {HTMLSelectElement} */
        const installation = installationEl;
        /** @type {HTMLElement} */
        const preview = previewEl;
        /** @type {HTMLButtonElement} */
        const check = checkEl;
        /** @type {HTMLButtonElement} */
        const submit = submitEl;
        /** @type {HTMLElement} */
        const clientName = clientNameEl;
        const i18n = readUndoMessages();
        const allInstallations =
            installation.options.length > 0 ? installation.options[0].text : '';

        /** @type {number | null} */
        let pollTimer = null;

        function readUndoMessages() {
            const holder = document.getElementById('xc-undo-i18n');
            const data = holder ? holder.dataset : /** @type {DOMStringMap} */ ({});
            const common = document.getElementById('xc-i18n');
            const commonData = common ? common.dataset : /** @type {DOMStringMap} */ ({});
            /**
             * @param {DOMStringMap} source
             * @param {string} key
             */
            const text = function (source, key) {
                const attribute = `data-${key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)}`;
                return window.krtI18nText(source[key], attribute);
            };
            return {
                started: text(data, 'started'),
                preview: text(data, 'preview'),
                previewActive: text(data, 'previewActive'),
                previewEmpty: text(data, 'previewEmpty'),
                confirmTitle: text(data, 'confirmTitle'),
                confirmBody: text(data, 'confirmBody'),
                installationOption: text(data, 'installationOption'),
                installationRevoked: text(data, 'installationRevoked'),
                summary: text(data, 'summary'),
                skippedTitle: text(data, 'skippedTitle'),
                none: text(data, 'none'),
                more: text(data, 'more'),
                unnamed: text(data, 'unnamed'),
                error: text(commonData, 'error'),
                confirmOk: text(commonData, 'confirmOk'),
                confirmCancel: text(commonData, 'confirmCancel'),
                /** @type {Record<string, string>} */
                reasons: {
                    CHANGED_AFTERWARDS: text(data, 'reasonChangedAfterwards'),
                    GONE: text(data, 'reasonGone'),
                    FAILED: text(data, 'reasonFailed'),
                },
                /** @type {Record<string, string>} */
                resources: {
                    BLUEPRINT: text(data, 'resourceBlueprint'),
                    STOCK: text(data, 'resourceStock'),
                    SHIP: text(data, 'resourceShip'),
                },
            };
        }

        /**
         * Fills `{0}`, `{1}`, ... placeholders.
         * @param {string} template
         * @param {Array<string | number>} values
         */
        function format(template, values) {
            return template.replace(/\{(\d)\}/g, (match, index) => {
                const value = values[Number(index)];
                return value === undefined ? match : String(value);
            });
        }

        /** @param {string | null | undefined} iso */
        function when(iso) {
            if (!iso) {
                return '';
            }
            const date = new Date(iso);
            return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
        }

        /** @returns {string} the chosen start of the span as an ISO instant, or '' when unset */
        function sinceIso() {
            if (!since.value) {
                return '';
            }
            const date = new Date(since.value);
            return Number.isNaN(date.getTime()) ? '' : date.toISOString();
        }

        /** @returns {Record<string, unknown>} the scope the dialog describes */
        function scope() {
            return {
                since: sinceIso(),
                resource: resource.value === '' ? null : resource.value,
                installationId: installation.value === '' ? null : installation.value,
            };
        }

        /** Forgets a check, so the scope has to be checked again before it can start. */
        function resetCheck() {
            submit.disabled = true;
            preview.hidden = true;
            preview.textContent = '';
        }

        /** Loads the installations with changes since the chosen time into the select. */
        function loadInstallations() {
            const id = form.getAttribute('data-client-id') || '';
            const iso = sinceIso();
            while (installation.options.length > 1) {
                installation.remove(1);
            }
            if (!id || !iso) {
                return;
            }
            window.krtFetch
                .getJson(
                    `${BASE}/${encodeURIComponent(id)}/undo/installations?since=${encodeURIComponent(
                        iso,
                    )}`,
                )
                .then((/** @type {ApiDto<'ExchangeBulkUndoInstallationDto'>[]} */ rows) => {
                    rows.forEach((row) => {
                        const option = document.createElement('option');
                        option.value = row.installationId || '';
                        let label = format(i18n.installationOption, [
                            row.memberName || i18n.unnamed,
                            row.entries != null ? row.entries : 0,
                            when(row.lastSeenAt),
                        ]);
                        if (row.revoked) {
                            label += ` ${i18n.installationRevoked}`;
                        }
                        option.textContent = label;
                        installation.appendChild(option);
                    });
                })
                .catch(() => {
                    if (typeof window.showFrontendErrorToast === 'function') {
                        window.showFrontendErrorToast(i18n.error);
                    }
                });
        }

        /**
         * Opens the dialog for one client, preset to the last 24 hours.
         * @param {Element} button the row's undo button
         */
        function open(button) {
            const row = button.closest('[data-client-id]');
            const id = row ? row.getAttribute('data-client-id') : null;
            if (!id) {
                return;
            }
            form.setAttribute('data-client-id', id);
            clientName.textContent = button.getAttribute('data-client-name') || '';
            const start = new Date(Date.now() - 24 * 60 * 60 * 1000);
            start.setSeconds(0, 0);
            const local = new Date(start.getTime() - start.getTimezoneOffset() * 60000);
            since.value = local.toISOString().slice(0, 16);
            resource.value = '';
            installation.value = '';
            if (installation.options.length > 0) {
                installation.options[0].text = allInstallations;
            }
            resetCheck();
            loadInstallations();
            window.krtModal.open('xc-undo-modal');
        }

        /** Asks the backend what the scope reaches and allows the start once it reaches anything. */
        function runCheck() {
            const id = form.getAttribute('data-client-id') || '';
            if (!id || !window.krtFetch || !form.reportValidity()) {
                return;
            }
            window.krtFetch.write({
                method: 'POST',
                url: `${BASE}/${encodeURIComponent(id)}/undo/preview`,
                payload: scope(),
                toast: false,
                errorMessage: i18n.error,
                submitter: check,
                onSuccess(/** @type {ApiDto<'ExchangeBulkUndoPreviewDto'>} */ result) {
                    const members = result.members != null ? result.members : 0;
                    const entries = result.entries != null ? result.entries : 0;
                    let text =
                        entries === 0
                            ? i18n.previewEmpty
                            : format(i18n.preview, [members, entries, when(result.since)]);
                    if (entries > 0 && result.clientActive) {
                        text += ` ${i18n.previewActive}`;
                    }
                    preview.textContent = text;
                    preview.hidden = false;
                    submit.disabled = entries === 0;
                },
            });
        }

        /** Starts the run after a confirmation and shows it in the list. */
        function start() {
            const id = form.getAttribute('data-client-id') || '';
            if (!id || !window.krtFetch || submit.disabled) {
                return;
            }
            const send = function () {
                window.krtFetch.write({
                    method: 'POST',
                    url: `${BASE}/${encodeURIComponent(id)}/undo`,
                    payload: scope(),
                    successMessage: i18n.started,
                    errorMessage: i18n.error,
                    submitter: submit,
                    onSuccess() {
                        window.krtModal.close('xc-undo-modal');
                        broadcastUndoStart();
                        return Promise.all([refreshRegistry(), refreshRuns()]);
                    },
                    onError() {
                        broadcastUndoStart();
                        refreshRegistry();
                        refreshRuns();
                        return false;
                    },
                });
            };
            if (typeof window.showKrtConfirm !== 'function') {
                send();
                return;
            }
            window
                .showKrtConfirm(
                    i18n.confirmTitle,
                    i18n.confirmBody,
                    i18n.confirmOk,
                    i18n.confirmCancel,
                )
                .then((ok) => {
                    if (ok) {
                        send();
                    }
                });
        }

        /** Re-renders the switch and the client table, which show the client suspended. */
        function refreshRegistry() {
            return window.krtFetch.swap({
                url: BASE,
                container: '#xc-registry-host',
                fragmentValue: 'registry',
                errorMessage: i18n.error,
            });
        }

        /** Re-renders the run list in place and keeps polling while a run is going. */
        function refreshRuns() {
            return window.krtFetch
                .swap({
                    url: BASE,
                    container: '#xc-undo-runs-host',
                    fragmentValue: 'undoRuns',
                    errorMessage: i18n.error,
                })
                .then(schedulePoll);
        }

        /** Polls the run list again while it shows a running run. */
        function schedulePoll() {
            if (pollTimer !== null) {
                clearTimeout(pollTimer);
                pollTimer = null;
            }
            const list = document.getElementById('xc-undo-runs');
            if (list && list.getAttribute('data-xc-running') === 'true') {
                pollTimer = window.setTimeout(() => {
                    pollTimer = null;
                    refreshRuns();
                }, RUN_POLL_MS);
            }
        }

        /**
         * Shows one run's totals and the entries it left alone, as text only.
         * @param {string} runId
         */
        function showRun(runId) {
            window.krtFetch
                .getJson(`${BASE}/undo-runs/${encodeURIComponent(runId)}`)
                .then((/** @type {ApiDto<'ExchangeBulkUndoRunDetailDto'> | null} */ detail) => {
                    const body = document.getElementById('xc-undo-run-body');
                    if (!detail || !detail.run || !body) {
                        return;
                    }
                    const run = detail.run;
                    body.replaceChildren();
                    const summary = document.createElement('p');
                    summary.textContent = format(i18n.summary, [
                        run.restored != null ? run.restored : 0,
                        run.skipped != null ? run.skipped : 0,
                        run.membersFailed != null ? run.membersFailed : 0,
                        run.membersTotal != null ? run.membersTotal : 0,
                    ]);
                    body.appendChild(summary);
                    const skipped = Array.isArray(detail.skipped) ? detail.skipped : [];
                    if (skipped.length === 0) {
                        const none = document.createElement('p');
                        none.textContent = i18n.none;
                        body.appendChild(none);
                    } else {
                        const title = document.createElement('h3');
                        title.textContent = i18n.skippedTitle;
                        body.appendChild(title);
                        const list = document.createElement('ul');
                        list.className = 'xc-undo-skipped';
                        skipped.forEach((entry) => {
                            const item = document.createElement('li');
                            const reason = i18n.reasons[entry.reason || ''] || entry.reason || '';
                            const who = entry.memberName || i18n.unnamed;
                            if (entry.resource) {
                                const what = i18n.resources[entry.resource] || entry.resource;
                                item.textContent = `${who} \u2013 ${what}: ${
                                    entry.label || i18n.unnamed
                                } \u2013 ${reason}`;
                            } else {
                                item.textContent = `${who} \u2013 ${reason}`;
                            }
                            list.appendChild(item);
                        });
                        body.appendChild(list);
                        const total =
                            detail.skippedTotal != null ? detail.skippedTotal : skipped.length;
                        if (total > skipped.length) {
                            const more = document.createElement('p');
                            more.textContent = format(i18n.more, [total - skipped.length]);
                            body.appendChild(more);
                        }
                    }
                    window.krtModal.open('xc-undo-run-modal');
                })
                .catch(() => {
                    if (typeof window.showFrontendErrorToast === 'function') {
                        window.showFrontendErrorToast(i18n.error);
                    }
                });
        }

        since.addEventListener('change', () => {
            resetCheck();
            loadInstallations();
        });
        resource.addEventListener('change', resetCheck);
        installation.addEventListener('change', resetCheck);
        check.addEventListener('click', runCheck);
        form.addEventListener('submit', (event) => {
            event.preventDefault();
            start();
        });

        document.addEventListener('click', (event) => {
            const target = event.target;
            if (!(target instanceof Element)) {
                return;
            }
            const undoBtn = target.closest('[data-xc-undo]');
            if (undoBtn) {
                open(undoBtn);
                return;
            }
            const runBtn = target.closest('[data-xc-undo-run]');
            if (runBtn) {
                const row = runBtn.closest('[data-run-id]');
                const runId = row ? row.getAttribute('data-run-id') : null;
                if (runId) {
                    showRun(runId);
                }
            }
        });

        schedulePoll();

        if (window.krtLiveSync && typeof window.krtLiveSync.createReceiver === 'function') {
            window.krtLiveSync.createReceiver({
                topic: 'exchange-clients',
                sections: EXCHANGE_CLIENTS_SECTIONS,
                coalesceMs: 1500,
                /** @param {string[]} keys the sections another admin changed */
                refresh(keys) {
                    if (keys.includes('registry')) {
                        refreshRegistry();
                    }
                    if (keys.includes('undoRuns')) {
                        refreshRuns();
                    }
                },
            });
        }
    }
})();
