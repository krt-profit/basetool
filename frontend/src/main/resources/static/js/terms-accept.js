// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

(function () {
    'use strict';

    const submitButton = document.getElementById('terms-accept-submit');
    const errorMessage = document.getElementById('terms-accept-error');

    if (!submitButton) {
        return;
    }

    submitButton.addEventListener('click', async function () {
        if (errorMessage) {
            errorMessage.hidden = true;
        }

        const result = await window.krtFetch.write({
            method: 'POST',
            url: '/terms/accept',
            payload: {},
            toast: false,
        });

        if (result && result.ok) {
            window.location.assign('/');
            return;
        }
        if (errorMessage) {
            errorMessage.hidden = false;
        }
    });
})();
