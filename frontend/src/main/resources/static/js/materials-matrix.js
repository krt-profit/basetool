/*
 * Materials trade-matrix grid — client-side renderer with vertical virtual scrolling.
 *
 * The server (`GET /materials/overview`) ships only an empty skeleton plus a JSON config element;
 * this module fetches the whole matrix once from `GET /materials/overview/data` and draws it. The
 * matrix is a dense materials x terminals grid that, rendered eagerly, builds tens of thousands of
 * DOM cells and freezes the browser. Instead we keep the full data in memory and materialize ONLY
 * the rows currently inside the scroll viewport (plus a small buffer) into <tbody>, padding the
 * scroll height with two spacer rows. Scrolling re-renders the window; the DOM node count stays
 * roughly constant no matter how large the universe grows.
 *
 * Filtering (material / system / loading-dock / auto-load) is done in memory against the fetched
 * data and re-renders the window — no server round-trip per filter change. Columns are NOT
 * virtualized (terminals are bounded by the game universe and rendered in full per visible row);
 * the unbounded dimension is the material rows, which is what we virtualize.
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
    let grid = null; // full data: { terminals: [...], groups: [...] }
    let cols = []; // current (column-filtered) terminal columns
    let flat = []; // flattened display items: { type: 'kind'|'row', ... }
    let renderedStart = -1;
    let renderedEnd = -1;
    let colsSig = ''; // signature of current columns, to know when headers must rebuild
    let scrollPending = false;
    let filterTimer = null;

    /* --------------------------------------------------------------------- data load */

    function init() {
        fetch(DATA_URL, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
            .then(function (res) {
                if (!res.ok) {
                    throw new Error('HTTP ' + res.status);
                }
                return res.json();
            })
            .then(function (data) {
                grid = {
                    terminals: (data && data.terminals) || [],
                    groups: (data && data.groups) || [],
                };
                if (loading) {
                    loading.classList.add(HIDDEN_CLASS);
                }
                wrapper.classList.remove(HIDDEN_CLASS);
                bindFilters();
                applyFilters();
            })
            .catch(function () {
                if (loading) {
                    loading.classList.add(HIDDEN_CLASS);
                }
                if (errorBox) {
                    errorBox.classList.remove(HIDDEN_CLASS);
                }
            });
    }

    /* ----------------------------------------------------------------------- filters */

    // Reads the checkbox state into a filter spec. A dimension with zero or all options checked is
    // treated as "no filter" (matches the previous server semantics and avoids needless work).
    function readFilters() {
        return {
            materials: selectedSet(document.getElementsByClassName('matCheck')),
            systems: selectedSet(document.getElementsByClassName('sysCheck')),
            loadingDock: isChecked('filterLoadingDock'),
            autoLoad: isChecked('filterAutoLoad'),
        };
    }

    function selectedSet(checks) {
        const total = checks.length;
        const picked = {};
        let count = 0;
        for (let i = 0; i < total; i++) {
            if (checks[i].checked) {
                picked[checks[i].value] = true;
                count++;
            }
        }
        if (count === 0 || count === total) {
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

    function applyFilters() {
        if (!grid) {
            return;
        }
        const f = readFilters();

        cols = grid.terminals.filter(function (t) {
            if (f.systems && !f.systems[t.starSystemName]) {
                return false;
            }
            if (f.loadingDock && !t.hasLoadingDock) {
                return false;
            }
            if (f.autoLoad && !t.isAutoLoad) {
                return false;
            }
            return true;
        });

        const groups = [];
        grid.groups.forEach(function (g) {
            const rows = g.rows.filter(function (r) {
                return !f.materials || f.materials[r.materialName];
            });
            if (rows.length) {
                groups.push({ kind: g.kind, rows: rows });
            }
        });

        renderHead();
        buildFlat(groups);
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

    function scheduleApply() {
        if (filterTimer) {
            clearTimeout(filterTimer);
        }
        filterTimer = setTimeout(function () {
            filterTimer = null;
            applyFilters();
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
                    scheduleApply();
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
                scheduleApply();
            });
        });

        // Boolean filters.
        Array.prototype.forEach.call(
            document.getElementsByClassName('mtx-bool-filter'),
            function (b) {
                b.addEventListener('change', scheduleApply);
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
                applyFilters();
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
            // Rebuild the flat list with the new collapse state, keeping current filters.
            applyFilters();
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
