import { krtHtml } from '/js/kernel/krt-html.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { showFrontendErrorToast, showFrontendSuccessToast } from '/js/kernel/toast.js';
import { byId as $ } from '/js/catalogue/catalogue-link-admin.js';

/** @typedef {ApiDto<'P4kReviewWorkspaceDetailDto'>} Detail */
/** @typedef {ApiDto<'P4kReviewCandidate'>} Candidate */
/** @typedef {ApiDto<'P4kReviewApplyRequest'>} ApplyRequest */
/** @typedef {[string, string | null | undefined]} Fact */

const DETAIL_TARGET = 'p4k-review-detail';
/** @type {Partial<Record<NonNullable<Candidate['action']>, string>>} */
const OPEN_CASE_TOASTS = {
    CORRECT_P4K_GUID: 'reviewCorrectionQueued',
    CANCEL_SHIP_REPAIR: 'reviewCorrectionCancelled',
};
const SYNTHETIC_GUID_REASONS = [
    'NO_RESOURCE_GROUP',
    'PRODUCT_KEY_UNRESOLVED',
    'PRODUCT_KEY_AMBIGUOUS',
    'STARMAP_LINK_AMBIGUOUS',
    'STARMAP_LINK_NOT_FOUND',
];

/**
 * Builds the detail pane for one open case.
 *
 * @param {{ pageUrl: (path: string) => string, t: (key: string) => string, sections: KrtPageSections, onApplied: () => void, onConflict: () => void }} config the workspace's transport and callbacks
 * @returns {{ open: (id: string) => void, close: (restoreFocus?: boolean) => void, refresh: () => void }} detail pane controls
 */
