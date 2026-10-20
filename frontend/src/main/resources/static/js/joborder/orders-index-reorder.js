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

/* global KRT_ORDERS_REORDER_I18N */

import { showFrontendSuccessToast } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtReorder } from '/js/kernel/krt-reorder.js';
import { joborderHooks } from '/js/joborder/joborder-hooks.js';

const QUEUE_ROW = 'tr.draggable-row';

/**
 * Moves a job order to a priority slot, refreshes the queue and toasts the outcome.
 *
 * @param {string | undefined} orderId the id of the moved job order
 * @param {string} targetPriority the priority of the neighbouring order it takes over
 * @returns {Promise<boolean>} whether the server accepted the new priority
 */
async function persistOrderReorder(orderId, targetPriority) {
    const container = document.getElementById('orders-results');
    const res = await krtFetch.write({
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
    if (joborderHooks.refreshOrdersQueue) {
        await joborderHooks.refreshOrdersQueue(
            ['queue'],
            res.ok ? undefined : { broadcast: false },
        );
    } else if (container) {
        await krtFetch.swap({
            url: window.location.pathname + window.location.search,
            container,
            fragmentValue: 'results',
            history: false,
            preserveScroll: true,
        });
    }
    if (res.ok) {
        showFrontendSuccessToast(KRT_ORDERS_REORDER_I18N.success);
    }
    return res.ok;
}

/**
 * Finds the priority a moved order takes over: the one of the neighbour it passed.
 *
 * @param {HTMLElement[]} rows the queue rows in their new order
 * @param {number} from the zero-based position before the move
 * @param {number} to the zero-based position after the move
 * @returns {string | undefined} the target priority, undefined when the order did not move
 */
function targetPriorityOf(rows, from, to) {
    if (to > from) return rows[to - 1]?.dataset.priority;
    if (to < from) return rows[to + 1]?.dataset.priority;
    return undefined;
}

/** Wires the queue's up/down controls, its Alt+Arrow keys and its drag-and-drop reorder. */
function initOrdersReorder() {
    const container = document.getElementById('orders-results');
    if (!container) return;

    krtReorder.attach({
        container,
        itemSelector: QUEUE_ROW,
        async persist({ row, rows, from, to }) {
            const targetPriority = targetPriorityOf(rows, from, to);
            if (!targetPriority) return false;
            return persistOrderReorder(row.dataset.id, targetPriority);
        },
    });

    /** @type {HTMLTableRowElement | null} */
    let draggedRow = null;
    let oldIndex = -1;

    /**
     * @param {Event} e a drag event inside the queue
     * @returns {HTMLTableRowElement | null} the draggable row under the event target
     */
    const rowOf = (e) =>
        /** @type {HTMLTableRowElement | null} */ (
            /** @type {Element} */ (e.target).closest(QUEUE_ROW)
        );

    const queueRows = () =>
        /** @type {HTMLTableRowElement[]} */ (Array.from(container.querySelectorAll(QUEUE_ROW)));

    container.addEventListener('dragstart', (e) => {
        draggedRow = rowOf(e);
        if (!draggedRow) return;

        joborderHooks.ordersDragging = true;
        draggedRow.style.opacity = '0.5';
        if (e.dataTransfer) {
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setData('text/plain', draggedRow.dataset.id ?? '');
        }

        oldIndex = queueRows().indexOf(draggedRow);
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
        const targetPriority = targetPriorityOf(rows, oldIndex, rows.indexOf(draggedRow));
        if (targetPriority) {
            persistOrderReorder(draggedRow.dataset.id, targetPriority);
        }
    });
}

document.addEventListener('DOMContentLoaded', initOrdersReorder);
