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

/* global KRT_ORDERS_REORDER_I18N */

import { showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

/**
 * Moves a job order to a priority slot, refreshes the queue and toasts the outcome.
 *
 * @param {string | undefined} orderId the id of the dragged job order
 * @param {string} targetPriority the priority of the neighbouring order it takes over
 */
async function persistOrderReorder(orderId, targetPriority) {
    const container = document.getElementById('orders-results');
    let res = { ok: false };
    if (krtFetch) {
        res = await krtFetch.write({
            method: 'PUT',
            url: `/orders/${orderId}/priority/ajax?priority=${targetPriority}`,
            toast: false,
            errorMessage: KRT_ORDERS_REORDER_I18N.error,
            conflict: {
                title: KRT_ORDERS_REORDER_I18N.conflict.title,
                reloadLabel: KRT_ORDERS_REORDER_I18N.conflict.reloadLabel,
                reloadQuestion: KRT_ORDERS_REORDER_I18N.conflict.reloadQuestion,
                dismissLabel: KRT_ORDERS_REORDER_I18N.conflict.dismissLabel,
                reloadDetailFallback: KRT_ORDERS_REORDER_I18N.conflict.detail,
            },
        });
    }
    if (joborderHooks.refreshOrdersQueue) {
        await joborderHooks.refreshOrdersQueue(
            ['queue'],
            res.ok ? undefined : { broadcast: false },
        );
    } else if (container && krtFetch && krtFetch.swap) {
        await krtFetch.swap({
            url: window.location.pathname + window.location.search,
            container,
            fragmentValue: 'results',
            history: false,
            preserveScroll: true,
        });
    }
    if (res.ok && typeof showFrontendSuccessToast === 'function') {
        showFrontendSuccessToast(KRT_ORDERS_REORDER_I18N.success);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const container = document.getElementById('orders-results');
    if (!container) return;

    /** @type {HTMLTableRowElement | null} */
    let draggedRow = null;
    let oldIndex = -1;

    /**
     * @param {Event} e a drag event inside the queue
     * @returns {HTMLTableRowElement | null} the draggable row under the event target
     */
    const rowOf = (e) =>
        /** @type {HTMLTableRowElement | null} */ (
            /** @type {Element} */ (e.target).closest('tr.draggable-row')
        );

    const queueRows = () =>
        /** @type {HTMLTableRowElement[]} */ (
            Array.from(container.querySelectorAll('tr.draggable-row'))
        );

    container.addEventListener('dragstart', (e) => {
        draggedRow = rowOf(e);
        if (!draggedRow) return;

        joborderHooks.ordersDragging = true;
        draggedRow.style.opacity = '0.5';
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setData('text/plain', draggedRow.dataset.id ?? '');
        }

        const rows = queueRows();
        oldIndex = rows.indexOf(draggedRow);
    });

    container.addEventListener('dragend', (_e) => {
        joborderHooks.ordersDragging = false;
        if (!draggedRow) return;
        draggedRow.style.opacity = '1';
        draggedRow = null;
        oldIndex = -1;
    });

    container.addEventListener('dragover', (e) => {
        e.preventDefault();
        if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';

        const targetRow = rowOf(e);
        if (!targetRow || !draggedRow || targetRow === draggedRow) return;

        const bounding = targetRow.getBoundingClientRect();
        const offset = bounding.y + bounding.height / 2;

        if (e.clientY - offset > 0) {
            targetRow.after(draggedRow);
        } else {
            targetRow.before(draggedRow);
        }
    });

    container.addEventListener('drop', (e) => {
        e.preventDefault();
        if (!draggedRow || oldIndex === -1) return;

        const rows = queueRows();
        const newIndex = rows.indexOf(draggedRow);

        if (newIndex === oldIndex) return;

        /** @type {string | undefined} */
        let targetPriority;
        if (newIndex > oldIndex) {
            targetPriority = rows[newIndex - 1].dataset.priority;
        } else if (newIndex < oldIndex) {
            targetPriority = rows[newIndex + 1].dataset.priority;
        }

        if (targetPriority) {
            persistOrderReorder(draggedRow.dataset.id, targetPriority);
        }
    });
});