export function createReviewDetail({ pageUrl, t, sections, onApplied, onConflict }) {
    const panel = $('krt-p4k-review-detail');
    const content = $('krt-p4k-review-detail-content');
    /** @type {Detail | null} */
    let detail = null;
    /** @type {string | null} */
    let caseId = null;
    /** @type {number | null} */
    let selected = null;
    let request = 0;

    /**
     * Renders named fields with values available in the catalogue.
     *
     * @param {Fact[]} fields the translation key and value of each field
     * @returns {KrtHtml} the definition list
     */
    function facts(fields) {
        return krtHtml`<dl class="p4k-review-facts">${fields
            .filter(([, value]) => value)
            .map(
                ([label, value]) => krtHtml`<div><dt>${t(label)}</dt><dd>${value}</dd></div>`,
            )}</dl>`;
    }

    /**
     * Displays the source identities of the compared catalogue entry.
     *
     * @param {Candidate} candidate the catalogue target
     * @returns {KrtHtml} the target facts
     */
    function candidateFacts(candidate) {
        return facts([
            [
                detail?.review?.kind === 'MANUFACTURER' ? 'reviewCode' : 'reviewClass',
                candidate.className,
            ],
            ['reviewP4kGuid', candidate.p4kGuid],
            ['reviewWikiUuid', candidate.scwikiUuid],
            ['reviewUexIds', candidate.uexIds?.join(', ')],
        ]);
    }

    /**
     * Renders a candidate and its match and conflict signals.
     *
     * @param {Candidate} candidate the catalogue target
     * @param {number} index the candidate position in the loaded comparison
     * @returns {KrtHtml} the target card
     */
    function candidateCard(candidate, index) {
        const action = candidate.action || 'INSPECT';
        const preview =
            action === 'INSPECT' || !candidate.targetId
                ? ''
                : krtHtml`<button class="btn btn-ghost btn-xs" type="button" data-review-preview="${index}">${t(
                      'reviewPreview',
                  )}</button>`;
        return krtHtml`<li class="card card--inset p4k-review-candidate" data-testid="p4k-review-candidate">
            <div class="p4k-review-heading"><h5>${candidate.name || t('noRow')}</h5>${preview}</div>
            ${candidateFacts(candidate)}
            <p class="form-hint">${t(`reviewAction.${action}`)}</p>
            <ul class="p4k-review-evidence">${(candidate.evidence || []).map(
                (signal) => krtHtml`<li>${t(`reviewEvidence.${signal}`)}</li>`,
            )}</ul>
        </li>`;
    }

    /** Renders the current case and any confirmed preview target. */
    function render() {
        if (!content || !detail?.review || !detail.options) return;
        const { review, options } = detail;
        const candidates = options.candidates || [];
        const target = selected === null ? null : candidates[selected];
        const action = target?.action;
        const preview =
            action && target && action !== 'INSPECT'
                ? krtHtml`<div class="card card--inset p4k-review-preview" data-testid="p4k-review-preview">
                <h4>${t('reviewPreview')}</h4>
                <p class="data-value">${t(`reviewAction.${action}`)} · ${target.name || target.targetId}</p>
                ${candidateFacts(target)}
                <p>${t(`reviewEffect.${action}`)}</p>
                <div class="p4k-review-heading">
                    <button type="button" class="btn btn-ghost" data-review-cancel>${t('reviewCancel')}</button>
                    <button type="button" class="btn btn--cta" data-review-confirm>${t('reviewConfirm')}</button>
                </div>
            </div>`
                : '';
        krtHtml.set(
            content,
            krtHtml`<div class="p4k-review-heading">
                <h4>${review.nameEn || review.className || review.rowName || review.guid}</h4>
                <span class="form-hint">${t(`reviewReadiness.${options.readiness}`)}</span>
            </div>
            ${facts([
                [
                    SYNTHETIC_GUID_REASONS.includes(review.reason || '')
                        ? 'reviewCaseKey'
                        : 'reviewP4kGuid',
                    review.guid,
                ],
                [review.kind === 'MANUFACTURER' ? 'reviewCode' : 'reviewClass', review.className],
                ['reviewRecordPath', detail.recordPath],
                [
                    'reviewContextPath',
                    options.contextPath === detail.recordPath ? null : options.contextPath,
                ],
                ['reviewCurrentRow', review.rowName || review.rowId],
                ['reviewOtherRow', review.otherRowId],
            ])}
            ${preview}
            <h4>${t('reviewCandidates')}</h4>
            ${
                candidates.length
                    ? krtHtml`<ul class="p4k-review-candidates">${candidates.map(candidateCard)}</ul>`
                    : krtHtml`<p class="form-hint">${t('reviewNoCandidates')}</p>`
            }`,
        );
    }

    /**
     * Loads the comparison for the selected case and discards the prior preview.
     *
     * @param {string} id the case
     */
    async function load(id) {
        const requestId = ++request;
        selected = null;
        detail = null;
        if (content) krtHtml.set(content, t('summaryRunning'));
        const response = await krtFetch
            .get(pageUrl(`/review-workspace/${encodeURIComponent(id)}`), {
                key: DETAIL_TARGET,
                accept: 'application/json',
            })
            .catch(() => null);
        if (caseId !== id || requestId !== request) return;
        if (response?.status === 404) {
            close();
            return;
        }
        /** @type {Detail | null} */
        const value = response?.ok ? await response.json().catch(() => null) : null;
        if (caseId !== id || requestId !== request) return;
        if (!value?.review || !value.options) {
            if (content) krtHtml.set(content, t('reviewDetailUnavailable'));
            return;
        }
        detail = value;
        render();
    }

    /**
     * Opens a case and moves keyboard focus to its comparison.
     *
     * @param {string} id the selected case
     */
    function open(id) {
        if (!panel) return;
        caseId = id;
        panel.hidden = false;
        panel.focus();
        load(id);
    }

    /**
     * Closes the comparison and optionally restores focus to the opening control.
     *
     * @param {boolean} [restoreFocus] whether to restore keyboard focus
     */
    function close(restoreFocus = false) {
        if (restoreFocus && caseId) {
            const button = document.querySelector(`[data-review-detail="${CSS.escape(caseId)}"]`);
            if (button instanceof HTMLButtonElement) button.focus();
        }
        request++;
        caseId = null;
        selected = null;
        detail = null;
        if (content) content.replaceChildren();
        if (panel) panel.hidden = true;
    }

    /** Refreshes the case comparison after a queue update. */
    function refresh() {
        if (caseId) load(caseId);
    }

    /**
     * Sends the selected action with the versions displayed in the preview.
     *
     * @param {HTMLButtonElement} button the confirmation control
     */
    function apply(button) {
        const candidate = selected === null ? null : detail?.options?.candidates?.[selected];
        if (
            !caseId ||
            !detail?.review ||
            !candidate?.targetId ||
            !candidate.action ||
            candidate.action === 'INSPECT' ||
            detail.review.version == null
        )
            return;
        /** @type {ApplyRequest} */
        const payload = {
            action: candidate.action,
            targetId: candidate.targetId,
            targetVersion: candidate.targetVersion ?? undefined,
            subjectVersion: candidate.subjectVersion ?? undefined,
            version: detail.review.version,
        };
        const appliedCase = caseId;
        const followUp = OPEN_CASE_TOASTS[candidate.action];
        sections
            .write({
                sectionKey: 'review',
                method: 'POST',
                url: pageUrl(`/review-workspace/${encodeURIComponent(caseId)}/apply`),
                payload,
                submitter: button,
                toast: false,
                onConflict() {
                    showFrontendErrorToast(t('conflict'));
                    onConflict();
                    return true;
                },
            })
            .then((result) => {
                if (!result.ok) return;
                if (caseId === appliedCase && !followUp) close();
                showFrontendSuccessToast(t(followUp || 'reviewApplied'));
                onApplied();
            });
    }

    panel?.addEventListener('click', (event) => {
        const button = event.target instanceof Element ? event.target.closest('button') : null;
        if (!(button instanceof HTMLButtonElement)) return;
        if (button.hasAttribute('data-review-close')) {
            close(true);
        } else if (button.hasAttribute('data-review-cancel')) {
            const index = selected;
            selected = null;
            render();
            const opener = content?.querySelector(`button[data-review-preview="${index}"]`);
            if (opener instanceof HTMLButtonElement) opener.focus();
        } else if (button.hasAttribute('data-review-preview')) {
            selected = Number(button.dataset.reviewPreview);
            render();
            const confirm = content?.querySelector('button[data-review-confirm]');
            if (confirm instanceof HTMLButtonElement) confirm.focus();
        } else if (button.hasAttribute('data-review-confirm')) {
            apply(button);
        }
    });

    return { open, close, refresh };
}
