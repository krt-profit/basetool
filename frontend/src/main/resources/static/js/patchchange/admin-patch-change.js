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

/** @import { PatchChangeFormSeam } from '/js/patchchange/patch-change-form.js' */

import { krtPageSections } from '/js/kernel/krt-page-sections.js';
import { initPatchChangeForm } from '/js/patchchange/patch-change-form.js';
import { initLabelCorrection } from '/js/patchchange/patch-change-history.js';
import { localizeTimes, readMessages } from '/js/patchchange/patch-change-messages.js';
import { initPhaseEnd } from '/js/patchchange/patch-change-phase.js';

/** The page's path, from which the history fragment is fetched. */
const PAGE_URL = '/admin/settings/patch-change';

/**
 * Wires the admin's patch-change page (REQ-PATCH-008, REQ-PATCH-024): the form with its preview
 * and trigger, the history with its label correction and phase end, and the `patch-change` room
 * the backend announces a trigger, a correction, a phase end or a relocation in, on which the
 * history and the form's previous values refresh in place.
 */
function initAdminPatchChange() {
    const i18n = readMessages();

    /** @type {PatchChangeFormSeam | null} */
    let form = null;

    const sections = krtPageSections.create({
        dict() {
            return { 'pc.refresh.error': i18n.refreshError };
        },
        keys: { refreshErrorKey: 'pc.refresh.error' },
        topicClass: 'PATCH_CHANGE',
        sections: {
            history: { container: '#pc-history-host', fragmentValue: 'history' },
        },
        pageUrl() {
            return PAGE_URL;
        },
        topic: 'patch-change',
        receiver: {
            coalesceMs: 1000,
            refresh(keys, run) {
                run(keys);
                if (form && keys.includes('history')) {
                    form.refreshPrevious();
                }
            },
        },
    });

    form = initPatchChangeForm(sections, i18n);
    initLabelCorrection(sections, i18n, () => {
        if (form) {
            form.refreshPrevious();
        }
    });
    initPhaseEnd(sections, i18n);

    localizeTimes(document);
    document.addEventListener('krt:swapped', (event) => {
        localizeTimes(event.detail && event.detail.container ? event.detail.container : document);
    });
}

initAdminPatchChange();
