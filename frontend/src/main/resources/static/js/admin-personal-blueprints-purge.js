(function () {
    'use strict';

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
                if (submitBtn && confirmInput) {
                    submitBtn.disabled =
                        confirmInput.value !== confirmInput.getAttribute('data-confirm-token');
                } else if (submitBtn) {
                    submitBtn.disabled = false;
                }
            });
    });
})();
