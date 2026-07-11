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

/*
 * Job-order queue drag-reorder page module (/orders, LOGISTICIAN only), extracted verbatim from the
 * second, sec:authorize-gated inline script of orders-index.html (ADR-0069, follow-up to #924).
 *
 * Delegates HTML5 drag-and-drop on the stable #orders-results container so it survives a filter
 * fragment swap, and on drop persists the new slot via window.krtFetch.write + a ?fragment=results
 * swap that re-renders the whole re-prioritised queue (#571 / #575). The loading <script th:src> tag
 * carries the SAME sec:authorize="hasRole(...LOGISTICIAN)" as its bootstrap, so neither the strings
 * nor this module reach non-logisticians.
 *
 * KRT_ORDERS_REORDER_I18N is defined by the sec:authorize-gated inline Thymeleaf bootstrap block of
 * orders-index.html, which executes immediately before this classic script.
 */

/* global KRT_ORDERS_REORDER_I18N */

// Bind the drag-reorder to the STABLE #orders-results container (not the <tbody>) so the
// delegation survives a filter fragment swap that replaces the table (#573). The handlers
// already use e.target.closest('tr.draggable-row'), so container-level delegation is equivalent.

// Persist a drag-reorder in place (#571 / #575): krtFetch.write the new slot, then re-render the
// WHOLE queue via a ?fragment=results swap. The backend reshuffles every active order's priority,
// so the swap is mandatory to refresh all siblings' data-priority (a partial DOM patch would make
// the next drag compute a stale target). The swap also reverts the optimistic dragover move when
// the write failed, keeping the visible order consistent with the persisted one.
async function persistOrderReorder(orderId, targetPriority) {
    const container = document.getElementById('orders-results');
    let res = { ok: false };
    if (window.krtFetch) {
        res = await window.krtFetch.write({
            method: 'PUT',
            url: '/orders/' + orderId + '/priority/ajax?priority=' + targetPriority,
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
    // Re-render the whole queue in place AND broadcast the change to peers on success (REQ-FE-015);
    // on a failed persist re-render only (broadcast:false) so a reverted optimistic move never pokes
    // peers. Falls back to the bespoke swap when the live-sync seam is unavailable.
    if (window.krtRefreshOrdersQueue) {
        await window.krtRefreshOrdersQueue(['queue'], res.ok ? undefined : { broadcast: false });
    } else if (container && window.krtFetch && window.krtFetch.swap) {
        await window.krtFetch.swap({
            url: window.location.pathname + window.location.search,
            container: container,
            fragmentValue: 'results',
            history: false,
            preserveScroll: true,
        });
    }
    if (res.ok && typeof window.showFrontendSuccessToast === 'function') {
        window.showFrontendSuccessToast(KRT_ORDERS_REORDER_I18N.success);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const container = document.getElementById('orders-results');
    if (!container) return;

    let draggedRow = null;
    let oldIndex = -1;

    container.addEventListener('dragstart', function (e) {
        draggedRow = e.target.closest('tr.draggable-row');
        if (!draggedRow) return;

        // Flag an in-flight drag so a peer's live-sync `queue` refresh holds back (busyTest in
        // orders-index.js) instead of yanking the rows out from under the drag (REQ-FE-015).
        window.__ordersDragging = true;
        draggedRow.style.opacity = '0.5';
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', draggedRow.dataset.id);

        const rows = Array.from(container.querySelectorAll('tr.draggable-row'));
        oldIndex = rows.indexOf(draggedRow);
    });

    container.addEventListener('dragend', function (_e) {
        window.__ordersDragging = false;
        if (!draggedRow) return;
        draggedRow.style.opacity = '1';
        draggedRow = null;
        oldIndex = -1;
    });

    container.addEventListener('dragover', function (e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';

        const targetRow = e.target.closest('tr.draggable-row');
        if (!targetRow || targetRow === draggedRow) return;

        const bounding = targetRow.getBoundingClientRect();
        const offset = bounding.y + bounding.height / 2;

        if (e.clientY - offset > 0) {
            targetRow.after(draggedRow);
        } else {
            targetRow.before(draggedRow);
        }
    });

    container.addEventListener('drop', function (e) {
        e.preventDefault();
        if (!draggedRow || oldIndex === -1) return;

        const rows = Array.from(container.querySelectorAll('tr.draggable-row'));
        const newIndex = rows.indexOf(draggedRow);

        if (newIndex === oldIndex) return;

        let targetPriority = null;
        if (newIndex > oldIndex) {
            targetPriority = rows[newIndex - 1].dataset.priority;
        } else if (newIndex < oldIndex) {
            targetPriority = rows[newIndex + 1].dataset.priority;
        }

        if (targetPriority) {
            // Read the order id synchronously before the async write (dragend nulls draggedRow).
            persistOrderReorder(draggedRow.dataset.id, targetPriority);
        }
    });
});
