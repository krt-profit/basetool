// @ts-check
/* exported krtSearchableSelect */
(function () {
    'use strict';

    let comboboxSeq = 0;

    const COMBOBOX_DATA_KEYS = [
        'krtCombobox',
        'krtComboboxDone',
        'comboboxNoResults',
        'comboboxHint',
        'comboboxInvalid',
        'comboboxLoading',
        'comboboxPlaceholder',
        'comboboxMax',
        'testid',
        'search',
    ];

    /**
     * Locates the <label> of a control, first via `for="<id>"`, then as the first label inside
     * the same .form-group, and gives it an id for aria-labelledby when it has none.
     *
     * @param {HTMLElement} select the control whose label is wanted
     * @param {string} uid the instance id used to mint a label id when missing
     * @returns {HTMLElement|null} the label element (with an id), or null
     */
    function findLabel(select, uid) {
        /** @type {HTMLElement | null} */
        let label = null;
        if (select.id) {
            label = document.querySelector('label[for="' + select.id + '"]');
        }
        if (!label) {
            const group = select.closest('.form-group');
            if (group) {
                label = group.querySelector('label');
            }
        }
        if (label && !label.id) {
            label.id = uid + '-label';
        }
        return label && label.id ? label : null;
    }

    /**
     * Appends an option label to a list element, wrapping the first
     * case-insensitive occurrence of the query in a <mark> for emphasis. Built
     * from text nodes so the (backend-supplied) label is never parsed as HTML.
     *
     * @param {HTMLElement} el the list item to fill
     * @param {string} label the full option label
     * @param {string} query the lower-cased filter text (may be empty)
     */
    function appendHighlighted(el, label, query) {
        const at = query ? label.toLowerCase().indexOf(query) : -1;
        if (at < 0) {
            el.appendChild(document.createTextNode(label));
            return;
        }
        el.appendChild(document.createTextNode(label.slice(0, at)));
        const mark = document.createElement('mark');
        mark.textContent = label.slice(at, at + query.length);
        el.appendChild(mark);
        el.appendChild(document.createTextNode(label.slice(at + query.length)));
    }

    /**
     * Builds a combobox option model whose lower-cased `search` haystack combines the label with
     * optional extra terms (e.g. a login name from `data-search`); highlighting uses the label only.
     *
     * @param {string} value the option value (submitted via the hidden input)
     * @param {string} label the visible option label
     * @param {string} [extra] optional extra search terms not shown in the label
     * @param {Object} [data] optional per-option metadata (camelCased dataset keys) mirrored onto
     *     the hidden input while this option is selected (REQ-FE-016)
     * @returns {{value: string, label: string, search: string, data: (Object|undefined)}} the
     *     option model
     */
    function makeItem(value, label, extra, data) {
        const terms = extra && extra.trim() ? label + ' ' + extra.trim() : label;
        return { value, label, search: terms.toLowerCase(), data };
    }

    /**
     * Collects an option's data-* attributes outside the combobox-owned keys into a plain map,
     * later mirrored onto the hidden input while the option is selected (REQ-FE-016).
     *
     * @param {HTMLOptionElement} option the source option
     * @returns {Object|undefined} the metadata map, or undefined when the option carries none
     */
    function optionData(option) {
        let map;
        Object.keys(option.dataset).forEach(function (key) {
            if (COMBOBOX_DATA_KEYS.indexOf(key) !== -1) {
                return;
            }
            if (!map) {
                map = {};
            }
            map[key] = option.dataset[key];
        });
        return map;
    }

    /**
     * Enhances a native <select> in place into a searchable combobox; a no-op on a non-select or
     * an already-enhanced one.
     *
     * @param {HTMLSelectElement} select the select to upgrade
     * @param {Object} [config] optional overrides, each text key with a `data-combobox-*`
     *     fallback: `placeholder`, `noResultsText`, `hintText` (shown when capped),
     *     `invalidText` (custom validity for unmatched text), `loadingText`, `maxResults`
     *     (default 50) and `remoteSource`, a `(query) => Promise<Array<{value,label,data?}>>`
     *     that fetches options on demand (debounced) instead of filtering the static list
     */
    function krtSearchableSelect(select, config) {
        if (!select || select.tagName !== 'SELECT') {
            return;
        }
        if (select.dataset.krtComboboxDone === 'true') {
            return;
        }
        select.dataset.krtComboboxDone = 'true';

        const opts = config || {};
        const data = select.dataset;
        const texts = {
            noResults:
                opts.noResultsText ||
                data.comboboxNoResults ||
                window.krtI18nText(
                    (window.krtComboboxI18n || {}).noResults,
                    'krtComboboxI18n.noResults',
                ),
            hint: opts.hintText || data.comboboxHint || '',
            invalid: opts.invalidText || data.comboboxInvalid || '',
            loading: opts.loadingText || data.comboboxLoading || '',
        };
        const remoteSource = typeof opts.remoteSource === 'function' ? opts.remoteSource : null;
        const maxResults = Math.max(
            1,
            parseInt(opts.maxResults || data.comboboxMax || '50', 10) || 50,
        );

        let items = [];
        let placeholder = opts.placeholder || data.comboboxPlaceholder || '';
        let optional = false;
        let clearLabel = '';
        Array.prototype.forEach.call(select.options, function (option) {
            if (option.value === '') {
                const emptyText = option.textContent.trim();
                if (!placeholder) {
                    placeholder = emptyText;
                }
                if (!select.required) {
                    optional = true;
                    clearLabel = emptyText;
                }
                return;
            }
            items.push(
                makeItem(
                    option.value,
                    option.textContent.trim(),
                    option.dataset.search,
                    optionData(option),
                ),
            );
        });

        const uid = 'krt-cb-' + ++comboboxSeq;
        const listboxId = uid + '-list';

        const wrapper = document.createElement('div');
        wrapper.className = 'krt-combobox';

        const hidden = document.createElement('input');
        hidden.type = 'hidden';
        if (select.name) {
            hidden.name = select.name;
        }
        if (select.id) {
            hidden.id = select.id;
        }
        const reservedKeys = [];
        Object.keys(data).forEach(function (key) {
            if (COMBOBOX_DATA_KEYS.indexOf(key) === -1) {
                hidden.dataset[key] = data[key];
                reservedKeys.push(key);
            }
        });

        const input = document.createElement('input');
        input.type = 'text';
        input.id = uid + '-input';
        input.className = 'krt-combobox__input';
        input.setAttribute('role', 'combobox');
        input.setAttribute('aria-autocomplete', 'list');
        input.setAttribute('aria-expanded', 'false');
        input.setAttribute('aria-controls', listboxId);
        input.setAttribute('autocomplete', 'off');
        input.setAttribute('autocapitalize', 'none');
        input.setAttribute('spellcheck', 'false');
        if (placeholder) {
            input.placeholder = placeholder;
        }
        if (select.required) {
            input.required = true;
        }
        if (select.disabled) {
            input.disabled = true;
            hidden.disabled = true;
        }
        if (data.testid) {
            input.dataset.testid = data.testid;
        }
        const labelEl = findLabel(select, uid);
        if (labelEl) {
            input.setAttribute('aria-labelledby', labelEl.id);
            /** @type {HTMLLabelElement} */ (labelEl).htmlFor = input.id;
        }

        const listbox = document.createElement('ul');
        listbox.id = listboxId;
        listbox.className = 'krt-combobox__listbox';
        listbox.setAttribute('role', 'listbox');
        listbox.hidden = true;

        wrapper.appendChild(hidden);
        wrapper.appendChild(input);
        wrapper.appendChild(listbox);

        let mirroredKeys = [];

        function mirrorItemData(item) {
            mirroredKeys.forEach(function (key) {
                delete hidden.dataset[key];
            });
            mirroredKeys = [];
            const map = item && item.data ? item.data : null;
            if (!map) {
                return;
            }
            Object.keys(map).forEach(function (key) {
                if (
                    COMBOBOX_DATA_KEYS.indexOf(key) !== -1 ||
                    reservedKeys.indexOf(key) !== -1 ||
                    map[key] == null
                ) {
                    return;
                }
                hidden.dataset[key] = map[key];
                mirroredKeys.push(key);
            });
        }

        let committedLabel = '';
        let committedValue = '';
        /** @type {any} */
        let committedItem = null;
        const preselected = items.find(function (it) {
            return it.value === select.value;
        });
        if (preselected) {
            committedLabel = preselected.label;
            committedValue = preselected.value;
            committedItem = preselected;
            hidden.value = preselected.value;
            input.value = committedLabel;
            mirrorItemData(preselected);
        }

        /** @type {Node} */ (select.parentNode).replaceChild(wrapper, select);

        let rendered = [];
        let activeIndex = -1;
        let remoteSeq = 0;
        /** @type {number | null} */
        let remoteTimer = null;
        /** @type {(() => void) | null} */
        let repositionHandler = null;

        function isOpen() {
            return listbox.hidden === false;
        }

        function positionListbox() {
            const rect = input.getBoundingClientRect();
            const gap = 4;
            const cap = 288;
            const below = window.innerHeight - rect.bottom;
            const above = rect.top;
            const flipUp = below < Math.min(cap, listbox.scrollHeight) && above > below;
            listbox.classList.toggle('krt-combobox__listbox--above', flipUp);
            listbox.style.position = 'fixed';
            listbox.style.left = rect.left + 'px';
            listbox.style.right = 'auto';
            listbox.style.width = rect.width + 'px';
            const avail = Math.max(0, Math.min(cap, (flipUp ? above : below) - gap));
            listbox.style.maxHeight = avail + 'px';
            if (flipUp) {
                listbox.style.top = 'auto';
                listbox.style.bottom = window.innerHeight - rect.top + 'px';
            } else {
                listbox.style.bottom = 'auto';
                listbox.style.top = rect.bottom + 'px';
            }
        }

        function resetListboxPosition() {
            listbox.classList.remove('krt-combobox__listbox--above');
            listbox.style.position = '';
            listbox.style.left = '';
            listbox.style.right = '';
            listbox.style.top = '';
            listbox.style.bottom = '';
            listbox.style.width = '';
            listbox.style.maxHeight = '';
        }

        function attachReposition() {
            if (repositionHandler) {
                return;
            }
            repositionHandler = function () {
                if (isOpen()) {
                    positionListbox();
                }
            };
            window.addEventListener('scroll', repositionHandler, true);
            window.addEventListener('resize', repositionHandler);
        }

        function detachReposition() {
            if (!repositionHandler) {
                return;
            }
            window.removeEventListener('scroll', repositionHandler, true);
            window.removeEventListener('resize', repositionHandler);
            repositionHandler = null;
        }

        function noticeRow(message) {
            const li = document.createElement('li');
            li.className = 'krt-combobox__notice';
            li.setAttribute('aria-disabled', 'true');
            li.textContent = message;
            return li;
        }

        function setActive(index) {
            if (activeIndex >= 0 && rendered[activeIndex]) {
                rendered[activeIndex].el.classList.remove('krt-combobox__option--active');
            }
            activeIndex = index;
            if (index < 0 || !rendered[index]) {
                input.removeAttribute('aria-activedescendant');
                return;
            }
            const el = rendered[index].el;
            el.classList.add('krt-combobox__option--active');
            input.setAttribute('aria-activedescendant', el.id);
            const top = el.offsetTop;
            const bottom = top + el.offsetHeight;
            if (top < listbox.scrollTop) {
                listbox.scrollTop = top;
            } else if (bottom > listbox.scrollTop + listbox.clientHeight) {
                listbox.scrollTop = bottom - listbox.clientHeight;
            }
        }

        function renderOptions(query) {
            const q = (query || '').trim().toLowerCase();
            listbox.textContent = '';
            rendered = [];
            activeIndex = -1;

            const matches = remoteSource
                ? items.slice()
                : q
                  ? items.filter(function (it) {
                        return (it.search || it.label.toLowerCase()).indexOf(q) !== -1;
                    })
                  : items.slice();
            const truncated = matches.length > maxResults;

            const rows = clearLabel && !q ? [makeItem('', clearLabel)] : [];
            Array.prototype.push.apply(rows, matches.slice(0, maxResults));

            rows.forEach(function (it, idx) {
                const li = document.createElement('li');
                li.id = listboxId + '-opt-' + idx;
                li.className = 'krt-combobox__option';
                if (it.value === '') {
                    li.classList.add('krt-combobox__option--clear');
                }
                li.setAttribute('role', 'option');
                li.setAttribute('aria-selected', it.value === hidden.value ? 'true' : 'false');
                li.dataset.value = it.value;
                appendHighlighted(li, it.label, q);
                li.addEventListener('mousedown', function (event) {
                    event.preventDefault();
                });
                li.addEventListener('click', function () {
                    commit(it);
                });
                li.addEventListener('mousemove', function () {
                    if (activeIndex !== idx) {
                        setActive(idx);
                    }
                });
                listbox.appendChild(li);
                rendered.push({ item: it, el: li });
            });

            if (rendered.length === 0) {
                listbox.appendChild(noticeRow(texts.noResults));
            } else if (truncated && texts.hint) {
                listbox.appendChild(noticeRow(texts.hint));
            }
        }

        function highlightCommitted() {
            const selIdx = rendered.findIndex(function (r) {
                return r.item.value === hidden.value;
            });
            if (selIdx >= 0) {
                setActive(selIdx);
            }
        }

        function open(query) {
            renderOptions(query);
            listbox.hidden = false;
            input.setAttribute('aria-expanded', 'true');
            positionListbox();
            attachReposition();
            highlightCommitted();
        }

        function renderLoading() {
            listbox.textContent = '';
            rendered = [];
            activeIndex = -1;
            listbox.appendChild(noticeRow(texts.loading || texts.hint || ''));
        }

        function loadRemote(query) {
            const token = ++remoteSeq;
            Promise.resolve(remoteSource(query))
                .then(function (list) {
                    if (token !== remoteSeq || !isOpen()) {
                        return;
                    }
                    items = Array.isArray(list) ? list.slice() : [];
                    renderOptions(query);
                    positionListbox();
                    highlightCommitted();
                })
                .catch(function () {
                    if (token !== remoteSeq || !isOpen()) {
                        return;
                    }
                    items = [];
                    renderOptions(query);
                    positionListbox();
                });
        }

        function openRemote(query, delay) {
            renderLoading();
            listbox.hidden = false;
            input.setAttribute('aria-expanded', 'true');
            positionListbox();
            attachReposition();
            window.clearTimeout(remoteTimer ?? undefined);
            remoteTimer = window.setTimeout(function () {
                loadRemote(query);
            }, delay || 0);
        }

        function close() {
            if (!isOpen()) {
                return;
            }
            listbox.hidden = true;
            input.setAttribute('aria-expanded', 'false');
            input.removeAttribute('aria-activedescendant');
            activeIndex = -1;
            detachReposition();
            resetListboxPosition();
        }

        function commit(item) {
            const next = item ? item.value : '';
            const changed = hidden.value !== next;
            hidden.value = next;
            mirrorItemData(next ? item : null);
            committedLabel = next ? item.label : '';
            committedValue = next;
            committedItem = next ? item : null;
            input.value = committedLabel;
            input.setCustomValidity('');
            close();
            if (changed) {
                hidden.dispatchEvent(new Event('change', { bubbles: true }));
            }
        }

        /**
         * Restores the committed selection to the textbox, the hidden value and the mirrored option
         * metadata, and fires `change` when the hidden value moves.
         */
        function restoreCommitted() {
            const changed = hidden.value !== committedValue;
            hidden.value = committedValue;
            mirrorItemData(committedItem);
            input.value = committedLabel;
            input.setCustomValidity('');
            if (changed) {
                hidden.dispatchEvent(new Event('change', { bubbles: true }));
            }
        }

        function reconcile() {
            const typed = input.value.trim().toLowerCase();
            /** @type {any} */
            let exact = null;
            for (let i = 0; i < items.length; i++) {
                if (items[i].label.toLowerCase() === typed) {
                    exact = items[i];
                    break;
                }
            }
            if (exact) {
                committedLabel = exact.label;
                committedValue = exact.value;
                committedItem = exact;
                if (hidden.value !== exact.value) {
                    hidden.value = exact.value;
                    mirrorItemData(exact);
                    hidden.dispatchEvent(new Event('change', { bubbles: true }));
                } else {
                    mirrorItemData(exact);
                }
                input.setCustomValidity('');
                return;
            }
            if (hidden.value) {
                hidden.value = '';
                mirrorItemData(null);
                hidden.dispatchEvent(new Event('change', { bubbles: true }));
            }
            if (optional && !input.value.trim()) {
                committedLabel = '';
                committedValue = '';
                committedItem = null;
            }
            input.setCustomValidity(input.value.trim() ? texts.invalid : '');
        }

        input.addEventListener('focus', function () {
            input.select();
        });

        input.addEventListener('click', function () {
            if (isOpen()) {
                close();
            } else if (remoteSource) {
                openRemote('', 0);
            } else {
                open('');
            }
        });

        input.addEventListener('input', function () {
            if (remoteSource) {
                openRemote(input.value, 250);
            } else {
                open(input.value);
            }
            reconcile();
        });

        input.addEventListener('keydown', function (event) {
            switch (event.key) {
                case 'ArrowDown':
                    event.preventDefault();
                    if (!isOpen()) {
                        if (remoteSource) {
                            openRemote('', 0);
                        } else {
                            open('');
                            if (activeIndex < 0) {
                                setActive(0);
                            }
                        }
                    } else {
                        setActive(activeIndex + 1 >= rendered.length ? 0 : activeIndex + 1);
                    }
                    break;
                case 'ArrowUp':
                    event.preventDefault();
                    if (!isOpen()) {
                        if (remoteSource) {
                            openRemote('', 0);
                        } else {
                            open('');
                            if (activeIndex < 0) {
                                setActive(rendered.length - 1);
                            }
                        }
                    } else {
                        setActive(activeIndex - 1 < 0 ? rendered.length - 1 : activeIndex - 1);
                    }
                    break;
                case 'Enter':
                    if (isOpen() && activeIndex >= 0 && rendered[activeIndex]) {
                        event.preventDefault();
                        commit(rendered[activeIndex].item);
                    } else if (isOpen() && rendered.length === 1) {
                        event.preventDefault();
                        commit(rendered[0].item);
                    } else if (isOpen()) {
                        close();
                    }
                    break;
                case 'Escape':
                    if (isOpen()) {
                        event.preventDefault();
                        close();
                        restoreCommitted();
                    }
                    break;
                case 'Home':
                    if (isOpen() && rendered.length) {
                        event.preventDefault();
                        setActive(0);
                    }
                    break;
                case 'End':
                    if (isOpen() && rendered.length) {
                        event.preventDefault();
                        setActive(rendered.length - 1);
                    }
                    break;
                default:
                    break;
            }
        });

        input.addEventListener('blur', function () {
            window.setTimeout(function () {
                if (document.activeElement === input) {
                    return;
                }
                close();
                restoreCommitted();
            }, 150);
        });

        /**
         * Selects the option with the given value, syncing the hidden value and the visible label
         * without firing `change`. A value outside the loaded items is accepted only with a
         * non-blank `label`; otherwise the selection is cleared.
         *
         * @param {string} value the option value to select, or empty/unknown to clear
         * @param {string} [label] the label for a value outside the loaded items; ignored when the
         *     value resolves locally
         * @param {Object} [data] option metadata mirrored onto the hidden input (REQ-FE-016)
         */
        function setValue(value, label, data) {
            const v = value == null ? '' : String(value);
            /** @type {any} */
            let match = null;
            for (let i = 0; i < items.length; i++) {
                if (items[i].value === v) {
                    match = items[i];
                    break;
                }
            }
            if (!match && v && label != null && String(label).trim() !== '') {
                match = makeItem(v, String(label), undefined, data);
            }
            hidden.value = match ? match.value : '';
            mirrorItemData(match);
            committedLabel = match ? match.label : '';
            committedValue = match ? match.value : '';
            committedItem = match;
            input.value = committedLabel;
            input.setCustomValidity('');
        }

        const controller = { setValue };
        hidden.krtCombobox = controller;
        wrapper.krtCombobox = controller;
    }

    function autoConfig(select) {
        const i18n = window.krtComboboxI18n || {};
        const d = select.dataset;
        const remoteSources = window.krtComboboxRemoteSources || {};
        const remoteSource = remoteSources[d.krtCombobox];
        const kind = (i18n.kinds || {})[d.krtCombobox] || {};
        return {
            placeholder: d.comboboxPlaceholder || kind.placeholder || i18n.placeholder,
            noResultsText: d.comboboxNoResults || kind.noResults || i18n.noResults,
            maxResults: d.comboboxMax || kind.maxResults,
            hintText: d.comboboxHint || i18n.hint,
            invalidText: d.comboboxInvalid || i18n.invalid,
            loadingText: d.comboboxLoading || i18n.loading,
            remoteSource: typeof remoteSource === 'function' ? remoteSource : undefined,
        };
    }

    function enhanceWithin(root) {
        if (!root || typeof root.querySelectorAll !== 'function') {
            return;
        }
        Array.prototype.forEach.call(
            root.querySelectorAll('select[data-krt-combobox]'),
            function (select) {
                krtSearchableSelect(select, autoConfig(select));
            },
        );
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            enhanceWithin(document);
        });
    } else {
        enhanceWithin(document);
    }
    document.addEventListener('krt:swapped', function (event) {
        const detail = /** @type {CustomEvent} */ (event).detail;
        enhanceWithin((detail && detail.container) || document);
    });

    window.krtSearchableSelect = krtSearchableSelect;
    window.krtEnhanceComboboxes = enhanceWithin;
})();
