import { showFrontendErrorToast } from '/js/kernel/toast.js';
import { krtFetch } from '/js/kernel/krt-fetch.js';
import { krtI18nText } from '/js/kernel/krt-i18n.js';

/** @typedef {ApiDto<'P4kImportDueDto'>} P4kImportDue */

/**
 * Reads one label of the page's dictionary.
 *
 * @param {string} key the dictionary key
 * @returns {string} the label
 */
function t(key) {
    const dict = window.krtP4kImportI18n || {};
    return krtI18nText(dict[key], `krtP4kImportI18n.${key}`);
}

/**
 * Describes the comparison basis: the last APPLY's patch version, a branch lower bound or none.
 *
 * @param {P4kImportDue} due the due signal
 * @returns {string} the basis text
 */
function basisText(due) {
    if (!due.basisVersion) return t('dueNoRun');
    if (due.basisOrigin === 'BRANCH_LOWER_BOUND')
        return t('dueLowerBound').replace('{0}', due.basisVersion);
    return due.basisVersion;
}

/**
 * Shows or hides the "P4K import due" banner for one due signal (REQ-P4K-047).
 *
 * @param {HTMLElement} banner the banner
 * @param {P4kImportDue | null} due the due signal, or null when there is none
 */
function render(banner, due) {
    banner.hidden = !(due && due.due);
    const detail = document.getElementById('krt-p4k-due-detail');
    if (!due || !detail) return;
    detail.textContent = t('dueDetail')
        .replace('{0}', due.uexVersion || t('dueUnknown'))
        .replace('{1}', due.wikiVersion || t('dueUnknown'))
        .replace('{2}', basisText(due));
}

/**
 * Re-reads the due signal and updates the banner in place; called on load and after an APPLY.
 *
 * @returns {Promise<void>} settles once the banner shows the current signal
 */
export function refreshDueBanner() {
    const banner = document.getElementById('krt-p4k-due');
    if (!banner) return Promise.resolve();
    const base = (window.krtP4kImportEndpoints || {}).page || '/admin/p4k-import';
    return krtFetch
        .getJson(`${base}/due`)
        .then((/** @type {P4kImportDue | null} */ due) => render(banner, due))
        .catch(() => showFrontendErrorToast(t('error')));
}
