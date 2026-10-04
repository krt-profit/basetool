// @ts-check
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

(function () {
    'use strict';
    if (!window.krtFetch) {
        return;
    }
    const krtFetch = window.krtFetch;
    /** @type {Record<string, string>} */
    const i18n = window.krtProfileI18n || {};

    /**
     * One editable section of the settings card: its form, the payload its endpoint takes and an
     * optional hook that applies the saved response to the page.
     *
     * @typedef {object} ProfileSection
     * @property {string} formId the id of the section's form
     * @property {(form: HTMLFormElement, version: number) => Record<string, unknown>} payload
     *     builds the JSON body for the section's endpoint
     * @property {(form: HTMLFormElement, body: any) => void} [onSaved] applies the response
     */

    /**
     * Reads the value of a named control of a form as a string, or null when the form has no such
     * control.
     *
     * @param {HTMLFormElement} form the form to read
     * @param {string} selector the control selector
     * @returns {HTMLInputElement | HTMLTextAreaElement | null} the control
     */
    function control(form, selector) {
        return /** @type {HTMLInputElement | HTMLTextAreaElement | null} */ (
            form.querySelector(selector)
        );
    }

    /** @type {ProfileSection[]} */
    const SECTIONS = [
        {
            formId: 'profile-description-form',
            payload(form, version) {
                const description = control(form, '#description');
                const displayName = control(form, '#displayName');
                return {
                    description: description ? description.value : '',
                    displayName: displayName ? displayName.value : '',
                    version,
                };
            },
            onSaved(_form, body) {
                const name = document.getElementById('profile-id-name');
                if (name) {
                    name.textContent = body.displayName || name.getAttribute('data-fallback') || '';
                }
            },
        },
        {
            formId: 'profile-rsi-handle-form',
            payload(form, version) {
                const input = control(form, '#rsiHandle');
                return { rsiHandle: input ? input.value : '', version };
            },
            onSaved(form, body) {
                const input = control(form, '#rsiHandle');
                if (input) {
                    input.value = body.rsiHandle || '';
                }
            },
        },
        {
            formId: 'profile-payout-form',
            payload(form, version) {
                const checked = control(form, 'input[name="defaultPayoutPreference"]:checked');
                return { defaultPayoutPreference: checked ? checked.value : null, version };
            },
        },
        {
            formId: 'profile-blueprint-sharing-form',
            payload(form, version) {
                const checkbox = /** @type {HTMLInputElement | null} */ (
                    form.querySelector('input[name="shareBlueprintsGlobally"]')
                );
                return { shareBlueprintsGlobally: checkbox ? checkbox.checked : false, version };
            },
        },
    ];

    const settings = document.getElementById('profile-settings');
    const bar = document.getElementById('profile-save-bar');
    const summary = document.getElementById('profile-save-summary');
    const saveButton = /** @type {HTMLButtonElement | null} */ (
        document.getElementById('profile-save')
    );
    const discardButton = document.getElementById('profile-discard');

    /** @type {Map<string, Map<Element, string>>} */
    const baselines = new Map();
    let saving = false;

    /**
     * The controls of a form whose value the user edits: everything but hidden inputs and buttons.
     *
     * @param {HTMLFormElement} form the section form
     * @returns {(HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement)[]} its data controls
     */
    function trackedControls(form) {
        return /** @type {(HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement)[]} */ (
            Array.from(form.querySelectorAll('input, select, textarea'))
        ).filter(function (el) {
            return !(el instanceof HTMLInputElement && el.type === 'hidden');
        });
    }

    /**
     * The comparable state of one control: the checked flag of a checkbox or radio, else its value.
     *
     * @param {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} el the control
     * @returns {string} its state
     */
    function stateOf(el) {
        if (el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio')) {
            return el.checked ? 'on' : 'off';
        }
        return el.value;
    }

    /**
     * Records the current state of a section form as its saved baseline.
     *
     * @param {HTMLFormElement} form the section form
     */
    function snapshot(form) {
        /** @type {Map<Element, string>} */
        const state = new Map();
        trackedControls(form).forEach(function (el) {
            state.set(el, stateOf(el));
        });
        baselines.set(form.id, state);
    }

    /**
     * Tells whether any control of a section form differs from its baseline.
     *
     * @param {HTMLFormElement} form the section form
     * @returns {boolean} true when the section holds unsaved changes
     */
    function isDirty(form) {
        const state = baselines.get(form.id);
        if (!state) {
            return false;
        }
        return trackedControls(form).some(function (el) {
            return state.get(el) !== stateOf(el);
        });
    }

    /**
     * The section forms present on the page, in page order.
     *
     * @returns {{ section: ProfileSection, form: HTMLFormElement }[]} the sections with their forms
     */
    function presentSections() {
        /** @type {{ section: ProfileSection, form: HTMLFormElement }[]} */
        const found = [];
        SECTIONS.forEach(function (section) {
            const form = document.getElementById(section.formId);
            if (form instanceof HTMLFormElement) {
                found.push({ section, form });
            }
        });
        return found;
    }

    /** Shows the save bar while any section is dirty and names the dirty sections in it. */
    function refreshBar() {
        if (!bar) {
            return;
        }
        const dirty = presentSections().filter(function (entry) {
            return isDirty(entry.form);
        });
        bar.hidden = dirty.length === 0;
        if (!dirty.length && !saving && typeof window.resetUnsavedChanges === 'function') {
            window.resetUnsavedChanges();
        }
        if (summary) {
            const names = dirty.map(function (entry) {
                return entry.form.getAttribute('data-section-name') || '';
            });
            const prefix = summary.getAttribute('data-prefix') || '';
            summary.textContent = dirty.length ? prefix + ': ' + names.join(' · ') : '';
        }
    }

    /**
     * Writes the user row's fresh version into every section form; the forms share one version.
     *
     * @param {unknown} version the version from the save response
     */
    function syncAllVersions(version) {
        if (version == null) {
            return;
        }
        document
            .querySelectorAll('#profile-settings input[name="version"]')
            .forEach(function (input) {
                /** @type {HTMLInputElement} */ (input).value = String(version);
            });
    }

    /**
     * Saves every dirty section in page order through its own endpoint, one after the other so
     * each write carries the version the previous one returned. Stops at the first failure; the
     * sections saved before it keep their new baseline and the rest stay dirty.
     *
     * @returns {Promise<void>} settles when the run ended
     */
    async function saveAll() {
        if (saving) {
            return;
        }
        const dirty = presentSections().filter(function (entry) {
            return isDirty(entry.form);
        });
        if (!dirty.length) {
            refreshBar();
            return;
        }
        const invalid = dirty.find(function (entry) {
            return !entry.form.checkValidity();
        });
        if (invalid) {
            invalid.form.reportValidity();
            return;
        }
        saving = true;
        if (saveButton) {
            saveButton.disabled = true;
        }
        let allSaved = true;
        try {
            for (let i = 0; i < dirty.length; i++) {
                const entry = dirty[i];
                const versionInput = control(entry.form, 'input[name="version"]');
                const result = await krtFetch.write({
                    method: 'POST',
                    url: entry.form.getAttribute('action') || '',
                    payload: entry.section.payload(
                        entry.form,
                        versionInput ? Number(versionInput.value) : 0,
                    ),
                    toast: i === dirty.length - 1,
                    successMessage: i18n.saved,
                    errorMessage: i18n.error,
                    conflictSectionLabel: entry.form.getAttribute('data-section-name') || undefined,
                    conflict: {
                        title: i18n.conflictTitle,
                        reloadLabel: i18n.conflictReload,
                        dismissLabel: i18n.conflictDismiss,
                        reloadQuestion: i18n.conflictQuestion,
                        reloadDetailFallback: i18n.conflictDetail,
                    },
                });
                if (!result.ok) {
                    allSaved = false;
                    break;
                }
                if (result.body) {
                    syncAllVersions(result.body.version);
                    if (entry.section.onSaved) {
                        entry.section.onSaved(entry.form, result.body);
                    }
                }
                snapshot(entry.form);
            }
        } finally {
            saving = false;
            if (saveButton) {
                saveButton.disabled = false;
            }
        }
        if (allSaved && typeof window.resetUnsavedChanges === 'function') {
            window.resetUnsavedChanges();
        }
        refreshBar();
    }

    const description = /** @type {HTMLTextAreaElement | null} */ (
        document.getElementById('description')
    );
    const counter = document.getElementById('profile-description-counter');

    /** Shows the description's length against its maxlength under the textarea. */
    function updateCounter() {
        if (!description || !counter) {
            return;
        }
        counter.textContent = description.value.length + ' / ' + (description.maxLength || 2000);
    }

    /** Restores every section to its baseline and hides the save bar. */
    function discardAll() {
        presentSections().forEach(function (entry) {
            const state = baselines.get(entry.form.id);
            if (!state) {
                return;
            }
            trackedControls(entry.form).forEach(function (el) {
                const saved = state.get(el);
                if (saved === undefined) {
                    return;
                }
                if (
                    el instanceof HTMLInputElement &&
                    (el.type === 'checkbox' || el.type === 'radio')
                ) {
                    el.checked = saved === 'on';
                } else {
                    el.value = saved;
                }
            });
        });
        updateCounter();
        if (typeof window.resetUnsavedChanges === 'function') {
            window.resetUnsavedChanges();
        }
        refreshBar();
    }

    presentSections().forEach(function (entry) {
        snapshot(entry.form);
        entry.form.addEventListener('submit', function (event) {
            event.preventDefault();
            saveAll();
        });
    });

    if (settings) {
        settings.addEventListener('input', refreshBar);
        settings.addEventListener('change', refreshBar);
    }
    if (description) {
        description.addEventListener('input', updateCounter);
    }
    if (saveButton) {
        saveButton.addEventListener('click', function () {
            saveAll();
        });
    }
    if (discardButton) {
        discardButton.addEventListener('click', discardAll);
    }
    refreshBar();

    const navLinks = /** @type {HTMLAnchorElement[]} */ (
        Array.from(document.querySelectorAll('.profile-nav a[href^="#"]'))
    );

    /**
     * Marks the navigation link of the given section as the current one.
     *
     * @param {string} id the id of the section in view
     */
    function markCurrent(id) {
        navLinks.forEach(function (link) {
            if (link.getAttribute('href') === '#' + id) {
                link.setAttribute('aria-current', 'true');
            } else {
                link.removeAttribute('aria-current');
            }
        });
    }

    if (navLinks.length && 'IntersectionObserver' in window) {
        const observer = new IntersectionObserver(
            function (entries) {
                const visible = entries
                    .filter(function (entry) {
                        return entry.isIntersecting;
                    })
                    .sort(function (a, b) {
                        return a.boundingClientRect.top - b.boundingClientRect.top;
                    });
                if (visible.length) {
                    markCurrent(visible[0].target.id);
                }
            },
            { rootMargin: '-20% 0px -60% 0px' },
        );
        navLinks.forEach(function (link) {
            const target = document.getElementById((link.getAttribute('href') || '').slice(1));
            if (target) {
                observer.observe(target);
            }
        });
    }

    const DELETION_URL = '/profile/deletion-request';

    /**
     * Re-renders the deletion card from its fragment endpoint.
     *
     * @returns {Promise<boolean> | undefined} the swap, or undefined when no swap helper exists
     */
    function refreshDeletionCard() {
        if (!krtFetch.swap) {
            return undefined;
        }
        return krtFetch.swap({
            url: DELETION_URL,
            container: '#profile-deletion-host',
            fragmentValue: 'card',
            errorMessage: i18n.deletionError,
        });
    }

    const deletionSubmit = document.getElementById('profile-deletion-submit');
    if (deletionSubmit) {
        deletionSubmit.addEventListener('click', function () {
            /** @type {HTMLInputElement | null} */
            const eraseHistory = document.querySelector('#profile-deletion-erase-history');
            krtFetch.write({
                method: 'POST',
                url: DELETION_URL,
                payload: { eraseHistory: eraseHistory ? eraseHistory.checked : false },
                successMessage: i18n.deletionRequested,
                errorMessage: i18n.deletionError,
                onSuccess() {
                    window.krtModal.close('profile-deletion-modal');
                    if (eraseHistory) {
                        eraseHistory.checked = false;
                    }
                    return refreshDeletionCard();
                },
            });
        });
    }

    const deletionHost = document.getElementById('profile-deletion-host');
    if (deletionHost) {
        deletionHost.addEventListener('click', function (event) {
            const target = event.target;
            if (!(target instanceof Element)) {
                return;
            }
            if (!target.closest('#profile-deletion-withdraw')) {
                return;
            }
            krtFetch.write({
                method: 'DELETE',
                url: DELETION_URL,
                successMessage: i18n.deletionWithdrawn,
                errorMessage: i18n.deletionError,
                onSuccess: refreshDeletionCard,
            });
        });
    }
})();
