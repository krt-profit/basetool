/*
 * Materials trade-matrix grid — client-side renderer with vertical virtual scrolling.
 *
 * The server (`GET /materials/overview`) ships only an empty skeleton plus a JSON config element;
 * this module fetches the matrix from `GET /materials/overview/data` and draws it. The matrix is a
 * dense materials x terminals grid that, rendered eagerly, builds tens of thousands of DOM cells
 * and freezes the browser. Instead we keep the fetched data in memory and materialize ONLY the
 * rows currently inside the scroll viewport (plus a small buffer) into <tbody>, padding the scroll
 * height with two spacer rows. Scrolling re-renders the window; the DOM node count stays roughly
 * constant no matter how large the universe grows.
 *
 * Filtering (material / system / loading-dock / auto-load) is SERVER-SIDE (ADR-0105, REQ-UI-014):
 * on every filter change the grid re-fetches `/materials/overview/data` with the selection as query
 * parameters and re-renders. This replaced an in-memory filter over a single clamped fetch, which
 * silently dropped material×terminal cells once the universe exceeded the backend page-size clamp.
 * A stale-response guard (fetchToken) discards out-of-order responses from rapid filter changes.
 * The category-grouping toggle and the collapse/expand of a category are pure presentation and stay
 * client-side (no re-fetch). Columns are NOT virtualized (terminals are bounded by the game
 * universe and rendered in full per visible row); the unbounded dimension is the material rows,
 * which is what we virtualize.
 */
