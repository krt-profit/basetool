import { krtHtml } from '/js/kernel/krt-html.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import {
    bindPager,
    pagerLabels,
    reloadedBeyondEnd,
    renderPager,
} from '/js/catalogue/catalogue-pager.js';
import { byId as $, filterValue, forgetRead } from '/js/catalogue/catalogue-link-admin.js';
import { formatTimestamp } from '/js/catalogue/catalogue-time.js';
import { createReviewDetail } from '/js/catalogue/p4k-review-detail.js';
import { createReviewBatch, GROUP_PAGE_SIZE } from '/js/catalogue/p4k-review-batch.js';

/** @typedef {ApiDto<'P4kReviewCaseDto'>} ReviewCase */
/** @typedef {ApiDto<'P4kReviewGroupDto'>} ReviewGroup */
/** @typedef {{ id: string, name: string }} Assignment */
/** @typedef {{ id: string, name: string, reason: string, batchable: boolean, page: number }} OpenGroup */

const WORKSPACE = '/review-workspace';
const GROUP_TARGET = 'p4k-review-group';
const QUEUE_TARGET = 'p4k-review-queue';
const FILTER_STORAGE_KEY = 'p4k_review_filters';
const FILTER_IDS = {
    kind: 'krt-p4k-review-kind',
    reason: 'krt-p4k-review-reason',
    readiness: 'krt-p4k-review-readiness',
};

/** @typedef {keyof typeof FILTER_IDS} FilterKey */
/** @typedef {Partial<Record<FilterKey, string>>} SavedFilters */

/**
 * Reads the review queue selections saved in this browser.
 *
 * @returns {SavedFilters} the saved values, or the rendered defaults when storage is unavailable
 */
function savedFilters() {
    try {
        const saved = /** @type {unknown} */ (
            JSON.parse(localStorage.getItem(FILTER_STORAGE_KEY) || '{}')
        );
        return saved && typeof saved === 'object' ? /** @type {SavedFilters} */ (saved) : {};
    } catch {
        return {};
    }
}

/**
 * Reads the three current selections for persistence and the queue request.
 *
 * @returns {Record<FilterKey, string>} the current filters
 */
function currentFilters() {
    return {
        kind: filterValue(FILTER_IDS.kind),
        reason: filterValue(FILTER_IDS.reason),
        readiness: filterValue(FILTER_IDS.readiness),
    };
}

/** Saves the current review selections without interrupting the queue when storage is denied. */
function rememberFilters() {
    try {
        localStorage.setItem(FILTER_STORAGE_KEY, JSON.stringify(currentFilters()));
    } catch {
        return;
    }
}

const READINESS = ['ACTIONABLE', 'CHECK_REQUIRED', 'BLOCKED', 'WAITING_FOR_IMPORT'];
const RESOURCE_JOIN_REASONS = ['RESOURCE_JOIN_NONE', 'RESOURCE_JOIN_SEVERAL'];

/**
 * Manages the grouped queue, individual review cases and assignment choices.
 *
 * @param {{pageUrl: (path: string) => string, readJson: (url: string, key: string) => Promise<any>, t: (key: string) => string, sections: KrtPageSections, kinds: string[], reasons: string[]}} config the page's localized transport
 * @returns {{ init: () => void, refresh: (reloadAssignments?: boolean) => void }} the workspace lifecycle
 */
