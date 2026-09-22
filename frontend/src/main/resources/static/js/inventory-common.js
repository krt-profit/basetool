// @ts-check
/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * The Lager behaviour the global ("Lager", /inventory/all, inventory-admin.js) and the personal
 * ("Mein Lager", /inventory/my, inventory-my.js) pages share (FE-SIMP-03). Until 2026-09 both page
 * scripts carried their own copy of every function below — about 700 lines, byte-identical or
 * differing only in the data-trigger prefix, the route root and the post-write refresh — and had to
 * prefix their module state (`adminBookOutItemId`, …) so a page loading both would not collide.
 *
 * Covers: the material-group / stack tree with its per-user localStorage expansion (REQ-INV-002,
 * REQ-INV-030) and the lazy, paginated stack-entry load; the book-out (Ausbuchen: DISCARD / SELL)
 * modal including the terminal lookup for a sale; the amount <-> target-amount coupling of the
 * book-out and Umbuchen modals, the Umbuchen close and its target-OrgUnit picker (#1328); the
 * Variante-C allocation chips and their popover (REQ-INV-027); and the cross-room live-sync pokes
 * a stock write owes the order and Materialbörse rooms (#1309).
 *
 * What stays in the page scripts is what genuinely differs: the filters, the Umbuchen open/submit
 * (the personal page adds the personal <-> shared toggle), the bulk operations of the personal page,
 * the admin delete-all, and each page's own `inventory` live-sync room.
 *
 * `window.krtInventory.createLager(config)` returns one instance per page; the page calls its
 * `bind()` once, at the position its own bindings used to run, which installs the delegated
 * `<prefix>-*` krtEvents handlers, the book-out form's submit listener and the initial tree restore.
 * Localized strings come from the page's th:inline bootstrap (`stackEntriesI18n`, `bookOutI18n`,
 * `inventoryConflictI18n`, `assocI18n`), so this file must load after that block and before the
 * page script.
 */
/* global stackEntriesI18n, bookOutI18n, inventoryConflictI18n, assocI18n */
(function () {
    'use strict';

    // Chip-display epsilon (three-decimal SCU): a |rest| within this reads as an exact zero.
    const ASSOC_EPS = 0.0005;

    /**
     * An element by id, typed as the input it is used as. Every lookup here names a control the
     * page template renders; a missing one makes the caller return rather than throw.
     *
     * @param {string} id the element id
     * @returns {HTMLInputElement | null} the element, or null when the page does not render it
     */
    function input(id) {
        return /** @type {HTMLInputElement | null} */ (document.getElementById(id));
    }

    /**
     * Replaces a select's options with one disabled, pre-selected placeholder. Built as an Option
     * node rather than an HTML string, so the localized text is never parsed as markup.
     *
     * @param {HTMLSelectElement} select the select to reset
     * @param {string} text the localized placeholder text
     */
    function setPlaceholderOption(select, text) {
        select.replaceChildren(new Option(text, '', true, true));
        select.options[0].disabled = true;
    }

    /**
     * Builds the shared Lager behaviour for one page.
     *
     * @param {KrtInventoryLagerConfig} cfg what differs between the two Lager pages
     * @returns {KrtInventoryLager} the page's instance
     */
    function createLager(cfg) {
        /**
         * The page's data-trigger name for a shared action.
         *
         * @param {string} action the action suffix, e.g. `toggle-group`
         * @returns {string} the prefixed trigger, e.g. `inv-admin-toggle-group`
         */
        function trigger(action) {
            return cfg.triggerPrefix + '-' + action;
        }

        // The item id the open book-out modal targets; set when the modal opens, read on submit.
        /** @type {string | null} */
        let bookOutItemId = null;
        // Guards against a second submit (Enter / rapid click) landing while the first write is in
        // flight — a duplicate book-out on the same version would otherwise 409.
        let bookOutInFlight = false;
        // #1328: the Umbuchen row's current owning org-unit id, used to preset the target-OrgUnit
        // picker so a submit that does not touch it keeps the stock in its current unit.
        /** @type {string | null} */
        let umbuchenCurrentOwningOrgUnitId = null;

        // ===================== Tree view (REQ-INV-002 / REQ-INV-030) ==========================

        /**
         * Which Lager view is active: the Material <-> Items switch is server-rendered navigation,
         * so the authoritative state is the page URL's view= parameter, which every filter re-swap
         * also carries (history.replaceState keeps the address bar in sync).
         *
         * @returns {boolean} true on the items view
         */
        function lagerIsItemsView() {
            try {
                return new URLSearchParams(window.location.search).get('view') === 'items';
            } catch {
                return false;
            }
        }

        /**
         * The grouping key of a tree group row: material rows carry data-material-id, game-item rows
         * data-game-item-id. Exactly one is present.
         *
         * @param {Element} el the group row
         * @returns {string | null} the group key
         */
        function groupKeyOf(el) {
            return el.getAttribute('data-material-id') || el.getAttribute('data-game-item-id');
        }

        /**
         * The per-user localStorage suffix taken from the tree's data-user-id, or null when the
         * table is absent / anonymous (nothing is then persisted).
         *
         * @returns {string | null} the user id
         */
        function lagerUserId() {
            const table = document.getElementById('inventoryTable');
            const userId = table ? table.getAttribute('data-user-id') : null;
            return userId && userId !== 'unknown' ? userId : null;
        }

        /**
         * The localStorage key holding the expanded group ids, view-scoped so the Material and the
         * Items tree remember their expansion independently.
         *
         * @returns {string | null} the key, or null when nothing is persisted
         */
        function groupStorageKey() {
            const userId = lagerUserId();
            if (!userId) return null;
            return (
                (lagerIsItemsView() ? 'expanded_rows_lager_items_' : 'expanded_rows_lager_') +
                userId
            );
        }

        /**
         * The localStorage key holding the expanded stack ids (view-scoped like the group key).
         *
         * @returns {string | null} the key, or null when nothing is persisted
         */
        function stackStorageKey() {
            const userId = lagerUserId();
            if (!userId) return null;
            return (
                (lagerIsItemsView() ? 'expanded_stacks_lager_items_' : 'expanded_stacks_lager_') +
                userId
            );
        }

        /**
         * Builds the lazy stack-entries fetch URL from the stack-key data-attributes the server
         * stamped on the stack-header row. An absent dimension is omitted so the backend's null-safe
         * match selects rows where it is itself absent. A game-item stack is addressed by gameItemId
         * with no quality key and goes to the item sibling endpoint. The parameter order is also the
         * persisted stack identity (see stackKey), so it must stay exactly as the two page copies
         * built it: the global Lager's stacks are per owner (userId after the grouping id), the
         * personal Lager's carry the personal flag after the quality.
         *
         * @param {Element} headerRow the stack header row
         * @param {number | null} page the entries page, or null for the page-less identity
         * @returns {string} the relative URL
         */
        function buildStackEntriesUrl(headerRow, page) {
            const params = new URLSearchParams();
            const gameItemId = headerRow.getAttribute('data-game-item-id');
            if (gameItemId) {
                params.set('gameItemId', gameItemId);
                if (cfg.stackPerOwner) {
                    params.set('userId', headerRow.getAttribute('data-user-id') ?? 'null');
                }
                params.set('locationId', headerRow.getAttribute('data-location-id') ?? 'null');
            } else {
                params.set('materialId', headerRow.getAttribute('data-material-id') ?? 'null');
                if (cfg.stackPerOwner) {
                    params.set('userId', headerRow.getAttribute('data-user-id') ?? 'null');
                }
                params.set('locationId', headerRow.getAttribute('data-location-id') ?? 'null');
                const quality = headerRow.getAttribute('data-quality');
                if (quality !== null && quality !== '') params.set('quality', quality);
            }
            if (cfg.stackPersonalFlag) {
                params.set('personal', headerRow.getAttribute('data-personal') || 'false');
            }
            const owningOrgUnitId = headerRow.getAttribute('data-owning-org-unit-id');
            if (owningOrgUnitId) params.set('owningOrgUnitId', owningOrgUnitId);
            if (page != null) params.set('page', String(page));
            const path = gameItemId
                ? cfg.basePath + '/game-item-stack/entries?'
                : cfg.basePath + '/stack/entries?';
            return path + params.toString();
        }

        /**
         * A stack's identity is exactly the page-less stack-entries URL its data-attributes build,
         * so the same stack maps to the same key across re-renders and a /all stack can never
         * collide with a /my one (they carry a different path prefix).
         *
         * @param {Element} headerRow the stack header row
         * @returns {string} the stack key
         */
        function stackKey(headerRow) {
            return buildStackEntriesUrl(headerRow, null);
        }

        /**
         * Reads a persisted expansion array, tolerating absent / corrupt storage.
         *
         * @param {string | null} key the storage key
         * @returns {string[]} the persisted ids
         */
        function readExpanded(key) {
            if (!key) return [];
            try {
                return JSON.parse(localStorage.getItem(key) || '[]');
            } catch (e) {
                console.warn('LocalStorage error', e);
                return [];
            }
        }

        /**
         * Persists an expansion array, tolerating a storage write failure (quota / privacy mode).
         *
         * @param {string | null} key the storage key
         * @param {string[]} values the ids to persist
         */
        function writeExpanded(key, values) {
            if (!key) return;
            try {
                localStorage.setItem(key, JSON.stringify(values));
            } catch (e) {
                console.warn('LocalStorage error', e);
            }
        }

        /**
         * Shows or hides the row that follows a tree toggle row and flips its arrow.
         *
         * @param {Element} row the toggle row
         * @param {Element} nextRow the row it expands
         * @param {boolean} open whether to show it
         */
        function setExpanded(row, nextRow, open) {
            /** @type {HTMLElement} */ (nextRow).style.display = open ? 'block' : 'none';
            const icon = row.querySelector('.toggle-icon');
            if (icon) icon.textContent = open ? '▼' : '▶';
        }

        /** Re-applies the persisted group expansion to the freshly rendered tree. */
        function restoreExpandedGroups() {
            const expandedRows = readExpanded(groupStorageKey());
            if (expandedRows.length === 0) return;
            document.querySelectorAll('.tree-row--group').forEach(function (row) {
                const groupKey = groupKeyOf(row);
                if (groupKey && expandedRows.includes(groupKey)) {
                    const nextRow = row.nextElementSibling;
                    if (nextRow && nextRow.classList.contains('tree-group-items')) {
                        setExpanded(row, nextRow, true);
                    }
                }
            });
        }

        /**
         * Re-applies the persisted stack expansion and re-triggers the lazy entry load for each
         * restored stack (the re-rendered header comes back with data-stack-loaded="false", so the
         * leaf rows — carrying the fresh post-write amounts — are fetched again).
         */
        function restoreExpandedStacks() {
            const expandedStacks = readExpanded(stackStorageKey());
            if (expandedStacks.length === 0) return;
            document.querySelectorAll('.stack-header').forEach(function (row) {
                if (!expandedStacks.includes(stackKey(row))) return;
                const nextRow = row.nextElementSibling;
                if (nextRow && nextRow.classList.contains('tree-stack-entries')) {
                    setExpanded(row, nextRow, true);
                    if (row.getAttribute('data-stack-loaded') !== 'true') {
                        loadStackEntries(row, 0);
                    }
                }
            });
        }

        /**
         * Restores the whole tree (groups first, then their stacks) — run on initial load and after
         * every in-place grouped-table re-swap, because a fragment swap does not re-fire
         * DOMContentLoaded and would otherwise collapse every row the user had opened.
         */
        function restoreExpandedTree() {
            restoreExpandedGroups();
            restoreExpandedStacks();
        }

        /**
         * Expands or collapses a material / game-item group and persists the choice.
         *
         * @param {Element} row the group row
         */
        function toggleGroup(row) {
            const nextRow = row.nextElementSibling;
            const groupKey = groupKeyOf(row);
            if (!nextRow || !nextRow.classList.contains('tree-group-items')) return;
            const key = groupStorageKey();
            const expandedRows = readExpanded(key);
            if (window.getComputedStyle(nextRow).display === 'none') {
                setExpanded(row, nextRow, true);
                if (groupKey && !expandedRows.includes(groupKey)) {
                    expandedRows.push(groupKey);
                    writeExpanded(key, expandedRows);
                }
            } else {
                setExpanded(row, nextRow, false);
                if (groupKey) {
                    writeExpanded(
                        key,
                        expandedRows.filter((id) => id !== groupKey),
                    );
                }
            }
        }

        /**
         * Expands or collapses a stack, persists the choice and — append-only Lager — fetches the
         * stack's entries on its first expand (ADR-0003, REQ-INV-002); later toggles only reveal
         * the rows already loaded.
         *
         * @param {Element} row the stack header row
         */
        function toggleStack(row) {
            const nextRow = row.nextElementSibling;
            if (!nextRow || !nextRow.classList.contains('tree-stack-entries')) return;
            const key = stackStorageKey();
            const expandedStacks = readExpanded(key);
            const id = stackKey(row);
            if (window.getComputedStyle(nextRow).display === 'none') {
                setExpanded(row, nextRow, true);
                // Persist so a later in-place re-swap (filter change or modal write) re-opens it.
                if (id && !expandedStacks.includes(id)) {
                    expandedStacks.push(id);
                    writeExpanded(key, expandedStacks);
                }
                if (row.getAttribute('data-stack-loaded') !== 'true') {
                    loadStackEntries(row, 0);
                }
            } else {
                setExpanded(row, nextRow, false);
                if (id) {
                    writeExpanded(
                        key,
                        expandedStacks.filter((k) => k !== id),
                    );
                }
            }
        }

        /**
         * Replaces a stack's entries container with a single status line (loading / error), built
         * via textContent so the i18n string is never interpreted as HTML.
         *
         * @param {Element} content the entries container
         * @param {string} message the localized status text
         * @param {boolean} isError whether to render it as an error
         */
        function setStackEntriesStatus(content, message, isError) {
            content.replaceChildren();
            const div = document.createElement('div');
            div.className = 'stack-entries-status';
            if (isError) div.classList.add('hud-box-error');
            div.style.padding = '1rem 2.5rem';
            div.style.color = 'var(--color-gray-2)';
            div.textContent = message;
            content.appendChild(div);
        }

        /**
         * Fetches one page of a stack's entries and injects the server-rendered fragment. The
         * injected rows carry the page's data-trigger hooks, so the delegated handlers keep working
         * without re-binding.
         *
         * @param {Element} headerRow the stack header row
         * @param {number} page the zero-based entries page
         */
        function loadStackEntries(headerRow, page) {
            const entriesRow = headerRow.nextElementSibling;
            if (!entriesRow) return;
            const content = /** @type {HTMLElement | null} */ (
                entriesRow.querySelector('.stack-entries-content')
            );
            if (!content) return;
            setStackEntriesStatus(content, stackEntriesI18n.loading, false);
            fetch(buildStackEntriesUrl(headerRow, page), {
                headers: { 'X-Requested-With': 'XMLHttpRequest' },
            })
                .then(function (r) {
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    return r.text();
                })
                .then(function (html) {
                    window.krtFetch.setTrustedHtml(content, html);
                    headerRow.setAttribute('data-stack-loaded', 'true');
                    // The entries are injected via innerHTML (not krtFetch.swap), so no
                    // krt:swapped fires — enhance the Variante-C allocation "+ Zuordnen"
                    // <select data-krt-combobox> popovers by hand (REQ-INV-027), else they stay
                    // raw native selects and the add-open reset has nothing to target.
                    if (typeof window.krtEnhanceComboboxes === 'function') {
                        window.krtEnhanceComboboxes(content);
                    }
                    if (cfg.onStackEntriesLoaded) cfg.onStackEntriesLoaded(content);
                })
                .catch(function (e) {
                    console.error('Failed to load stack entries', e);
                    setStackEntriesStatus(content, stackEntriesI18n.error, true);
                });
        }

        /**
         * Pagination handler: re-fetches the target page for the stack owning the clicked button
         * and replaces the entries container in place.
         *
         * @param {Element} btn the clicked page button
         */
        function goToStackEntriesPage(btn) {
            const entriesRow = btn.closest('.tree-stack-entries');
            if (!entriesRow) return;
            const headerRow = entriesRow.previousElementSibling;
            if (!headerRow) return;
            const page = parseInt(btn.getAttribute('data-page') ?? '', 10);
            loadStackEntries(headerRow, isNaN(page) ? 0 : page);
        }

        // ===================== Cross-feature live-sync (#1309) ================================

        /**
         * Tells each affected job order's detail viewers to re-pull their material collection (its
         * stock column tracks the earmark roll-up) and item-stock panel, plus — once — the
         * cross-order material-demand overview in the global `orders` room. The actor is not in
         * those rooms, so there is no self-refresh.
         *
         * @param {Array<string | null | undefined> | null | undefined} orderIds the affected orders
         */
        function broadcastOrdersChanged(orderIds) {
            if (!window.krtLiveSync || typeof window.krtLiveSync.sendChanged !== 'function') return;
            let touchedAnyOrder = false;
            (orderIds || []).forEach(function (orderId) {
                if (orderId) {
                    touchedAnyOrder = true;
                    window.krtLiveSync.sendChanged('order:' + orderId, [
                        'materials',
                        'aggregated',
                        'item-stock',
                    ]);
                }
            });
            if (touchedAnyOrder) {
                window.krtLiveSync.sendChanged('orders', ['demand']);
            }
        }

        /**
         * Tells the Materialbörse to re-pull its board after a stock-reducing write (the backend
         * clamps an offer down to the remaining stock).
         */
        function broadcastBoardChanged() {
            if (window.krtLiveSync && typeof window.krtLiveSync.sendChanged === 'function') {
                window.krtLiveSync.sendChanged('materialboard', ['board']);
            }
        }

        /**
         * The job-order ids currently earmarked on an entry's leaf row — read before a stock write,
         * so the affected orders are known even for a rest-first book-out the backend distributes.
         *
         * @param {string | null} itemId the inventory entry id
         * @returns {string[]} the distinct order ids
         */
        function collectLeafOrderIds(itemId) {
            const leaf = document.querySelector('.tree-row--leaf[data-item-id="' + itemId + '"]');
            if (!leaf) return [];
            /** @type {string[]} */
            const ids = [];
            leaf.querySelectorAll(
                '.assoc-split[data-assoc-field="JOB_ORDER"] [data-assoc-chip][data-target-id]',
            ).forEach(function (chip) {
                const id = chip.getAttribute('data-target-id');
                if (id && ids.indexOf(id) < 0) ids.push(id);
            });
            return ids;
        }

        // ===================== Amount <-> target coupling =====================================

        /**
         * Keeps an amount field and its "target stock" twin consistent: writing one sets the other
         * to `max - value`, rounded to three decimals.
         *
         * @param {string} sourceId the field the user typed into
         * @param {string} otherId the field to recompute
         * @param {string} maxId the hidden field carrying the entry's amount
         */
        function coupleAmounts(sourceId, otherId, maxId) {
            const source = input(sourceId);
            const other = input(otherId);
            const maxEl = input(maxId);
            if (!source || !other || !maxEl || !window.krtScuInput) return;
            const value = window.krtScuInput.parse(source.value);
            const max = parseFloat(maxEl.value) || 0;
            if (!isNaN(value)) {
                other.value = String(Number(Math.max(0, max - value).toFixed(3)));
            }
        }

        /** Book-out: recomputes the amount from the typed target stock. */
        function updateAmountFromTarget() {
            coupleAmounts('targetAmount', 'amount', 'maxAmount');
        }

        /** Book-out: recomputes the target stock from the typed amount. */
        function updateTargetFromAmount() {
            coupleAmounts('amount', 'targetAmount', 'maxAmount');
        }

        /** Umbuchen: recomputes the amount from the typed target stock. */
        function updateUmbuchenAmountFromTarget() {
            coupleAmounts('umbuchenTargetAmount', 'umbuchenAmount', 'umbuchenMaxAmount');
        }

        /** Umbuchen: recomputes the target stock from the typed amount. */
        function updateUmbuchenTargetFromAmount() {
            coupleAmounts('umbuchenAmount', 'umbuchenTargetAmount', 'umbuchenMaxAmount');
        }

        // ===================== Book-out modal (Ausbuchen) =====================================

        /**
         * Shows the sell fields (terminal + proceeds, both required) for a SELL and hides them for a
         * DISCARD, and relabels the submit button to match.
         */
        function toggleBookOutTypeFields() {
            const sellRadio = sellTypeRadio();
            const typeSell = !!(sellRadio && sellRadio.checked);
            const sellFields = document.getElementById('sellFields');
            const terminal = /** @type {HTMLSelectElement | null} */ (
                document.getElementById('terminal')
            );
            const sellAmount = input('sellAmount');
            if (sellFields) sellFields.style.display = typeSell ? 'block' : 'none';
            if (terminal) terminal.required = typeSell;
            if (sellAmount) sellAmount.required = typeSell;
            const submitBtn = document.getElementById('bookOutSubmitBtn');
            if (submitBtn) {
                submitBtn.textContent = submitBtn.getAttribute(
                    typeSell ? 'data-text-sell' : 'data-text-discard',
                );
            }
        }

        /**
         * The book-out type radio for the given value.
         *
         * @param {string} value `SELL` or `DISCARD`
         * @returns {HTMLInputElement | null} the radio
         */
        function typeRadio(value) {
            return /** @type {HTMLInputElement | null} */ (
                document.querySelector('input[name="type"][value="' + value + '"]')
            );
        }

        /**
         * The SELL radio of the book-out modal.
         *
         * @returns {HTMLInputElement | null} the radio
         */
        function sellTypeRadio() {
            return typeRadio('SELL');
        }

        /**
         * Falls back to DISCARD when a sale turned out impossible (no terminal buys the material,
         * or the lookup failed), and shows the reason.
         *
         * @param {HTMLInputElement} sellRadio the SELL radio
         * @param {HTMLElement | null} reason the "no sale possible" hint
         */
        function disableSell(sellRadio, reason) {
            sellRadio.disabled = true;
            if (sellRadio.checked) {
                const discard = typeRadio('DISCARD');
                if (discard) discard.checked = true;
                toggleBookOutTypeFields();
            }
            if (reason) reason.style.display = 'inline';
        }

        /**
         * Loads the terminals that buy the entry's material into the book-out terminal select and
         * enables SELL when there is at least one.
         *
         * @param {string | null} materialId the entry's material, or null for a game item
         * @param {HTMLSelectElement} terminalSelect the terminal select
         * @param {HTMLInputElement} sellRadio the SELL radio
         * @param {HTMLElement | null} reason the "no sale possible" hint
         */
        function loadSellTerminals(materialId, terminalSelect, sellRadio, reason) {
            setPlaceholderOption(terminalSelect, bookOutI18n.terminalLoading);
            if (!materialId) {
                setPlaceholderOption(terminalSelect, bookOutI18n.terminalNoMaterial);
                sellRadio.disabled = true;
                if (reason) reason.style.display = 'inline';
                return;
            }
            fetch('/api/proxy/materials/' + encodeURIComponent(materialId) + '/terminals')
                .then(function (r) {
                    if (!r.ok) throw new Error('Network response was not ok');
                    return r.json();
                })
                .then(function (data) {
                    if (data && data.length > 0) {
                        setPlaceholderOption(terminalSelect, bookOutI18n.terminalChoose);
                        sellRadio.disabled = false;
                        data.forEach(
                            /** @param {{ terminalName: string, priceSell?: number }} terminal */
                            function (terminal) {
                                const label =
                                    terminal.priceSell && terminal.priceSell > 0
                                        ? bookOutI18n.terminalPrice
                                              .replace('{0}', terminal.terminalName)
                                              .replace('{1}', String(terminal.priceSell))
                                        : terminal.terminalName;
                                terminalSelect.appendChild(
                                    new Option(label, terminal.terminalName),
                                );
                            },
                        );
                    } else {
                        setPlaceholderOption(terminalSelect, bookOutI18n.terminalNone);
                        disableSell(sellRadio, reason);
                    }
                })
                .catch(function (e) {
                    console.error('Error loading terminals:', e);
                    setPlaceholderOption(terminalSelect, bookOutI18n.terminalError);
                    disableSell(sellRadio, reason);
                });
        }

        /**
         * Opens the book-out modal for one leaf entry.
         *
         * @param {string | null} id the entry id
         * @param {string | null} amount the entry's amount
         * @param {string | null} version the entry's optimistic-lock version
         * @param {string | null} materialId the entry's material, or null for a game item
         * @param {string | null} quantityType `PIECE` or `SCU`
         */
        function openBookOutModal(id, amount, version, materialId, quantityType) {
            const bookOutForm = /** @type {HTMLFormElement | null} */ (
                document.getElementById('bookOutForm')
            );
            const amountInput = input('amount');
            const targetAmountInput = input('targetAmount');
            const terminalSelect = /** @type {HTMLSelectElement | null} */ (
                document.getElementById('terminal')
            );
            const sellRadio = sellTypeRadio();
            const discardRadio = typeRadio('DISCARD');
            const modal = document.getElementById('bookOutModal');
            if (
                !bookOutForm ||
                !amountInput ||
                !targetAmountInput ||
                !terminalSelect ||
                !sellRadio ||
                !discardRadio ||
                !modal
            ) {
                return;
            }
            bookOutItemId = id;
            if (window.safeSameOriginUrl) {
                bookOutForm.action = window.safeSameOriginUrl(
                    '/inventory/' + id + '/book-out',
                    bookOutForm.action,
                );
            }
            const isScu = quantityType !== 'PIECE';
            amountInput.setAttribute('step', isScu ? '0.001' : '1');
            targetAmountInput.setAttribute('step', isScu ? '0.001' : '1');
            const targetScuHint = document.getElementById('bookout-target-scu-hint');
            const amountScuHint = document.getElementById('bookout-amount-scu-hint');
            if (targetScuHint) targetScuHint.classList.toggle('krtm-hidden', !isScu);
            if (amountScuHint) amountScuHint.classList.toggle('krtm-hidden', !isScu);

            amountInput.value = amount ?? '';
            amountInput.max = amount ?? '';
            targetAmountInput.value = '0';
            const maxAmount = input('maxAmount');
            if (maxAmount) maxAmount.value = amount ?? '';
            const amountOfSpan = document.getElementById('amountOfText');
            if (amountOfSpan) {
                amountOfSpan.textContent = (
                    amountOfSpan.getAttribute('data-template') ?? ''
                ).replace('{0}', amount ?? '');
            }
            const versionInput = input('version');
            if (versionInput) versionInput.value = version ?? '';
            discardRadio.checked = true;
            toggleBookOutTypeFields();

            const sellNotPossibleReason = document.getElementById('sellNotPossibleReason');
            sellRadio.disabled = true;
            if (sellNotPossibleReason) sellNotPossibleReason.style.display = 'none';
            loadSellTerminals(materialId, terminalSelect, sellRadio, sellNotPossibleReason);

            // Inline `flex` (not `block`) so `.modal`'s flex centring is preserved (#1328).
            modal.style.display = 'flex';
            // Variante C (REQ-INV-027): build the "Herkunft" (deduct-from) picker from this
            // entry's chips now that the modal is shown, so its initial validity gates the submit.
            if (window.krtHerkunft && id) {
                window.krtHerkunft.populate('bookout', id);
            }
        }

        /** Closes the book-out modal and resets its unsaved-changes and Herkunft state. */
        function closeBookOutModal() {
            if (typeof window.resetUnsavedChanges === 'function') {
                window.resetUnsavedChanges();
            }
            if (window.krtHerkunft) {
                window.krtHerkunft.reset('bookout');
            }
            const modal = document.getElementById('bookOutModal');
            if (modal) modal.style.display = 'none';
        }

        /**
         * Submits the book-out in place through krtFetch (#577 part 2), reusing the POST
         * /inventory/{id}/transfer proxy. On success the grouped table is re-pulled (the server
         * regroups) instead of the page reloading; the classic POST stays the no-JS fallback.
         *
         * @param {SubmitEvent} event the form submit
         */
        function submitBookOut(event) {
            // scu-decimal-input.js canonicalises + validates the amount fields in the capture phase
            // first; if it found an invalid amount it already blocked the submit. Respect that.
            if (event.defaultPrevented) return;
            event.preventDefault();
            if (bookOutInFlight || !window.krtFetch || !bookOutItemId) return;
            const itemId = bookOutItemId;
            const typeInput = /** @type {HTMLInputElement | null} */ (
                document.querySelector('input[name="type"]:checked')
            );
            const type = typeInput ? typeInput.value : 'DISCARD';
            const amountEl = input('amount');
            const sellAmountEl = input('sellAmount');
            const terminalEl = /** @type {HTMLSelectElement | null} */ (
                document.getElementById('terminal')
            );
            const versionEl = input('version');
            if (!amountEl || !versionEl) return;
            const amount = window.krtScuInput
                ? window.krtScuInput.parse(amountEl.value)
                : parseFloat(amountEl.value);
            // Variante C (REQ-INV-027): the "Herkunft" picker chooses which order/mission slices
            // (or the rest) the deduction comes from. An invalid plan already disables the submit
            // button; guard the Enter-key path too. A null list means "take it from the rest".
            if (window.krtHerkunft && !window.krtHerkunft.isValid('bookout')) {
                if (typeof window.showFrontendErrorToast === 'function') {
                    window.showFrontendErrorToast(assocI18n.overallocated);
                }
                return;
            }
            const reductions = window.krtHerkunft
                ? window.krtHerkunft.collect('bookout')
                : { jobOrderReductions: null, missionReductions: null };
            // Ausbuchen only discards or sells — the transfer-only fields stay null.
            const payload = {
                amount: amount,
                type: type,
                terminal: type === 'SELL' && terminalEl ? terminalEl.value || null : null,
                sellAmount:
                    type === 'SELL' && sellAmountEl && sellAmountEl.value !== ''
                        ? Number(sellAmountEl.value)
                        : null,
                version: parseInt(versionEl.value, 10),
                jobOrderReductions: reductions.jobOrderReductions,
                missionReductions: reductions.missionReductions,
            };
            const submitBtn = /** @type {HTMLButtonElement | null} */ (
                document.getElementById('bookOutSubmitBtn')
            );
            // Read the earmarked orders before the write (the leaf is replaced on the re-swap).
            const affectedOrderIds = collectLeafOrderIds(itemId);
            bookOutInFlight = true;
            if (submitBtn) submitBtn.disabled = true;
            window.krtFetch
                .write({
                    method: 'POST',
                    url: '/inventory/' + itemId + '/transfer',
                    payload: payload,
                    successMessage: bookOutI18n.success,
                    errorMessage: bookOutI18n.error,
                    conflict: inventoryConflictI18n,
                    onSuccess: function () {
                        closeBookOutModal();
                        cfg.refreshTable();
                        cfg.notifyInventoryChanged();
                        broadcastOrdersChanged(affectedOrderIds);
                        broadcastBoardChanged();
                    },
                })
                .then(function () {
                    bookOutInFlight = false;
                    if (submitBtn) submitBtn.disabled = false;
                });
        }

        // ===================== Umbuchen (shared parts) ========================================

        /** Closes the Umbuchen modal and resets its unsaved-changes and Herkunft state. */
        function closeUmbuchenModal() {
            if (typeof window.resetUnsavedChanges === 'function') window.resetUnsavedChanges();
            if (window.krtHerkunft) {
                window.krtHerkunft.reset('umbuchen');
            }
            const modal = document.getElementById('umbuchenModal');
            if (modal) modal.style.display = 'none';
        }

        /**
         * Records the Umbuchen row's current owning org unit, which the target-OrgUnit picker is
         * preset to (#1328). Called by the page's Umbuchen open.
         *
         * @param {string | null} orgUnitId the row's owning org unit, or null for an ownerless row
         */
        function setUmbuchenCurrentOwningOrgUnit(orgUnitId) {
            umbuchenCurrentOwningOrgUnitId = orgUnitId || null;
        }

        /**
         * Fills the Umbuchen target-OrgUnit picker with the selected target user's direct
         * memberships across all four org-unit kinds (#1328, `?allKinds=true`, mirroring the bank
         * counterparty picker REQ-BANK-044), preset to the row's current owning unit when the
         * target is a member of it. Hidden for a membershipless target. The fetch goes through the
         * frontend's /users/{id}/memberships proxy — the frontend origin maps no /api/v1/users/**.
         */
        function refreshUmbuchenTransferOrgUnitPicker() {
            const wrapper = document.getElementById('umbuchenTargetOwningOrgUnitWrapper');
            const select = /** @type {HTMLSelectElement | null} */ (
                document.getElementById('umbuchenTargetOwningOrgUnitId')
            );
            const userSelect = input('umbuchenTargetUserId');
            if (!wrapper || !select || !userSelect) return;
            const targetUserId = userSelect.value;
            select.replaceChildren();
            if (!targetUserId) {
                wrapper.style.display = 'none';
                return;
            }
            fetch('/users/' + encodeURIComponent(targetUserId) + '/memberships?allKinds=true', {
                headers: { Accept: 'application/json' },
                credentials: 'same-origin',
            })
                .then(function (r) {
                    return r.ok ? r.json() : [];
                })
                .then(
                    /** @param {Array<{ orgUnitId: string, orgUnitName: string }>} memberships */
                    function (memberships) {
                        if (!Array.isArray(memberships) || memberships.length < 1) {
                            wrapper.style.display = 'none';
                            return;
                        }
                        memberships.forEach(function (opt) {
                            select.appendChild(new Option(opt.orgUnitName, opt.orgUnitId));
                        });
                        const current = umbuchenCurrentOwningOrgUnitId;
                        if (
                            current &&
                            memberships.some(function (m) {
                                return m.orgUnitId === current;
                            })
                        ) {
                            select.value = current;
                        }
                        wrapper.style.display = 'block';
                    },
                )
                .catch(function () {
                    wrapper.style.display = 'none';
                });
        }

        // ===================== Variante C allocation chips (REQ-INV-027) ======================
        // Each .assoc-split (one per dimension per entry) renders its job-order / mission
        // allocations as chips + a trailing rest chip, plus a "+ Zuordnen" combobox popover. Add /
        // edit / remove call the per-allocation endpoints (POST/PATCH/DELETE
        // /inventory/{id}/allocation) and update the split in place from the returned
        // InventoryItemDto (chips + rest + version), so the drilled-down stack stays expanded and
        // no full-page reload is needed (REQ-FE-001).

        /**
         * Formats an amount for a chip / rest label: whole for PIECE, three decimals for SCU.
         *
         * @param {number | string} amount the amount
         * @param {boolean} isPiece whether the material counts pieces
         * @returns {string} the label
         */
        function assocFormatAmount(amount, isPiece) {
            const n = typeof amount === 'number' ? amount : parseFloat(amount);
            if (isNaN(n)) return '0';
            return isPiece ? String(Math.round(n)) : n.toFixed(3);
        }

        /**
         * Hides every open allocation popover except `except` (the one being opened).
         *
         * @param {Element | null} except the popover to leave alone
         */
        function assocCloseAllPops(except) {
            document.querySelectorAll('[data-assoc-pop]').forEach(function (p) {
                if (p !== except) p.classList.add('krtm-hidden');
            });
        }

        /**
         * Anchors a `position: fixed` allocation popover to its trigger in viewport space (fixed so
         * the horizontally scrolling table containers cannot crop it). Flips above the trigger only
         * when it actually fits there, and clamps into the viewport either way, since a fixed box
         * cannot be scrolled into view (REQ-UI-011). Runs while the popover is visible.
         *
         * @param {HTMLElement} pop the popover
         */
        function assocPositionPop(pop) {
            const wrap = pop.closest('.assoc-add-wrap');
            if (!wrap) return;
            const rect = wrap.getBoundingClientRect();
            const gap = 5;
            const popHeight = pop.offsetHeight;
            const below = window.innerHeight - rect.bottom;
            const above = rect.top;
            const flipUp = below < popHeight + gap && above >= popHeight + gap;
            const maxTop = Math.max(gap, window.innerHeight - popHeight - gap);
            const wantedTop = flipUp ? rect.top - gap - popHeight : rect.bottom + gap;
            const top = Math.max(gap, Math.min(wantedTop, maxTop));
            pop.style.left = rect.left + 'px';
            if (flipUp) {
                // Bottom-anchored so a later switch to the taller/shorter amount section keeps the
                // popover's lower edge glued to the trigger.
                pop.style.top = 'auto';
                pop.style.bottom = window.innerHeight - top - popHeight + 'px';
            } else {
                pop.style.bottom = 'auto';
                pop.style.top = top + 'px';
            }
        }

        /**
         * Keeps the open popover glued to its trigger while the window or the table's own scroll
         * container moves. Only one popover is open at a time, so the first visible one wins.
         */
        function assocRepositionOpenPop() {
            const pop = /** @type {HTMLElement | null} */ (
                document.querySelector('[data-assoc-pop]:not(.krtm-hidden)')
            );
            if (pop) assocPositionPop(pop);
        }

        /**
         * Switches a popover to its combobox (pick) section.
         *
         * @param {Element} pop the popover
         */
        function assocShowPickSection(pop) {
            const pick = pop.querySelector('[data-assoc-pop-pick]');
            const amount = pop.querySelector('[data-assoc-pop-amount]');
            if (pick) pick.classList.remove('krtm-hidden');
            if (amount) amount.classList.add('krtm-hidden');
        }

        /**
         * Switches a popover to its amount-editor section; `showRemove` reveals the remove button
         * (edit mode).
         *
         * @param {Element} pop the popover
         * @param {boolean} showRemove whether the slice exists and can be removed
         */
        function assocShowAmountSection(pop, showRemove) {
            const pick = pop.querySelector('[data-assoc-pop-pick]');
            const amount = pop.querySelector('[data-assoc-pop-amount]');
            if (pick) pick.classList.add('krtm-hidden');
            if (amount) amount.classList.remove('krtm-hidden');
            const removeBtn = pop.querySelector('[data-trigger="' + trigger('assoc-remove') + '"]');
            if (removeBtn) removeBtn.classList.toggle('krtm-hidden', !showRemove);
        }

        /**
         * Builds one allocation chip element from a returned allocation DTO.
         *
         * @param {string | null} field `JOB_ORDER` or `MISSION`
         * @param {any} alloc the allocation DTO
         * @param {boolean} isPiece whether the material counts pieces
         * @returns {HTMLSpanElement} the chip
         */
        function assocBuildChip(field, alloc, isPiece) {
            const isOrder = field === 'JOB_ORDER';
            const chip = document.createElement('span');
            chip.className =
                'assoc-chip ' + (isOrder ? 'assoc-chip--order' : 'assoc-chip--mission');
            chip.setAttribute('role', 'button');
            chip.setAttribute('tabindex', '0');
            chip.setAttribute('data-trigger', trigger('assoc-edit'));
            chip.setAttribute('data-assoc-chip', isOrder ? 'jobOrder' : 'mission');
            chip.setAttribute('data-target-id', isOrder ? alloc.jobOrderId : alloc.missionId);
            chip.setAttribute('data-amount', alloc.amount);
            const label = isOrder ? '#' + alloc.jobOrderDisplayId : alloc.missionName;
            chip.appendChild(document.createTextNode(label + ' · '));
            const amt = document.createElement('span');
            amt.className = 'assoc-chip__amt';
            amt.textContent = assocFormatAmount(alloc.amount, isPiece);
            chip.appendChild(amt);
            return chip;
        }

        /**
         * Recomputes a rest chip's tone + label: 0 -> success, unassigned remainder -> muted,
         * over-allocation (negative) -> danger.
         *
         * @param {Element | null} el the rest chip
         * @param {number | null | undefined} rest the dimension's rest
         * @param {boolean} isPiece whether the material counts pieces
         */
        function assocUpdateRestChip(el, rest, isPiece) {
            if (!el) return;
            el.classList.remove('chip--success', 'chip--muted', 'chip--danger');
            if (rest == null || Math.abs(rest) <= ASSOC_EPS) {
                el.classList.add('chip--success');
                el.textContent = assocI18n.restZero;
            } else if (rest < 0) {
                el.classList.add('chip--danger');
                el.textContent = assocI18n.restOver.replace(
                    '{0}',
                    assocFormatAmount(-rest, isPiece),
                );
            } else {
                el.classList.add('chip--muted');
                el.textContent = assocI18n.restFree.replace(
                    '{0}',
                    assocFormatAmount(rest, isPiece),
                );
            }
        }

        /**
         * Re-renders a split's chips + rest from the returned entry DTO and propagates the fresh
         * entry version to every data-version control in the leaf row (both dimensions share it).
         *
         * @param {Element} split the .assoc-split
         * @param {any} dto the returned InventoryItemDto
         */
        function assocRerender(split, dto) {
            const field = split.getAttribute('data-assoc-field');
            const isPiece = split.getAttribute('data-piece') === 'true';
            const isOrder = field === 'JOB_ORDER';
            const allocs = (isOrder ? dto.jobOrderAllocations : dto.missionAllocations) || [];
            const rest = isOrder ? dto.jobOrderRest : dto.missionRest;
            split.querySelectorAll('[data-assoc-chip]').forEach(function (c) {
                c.remove();
            });
            const addWrap = split.querySelector('.assoc-add-wrap');
            allocs.forEach(
                /** @param {any} a */
                function (a) {
                    split.insertBefore(assocBuildChip(field, a, isPiece), addWrap);
                },
            );
            assocUpdateRestChip(split.querySelector('[data-assoc-rest]'), rest, isPiece);
            if (
                dto.version != null &&
                window.krtFetch &&
                typeof window.krtFetch.syncVersion === 'function'
            ) {
                const leaf = split.closest('.tree-row--leaf');
                if (leaf) window.krtFetch.syncVersion(leaf, dto.version);
            }
        }

        /**
         * The chip for `targetId` in this split, or null. The chips are re-rendered from each
         * write's own response, so — unlike the picker's server-rendered option list — they always
         * reflect what is currently allocated.
         *
         * @param {Element | null} split the .assoc-split
         * @param {string} targetId the order or mission id
         * @returns {Element | null} the chip
         */
        function assocFindChip(split, targetId) {
            if (!split || !targetId) return null;
            const chips = split.querySelectorAll('[data-assoc-chip][data-target-id]');
            for (let i = 0; i < chips.length; i++) {
                if (chips[i].getAttribute('data-target-id') === targetId) return chips[i];
            }
            return null;
        }

        /**
         * Sends the allocation write through krtFetch (CSRF, the bare-403 retry, the re-auth
         * redirect). The DELETE mapping reads the same body, hence bodyOnDelete. A 422
         * over-allocation toasts and keeps the popover open; anything else falls through to
         * krtFetch (a 409 gets the conflict confirm, other refusals toast the backend's detail).
         *
         * @param {string | null} entryId the inventory entry id
         * @param {string} method POST, PATCH or DELETE
         * @param {{ field: string | null, targetId: string | null, amount: number | null, version: number }} body the payload
         * @param {Element} split the .assoc-split
         * @param {Element} pop the popover
         * @returns {Promise<void>} settles when the write finished
         */
        async function assocSend(entryId, method, body, split, pop) {
            if (!window.krtFetch) return;
            await window.krtFetch.write({
                method: method,
                url: '/inventory/' + encodeURIComponent(entryId ?? '') + '/allocation',
                payload: body,
                bodyOnDelete: true,
                successMessage: assocI18n.saved,
                errorMessage: assocI18n.failed,
                conflict: Object.assign({}, inventoryConflictI18n, {
                    reloadDetailFallback: assocI18n.conflict,
                }),
                onSuccess: function (dto) {
                    if (dto && typeof dto === 'object') assocRerender(split, dto);
                    pop.classList.add('krtm-hidden');
                    cfg.notifyInventoryChanged();
                    // A job-order earmark change shifts that order's material collection.
                    if (body && body.field === 'JOB_ORDER') {
                        broadcastOrdersChanged([body.targetId]);
                    }
                },
                onError: function (status) {
                    if (status === 422) {
                        if (typeof window.showFrontendErrorToast === 'function') {
                            window.showFrontendErrorToast(assocI18n.overallocated);
                        }
                        return true;
                    }
                    return false;
                },
            });
        }

        /**
         * Sends an allocation write, serialized per entry so a rapid second edit of the same row
         * waits for the fresh version (REQ-INV-026 / REQ-FE-003). Guards double-submit itself —
         * krtFetch's submitter capture only sees form submits — by disabling the popover's buttons
         * synchronously, before the serialized send is deferred.
         *
         * @param {Element} split the .assoc-split
         * @param {Element} pop the popover
         * @param {string} method POST, PATCH or DELETE
         * @returns {Promise<void> | undefined} the queued write, or undefined when refused
         */
        function assocSubmit(split, pop, method) {
            const entryId = split.getAttribute('data-entry-id');
            const field = split.getAttribute('data-assoc-field');
            const targetId = pop.getAttribute('data-assoc-target');
            const isPiece = split.getAttribute('data-piece') === 'true';
            /** @type {number | null} */
            let amount = null;
            if (method !== 'DELETE') {
                const amountInput = /** @type {HTMLInputElement | null} */ (
                    pop.querySelector('[data-assoc-amount-input]')
                );
                amount = parseFloat(amountInput ? amountInput.value : '');
                if (isNaN(amount) || amount <= 0 || (isPiece && amount % 1 !== 0)) {
                    if (typeof window.showFrontendErrorToast === 'function') {
                        window.showFrontendErrorToast(assocI18n.amountRequired);
                    }
                    return;
                }
                amount = Math.round(amount * 1000) / 1000;
            }
            const buttons = pop.querySelectorAll('button');
            buttons.forEach(function (b) {
                b.disabled = true;
            });
            const release = function () {
                buttons.forEach(function (b) {
                    b.disabled = false;
                });
            };
            const run = function () {
                // Read the entry version at SEND time, not click time (REQ-FE-003): a queued second
                // edit of the same entry picks up the version the first one synced onto the split.
                const version = parseInt(split.getAttribute('data-version') ?? '', 10);
                const body = { field: field, targetId: targetId, amount: amount, version: version };
                return assocSend(entryId, method, body, split, pop);
            };
            if (window.krtFetch && typeof window.krtFetch.serialize === 'function') {
                return window.krtFetch.serialize('inv-assoc:' + entryId, run).finally(release);
            }
            return Promise.resolve().then(run).finally(release);
        }

        /**
         * The split + popover a delegated allocation event belongs to.
         *
         * @param {Element} el the element the event fired on
         * @returns {{ split: Element, pop: HTMLElement } | null} both, or null when either is missing
         */
        function assocContext(el) {
            const split = el.closest('.assoc-split');
            const pop = /** @type {HTMLElement | null} */ (
                split ? split.querySelector('[data-assoc-pop]') : null
            );
            return split && pop ? { split: split, pop: pop } : null;
        }

        /**
         * The POST/PATCH verb a popover's save sends: PATCH when it edits an existing slice.
         *
         * @param {Element} pop the popover
         * @returns {string} the HTTP method
         */
        function assocSaveMethod(pop) {
            return pop.getAttribute('data-assoc-mode') === 'edit' ? 'PATCH' : 'POST';
        }

        /** Installs the delegated allocation-chip handlers and the popover's window listeners. */
        function bindAssoc() {
            window.krtEvents.on('click', trigger('assoc-add-open'), function (el) {
                const ctx = assocContext(el);
                if (!ctx) return;
                const pop = ctx.pop;
                const wasHidden = pop.classList.contains('krtm-hidden');
                assocCloseAllPops(pop);
                if (wasHidden) {
                    pop.removeAttribute('data-assoc-target');
                    assocShowPickSection(pop);
                    const hidden = /** @type {HTMLInputElement | null} */ (
                        pop.querySelector('input[type="hidden"]')
                    );
                    if (hidden && hidden.krtCombobox) hidden.krtCombobox.setValue('');
                    pop.classList.remove('krtm-hidden');
                    assocPositionPop(pop);
                    const cbInput = /** @type {HTMLElement | null} */ (
                        pop.querySelector('.krt-combobox__input')
                    );
                    if (cbInput) cbInput.focus();
                } else {
                    pop.classList.add('krtm-hidden');
                }
            });
            window.krtEvents.on('change', trigger('assoc-pick'), function (el) {
                const value = /** @type {HTMLInputElement} */ (el).value;
                if (!value) return;
                const pop = el.closest('[data-assoc-pop]');
                if (!pop) return;
                // The <option> list drops already-allocated targets at fragment-RENDER time only,
                // so a target THIS viewer allocated since the render is still listed. The chips are
                // current: resolve the pick against them and open an existing slice in edit mode
                // instead of POSTing a duplicate.
                const existing = assocFindChip(pop.closest('.assoc-split'), value);
                pop.setAttribute('data-assoc-target', value);
                pop.setAttribute('data-assoc-mode', existing ? 'edit' : 'add');
                assocShowAmountSection(pop, !!existing);
                const amountInput = /** @type {HTMLInputElement | null} */ (
                    pop.querySelector('[data-assoc-amount-input]')
                );
                if (amountInput) {
                    amountInput.value = existing
                        ? (existing.getAttribute('data-amount') ?? '')
                        : '';
                    amountInput.focus();
                }
            });
            window.krtEvents.on('click', trigger('assoc-edit'), function (el) {
                const ctx = assocContext(el);
                if (!ctx) return;
                const pop = ctx.pop;
                assocCloseAllPops(pop);
                pop.setAttribute('data-assoc-target', el.getAttribute('data-target-id') ?? '');
                pop.setAttribute('data-assoc-mode', 'edit');
                assocShowAmountSection(pop, true);
                const amountInput = /** @type {HTMLInputElement | null} */ (
                    pop.querySelector('[data-assoc-amount-input]')
                );
                if (amountInput) amountInput.value = el.getAttribute('data-amount') ?? '';
                pop.classList.remove('krtm-hidden');
                assocPositionPop(pop);
                if (amountInput) amountInput.focus();
            });
            window.krtEvents.on('click', trigger('assoc-save'), function (el) {
                const pop = el.closest('[data-assoc-pop]');
                const split = el.closest('.assoc-split');
                if (!pop || !split) return;
                assocSubmit(split, pop, assocSaveMethod(pop));
            });
            window.krtEvents.on('click', trigger('assoc-remove'), function (el) {
                const pop = el.closest('[data-assoc-pop]');
                const split = el.closest('.assoc-split');
                if (!pop || !split) return;
                assocSubmit(split, pop, 'DELETE');
            });
            // Keep the fixed popover anchored while the page or the table's own horizontal scroll
            // container moves (capture reaches inner-container scrolls that don't bubble).
            window.addEventListener('scroll', assocRepositionOpenPop, true);
            window.addEventListener('resize', assocRepositionOpenPop);
            // Close popovers on an outside click; keyboard: Enter saves the amount, Enter/Space
            // opens a chip's editor (a role=button <span> gets no synthetic click on key press).
            document.addEventListener('click', function (e) {
                const target = /** @type {Element} */ (e.target);
                if (
                    !target.closest('[data-assoc-pop]') &&
                    !target.closest('.assoc-add') &&
                    !target.closest('[data-assoc-chip]')
                ) {
                    assocCloseAllPops(null);
                }
            });
            document.addEventListener('keydown', function (e) {
                const target = /** @type {HTMLElement | null} */ (e.target);
                if (!target || typeof target.matches !== 'function') return;
                if (e.key === 'Enter' && target.matches('[data-assoc-amount-input]')) {
                    e.preventDefault();
                    const pop = target.closest('[data-assoc-pop]');
                    const split = target.closest('.assoc-split');
                    if (pop && split) assocSubmit(split, pop, assocSaveMethod(pop));
                } else if (
                    (e.key === 'Enter' || e.key === ' ') &&
                    target.matches('[data-assoc-chip]')
                ) {
                    e.preventDefault();
                    target.click();
                }
            });
        }

        /**
         * Installs everything the shared Lager behaviour listens to: the delegated `<prefix>-*`
         * handlers for the tree, the allocation chips, the book-out modal and the Umbuchen parts
         * shared here; the book-out form's submit listener; the book-out backdrop close; and the
         * initial tree restore. Call once, where the page's own bindings run.
         */
        function bind() {
            document.addEventListener('DOMContentLoaded', restoreExpandedTree);
            if (window.krtEvents && typeof window.krtEvents.on === 'function') {
                window.krtEvents.on('click', trigger('toggle-group'), toggleGroup);
                window.krtEvents.on('click', trigger('toggle-stack'), toggleStack);
                window.krtEvents.on('click', trigger('stack-page'), goToStackEntriesPage);
                bindAssoc();
                window.krtEvents.on('click', trigger('bookout'), function (el) {
                    openBookOutModal(
                        el.getAttribute('data-id'),
                        el.getAttribute('data-amount'),
                        el.getAttribute('data-version'),
                        el.getAttribute('data-material-id'),
                        el.getAttribute('data-quantity-type'),
                    );
                });
                window.krtEvents.on('click', trigger('close-bookout'), closeBookOutModal);
                window.krtEvents.on('input', trigger('amount-from-target'), updateAmountFromTarget);
                window.krtEvents.on('input', trigger('target-from-amount'), updateTargetFromAmount);
                window.krtEvents.on(
                    'change',
                    trigger('toggle-bookout-type'),
                    toggleBookOutTypeFields,
                );
                window.krtEvents.on('click', trigger('close-umbuchen'), closeUmbuchenModal);
                window.krtEvents.on(
                    'input',
                    trigger('umbuchen-amount-from-target'),
                    updateUmbuchenAmountFromTarget,
                );
                window.krtEvents.on(
                    'input',
                    trigger('umbuchen-target-from-amount'),
                    updateUmbuchenTargetFromAmount,
                );
                window.krtEvents.on(
                    'change',
                    trigger('umbuchen-target-user-changed'),
                    refreshUmbuchenTransferOrgUnitPicker,
                );
            }
            // The book-out form is a stable top-level element (outside the swapped table
            // container), so a submit listener bound once survives the grouped-table re-swaps.
            const bookOutForm = document.getElementById('bookOutForm');
            if (bookOutForm) {
                bookOutForm.addEventListener('submit', function (e) {
                    submitBookOut(/** @type {SubmitEvent} */ (e));
                });
            }
            window.onclick = function (event) {
                const modal = document.getElementById('bookOutModal');
                if (modal && event.target === modal) {
                    modal.style.display = 'none';
                }
            };
        }

        return {
            lagerIsItemsView: lagerIsItemsView,
            restoreExpandedTree: restoreExpandedTree,
            collectLeafOrderIds: collectLeafOrderIds,
            broadcastOrdersChanged: broadcastOrdersChanged,
            broadcastBoardChanged: broadcastBoardChanged,
            closeUmbuchenModal: closeUmbuchenModal,
            setUmbuchenCurrentOwningOrgUnit: setUmbuchenCurrentOwningOrgUnit,
            refreshUmbuchenTransferOrgUnitPicker: refreshUmbuchenTransferOrgUnitPicker,
            bind: bind,
        };
    }

    window.krtInventory = { createLager: createLager };
})();