(function () {
    'use strict';

    const config = document.getElementById('matrixConfig');
    const wrapper = document.getElementById('tableContainer');
    const colgroup = document.getElementById('matrixColgroup');
    const head = document.getElementById('matrixHead');
    const body = document.getElementById('matrixBody');
    const loading = document.getElementById('matrixLoading');
    const errorBox = document.getElementById('matrixError');
    if (!config || !wrapper || !colgroup || !head || !body) {
        return;
    }

    // Visibility is class-based (ADR-0093): the server skeleton hides #tableContainer / #matrixError
    // with this generated `display:none` class. Under CSP `style-src-attr 'none'` you cannot reveal
    // them by writing an inline `style="display:.."` (blocked), and clearing `element.style.display`
    // is a no-op against the class. Toggle the class instead — a CSSOM class change is CSP-clean.
    const HIDDEN_CLASS = 'krtm-display-none-5790';

    const DATA_URL = config.getAttribute('data-data-url');
    const I18N = {
        material: config.getAttribute('data-label-material') || 'Material',
        unsorted: config.getAttribute('data-label-unsorted') || 'Unsortiert',
        unsortedSentinel: 'Unsortiert',
        noResults: config.getAttribute('data-label-no-results') || '',
        illegal: config.getAttribute('data-label-illegal') || '',
        volatileQt: config.getAttribute('data-label-volatile-qt') || '',
        volatileTime: config.getAttribute('data-label-volatile-time') || '',
    };

    // Group thousands with '.' to preserve the server's previous `formatInteger(.., 'POINT')` look,
    // independent of UI locale; prices are whole-number aUEC.
    const NUM = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 0 });

    // Escape HTML meta-characters before any value is written via innerHTML. A self-contained
    // replace chain (not a delegate to window.escapeHtml) so it is an unconditional, statically
    // recognizable HTML-escape barrier on every path (CodeQL js/xss-through-dom).
    function esc(v) {
        return String(v == null ? '' : v)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    const BUFFER = 8; // extra rows rendered above and below the viewport
    const collapsed = {}; // kind -> true when its rows are hidden
    const GROUP_PREF_KEY = 'materials_matrix_group_by_category';
    let grouped = true; // false -> one flat, alphabetically sorted row list with no category headers

    let rowHeight = 0; // measured from the first rendered row (uniform via CSS)
    let calibrated = false;
    let grid = null; // fetched (already server-filtered) data: { terminals: [...], groups: [...] }
    let cols = []; // current terminal columns (the fetched grid's terminals)
    let flat = []; // flattened display items: { type: 'kind'|'row', ... }
    let renderedStart = -1;
    let renderedEnd = -1;
    let colsSig = ''; // signature of current columns, to know when headers must rebuild
    let scrollPending = false;
    let filterTimer = null;
    let fetchToken = 0; // monotonic; a response whose token is stale (superseded) is discarded
    let bound = false; // filter listeners are attached exactly once

    /* --------------------------------------------------------------------- data load */

    function init() {
        // Bind first so the grouping preference is restored before the initial render and the
        // filters are live immediately; then load the (unfiltered) grid.
        bindFilters();
        fetchGrid();
    }

    // Fetches the grid for the current filter selection and re-renders. Filtering is server-side:
    // buildFilterQuery() turns the checkbox state into query parameters the backend applies, so the
    // response already contains only the matching slice. Out-of-order responses from rapid filter
    // changes are discarded via a monotonic token so the grid never shows a stale selection.
    function fetchGrid() {
        const token = ++fetchToken;
        fetch(DATA_URL + buildFilterQuery(), { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
            .then(function (res) {
                if (!res.ok) {
                    throw new Error('HTTP ' + res.status);
                }
                return res.json();
            })
            .then(function (data) {
                if (token !== fetchToken) {
                    return; // a newer fetch has superseded this one
                }
                grid = {
                    terminals: (data && data.terminals) || [],
                    groups: (data && data.groups) || [],
                };
                if (loading) {
                    loading.classList.add(HIDDEN_CLASS);
                }
                if (errorBox) {
                    errorBox.classList.add(HIDDEN_CLASS);
                }
                wrapper.classList.remove(HIDDEN_CLASS);
                render();
            })
            .catch(function () {
                if (token !== fetchToken) {
                    return;
                }
                if (loading) {
                    loading.classList.add(HIDDEN_CLASS);
                }
                // Keep the error state exclusive: hide the grid so a failed filter re-fetch never
                // leaves the previous selection's rows visible under the error banner (they would
                // contradict the filter widgets, which already updated synchronously). Drop the
                // stale data too so a later scroll can't resurface it before a successful re-fetch.
                wrapper.classList.add(HIDDEN_CLASS);
                grid = null;
                if (errorBox) {
                    errorBox.classList.remove(HIDDEN_CLASS);
                }
            });
    }

    /* ----------------------------------------------------------------------- filters */

    // Turns the checkbox state into the backend query string. A dimension with zero or all options
    // checked is treated as "no filter" (omitted), matching the backend's null-means-all semantics
    // and keeping the unfiltered request URL identical to the cached default. The parameter names
    // mirror MaterialsPageController#getMatrixData (materials / systems / loadingDock / autoLoad).
    function buildFilterQuery() {
        const parts = [];
        const materials = selectedValues('matCheck');
        const systems = selectedValues('sysCheck');
        if (materials) {
            materials.forEach(function (v) {
                parts.push('materials=' + encodeURIComponent(v));
            });
        }
        if (systems) {
            systems.forEach(function (v) {
                parts.push('systems=' + encodeURIComponent(v));
            });
        }
        if (isChecked('filterLoadingDock')) {
            parts.push('loadingDock=true');
        }
        if (isChecked('filterAutoLoad')) {
            parts.push('autoLoad=true');
        }
        return parts.length ? '?' + parts.join('&') : '';
    }

    // Returns the checked values of a filter dimension, or null when zero or all are checked (i.e.
    // the dimension applies no filter). Only the per-option checkboxes of `className` are counted,
    // never the select-all box.
    function selectedValues(className) {
        const checks = document.getElementsByClassName(className);
        const total = checks.length;
        const picked = [];
        for (let i = 0; i < total; i++) {
            if (checks[i].checked) {
                picked.push(checks[i].value);
            }
        }
        if (picked.length === 0 || picked.length === total) {
            return null; // no filter
        }
        return picked;
    }

    function isChecked(id) {
        const el = document.getElementById(id);
        return !!(el && el.checked);
    }

    // Per-browser persistence of the category-grouping preference (guarded so privacy modes that
    // throw on storage access degrade to the default grouped view instead of breaking the page).
    function readGroupPref() {
        try {
            return localStorage.getItem(GROUP_PREF_KEY);
        } catch (_e) {
            return null;
        }
    }

    function writeGroupPref(value) {
        try {
            localStorage.setItem(GROUP_PREF_KEY, value);
        } catch (_e) {
            /* storage unavailable */
        }
    }

    // Renders the currently fetched grid. The server has already applied the four filter dimensions,
    // so this is pure presentation: the columns are the fetched terminals and the rows are the
    // fetched groups, arranged per the client-only grouping/collapse state. Called after every fetch
    // and whenever a client-only view control (grouping toggle, category collapse) changes.
    function render() {
        if (!grid) {
            return;
        }
        cols = grid.terminals;
        renderHead();
        buildFlat(grid.groups);
        // Force a full body re-render for the new data set.
        renderedStart = -1;
        renderedEnd = -1;
        wrapper.scrollTop = 0;
        renderBody();
    }

    function buildFlat(groups) {
        flat = [];
        if (!grouped) {
            // Flat mode: merge every category's rows into one list, sort alphabetically by
            // material name, and emit only material rows (no category header rows).
            const rows = [];
            groups.forEach(function (g) {
                for (let i = 0; i < g.rows.length; i++) {
                    rows.push(g.rows[i]);
                }
            });
            rows.sort(function (a, b) {
                return String(a.materialName).localeCompare(String(b.materialName), undefined, {
                    sensitivity: 'base',
                });
            });
            rows.forEach(function (r) {
                flat.push({ type: 'row', row: r, kind: null });
            });
            return;
        }
        groups.forEach(function (g) {
            flat.push({ type: 'kind', kind: g.kind });
            if (!collapsed[g.kind]) {
                g.rows.forEach(function (r) {
                    flat.push({ type: 'row', row: r, kind: g.kind });
                });
            }
        });
    }

    /* ------------------------------------------------------------------- header build */

    function renderHead() {
        const sig =
            String(cols.length) +
            '|' +
            cols
                .map(function (c) {
                    return c.name;
                })
                .join('');
        if (sig === colsSig) {
            return; // columns unchanged — keep existing header/colgroup
        }
        colsSig = sig;

        const cg = ['<col class="mtx-col-first" />'];
        const sysRow = ['<th></th>'];
        const termRow = ['<th>' + esc(I18N.material) + '</th>'];

        systemGroups(cols).forEach(function (sg) {
            const label = sg.name ? esc(sg.name) : '-';
            cg.push('<col class="mtx-col-term" span="' + sg.count + '" />');
            sysRow.push('<th colspan="' + sg.count + '" class="col-system">' + label + '</th>');
        });

        cols.forEach(function (c) {
            const label = c.nickname ? c.nickname : c.name;
            const title = c.planetName ? label + ' — ' + c.planetName : label;
            const cls = 'col-terminal' + (c.planetCssClass ? ' ' + c.planetCssClass : '');
            termRow.push(
                '<th class="' + cls + '" title="' + esc(title) + '">' + esc(label) + '</th>',
            );
        });

        colgroup.innerHTML = cg.join('');
        head.innerHTML =
            '<tr class="row-system">' +
            sysRow.join('') +
            '</tr>' +
            '<tr class="row-terminal">' +
            termRow.join('') +
            '</tr>';
    }

    // Contiguous-run counts of the star-system header over the (filtered) column order.
    function systemGroups(columns) {
        const out = [];
        let current = null;
        let count = 0;
        for (let i = 0; i < columns.length; i++) {
            const sys = columns[i].starSystemName || '';
            if (current === null) {
                current = sys;
                count = 1;
            } else if (current === sys) {
                count++;
            } else {
                out.push({ name: current, count: count });
                current = sys;
                count = 1;
            }
        }
        if (current !== null) {
            out.push({ name: current, count: count });
        }
        return out;
    }

    /* --------------------------------------------------------------- body / virtualize */

    function renderBody() {
        if (!flat.length) {
            body.innerHTML =
                '<tr><td colspan="' +
                (cols.length + 1) +
                '" class="mtx-no-results">' +
                esc(I18N.noResults) +
                '</td></tr>';
            renderedStart = 0;
            renderedEnd = 0;
            return;
        }

        // Before calibration we render an initial window with an estimated row height, measure a
        // real row, then re-render once with the true height so spacer math is exact.
        const rh = rowHeight || 44;
        const viewport = wrapper.clientHeight || 600;
        const firstVisible = Math.floor(wrapper.scrollTop / rh);
        const lastVisible = Math.ceil((wrapper.scrollTop + viewport) / rh);
        const start = Math.max(0, firstVisible - BUFFER);
        const end = Math.min(flat.length, lastVisible + BUFFER);

        const html = [];
        if (start > 0) {
            html.push(spacer(start * rh));
        }
        for (let i = start; i < end; i++) {
            html.push(rowHtml(flat[i]));
        }
        if (end < flat.length) {
            html.push(spacer((flat.length - end) * rh));
        }
        body.innerHTML = html.join('');
        applySpacerHeights();
        renderedStart = start;
        renderedEnd = end;

        if (!calibrated) {
            calibrate();
        }
    }

    function spacer(heightPx) {
        // Spacer height is genuinely dynamic (row count x measured row height). Emit it as a
        // `data-krtm-height` hint, NOT an inline `style="height:.."` attribute — the latter is
        // injected via innerHTML and blocked by CSP `style-src-attr 'none'`. applySpacerHeights()
        // then writes it to `style.height` through the CSSOM, which `style-src-attr` does not govern.
        return (
            '<tr class="row-spacer"><td colspan="' +
            (cols.length + 1) +
            '" data-krtm-height="' +
            heightPx +
            '"></td></tr>'
        );
    }

    // Applies the `data-krtm-height` hints to `style.height` via the CSSOM, mirroring the
    // `data-krtm-width` pattern of inline-style-apply.js (ADR-0093). Must run after every
    // body.innerHTML rewrite so freshly materialized spacer rows get their height.
    function applySpacerHeights() {
        const spacers = body.querySelectorAll('td[data-krtm-height]');
        for (let i = 0; i < spacers.length; i++) {
            const h = parseFloat(spacers[i].getAttribute('data-krtm-height'));
            spacers[i].style.height = (isFinite(h) ? h : 0) + 'px';
        }
    }

    function rowHtml(item) {
        if (item.type === 'kind') {
            const label = item.kind === I18N.unsortedSentinel ? I18N.unsorted : item.kind;
            const icon = collapsed[item.kind] ? '+' : '−';
            return (
                '<tr class="row-kind" data-kind="' +
                esc(item.kind) +
                '">' +
                '<td colspan="' +
                (cols.length + 1) +
                '" class="mtx-kind-cell">' +
                '<span class="toggle-icon">' +
                icon +
                '</span>' +
                '<span>' +
                esc(label) +
                '</span></td></tr>'
            );
        }
        const r = item.row;
        const cells = ['<td class="mtx-name-cell">' + warnings(r) + esc(r.materialName) + '</td>'];
        for (let i = 0; i < cols.length; i++) {
            const c = cols[i];
            const cls = 'col-terminal' + (c.planetCssClass ? ' ' + c.planetCssClass : '');
            cells.push('<td class="' + cls + '">' + cellHtml(r.prices[c.name]) + '</td>');
        }
        return '<tr class="row-material">' + cells.join('') + '</tr>';
    }

    function warnings(r) {
        let out = '';
        if (r.isIllegal) {
            out += '<span class="text-danger mtx-warn" title="' + esc(I18N.illegal) + '">⚠</span>';
        }
        if (r.isVolatileQt) {
            out +=
                '<span class="text-warning mtx-warn" title="' + esc(I18N.volatileQt) + '">⚠</span>';
        }
        if (r.isVolatileTime) {
            out +=
                '<span class="text-warning mtx-warn" title="' +
                esc(I18N.volatileTime) +
                '">⚠</span>';
        }
        return out;
    }

    function cellHtml(cell) {
        let out = '';
        if (cell) {
            if (cell.priceSell != null && cell.priceSell > 0) {
                out += '<div class="price-sell">+' + NUM.format(cell.priceSell) + '</div>';
            }
            if (cell.priceBuy != null && cell.priceBuy > 0) {
                out += '<div class="price-buy">-' + NUM.format(cell.priceBuy) + '</div>';
            }
        }
        return out || '-';
    }

    // Measure the true height of a rendered material row (all rows are forced to a uniform height
    // in CSS) and re-render once if it differs from the estimate.
    function calibrate() {
        const sample = body.querySelector('tr.row-material') || body.querySelector('tr.row-kind');
        if (!sample) {
            calibrated = true;
            return;
        }
        const h = Math.round(sample.getBoundingClientRect().height);
        calibrated = true;
        if (h > 0 && Math.abs(h - rowHeight) > 1) {
            rowHeight = h;
            renderedStart = -1;
            renderBody();
        }
    }

    /* --------------------------------------------------------------------- scrolling */

    function onScroll() {
        if (scrollPending) {
            return;
        }
        scrollPending = true;
        window.requestAnimationFrame(function () {
            scrollPending = false;
            maybeRenderOnScroll();
        });
    }

    function maybeRenderOnScroll() {
        if (!flat.length) {
            return;
        }
        const rh = rowHeight || 44;
        const viewport = wrapper.clientHeight || 600;
        const firstVisible = Math.floor(wrapper.scrollTop / rh);
        const lastVisible = Math.ceil((wrapper.scrollTop + viewport) / rh);
        if (firstVisible - BUFFER < renderedStart || lastVisible + BUFFER > renderedEnd) {
            renderBody();
        }
    }

    /* ----------------------------------------------------------------- filter wiring */

    // Debounces a server re-fetch so dragging through many checkboxes issues one request, not one
    // per click. Each fired fetch carries a fresh token, so an earlier in-flight response that
    // arrives late is discarded rather than clobbering the newer selection.
    function scheduleRefetch() {
        if (filterTimer) {
            clearTimeout(filterTimer);
        }
        filterTimer = setTimeout(function () {
            filterTimer = null;
            fetchGrid();
        }, 200);
    }

    function updateSelectedText(checkClass, headerId) {
        const header = document.getElementById(headerId);
        if (!header) {
            return;
        }
        const checks = document.getElementsByClassName(checkClass);
        const total = checks.length;
        let count = 0;
        let firstLabel = null;
        for (let i = 0; i < total; i++) {
            if (checks[i].checked) {
                count++;
                if (firstLabel === null && checks[i].previousElementSibling) {
                    firstLabel = checks[i].previousElementSibling.textContent;
                }
            }
        }
        const textEl = header.querySelector('.selected-text');
        if (!textEl) {
            return;
        }
        if (count === total) {
            textEl.textContent = header.getAttribute('data-all');
        } else if (count === 1) {
            textEl.textContent = firstLabel;
        } else {
            textEl.textContent = count + ' ' + header.getAttribute('data-selected');
        }
    }

    function bindFilters() {
        if (bound) {
            return; // listeners attach exactly once, even if init runs again
        }
        bound = true;
        // Dropdown open / close.
        Array.prototype.forEach.call(
            document.getElementsByClassName('mtx-multi-header'),
            function (h) {
                h.addEventListener('click', function () {
                    const opts = document.getElementById(h.getAttribute('data-options-id'));
                    const wasOpen = opts.classList.contains('open');
                    closeAllDropdowns();
                    if (!wasOpen) {
                        opts.classList.add('open');
                    }
                });
            },
        );
        document.addEventListener('click', function (ev) {
            if (!ev.target.closest('.multi-select-container')) {
                closeAllDropdowns();
            }
        });

        // Select-all toggles.
        Array.prototype.forEach.call(
            document.getElementsByClassName('mtx-select-all'),
            function (box) {
                box.addEventListener('change', function () {
                    const checkClass = box.getAttribute('data-check-class');
                    const checks = document.getElementsByClassName(checkClass);
                    for (let i = 0; i < checks.length; i++) {
                        checks[i].checked = box.checked;
                    }
                    updateSelectedText(checkClass, box.getAttribute('data-header-id'));
                    scheduleRefetch();
                });
            },
        );

        // Individual option toggles.
        Array.prototype.forEach.call(document.getElementsByClassName('mtx-check'), function (chk) {
            chk.addEventListener('change', function () {
                const checkClass = chk.getAttribute('data-check-class');
                const siblings = document.getElementsByClassName(checkClass);
                let allChecked = true;
                for (let i = 0; i < siblings.length; i++) {
                    if (!siblings[i].checked) {
                        allChecked = false;
                        break;
                    }
                }
                const allBox = document.getElementById(chk.getAttribute('data-all-id'));
                if (allBox) {
                    allBox.checked = allChecked;
                }
                updateSelectedText(checkClass, chk.getAttribute('data-header-id'));
                scheduleRefetch();
            });
        });

        // Boolean filters.
        Array.prototype.forEach.call(
            document.getElementsByClassName('mtx-bool-filter'),
            function (b) {
                b.addEventListener('change', scheduleRefetch);
            },
        );

        // Category-grouping toggle: switch between category-grouped header rows and a single
        // flat, alphabetically sorted row list. Restore the saved preference into `grouped`
        // before the initial render, then re-apply (and persist) on every change.
        const groupBox = document.getElementById('filterGroupByCategory');
        if (groupBox) {
            const pref = readGroupPref();
            if (pref !== null) {
                groupBox.checked = pref === '1';
            }
            grouped = groupBox.checked;
            groupBox.addEventListener('change', function () {
                grouped = groupBox.checked;
                writeGroupPref(grouped ? '1' : '0');
                // Pure presentation over the already-fetched grid — no server round-trip.
                render();
            });
        }

        // Category collapse / expand (delegated to the virtual <tbody>).
        body.addEventListener('click', function (ev) {
            const kindRow = ev.target.closest('tr.row-kind');
            if (!kindRow) {
                return;
            }
            const kind = kindRow.getAttribute('data-kind');
            collapsed[kind] = !collapsed[kind];
            // Rebuild the flat list with the new collapse state — client-only, no re-fetch.
            render();
        });

        wrapper.addEventListener('scroll', onScroll);
    }

    function closeAllDropdowns() {
        Array.prototype.forEach.call(
            document.getElementsByClassName('multi-select-options'),
            function (o) {
                o.classList.remove('open');
            },
        );
    }

    init();
})();