export function createReviewWorkspace({ pageUrl, readJson, t, sections, kinds, reasons }) {
    let queuePage = 0;
    /** @type {OpenGroup | null} */
    let openGroup = null;
    /** @type {KrtHtml[]} */
    let groupOptions = [];
    /** @type {KrtHtml[]} */
    let materialOptions = [];

    const batch = createReviewBatch({ pageUrl, t, sections, onChanged: refresh });
    const detail = createReviewDetail({
        pageUrl,
        t,
        sections,
        onApplied: refresh,
        onConflict: refresh,
    });

    /**
     * Renders a case's legacy resolution controls alongside the candidate comparison.
     *
     * @param {ReviewCase} entry the open case
     * @returns {KrtHtml} the row's actions
     */
    function caseActions(entry) {
        const compare = krtHtml`<button class="btn btn-ghost btn-xs" type="button"
            data-review-detail="${entry.id}" aria-controls="krt-p4k-review-detail">${t('reviewDetails')}</button>`;
        const keep = krtHtml`<button type="button" class="btn btn-ghost btn-xs" data-resolution="KEEP">${t(
            'actionKeep',
        )}</button>`;
        const assign = krtHtml`<button type="button" class="btn btn-ghost btn-xs" data-resolution="ASSIGN">${t(
            'actionAssign',
        )}</button>`;
        if (entry.reason === 'NO_RESOURCE_GROUP') {
            return krtHtml`${compare} <select class="form-input" data-group-pick aria-label="${t(
                'pickGroup',
            )}"><option value="">${t('pickGroup')}</option>${groupOptions}</select> ${assign}`;
        }
        if (RESOURCE_JOIN_REASONS.includes(entry.reason || '')) {
            return krtHtml`${compare} <select class="form-input" data-material-pick aria-label="${t(
                'pickMaterial',
            )}"><option value="">${t('pickMaterial')}</option>${materialOptions}</select> ${assign} ${keep}`;
        }
        return krtHtml`${compare} ${keep}
            <button type="button" class="btn btn-quiet-danger btn-xs" data-resolution="HIDE">${t(
                'actionHide',
            )}</button>`;
    }

    /**
     * Renders the current readiness of a review group or singleton case.
     *
     * @param {string | null} [readiness] the evaluated group status
     * @returns {KrtHtml | string} the status label when available
     */
    function readinessLabel(readiness) {
        return readiness
            ? krtHtml`<span class="cell-sub">${t(`reviewReadiness.${readiness}`)}</span>`
            : '';
    }

    /**
     * Renders an individual review case, including its optimistic version.
     *
     * @param {ReviewCase} entry the open case
     * @param {boolean} [member] whether this row is inside the open group
     * @param {string | null} [readiness] the status of a one-case group
     * @returns {KrtHtml} the table row
     */
    function caseRow(entry, member = false, readiness) {
        const name = entry.nameEn || entry.className || entry.rowName || entry.guid;
        const classLine =
            entry.className && entry.className !== name
                ? krtHtml`<span class="cell-sub p4k-review-source-path">${entry.className}</span>`
                : '';
        return krtHtml`<tr data-testid="p4k-case-row" data-case-id="${entry.id}" data-case-version="${String(
            entry.version,
        )}"><td>${t(`kind.${entry.kind}`)}</td><td>${t(`reason.${entry.reason}`)}${readinessLabel(readiness)}</td><td><span class="cell-title">${name}</span>
            ${classLine}<span class="cell-sub data-value--mono">${entry.guid}</span></td><td>${
                entry.rowName || t('noRow')
            }</td><td>${formatTimestamp(entry.createdAt)}</td><td class="cell-actions">${member ? batch.checkbox(entry) : ''} ${caseActions(entry)}</td></tr>`;
    }

    /**
     * Shows a one-case group directly and opens larger groups on demand.
     *
     * @param {ReviewGroup} group the grouped review cases
     * @returns {KrtHtml} the group or case row
     */
    function groupRow(group) {
        if (group.count === 1 && group.representative)
            return caseRow(group.representative, false, group.readiness);
        return krtHtml`<tr data-testid="p4k-review-group"><td>${t(`kind.${group.kind}`)}</td>
            <td>${t(`reason.${group.reason}`)}${readinessLabel(group.readiness)}</td>
            <td><span class="cell-title">${group.name}</span>
            <span class="cell-sub p4k-review-source-path">${group.contextPath}</span>
            <span class="cell-sub">${t('reviewGroupCount').replace('{0}', String(group.count))}</span></td>
            <td></td><td>${formatTimestamp(group.representative?.createdAt)}</td>
            <td class="cell-actions"><button type="button" class="btn btn-ghost btn-xs"
                data-review-group="${group.id}" data-group-name="${group.name}"
                data-group-reason="${group.reason}" data-group-batchable="${String(group.batchable)}"
                aria-expanded="${openGroup?.id === group.id ? 'true' : 'false'}"
                aria-controls="krt-p4k-review-group">${t('reviewGroupOpen')}</button></td></tr>`;
    }

    /**
     * Reads a page of the filtered review queue.
     *
     * @param {number} page the zero-based queue page
     */
    function loadQueue(page) {
        queuePage = page;
        const query = new URLSearchParams({
            page: String(page),
            ...currentFilters(),
        });
        readJson(pageUrl(`${WORKSPACE}?${query}`), QUEUE_TARGET).then(
            (/** @type {ApiPage<'P4kReviewGroupDto'> | null} */ result) => {
                if (!result || reloadedBeyondEnd(page, result, loadQueue)) return;
                const rows = result.content || [];
                const list = $('krt-p4k-cases');
                if (list) krtHtml.set(list, rows.map(groupRow));
                const empty = $('krt-p4k-cases-empty');
                if (empty) empty.hidden = rows.length > 0;
                renderPager($('krt-p4k-cases-pager'), result, pagerLabels(t));
            },
        );
    }

    /** Clears a group comparison and discards any response still in flight. */
    function closeGroup() {
        if (openGroup) {
            const opener = document.querySelector(
                `[data-review-group="${CSS.escape(openGroup.id)}"]`,
            );
            if (opener instanceof HTMLButtonElement) opener.setAttribute('aria-expanded', 'false');
        }
        openGroup = null;
        batch.load(null);
        forgetRead(GROUP_TARGET);
        const panel = $('krt-p4k-review-group');
        if (panel) panel.hidden = true;
    }

    /**
     * Loads one page of the selected group's current members.
     *
     * @param {number} page the zero-based members page
     */
    function loadMembers(page) {
        if (!openGroup) return;
        const { id } = openGroup;
        openGroup.page = page;
        batch.load(openGroup);
        $('krt-p4k-review-members')?.replaceChildren();
        readJson(
            pageUrl(
                `${WORKSPACE}/groups/${encodeURIComponent(id)}?page=${page}&size=${GROUP_PAGE_SIZE}`,
            ),
            GROUP_TARGET,
        ).then((/** @type {ApiPage<'P4kReviewCaseDto'> | null} */ result) => {
            if (!result || openGroup?.id !== id || reloadedBeyondEnd(page, result, loadMembers))
                return;
            const cases = result.content || [];
            if ((result.totalElements || 0) <= 1) {
                closeGroup();
                return;
            }
            batch.load(openGroup, cases);
            const list = $('krt-p4k-review-members');
            if (list)
                krtHtml.set(
                    list,
                    cases.map((entry) => caseRow(entry, true)),
                );
            renderPager($('krt-p4k-review-members-pager'), result, pagerLabels(t));
        });
    }

    /**
     * Opens the selected group or collapses the current group.
     *
     * @param {HTMLButtonElement} opener the group button
     */
    function toggleGroup(opener) {
        const id = opener.dataset.reviewGroup;
        if (!id) return;
        const wasOpen = openGroup?.id === id;
        closeGroup();
        detail.close();
        if (wasOpen) return;
        openGroup = {
            id,
            name: opener.dataset.groupName || '',
            reason: opener.dataset.groupReason || '',
            batchable: opener.dataset.groupBatchable === 'true',
            page: 0,
        };
        const panel = $('krt-p4k-review-group');
        const heading = $('krt-p4k-review-group-title');
        if (heading) heading.textContent = openGroup.name;
        if (panel) {
            panel.hidden = false;
            panel.focus();
        }
        opener.setAttribute('aria-expanded', 'true');
        loadMembers(0);
    }

    /**
     * Reads available resources as select options.
     *
     * @param {string} path the options endpoint
     * @returns {Promise<KrtHtml[] | null>} the option markup
     */
    function loadOptions(path) {
        return readJson(pageUrl(path), `p4k-review-options:${path}`).then(
            (/** @type {Assignment[] | null} */ rows) =>
                Array.isArray(rows)
                    ? rows.map((row) => krtHtml`<option value="${row.id}">${row.name}</option>`)
                    : null,
        );
    }

    /**
     * Reloads the queue and any open comparison after a local or peer change.
     *
     * @param {boolean} [reloadAssignments] whether import-created assignment targets may have changed
     */
    function refresh(reloadAssignments = false) {
        if (reloadAssignments) {
            Promise.all([loadOptions('/resource-groups'), loadOptions('/materials')]).then(
                ([groups, materials]) => {
                    groupOptions = groups || groupOptions;
                    materialOptions = materials || materialOptions;
                    refresh();
                },
            );
            return;
        }
        loadQueue(queuePage);
        if (openGroup) loadMembers(openGroup.page);
        detail.refresh();
    }

    /**
     * Resolves an individual case using its version and the chosen assignment.
     *
     * @param {HTMLButtonElement} button the resolution control
     */
    function resolve(button) {
        const row = button.closest('tr[data-case-id]');
        if (!(row instanceof HTMLElement) || !row.dataset.caseId) return;
        const resolution = button.dataset.resolution;
        const category = row.querySelector('select[data-group-pick]');
        const material = row.querySelector('select[data-material-pick]');
        const categoryId = category instanceof HTMLSelectElement ? category.value : '';
        const materialId = material instanceof HTMLSelectElement ? material.value : '';
        if (resolution === 'ASSIGN' && !categoryId && !materialId) {
            const picker = category || material;
            if (picker instanceof HTMLSelectElement) picker.focus();
            return;
        }
        sections
            .write({
                sectionKey: 'review',
                method: 'POST',
                url: pageUrl(`/review-cases/${encodeURIComponent(row.dataset.caseId)}/resolve`),
                payload: {
                    resolution,
                    categoryId: categoryId || null,
                    materialId: materialId || null,
                    version: Number(row.dataset.caseVersion),
                },
                submitter: button,
                toast: false,
                onConflict() {
                    showFrontendErrorToast(t('conflict'));
                    refresh();
                    return true;
                },
            })
            .then((result) => {
                if (!result.ok) return;
                const key =
                    resolution === 'ASSIGN'
                        ? 'toastAssigned'
                        : resolution === 'HIDE'
                          ? 'toastHidden'
                          : 'toastKept';
                showFrontendSuccessToast(t(key));
                detail.close();
                refresh();
            });
    }

    /**
     * Delegates clicks on group and case rows.
     *
     * @param {MouseEvent} event the table click
     */
    function onClick(event) {
        const button = event.target instanceof Element ? event.target.closest('button') : null;
        if (!(button instanceof HTMLButtonElement)) return;
        if (button.dataset.reviewGroup) toggleGroup(button);
        else if (button.dataset.reviewDetail) detail.open(button.dataset.reviewDetail);
        else if (button.dataset.resolution) resolve(button);
    }

    /**
     * Fills a review filter and reloads the first page when it changes.
     *
     * @param {FilterKey} key the queue filter
     * @param {string[]} values the selectable values
     * @param {string} labelPrefix the translation key prefix
     * @param {string | undefined} saved the last value in this browser
     */
    function filter(key, values, labelPrefix, saved) {
        const select = $(FILTER_IDS[key]);
        if (!(select instanceof HTMLSelectElement)) return;
        values.forEach((value) => select.add(new Option(t(`${labelPrefix}.${value}`), value)));
        if (saved && values.includes(saved)) select.value = saved;
        select.addEventListener('change', () => {
            rememberFilters();
            closeGroup();
            detail.close();
            loadQueue(0);
        });
    }

    /** Binds the workspace controls and loads the first queue page. */
    function init() {
        const saved = savedFilters();
        filter('kind', kinds, 'kind', saved.kind);
        filter('reason', reasons, 'reason', saved.reason);
        filter('readiness', READINESS, 'reviewReadiness', saved.readiness);
        $('krt-p4k-cases')?.addEventListener('click', onClick);
        $('krt-p4k-review-members')?.addEventListener('click', onClick);
        $('krt-p4k-review-close-group')?.addEventListener('click', () => {
            const id = openGroup?.id;
            closeGroup();
            detail.close();
            const opener = id
                ? document.querySelector(`[data-review-group="${CSS.escape(id)}"]`)
                : null;
            if (opener instanceof HTMLButtonElement) opener.focus();
        });
        bindPager($('krt-p4k-cases-pager'), (page) => {
            closeGroup();
            detail.close();
            loadQueue(page);
        });
        bindPager($('krt-p4k-review-members-pager'), loadMembers);
        refresh(true);
    }

    return { init, refresh };
}
