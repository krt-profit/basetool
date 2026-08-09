// @ts-check
(function () {
    'use strict';

    function config() {
        return window.krtBlueprintOverview || {};
    }

    function i18n() {
        return config().i18n || {};
    }

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

    function clear(node) {
        while (node.firstChild) {
            node.removeChild(node.firstChild);
        }
    }

    function ownersUrl(productKey) {
        const base = config().ownersUrl || '';
        const sep = base.indexOf('?') === -1 ? '?' : '&';
        const url = base + sep + 'productKey=' + encodeURIComponent(productKey);
        return window.safeSameOriginUrl ? window.safeSameOriginUrl(url, url) : url;
    }

    function renderOwners(panel, owners) {
        clear(panel);
        if (!owners || owners.length === 0) {
            panel.appendChild(el('div', 'bp-owners-empty', i18n().empty || 'No owners.'));
            return;
        }
        const list = el('ul', 'bp-owners-list');
        owners.forEach(function (owner) {
            const item = el('li', 'bp-owner', owner.ownerName);
            // Discreet marker for an owner who is visible only via global blueprint sharing
            // (REQ-INV-018), i.e. not a member of the caller's oversight org unit.
            if (owner.orgUnitMember === false) {
                const hint = el(
                    'span',
                    'bp-owner-external',
                    i18n().notMember || '(not a unit member)',
                );
                const tip = i18n().notMemberHint;
                if (tip) {
                    hint.title = tip;
                }
                item.appendChild(hint);
            }
            list.appendChild(item);
        });
        panel.appendChild(list);
    }

    function showError(panel) {
        // Reset the loaded flag so the next expand retries the fetch.
        panel.setAttribute('data-loaded', 'false');
        clear(panel);
        panel.appendChild(el('div', 'bp-owners-error', i18n().error || 'Could not load owners.'));
    }

    function loadOwners(productKey, panel) {
        panel.setAttribute('data-loaded', 'true');
        clear(panel);
        panel.appendChild(el('div', 'bp-owners-loading', i18n().loading || 'Loading...'));
        fetch(ownersUrl(productKey), {
            credentials: 'same-origin',
            headers: { Accept: 'application/json' },
        })
            .then(function (resp) {
                return resp.ok ? resp.json() : null;
            })
            .then(function (owners) {
                if (owners === null) {
                    showError(panel);
                    return;
                }
                renderOwners(panel, owners);
            })
            .catch(function () {
                showError(panel);
            });
    }

    function wireDetails(details) {
        // Idempotent: the table is an AJAX swap target, so wireDetails runs again on
        // krt:swapped over freshly rendered rows. The guard stops a re-swap from
        // double-binding the toggle handler on rows that survived (none do today, but
        // the marker keeps this safe if the swap ever becomes a partial update).
        if (details.dataset.bpWired === '1') {
            return;
        }
        details.dataset.bpWired = '1';
        details.addEventListener('toggle', function () {
            const row = details.closest('tr');
            const detailsRow = row ? row.nextElementSibling : null;
            if (!detailsRow || !detailsRow.classList.contains('details-row')) {
                return;
            }
            // Show/hide the companion row via a class on it directly — a CSS
            // tr:has(details[open]) sibling rule re-evaluates style across the
            // whole table per toggle and janks the UI (REQ-INV-012).
            detailsRow.classList.toggle('bp-expanded', details.open);
            if (!details.open) {
                return;
            }
            const productKey = details.getAttribute('data-product-key');
            const panel = detailsRow.querySelector('.bp-owners-panel');
            if (!productKey || !panel) {
                return;
            }
            if (panel.getAttribute('data-loaded') !== 'true') {
                loadOwners(productKey, panel);
            }
        });
    }

    const RESULTS_ID = 'bp-overview-results';

    function wireDetailsIn(root) {
        (root || document).querySelectorAll('details[data-product-key]').forEach(wireDetails);
    }

    // The product search is server-side (REQ-INV-013): the table is paginated, so a
    // client-side row filter would only ever see the current page. The form carries the
    // active page size in a hidden input; submitting it (or typing, debounced) rebuilds
    // the URL and swaps only the results block in place (REQ-FE-002) instead of reloading.
    // The page-size picker sits INSIDE the swap container, so after a re-size the form's
    // hidden size input is stale. Take the active size from the address bar (kept current
    // by the history-synced swap) and fall back to the hidden input on first load. The
    // page index is intentionally dropped so a new search always lands on page 0.
    function activeSize(form) {
        const fromUrl = new URLSearchParams(window.location.search).get('size');
        if (fromUrl) {
            return fromUrl;
        }
        const hidden = form.querySelector('input[name="size"]');
        return hidden ? hidden.value : '';
    }

    function buildSearchUrl(form) {
        const input = form.querySelector('input[type="search"]');
        const params = new URLSearchParams();
        const search = input ? input.value.trim() : '';
        if (search) {
            params.set('search', search);
        }
        const size = activeSize(form);
        if (size) {
            params.set('size', size);
        }
        const query = params.toString();
        return form.getAttribute('action') + (query ? '?' + query : '');
    }

    function swapResults(url) {
        if (!window.krtFetch) {
            window.location.assign(url);
            return;
        }
        window.krtFetch.swap({ url: url, container: '#' + RESULTS_ID, history: true });
    }

    function wireFilter() {
        const form = document.querySelector('form.bp-filter');
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
        wireDetailsIn(document);
        wireFilter();
        // Intercept the size picker / page-nav anchors inside the results block so paging
        // swaps in place. No initial fetch — the server already rendered page 0.
        if (window.krtFetch) {
            window.krtFetch.bindSwap({ container: '#' + RESULTS_ID, history: true });
        }
    });

    // Re-bind the per-element <details> toggle handlers over rows that came in via a swap.
    document.addEventListener('krt:swapped', function (event) {
        const container = event.detail && event.detail.container;
        if (container && container.id === RESULTS_ID) {
            wireDetailsIn(container);
        }
    });
})();
