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

/** @import { PatchChangeMessages } from '/js/patchchange/patch-change-messages.js' */

import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtHtml } from '/js/kernel/krt-html.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';
import { formatNumber } from '/js/kernel/krt-number-format.js';
import { format, localTime, readCountLabels } from '/js/patchchange/patch-change-messages.js';

/** The page's path, also the base of its relay endpoints. */
const BASE = '/admin/settings/patch-change';

/** How long the live time rests before its preview is fetched, in milliseconds. */
const LIVE_AT_DEBOUNCE_MS = 400;

/** Milliseconds per minute, for the offset of the viewer's time zone. */
const MS_PER_MINUTE = 60 * 1000;

/** Length of `YYYY-MM-DDTHH:mm`, the value of a `datetime-local` input to the minute. */
const LOCAL_MINUTE_LENGTH = 16;

/**
 * The option switches besides the wipe groups, by input name; each kind radio carries its preset
 * of them as the `data-*` attribute of the same name.
 */
const OPTION_NAMES = ['cancelRefineryOrders', 'currencyWipe', 'homeLocationPrompt'];

/**
 * The controls of the patch-change form, resolved and type-checked once.
 *
 * @typedef {object} PatchChangeControls
 * @property {HTMLFormElement} form the form around the fields, the preview and the trigger
 * @property {HTMLInputElement} label the patch label
 * @property {HTMLInputElement} liveAt the live time in the viewer's local time
 * @property {HTMLInputElement} confirm the typed confirmation
 * @property {HTMLElement} confirmLabel the confirmation's label naming the token
 * @property {HTMLButtonElement} trigger the danger button
 * @property {HTMLElement} counts the preview's tiles
 * @property {HTMLElement} previewError the preview's error in place of the tiles
 * @property {HTMLElement} currencyAlert the alert shown while the currency wipe is on
 * @property {HTMLElement} previous the hint naming the previous patch change
 */

/**
 * What the form offers the rest of the page.
 *
 * @typedef {object} PatchChangeFormSeam
 * @property {() => void} refreshPrevious re-reads the previous patch change for the hint and the
 *     preview, leaving the admin's inputs as they are
 */

/**
 * Resolves the form's controls.
 *
 * @returns {PatchChangeControls | null} the controls, or null when the page renders no form
 */
function resolveControls() {
    const form = document.getElementById('pc-form');
    const label = document.getElementById('pc-label');
    const liveAt = document.getElementById('pc-live-at');
    const confirm = document.getElementById('pc-confirm');
    const confirmLabel = document.getElementById('pc-confirm-label');
    const trigger = document.getElementById('pc-trigger');
    const counts = document.getElementById('pc-preview-counts');
    const previewError = document.getElementById('pc-preview-error');
    const currencyAlert = document.getElementById('pc-currency-alert');
    const previous = document.getElementById('pc-previous');
    if (
        !(form instanceof HTMLFormElement) ||
        !(label instanceof HTMLInputElement) ||
        !(liveAt instanceof HTMLInputElement) ||
        !(confirm instanceof HTMLInputElement) ||
        !confirmLabel ||
        !(trigger instanceof HTMLButtonElement) ||
        !counts ||
        !previewError ||
        !currencyAlert ||
        !previous
    ) {
        return null;
    }
    return {
        form,
        label,
        liveAt,
        confirm,
        confirmLabel,
        trigger,
        counts,
        previewError,
        currencyAlert,
        previous,
    };
}

/**
 * The current minute in the viewer's local time, as a `datetime-local` value.
 *
 * @returns {string} `YYYY-MM-DDTHH:mm`
 */
function localNow() {
    const now = new Date();
    now.setSeconds(0, 0);
    return new Date(now.getTime() - now.getTimezoneOffset() * MS_PER_MINUTE)
        .toISOString()
        .slice(0, LOCAL_MINUTE_LENGTH);
}

/**
 * Reads the defaults of a new patch change.
 *
 * @returns {Promise<ApiDto<'PatchChangeFormDto'> | null>} the defaults, or null when unreadable
 */
function readForm() {
    return krtFetch.getJson(`${BASE}/form`).then(
        (body) => body || null,
        () => null,
    );
}

/**
 * Installs the patch-change form: the kind presets, the preview refreshed on every change, the
 * typed confirmation and the trigger, after which the history re-renders and the form starts over
 * from fresh defaults (REQ-PATCH-005, REQ-PATCH-008).
 *
 * @param {KrtPageSections} sections the page's section seam
 * @param {PatchChangeMessages} i18n the page's localized strings
 * @returns {PatchChangeFormSeam | null} the form's seam, or null when the page renders no form
 */
