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
            panel.appendChild(
                el(
                    'div',
                    'bp-owners-empty',
                    window.krtI18nText(i18n().empty, 'krtBlueprintOverview.i18n.empty'),
                ),
            );
            return;
        }
        const list = el('ul', 'bp-owners-list');
        owners.forEach(function (owner) {
            const item = el('li', 'bp-owner', owner.ownerName);
            if (owner.orgUnitMember === false) {
                const hint = el(
                    'span',
                    'bp-owner-external',
                    window.krtI18nText(i18n().notMember, 'krtBlueprintOverview.i18n.notMember'),
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
        panel.setAttribute('data-loaded', 'false');
        clear(panel);
        panel.appendChild(
            el(
                'div',
                'bp-owners-error',
                window.krtI18nText(i18n().error, 'krtBlueprintOverview.i18n.error'),
            ),
        );
    }

    function loadOwners(productKey, panel) {
        panel.setAttribute('data-loaded', 'true');
        clear(panel);
        panel.appendChild(
            el(
                'div',
                'bp-owners-loading',
                window.krtI18nText(i18n().loading, 'krtBlueprintOverview.i18n.loading'),
            ),
        );
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
        window.krtFetch.swap({ url, container: '#' + RESULTS_ID, history: true });
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
        if (window.krtFetch) {
            window.krtFetch.bindSwap({ container: '#' + RESULTS_ID, history: true });
        }
    });

    document.addEventListener('krt:swapped', function (event) {
        const container = event.detail && event.detail.container;
        if (container && container.id === RESULTS_ID) {
            wireDetailsIn(container);
        }
    });
})();
