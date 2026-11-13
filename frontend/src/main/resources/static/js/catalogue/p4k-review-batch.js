import { krtHtml } from '/js/kernel/krt-html.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { byId as $ } from '/js/catalogue/catalogue-link-admin.js';

/** @typedef {ApiDto<'P4kReviewCaseDto'>} ReviewCase */
/** @typedef {ApiDto<'P4kReviewBatchRequest'>} BatchRequest */
/** @typedef {'KEEP' | 'HIDE'} BatchResolution */
/** @typedef {{ id: string, reason: string, batchable: boolean }} BatchGroup */

export const GROUP_PAGE_SIZE = 100;

/**
 * Manages the selection and confirmation of a bulk review decision on the displayed members page.
 *
 * @param {{pageUrl: (path: string) => string, t: (key: string) => string, sections: KrtPageSections, onChanged: () => void}} config the workspace's localized transport
 * @returns {{ load: (group: BatchGroup | null, rows?: ReviewCase[]) => void, checkbox: (entry: ReviewCase) => KrtHtml | string }} the selection controls
 */
export function createReviewBatch({ pageUrl, t, sections, onChanged }) {
    const toolbar = $('krt-p4k-review-batch');
    /** @type {BatchGroup | null} */
    let group = null;
    /** @type {ReviewCase[]} */
    let rows = [];
    /** @type {Set<string>} */
    const selected = new Set();
    /** @type {BatchResolution | null} */
    let resolution = null;

    /** Renders the actions and the optional preview for the current selection. */
    function render() {
        if (!toolbar) return;
        toolbar.hidden = !group?.batchable || !rows.length;
        if (toolbar.hidden) {
            toolbar.replaceChildren();
            return;
        }
        const count = String(selected.size);
        const preview = resolution
            ? krtHtml`<div class="card card--inset p4k-review-preview" data-testid="p4k-review-batch-preview">
                <h4>${t('reviewPreview')}</h4>
                <p>${t(resolution === 'KEEP' ? 'reviewBatchKeepEffect' : 'reviewBatchHideEffect').replace('{0}', count)}</p>
                <ul class="p4k-review-batch-selected">${rows
                    .filter((row) => row.id && selected.has(row.id))
                    .map(
                        (row) =>
                            krtHtml`<li>${row.nameEn || row.className || row.guid} <span class="data-value--mono">${row.guid}</span></li>`,
                    )}</ul>
                <div class="p4k-review-heading">
                    <button type="button" class="btn btn-ghost" data-review-batch-cancel>${t('reviewCancel')}</button>
                    <button type="button" class="btn btn--cta" data-review-batch-confirm>${t('reviewConfirm')}</button>
                </div>
            </div>`
            : '';
        const hide =
            group?.reason === 'SEEDED_NOT_CREATABLE'
                ? krtHtml`<button type="button" class="btn btn-quiet-danger btn-xs" data-review-batch-action="HIDE">${t('reviewBatchHide')}</button>`
                : '';
        krtHtml.set(
            toolbar,
            krtHtml`<div class="p4k-review-heading">
            <div class="p4k-review-controls">
                <button type="button" class="btn btn-ghost btn-xs" data-review-select-page>${t('reviewSelectPage')}</button>
                <button type="button" class="btn btn-ghost btn-xs" data-review-clear-selection>${t('reviewClearSelection')}</button>
                <span role="status">${t('reviewSelected').replace('{0}', count)}</span>
            </div>
            <div class="p4k-review-controls">
                <button type="button" class="btn btn-ghost btn-xs" data-review-batch-action="KEEP">${t('reviewBatchKeep')}</button>
                ${hide}
            </div>
        </div>${preview}`,
        );
        toolbar
            .querySelectorAll(
                'button[data-review-batch-action], button[data-review-clear-selection]',
            )
            .forEach((button) => {
                if (button instanceof HTMLButtonElement) button.disabled = !selected.size;
            });
    }

    /**
     * Replaces the displayed members and invalidates a preview from the previous version.
     *
     * @param {BatchGroup | null} nextGroup the open group
     * @param {ReviewCase[]} [nextRows] the cases visible on its members page
     */
    function load(nextGroup, nextRows = []) {
        group = nextGroup;
        rows = nextRows;
        selected.clear();
        resolution = null;
        render();
    }

    /**
     * Renders a selectable member's checkbox.
     *
     * @param {ReviewCase} entry the visible case
     * @returns {KrtHtml | string} the case's selection control when batchable
     */
    function checkbox(entry) {
        if (!group?.batchable || !entry.id || entry.version == null) return '';
        const label = `${t('reviewSelectCase')} ${entry.className || entry.nameEn || entry.guid}`;
        return krtHtml`<input type="checkbox" aria-label="${label}" data-review-select="${entry.id}">`;
    }

    /**
     * Applies the reviewed selection using the displayed case versions.
     *
     * @param {HTMLButtonElement} button the confirmation control
     */
    function confirm(button) {
        if (!group?.batchable || !resolution || !selected.size) return;
        /** @type {BatchRequest} */
        const payload = {
            resolution,
            members: rows.flatMap((row) =>
                row.id && row.version != null && selected.has(row.id)
                    ? [{ id: row.id, version: row.version }]
                    : [],
            ),
        };
        sections
            .write({
                sectionKey: 'review',
                method: 'POST',
                url: pageUrl(`/review-workspace/groups/${encodeURIComponent(group.id)}/resolve`),
                payload,
                submitter: button,
                toast: false,
                onConflict() {
                    showFrontendErrorToast(t('conflict'));
                    onChanged();
                    return true;
                },
            })
            .then((result) => {
                if (!result.ok) return;
                showFrontendSuccessToast(t('reviewBatchApplied'));
                onChanged();
            });
    }

    /**
     * Updates the checked rows of the displayed page and drops the old preview.
     *
     * @param {boolean} select whether to select or clear the page
     */
    function setPageSelection(select) {
        $('krt-p4k-review-members')
            ?.querySelectorAll('input[data-review-select]')
            .forEach((input) => {
                if (!(input instanceof HTMLInputElement) || !input.dataset.reviewSelect) return;
                input.checked = select;
                if (select) selected.add(input.dataset.reviewSelect);
            });
        if (!select) selected.clear();
        resolution = null;
        render();
    }

    $('krt-p4k-review-members')?.addEventListener('change', (event) => {
        const input = event.target;
        if (!(input instanceof HTMLInputElement) || !input.dataset.reviewSelect) return;
        if (input.checked) selected.add(input.dataset.reviewSelect);
        else selected.delete(input.dataset.reviewSelect);
        resolution = null;
        render();
    });

    toolbar?.addEventListener('click', (event) => {
        const button = event.target instanceof Element ? event.target.closest('button') : null;
        if (!(button instanceof HTMLButtonElement)) return;
        if (button.hasAttribute('data-review-batch-confirm')) {
            confirm(button);
            return;
        }
        const action = button.dataset.reviewBatchAction;
        if (action === 'KEEP' || action === 'HIDE') {
            resolution = action;
            render();
            const primary = toolbar.querySelector('button[data-review-batch-confirm]');
            if (primary instanceof HTMLButtonElement) primary.focus();
        } else if (button.hasAttribute('data-review-batch-cancel')) {
            const prior = resolution;
            resolution = null;
            render();
            const opener = toolbar.querySelector(`button[data-review-batch-action="${prior}"]`);
            if (opener instanceof HTMLButtonElement) opener.focus();
        } else {
            const select = button.hasAttribute('data-review-select-page');
            setPageSelection(select);
            const next = toolbar.querySelector(
                select ? '[data-review-clear-selection]' : '[data-review-select-page]',
            );
            if (next instanceof HTMLButtonElement) next.focus();
        }
    });

    return { load, checkbox };
}