export function initPatchChangeForm(sections, i18n) {
    const el = resolveControls();
    return el ? wireForm(el, sections, i18n) : null;
}

/**
 * Wires the resolved controls of the form.
 *
 * @param {PatchChangeControls} el the form's controls
 * @param {KrtPageSections} sections the page's section seam
 * @param {PatchChangeMessages} i18n the page's localized strings
 * @returns {PatchChangeFormSeam} the form's seam
 */
function wireForm(el, sections, i18n) {
    const countLabels = readCountLabels();
    let previewSeq = 0;
    /** @type {number | null} */
    let previewTimer = null;

    /**
     * @param {string} name the inputs' name
     * @returns {HTMLInputElement[]} the form's inputs of that name
     */
    function inputs(name) {
        return Array.from(el.form.querySelectorAll(`input[name="${name}"]`)).filter(
            (input) => input instanceof HTMLInputElement,
        );
    }

    /** @returns {Record<string, unknown>} the options as the admin left them */
    function options() {
        /** @type {Record<string, unknown>} */
        const result = {
            wipeGroups: inputs('wipeGroup')
                .filter((group) => group.checked)
                .map((group) => group.value),
        };
        OPTION_NAMES.forEach((name) => {
            result[name] = inputs(name).some((option) => option.checked);
        });
        return result;
    }

    /**
     * Sets every option to the preset the kind radio carries.
     *
     * @param {HTMLInputElement} radio the chosen kind
     */
    function applyPreset(radio) {
        const groups = (radio.dataset.wipeGroups || '').split(' ').filter(Boolean);
        inputs('wipeGroup').forEach((group) => {
            group.checked = groups.includes(group.value);
        });
        OPTION_NAMES.forEach((name) => {
            inputs(name).forEach((option) => {
                option.checked = radio.dataset[name] === 'true';
            });
        });
    }

    /** @returns {string | null} the live time as an ISO instant, or null for now */
    function liveAtIso() {
        if (!el.liveAt.value) {
            return null;
        }
        const date = new Date(el.liveAt.value);
        return Number.isNaN(date.getTime()) ? null : date.toISOString();
    }

    /** @returns {string} the label as the admin typed it, without surrounding blanks */
    function currentLabel() {
        return el.label.value.trim();
    }

    /** Names the label in the confirmation and the button, which only a typed label enables. */
    function syncConfirmation() {
        const value = currentLabel();
        el.confirm.setAttribute('data-confirm-token', value);
        el.confirm.placeholder = value;
        el.confirmLabel.textContent = format(i18n.confirmLabel, [value]);
        el.trigger.textContent = format(i18n.triggerLabel, [value]);
        el.trigger.disabled = value === '' || el.confirm.value.trim() !== value;
    }

    /** Shows the danger alert while the currency wipe is on. */
    function syncCurrencyAlert() {
        el.currencyAlert.hidden = !inputs('currencyWipe').some((option) => option.checked);
    }

    /**
     * Names the previous patch change in the hint, or hides it before the first.
     *
     * @param {string | null | undefined} label the previous label
     * @param {string | null | undefined} liveAt the previous live time as an ISO instant
     */
    function showPrevious(label, liveAt) {
        if (!label) {
            el.previous.hidden = true;
            el.previous.textContent = '';
            return;
        }
        el.previous.textContent = format(i18n.previous, [label, localTime(liveAt)]);
        el.previous.hidden = false;
    }

    /**
     * @param {string | undefined} kind a count kind
     * @returns {string} its tile label
     */
    function countLabel(kind) {
        return krtI18nText(countLabels.get(kind || ''), `admin.patchChange.count.${kind}`);
    }

    /**
     * Renders one tile per count in place of the preview's content.
     *
     * @param {ApiDto<'PatchCountDto'>[]} counts the counts of the enabled effects
     */
    function showCounts(counts) {
        el.previewError.hidden = true;
        el.counts.hidden = false;
        el.counts.removeAttribute('aria-busy');
        if (counts.length === 0) {
            krtHtml.set(el.counts, krtHtml`<p class="form-hint pc-hint">${i18n.previewEmpty}</p>`);
            return;
        }
        const lang = document.documentElement.lang;
        krtHtml.set(
            el.counts,
            counts.map(
                (count) =>
                    krtHtml`<div class="kpi-total kpi-total--inset" data-count-kind="${count.kind}"><div class="kpi-label">${countLabel(count.kind)}</div><div class="kpi-value">${formatNumber(count.value ?? 0, 0, lang)}</div></div>`,
            ),
        );
    }

    /**
     * Shows the preview's refusal in place of the tiles.
     *
     * @param {string} message the localized reason
     */
    function showPreviewError(message) {
        el.counts.hidden = true;
        el.counts.removeAttribute('aria-busy');
        el.previewError.textContent = message;
        el.previewError.hidden = false;
    }

    /** Fetches the preview of the current inputs; an answer to an older request is dropped. */
    function runPreview() {
        window.clearTimeout(previewTimer ?? undefined);
        previewTimer = null;
        previewSeq += 1;
        const seq = previewSeq;
        el.counts.setAttribute('aria-busy', 'true');
        krtFetch.write({
            method: 'POST',
            url: `${BASE}/preview`,
            payload: { liveAt: liveAtIso(), options: options() },
            toast: false,
            errorMessage: i18n.previewError,
            onSuccess(/** @type {ApiDto<'PatchChangePreviewDto'> | null} */ body) {
                if (seq === previewSeq) {
                    showCounts(body && body.counts ? body.counts : []);
                }
            },
            onError(_status, /** @type {ApiProblem | null} */ problem) {
                if (seq === previewSeq) {
                    showPreviewError(
                        problem && problem.detail ? problem.detail : i18n.previewError,
                    );
                }
                return true;
            },
            onNetworkError() {
                if (seq === previewSeq) {
                    showPreviewError(i18n.previewError);
                }
                return true;
            },
        });
    }

    /** Fetches the preview once the live time has rested. */
    function schedulePreview() {
        window.clearTimeout(previewTimer ?? undefined);
        previewTimer = window.setTimeout(runPreview, LIVE_AT_DEBOUNCE_MS);
    }

    /**
     * Starts the form over from fresh defaults: the default kind with its preset, the suggested
     * label, the live time now and an empty confirmation.
     *
     * @returns {Promise<void>} settles once the form is reset
     */
    function resetForm() {
        return readForm().then((fresh) => {
            el.form.reset();
            el.liveAt.value = localNow();
            el.label.value = fresh && fresh.suggestedLabel ? fresh.suggestedLabel : '';
            if (fresh) {
                showPrevious(fresh.previousLabel, fresh.previousLiveAt);
            }
            syncConfirmation();
            syncCurrencyAlert();
            runPreview();
        });
    }

    /** Re-reads the previous patch change for the hint and refreshes the preview. */
    function refreshPrevious() {
        readForm().then((fresh) => {
            if (fresh) {
                showPrevious(fresh.previousLabel, fresh.previousLiveAt);
            }
            runPreview();
        });
    }

    /** Triggers the patch change once the typed confirmation equals the label. */
    function trigger() {
        const value = currentLabel();
        const kind = inputs('kind').find((radio) => radio.checked);
        if (!kind || value === '' || el.confirm.value.trim() !== value) {
            return;
        }
        sections
            .write({
                method: 'POST',
                url: BASE,
                payload: {
                    label: value,
                    kind: kind.value,
                    liveAt: liveAtIso(),
                    options: options(),
                    confirmation: el.confirm.value.trim(),
                },
                successMessage: format(i18n.triggered, [value]),
                errorMessage: i18n.triggerError,
                submitter: el.trigger,
                onSuccess() {
                    return Promise.all([
                        sections.refresh('history', { broadcast: false }),
                        resetForm(),
                    ]);
                },
            })
            .finally(syncConfirmation);
    }

    el.form.addEventListener('submit', (event) => {
        event.preventDefault();
        trigger();
    });
    el.form.addEventListener('input', (event) => {
        if (event.target === el.label || event.target === el.confirm) {
            syncConfirmation();
        } else if (event.target === el.liveAt) {
            schedulePreview();
        }
    });
    el.form.addEventListener('change', (event) => {
        const target = event.target;
        if (!(target instanceof HTMLInputElement) || target === el.label || target === el.confirm) {
            return;
        }
        if (target.name === 'kind') {
            applyPreset(target);
        }
        syncCurrencyAlert();
        runPreview();
    });

    el.liveAt.value = localNow();
    showPrevious(el.form.dataset.previousLabel, el.form.dataset.previousLiveAt);
    syncConfirmation();
    syncCurrencyAlert();
    runPreview();
    return { refreshPrevious };
}
