/*
 * Admin — Personal Blueprints global purge (REQ-INV-024).
 *
 * Wipe-reset-grade danger action: clears every user's removable blueprints (auto-granted defaults,
 * REQ-INV-016, are preserved server-side). The danger modal is opened / closed by the shared
 * open-modal-display / close-modal-display handlers (common-handlers.js); this module adds the two
 * pieces that are NOT global:
 *   1. the type-to-confirm gate — the submit button stays disabled until the typed value equals the
 *      token exactly (case-sensitive), guarding an accidental all-users wipe; and
 *   2. the write path — the form submit goes through krtFetch.submitForm (the FormData twin of
 *      write, S10 #916), which owns the CSRF header + retry-on-403; on success we toast the removed
 *      count and (if a member's owned list is currently shown) re-render just that fragment in place
 *      (REQ-FE-002), never a full-page reload.
 * The form keeps its th:action/method=post so a script-less browser gets the native POST->redirect
 * fallback. All translatable strings ride on the form's data-* attributes so this module stays
 * i18n-free (mirrors bank.js' data-bank-wipe pattern).
 */
(function () {
    'use strict';

    // Type-to-confirm: arm the submit only when the typed value matches the token exactly.
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

    // Intercept the purge form submit -> krtFetch. On success toast the removed count and refresh
    // the current member's owned-list fragment in place; a missing krtFetch falls back to a real
    // form POST (no-JS path).
    document.addEventListener('submit', function (event) {
        const form = event.target.closest('form[data-bp-purge]');
        if (!form) {
            return;
        }
        event.preventDefault();
        if (!window.krtFetch) {
            form.submit();
            return;
        }
        const confirmInput = form.querySelector('input[name="confirm"]');
        const submitBtn = form.querySelector('button[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
        }
        window.krtFetch
            .submitForm({
                form,
                toast: false,
                errorMessage: form.getAttribute('data-purge-error') || '',
                onSuccess(body) {
                    const count = body && body.deleted != null ? body.deleted : 0;
                    if (typeof window.showFrontendSuccessToast === 'function') {
                        const tpl = form.getAttribute('data-purge-success') || '{0}';
                        window.showFrontendSuccessToast(String(tpl).replace('{0}', count));
                    }
                    // Close the danger modal and clear the confirm field so a re-open re-arms.
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
                    // Live-update: if a member's owned list is on screen, re-render that fragment.
                    if (window.krtFetch.swap && document.getElementById('bp-results')) {
                        window.krtFetch.swap({
                            url: window.location.pathname + window.location.search,
                            container: '#bp-results',
                            fragmentValue: 'results',
                            history: false,
                        });
                    }
                },
            })
            .finally(function () {
                // Re-arm the hurdle: the submit stays disabled until the token is re-typed.
                if (submitBtn && confirmInput) {
                    submitBtn.disabled =
                        confirmInput.value !== confirmInput.getAttribute('data-confirm-token');
                } else if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    });
})();
