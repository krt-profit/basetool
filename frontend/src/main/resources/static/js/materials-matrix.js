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

    const HIDDEN_CLASS = 'krtm-display-none-5790';

    const DATA_URL = config.getAttribute('data-data-url');
    const I18N = {
        material: window.krtI18nText(
            config.getAttribute('data-label-material'),
            'data-label-material',
        ),
        unsorted: window.krtI18nText(
            config.getAttribute('data-label-unsorted'),
            'data-label-unsorted',
        ),
        unsortedSentinel: 'Unsortiert',
        noResults: config.getAttribute('data-label-no-results') || '',
        illegal: config.getAttribute('data-label-illegal') || '',
        volatileQt: config.getAttribute('data-label-volatile-qt') || '',
        volatileTime: config.getAttribute('data-label-volatile-time') || '',
    };

    const NUM = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 0 });

    const BUFFER = 8;
    const collapsed = {};
    const GROUP_PREF_KEY = 'materials_matrix_group_by_category';
    const FILTER_PREF_KEY = 'materials_matrix_filters';
    let grouped = true;

    let rowHeight = 0;
    let calibrated = false;
    let grid = null;
    let cols = [];
    let flat = [];
    let renderedStart = -1;
    let renderedEnd = -1;
    let colsSig = '';
    let scrollPending = false;
    let filterTimer = null;
    let fetchToken = 0;
    let bound = false;

    function init() {
        bindFilters();
        restoreFilters();
        fetchGrid();
    }

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
                    return;
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
                wrapper.classList.add(HIDDEN_CLASS);
                grid = null;
                if (errorBox) {
                    errorBox.classList.remove(HIDDEN_CLASS);
                }
            });
    }

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
            return null;
        }
        return picked;
    }

    function isChecked(id) {
        const el = document.getElementById(id);
        return !!(el && el.checked);
    }

    function readFilterPref() {
        try {
            const raw = localStorage.getItem(FILTER_PREF_KEY);
            return raw === null ? null : JSON.parse(raw);
        } catch (_e) {
            return null;
        }
    }

    function writeFilterPref(value) {
        try {
            localStorage.setItem(FILTER_PREF_KEY, JSON.stringify(value));
        } catch (_e) {}
    }

    function persistFilters() {
        writeFilterPref({
            materials: selectedValues('matCheck'),
            systems: selectedValues('sysCheck'),
            loadingDock: isChecked('filterLoadingDock'),
            autoLoad: isChecked('filterAutoLoad'),
        });
    }

    function restoreFilters() {
        const saved = readFilterPref();
        if (!saved || typeof saved !== 'object') {
            return;
        }
        applySavedSelection('matCheck', 'matAll', 'materialHeader', saved.materials);
        applySavedSelection('sysCheck', 'sysAll', 'systemHeader', saved.systems);
        setCheckedById('filterLoadingDock', saved.loadingDock);
        setCheckedById('filterAutoLoad', saved.autoLoad);
    }

    function applySavedSelection(className, allId, headerId, saved) {
        if (!Array.isArray(saved) || saved.length === 0) {
            return;
        }
        const checks = document.getElementsByClassName(className);
        let anyChecked = false;
        let allChecked = true;
        for (let i = 0; i < checks.length; i++) {
            const on = saved.indexOf(checks[i].value) >= 0;
            checks[i].checked = on;
            if (on) {
                anyChecked = true;
            } else {
                allChecked = false;
            }
        }
        if (!anyChecked) {
            for (let i = 0; i < checks.length; i++) {
                checks[i].checked = true;
            }
            allChecked = true;
        }
        const allBox = document.getElementById(allId);
        if (allBox) {
            allBox.checked = allChecked;
        }
        updateSelectedText(className, headerId);
    }

    function setCheckedById(id, value) {
        const el = document.getElementById(id);
        if (el && typeof value === 'boolean') {
            el.checked = value;
        }
    }

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
        } catch (_e) {}
    }

    function render() {
        if (!grid) {
            return;
        }
        cols = grid.terminals;
        renderHead();
        buildFlat(grid.groups);
        renderedStart = -1;
        renderedEnd = -1;
        wrapper.scrollTop = 0;
        renderBody();
    }

    function buildFlat(groups) {
        flat = [];
        if (!grouped) {
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
            return;
        }
        colsSig = sig;

        let cgHtml = '<col class="mtx-col-first" />';
        let sysHtml = '<th></th>';
        let termHtml = '<th>' + escapeHtml(I18N.material) + '</th>';

        systemGroups(cols).forEach(function (sg) {
            cgHtml += '<col class="mtx-col-term" span="' + escapeAttr(sg.count) + '" />';
            sysHtml +=
                '<th colspan="' +
                escapeAttr(sg.count) +
                '" class="col-system">' +
                escapeHtml(sg.name ? sg.name : '-') +
                '</th>';
        });

        cols.forEach(function (c) {
            const label = c.nickname ? c.nickname : c.name;
            const title = c.planetName ? label + ' — ' + c.planetName : label;
            const cls = 'col-terminal' + (c.planetCssClass ? ' ' + c.planetCssClass : '');
            termHtml +=
                '<th class="' +
                escapeAttr(cls) +
                '" title="' +
                escapeAttr(title) +
                '">' +
                escapeHtml(label) +
                '</th>';
        });

        colgroup.innerHTML = cgHtml;
        head.innerHTML =
            '<tr class="row-system">' +
            sysHtml +
            '</tr>' +
            '<tr class="row-terminal">' +
            termHtml +
            '</tr>';
    }

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
                out.push({ name: current, count });
                current = sys;
                count = 1;
            }
        }
        if (current !== null) {
            out.push({ name: current, count });
        }
        return out;
    }

    function renderBody() {
        if (!flat.length) {
            body.innerHTML =
                '<tr><td colspan="' +
                escapeAttr(cols.length + 1) +
                '" class="mtx-no-results">' +
                escapeHtml(I18N.noResults) +
                '</td></tr>';
            renderedStart = 0;
            renderedEnd = 0;
            return;
        }

        let bodyHtml = '';

        function appendSpacer(heightPx) {
            bodyHtml +=
                '<tr class="row-spacer"><td colspan="' +
                escapeAttr(cols.length + 1) +
                '" data-krtm-height="' +
                escapeAttr(heightPx) +
                '"></td></tr>';
        }

        function appendRow(item) {
            if (item.type === 'kind') {
                const label = item.kind === I18N.unsortedSentinel ? I18N.unsorted : item.kind;
                const icon = collapsed[item.kind] ? '+' : '−';
                bodyHtml +=
                    '<tr class="row-kind" data-kind="' +
                    escapeAttr(item.kind) +
                    '">' +
                    '<td colspan="' +
                    escapeAttr(cols.length + 1) +
                    '" class="mtx-kind-cell">' +
                    '<span class="toggle-icon">' +
                    escapeHtml(icon) +
                    '</span>' +
                    '<span>' +
                    escapeHtml(label) +
                    '</span></td></tr>';
                return;
            }
            const r = item.row;
            bodyHtml += '<tr class="row-material"><td class="mtx-name-cell">';
            appendWarnings(r);
            bodyHtml += escapeHtml(r.materialName) + '</td>';
            for (let i = 0; i < cols.length; i++) {
                const c = cols[i];
                const cls = 'col-terminal' + (c.planetCssClass ? ' ' + c.planetCssClass : '');
                bodyHtml += '<td class="' + escapeAttr(cls) + '">';
                appendCell(r.prices[c.name]);
                bodyHtml += '</td>';
            }
            bodyHtml += '</tr>';
        }

        function appendWarnings(r) {
            if (r.isIllegal) {
                bodyHtml +=
                    '<span class="text-danger mtx-warn" title="' +
                    escapeAttr(I18N.illegal) +
                    '">⚠</span>';
            }
            if (r.isVolatileQt) {
                bodyHtml +=
                    '<span class="text-warning mtx-warn" title="' +
                    escapeAttr(I18N.volatileQt) +
                    '">⚠</span>';
            }
            if (r.isVolatileTime) {
                bodyHtml +=
                    '<span class="text-warning mtx-warn" title="' +
                    escapeAttr(I18N.volatileTime) +
                    '">⚠</span>';
            }
        }

        function appendCell(cell) {
            let any = false;
            if (cell) {
                if (cell.priceSell != null && cell.priceSell > 0) {
                    bodyHtml +=
                        '<div class="price-sell">+' +
                        escapeHtml(NUM.format(cell.priceSell)) +
                        '</div>';
                    any = true;
                }
                if (cell.priceBuy != null && cell.priceBuy > 0) {
                    bodyHtml +=
                        '<div class="price-buy">-' +
                        escapeHtml(NUM.format(cell.priceBuy)) +
                        '</div>';
                    any = true;
                }
            }
            if (!any) {
                bodyHtml += '-';
            }
        }

        const rh = rowHeight || 44;
        const viewport = wrapper.clientHeight || 600;
        const firstVisible = Math.floor(wrapper.scrollTop / rh);
        const lastVisible = Math.ceil((wrapper.scrollTop + viewport) / rh);
        const start = Math.max(0, firstVisible - BUFFER);
        const end = Math.min(flat.length, lastVisible + BUFFER);

        if (start > 0) {
            appendSpacer(start * rh);
        }
        for (let i = start; i < end; i++) {
            appendRow(flat[i]);
        }
        if (end < flat.length) {
            appendSpacer((flat.length - end) * rh);
        }
        body.innerHTML = bodyHtml;
        applySpacerHeights();
        renderedStart = start;
        renderedEnd = end;

        if (!calibrated) {
            calibrate();
        }
    }

    function applySpacerHeights() {
        const spacers = body.querySelectorAll('td[data-krtm-height]');
        for (let i = 0; i < spacers.length; i++) {
            const h = parseFloat(spacers[i].getAttribute('data-krtm-height'));
            spacers[i].style.height = (isFinite(h) ? h : 0) + 'px';
        }
    }

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

    function scheduleRefetch() {
        persistFilters();
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
            return;
        }
        bound = true;
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

        Array.prototype.forEach.call(
            document.getElementsByClassName('mtx-bool-filter'),
            function (b) {
                b.addEventListener('change', scheduleRefetch);
            },
        );

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
                render();
            });
        }

        body.addEventListener('click', function (ev) {
            const kindRow = ev.target.closest('tr.row-kind');
            if (!kindRow) {
                return;
            }
            const kind = kindRow.getAttribute('data-kind');
            collapsed[kind] = !collapsed[kind];
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
