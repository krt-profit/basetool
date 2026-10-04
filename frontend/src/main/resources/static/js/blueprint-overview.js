// @ts-check
(function () {
    'use strict';

    const RESULTS_ID = 'bp-overview-results';
    const CHIP_LIMIT = 4;
    const MAX_PARALLEL = 4;

    /** @type {Map<string, BpoOwner[] | null>} */
    const ownersByKey = new Map();
    /** @type {HTMLElement[]} */
    const queue = [];
    let running = 0;
    /** @type {IntersectionObserver | null} */
    let observer = null;

    /**
     * @typedef {{ ownerName: string, orgUnitMember?: boolean }} BpoOwner
     */

    function config() {
        return window.krtBlueprintOverview || {};
    }

    function i18n() {
        return config().i18n || {};
    }

    /**
     * Creates a detached element with an optional class and text content.
     *
     * @param {string} tag element tag name
     * @param {string | null} [cls] class attribute to set when truthy
     * @param {string | null} [text] text content to set when not null/undefined
     * @returns {HTMLElement} the new, unattached element
     */
    function el(tag, cls, text) {
        const node = document.createElement(tag);
        if (cls) {
            node.className = cls;
        }
        if (text != null) {
            node.textContent = text;
        }
        return node;
    }

    /**
     * Removes every child of `node`.
     *
     * @param {Node} node the node to empty
     * @returns {void}
     */
    function clear(node) {
        while (node.firstChild) {
            node.removeChild(node.firstChild);
        }
    }

    /**
     * Builds the same-origin owners URL of one availability row.
     *
     * @param {string} productKey the row's variant family key
     * @returns {string} the owners endpoint URL
     */
    function ownersUrl(productKey) {
        const base = config().ownersUrl || '';
        const sep = base.indexOf('?') === -1 ? '?' : '&';
        const url = base + sep + 'productKey=' + encodeURIComponent(productKey);
        return window.safeSameOriginUrl ? window.safeSameOriginUrl(url, url) : url;
    }

    /**
     * Renders one owner as a chip; an owner reached only through global sharing is marked.
     *
     * @param {BpoOwner} owner the owner to show
     * @returns {HTMLElement} the chip
     */
    function ownerChip(owner) {
        const chip = el('span', 'chip bpo-chip', owner.ownerName);
        if (owner.orgUnitMember === false) {
            chip.classList.add('bpo-chip--external');
            const tip = i18n().notMemberHint;
            if (tip) {
                chip.title = tip;
            }
            chip.appendChild(
                el(
                    'span',
                    'visually-hidden',
                    ' (' +
                        window.krtI18nText(
                            i18n().notMember,
                            'krtBlueprintOverview.i18n.notMember',
                        ) +
                        ')',
                ),
            );
        }
        return chip;
    }

    /**
     * Returns the companion details row a chip cell expands.
     *
     * @param {HTMLElement} cell the `.bpo-chips` cell
     * @returns {HTMLElement | null} the details row, or null when missing
     */
    function detailsRowOf(cell) {
        const id = cell.getAttribute('data-more-for');
        return id ? document.getElementById(id) : null;
    }

    /**
     * Fills a row's chip cell with the first owners and the „+n weitere" toggle.
     *
     * @param {HTMLElement} cell the `.bpo-chips` cell
     * @param {BpoOwner[]} owners the row's owners
     * @returns {void}
     */
    function renderChips(cell, owners) {
        clear(cell);
        cell.setAttribute('data-loaded', 'true');
        if (owners.length === 0) {
            cell.appendChild(
                el(
                    'span',
                    'bpo-note',
                    window.krtI18nText(i18n().empty, 'krtBlueprintOverview.i18n.empty'),
                ),
            );
            return;
        }
        owners.slice(0, CHIP_LIMIT).forEach(function (owner) {
            cell.appendChild(ownerChip(owner));
        });
        const rest = owners.slice(CHIP_LIMIT);
        const detailsRow = detailsRowOf(cell);
        if (rest.length === 0 || !detailsRow) {
            return;
        }
        const panel = detailsRow.querySelector('.bp-owners-panel');
        if (panel) {
            clear(panel);
            const list = el('div', 'bpo-chip-list');
            rest.forEach(function (owner) {
                list.appendChild(ownerChip(owner));
            });
            panel.appendChild(list);
        }
        const moreLabel = window
            .krtI18nText(i18n().more, 'krtBlueprintOverview.i18n.more')
            .replace('{0}', String(rest.length));
        const toggle = /** @type {HTMLButtonElement} */ (
            el('button', 'chip chip--primary bpo-more', moreLabel)
        );
        toggle.type = 'button';
        toggle.setAttribute('aria-expanded', 'false');
        toggle.setAttribute('aria-controls', detailsRow.id);
        toggle.setAttribute('data-testid', 'bp-overview-more');
        toggle.addEventListener('click', function () {
            const open = !detailsRow.classList.contains('bp-expanded');
            detailsRow.classList.toggle('bp-expanded', open);
            toggle.setAttribute('aria-expanded', String(open));
            toggle.textContent = open
                ? window.krtI18nText(i18n().less, 'krtBlueprintOverview.i18n.less')
                : moreLabel;
        });
        cell.appendChild(toggle);
    }

    /**
     * Shows a loading or error line in a chip cell.
     *
     * @param {HTMLElement} cell the `.bpo-chips` cell
     * @param {'loading' | 'error'} state which line to show
     * @returns {void}
     */
    function renderState(cell, state) {
        clear(cell);
        cell.setAttribute('data-loaded', state === 'error' ? 'false' : 'pending');
        cell.appendChild(
            el(
                'span',
                state === 'error' ? 'bpo-note bpo-note--error' : 'bpo-note',
                window.krtI18nText(i18n()[state], 'krtBlueprintOverview.i18n.' + state),
            ),
        );
    }

    /**
     * Starts queued owner reads until {@link MAX_PARALLEL} are in flight.
     *
     * @returns {void}
     */
    function pump() {
        while (running < MAX_PARALLEL && queue.length > 0) {
            const cell = queue.shift();
            if (cell) {
                load(cell);
            }
        }
    }

    /**
     * Reads one row's owners, from the cache when this key was read before.
     *
     * @param {HTMLElement} cell the `.bpo-chips` cell
     * @returns {void}
     */
    function load(cell) {
        const row = cell.closest('tr');
        const productKey = row ? row.getAttribute('data-product-key') : null;
        if (!productKey) {
            return;
        }
        const cached = ownersByKey.get(productKey);
        if (cached) {
            renderChips(cell, cached);
            return;
        }
        running++;
        fetch(ownersUrl(productKey), {
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        })
            .then(function (resp) {
                return resp.ok ? resp.json() : null;
            })
            .then(function (owners) {
                if (!Array.isArray(owners)) {
                    renderState(cell, 'error');
                    return;
                }
                ownersByKey.set(productKey, owners);
                if (cell.isConnected) {
                    renderChips(cell, owners);
                }
            })
            .catch(function () {
                renderState(cell, 'error');
            })
            .finally(function () {
                running--;
                pump();
            });
    }

    /**
     * Queues a chip cell for loading unless it is loaded or already waiting.
     *
     * @param {HTMLElement} cell the `.bpo-chips` cell
     * @returns {void}
     */
    function enqueue(cell) {
        if (cell.getAttribute('data-loaded') !== 'false') {
            return;
        }
        renderState(cell, 'loading');
        queue.push(cell);
        pump();
    }

    /**
     * Wires every chip cell under `root`: cells load once they scroll into view.
     *
     * @param {ParentNode} root the subtree holding the rows
     * @returns {void}
     */
    function wireRows(root) {
        const cells = /** @type {NodeListOf<HTMLElement>} */ (
            root.querySelectorAll('.bpo-chips[data-loaded="false"]')
        );
        if (typeof IntersectionObserver !== 'function') {
            cells.forEach(enqueue);
            return;
        }
        if (!observer) {
            observer = new IntersectionObserver(
                function (entries, obs) {
                    entries.forEach(function (entry) {
                        if (entry.isIntersecting) {
                            obs.unobserve(entry.target);
                            enqueue(/** @type {HTMLElement} */ (entry.target));
                        }
                    });
                },
                { rootMargin: '200px 0px' },
            );
        }
        const obs = observer;
        cells.forEach(function (cell) {
            obs.observe(cell);
        });
    }

    /**
     * Builds the swap URL of the search form, keeping the active page size.
     *
     * @param {HTMLFormElement} form the search form
     * @returns {string} the list URL to swap in
     */
    function buildSearchUrl(form) {
        const input = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[type="search"]')
        );
        const params = new URLSearchParams();
        const search = input ? input.value.trim() : '';
        if (search) {
            params.set('search', search);
        }
        const fromUrl = new URLSearchParams(window.location.search).get('size');
        const hidden = /** @type {HTMLInputElement | null} */ (
            form.querySelector('input[name="size"]')
        );
        const size = fromUrl || (hidden ? hidden.value : '');
        if (size) {
            params.set('size', size);
        }
        const query = params.toString();
        return form.getAttribute('action') + (query ? '?' + query : '');
    }

    /**
     * Swaps the results card in place, or navigates without the fetch layer.
     *
     * @param {string} url the list URL
     * @returns {void}
     */
    function swapResults(url) {
        if (!window.krtFetch) {
            window.location.assign(url);
            return;
        }
        window.krtFetch.swap({ url, container: '#' + RESULTS_ID, history: true });
    }

    /**
     * Wires the live search: typing swaps the results after a short pause, Enter at once.
     *
     * @returns {void}
     */
    function wireFilter() {
        const form = /** @type {HTMLFormElement | null} */ (
            document.getElementById('bp-overview-filter-form')
        );
        if (!form) {
            return;
        }
        /** @type {number | null} */
        let debounce = null;
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            if (debounce) {
                window.clearTimeout(debounce);
                debounce = null;
            }
            swapResults(buildSearchUrl(form));
        });
        const input = form.querySelector('input[type="search"]');
        if (input) {
            input.addEventListener('input', function () {
                if (debounce) {
                    window.clearTimeout(debounce);
                }
                debounce = window.setTimeout(function () {
                    debounce = null;
                    swapResults(buildSearchUrl(form));
                }, 300);
            });
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        wireRows(document);
        wireFilter();
        if (window.krtFetch) {
            window.krtFetch.bindSwap({ container: '#' + RESULTS_ID, history: true });
        }
    });

    document.addEventListener('krt:swapped', function (event) {
        const container = event.detail && event.detail.container;
        if (container && container.id === RESULTS_ID) {
            queue.length = 0;
            wireRows(container);
        }
    });
})();
