// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

/*
 * Terms-of-Use consent gate (REQ-SEC-028).
 *
 * The accept button is a plain button driving a krtFetch write rather than a form post, so a
 * failure to record consent leaves the user on this page with an error they can retry, instead of
 * bouncing them through a redirect that lands back here with no explanation.
 *
 * The navigation on success is deliberate and is NOT the full-page reload REQ-FE-001 forbids: that
 * rule is about mutations on a working surface, and this one's whole purpose is to move the user
 * off the gate and into the tool. The target is the fixed start page rather than a remembered
 * original URL — replaying a URL carried through the gate is an open-redirect waiting to happen,
 * and one extra click is a fair price for not having that.
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
            // No success toast: the page is about to be replaced, so the toast would either flash
            // for a few milliseconds or survive into the start page as a non-sequitur.
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
