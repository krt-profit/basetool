// @ts-check

import { showFrontendSuccessToast } from '/js/kernel/toast.js';

document.addEventListener('input', (event) => {
    const input = /** @type {HTMLInputElement | null} */ (
        /** @type {Element} */ (event.target).closest(
            'input[data-confirm-token][data-confirm-submit]',
        )
    );
    if (!input) {
        return;
    }
    const submit = /** @type {HTMLButtonElement | null} */ (
        document.getElementById(/** @type {string} */ (input.getAttribute('data-confirm-submit')))
    );
    if (submit) {
        submit.disabled = input.value !== input.getAttribute('data-confirm-token');
    }
});

document.addEventListener('submit', (event) => {
    const form = /** @type {HTMLFormElement | null} */ (
        /** @type {Element} */ (event.target).closest('form[data-bp-purge]')
    );
    if (!form) {
        return;
    }
    event.preventDefault();
    if (!window.krtFetch) {
        form.submit();
        return;
    }
    const confirmInput = /** @type {HTMLInputElement | null} */ (
        form.querySelector('input[name="confirm"]')
    );
    const submitBtn = /** @type {HTMLButtonElement | null} */ (
        form.querySelector('button[type="submit"]')
    );
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
                if (typeof showFrontendSuccessToast === 'function') {
                    const tpl = form.getAttribute('data-purge-success') || '{0}';
                    showFrontendSuccessToast(String(tpl).replace('{0}', count));
                }
                const overlay = form.closest('.krt-modal-overlay');
                const closeBtn = /** @type {HTMLElement | null} */ (
                    overlay ? overlay.querySelector('[data-trigger="close-modal-display"]') : null
                );
                if (closeBtn) {
                    closeBtn.click();
                }
                if (confirmInput) {
                    confirmInput.value = '';
                }
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
        .finally(() => {
            if (submitBtn && confirmInput) {
                submitBtn.disabled =
                    confirmInput.value !== confirmInput.getAttribute('data-confirm-token');
            } else if (submitBtn) {
                submitBtn.disabled = false;
            }
        });
});
