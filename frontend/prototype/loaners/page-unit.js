/** Search and open drill-downs of the unit overview. */
const UNIT_VIEW = { search: '', open: new Set() };

/**
 * Collects the entries of unit ALP: the viewer's ships and shown loaners stamped ALP plus the
 * other members' entries.
 * @returns {Array<{owner: string, type: string, loaner: boolean, fitted: boolean, loc: string|null}>}
 */
function unitEntries() {
  const mine = MY_SHIPS.filter((s) => s.unit === 'ALP').map((s) => ({ owner: ME, type: s.type, loaner: false, fitted: s.fitted, loc: s.loc }));
  const loaners = [...heldLoaners(MY_SHIPS).keys()]
    .filter((t) => MY_LOANER_STATE[t].unit === 'ALP' && !MY_LOANER_STATE[t].hidden)
    .map((t) => ({ owner: ME, type: t, loaner: true, fitted: MY_LOANER_STATE[t].fitted, loc: MY_LOANER_STATE[t].loc }));
  const others = UNIT_OTHERS.map((o) => ({ ...o, loaner: Boolean(o.loaner) }));
  return [...mine, ...loaners, ...others];
}

/**
 * Groups entries by type in the fixed type order.
 * @param {ReturnType<typeof unitEntries>} entries
 * @returns {Array<{type: string, items: ReturnType<typeof unitEntries>}>}
 */
function groupByType(entries) {
  const map = new Map();
  for (const e of entries) map.set(e.type, [...(map.get(e.type) || []), e]);
  return [...map.entries()].sort(([a], [b]) => typeSortKey(a).localeCompare(typeSortKey(b))).map(([type, items]) => ({ type, items }));
}

/**
 * Returns the count of the unit tab: every entry outside „Nicht flugbereit".
 * @returns {number}
 */
function unitTabCount() {
  return unitEntries().filter((e) => TYPES[e.type].kind !== 'concept').length;
}

/**
 * Renders the owner rows of one type.
 * @param {string} id
 * @param {ReturnType<typeof unitEntries>} items
 * @param {boolean} concept
 * @returns {string}
 */
function ownerRows(id, items, concept) {
  const open = UNIT_VIEW.open.has(id);
  const rows = items.map((d) => `<tr class="hangar-tree__owner"><td><span class="hangar-tree__owner-name">${esc(d.owner)}${d.loaner ? ' <span class="chip chip--info ln-chip-loaner">Loaner</span>' : ''}</span></td><td class="num hangar-tree__owner-gap"></td>
    <td><div class="hangar-tree__owner-meta">${concept ? '<span class="ln-nofit">kein Einsatzstatus</span>' : fitBadge(d.fitted)}<span class="hangar-tree__owner-loc">${esc(d.loc || '–')}</span></div></td></tr>`).join('');
  return `<tbody class="hangar-tree__owners" id="${id}"${open ? '' : ' hidden'}>${rows}</tbody>`;
}

/**
 * Renders a type row of the overview.
 * @param {{type: string, items: ReturnType<typeof unitEntries>}} g
 * @param {string} id
 * @param {{officer: boolean, count: string}} o
 * @returns {string}
 */
function typeRow(g, id, o) {
  const n = g.items.length;
  const fitted = g.items.filter((e) => e.fitted).length;
  const loaners = g.items.filter((e) => e.loaner).length;
  const pct = n ? Math.round((fitted * 100) / n) : 0;
  const open = UNIT_VIEW.open.has(id);
  const toggle = o.officer ? `<button type="button" class="btn-icon hangar-tree__toggle" aria-expanded="${open}" data-act="toggle-owners" data-id="${id}" aria-label="Besitzer zeigen">${icon('chevron-right')}</button>` : '';
  const sub = o.count === 'B' && loaners ? `<span class="cell-sub">${TYPES[g.type].maker} · davon ${loaners} Loaner</span>` : `<span class="cell-sub">${esc(TYPES[g.type].maker)}</span>`;
  const countCell = o.count === 'C' && loaners ? `${n} <span class="chip chip--info ln-chip-loaner ln-chip-count" title="davon ${loaners} Loaner">${loaners} Loaner</span>` : String(n);
  return `<tbody class="hangar-tree__group"><tr class="hangar-tree__row${open ? ' is-open' : ''}"><td><div class="hangar-tree__type"><span class="hangar-tree__toggle-slot">${toggle}</span>
    <span class="hangar-tree__name"><span class="cell-title">${esc(TYPES[g.type].name)}</span> ${typeChip(g.type)}${sub}</span></div></td>
    <td class="num hangar-tree__count">${countCell}</td>
    <td class="hangar-tree__fit"><div class="hangar-tree__fit-inner"><div class="meter hangar-tree__meter" aria-hidden="true"><i class="meter__fill--success" style="width:${pct}%"></i></div><span class="hangar-tree__fit-text">${fitted} von ${n}</span></div></td></tr></tbody>
    ${o.officer ? ownerRows(id, g.items, false) : ''}`;
}

/**
 * Renders a row of the section „Nicht flugbereit": count, no readiness bar.
 * @param {{type: string, items: ReturnType<typeof unitEntries>}} g
 * @param {string} id
 * @param {boolean} officer
 * @returns {string}
 */
function conceptRow(g, id, officer) {
  const open = UNIT_VIEW.open.has(id);
  const toggle = officer ? `<button type="button" class="btn-icon hangar-tree__toggle" aria-expanded="${open}" data-act="toggle-owners" data-id="${id}" aria-label="Besitzer zeigen">${icon('chevron-right')}</button>` : '';
  const grants = grantsOf(g.type);
  return `<tbody class="hangar-tree__group"><tr class="hangar-tree__row ln-concept-row${open ? ' is-open' : ''}"><td><div class="hangar-tree__type"><span class="hangar-tree__toggle-slot">${toggle}</span>
    <span class="hangar-tree__name"><span class="cell-title">${esc(TYPES[g.type].name)}</span> ${typeChip(g.type)}<span class="cell-sub">${esc(TYPES[g.type].maker)}${grants.length ? ` · gewährt ${grants.map((x) => esc(TYPES[x].name)).join(', ')}` : ''}</span></span></div></td>
    <td class="num hangar-tree__count">${g.items.length}</td>
    <td class="hangar-tree__fit"><span class="ln-nofit">zählt nicht zur Einsatzbereitschaft</span></td></tr></tbody>
    ${officer ? ownerRows(id, g.items, true) : ''}`;
}

/**
 * Renders the unit overview tab.
 * @param {{officer: boolean, count: string, section: string}} o
 * @returns {string}
 */
function renderUnit(o) {
  const q = UNIT_VIEW.search.trim().toLowerCase();
  const entries = unitEntries().filter((e) => !q || typeLabel(e.type).toLowerCase().includes(q));
  const groups = groupByType(entries);
  const ships = groups.filter((g) => TYPES[g.type].kind !== 'concept');
  const concepts = groups.filter((g) => TYPES[g.type].kind === 'concept');
  const head = pageHead({ eyebrow: 'Einsatz & Flotte', title: 'Hangar' });
  const tabs = `<nav class="tab-nav hangar-tabs" aria-label="Hangar"><a href="#" class="tab" data-go="mine"><span>Meine Schiffe</span><span class="tab-count">${MY_SHIPS.length + [...heldLoaners(MY_SHIPS).keys()].filter((t) => !MY_LOANER_STATE[t].hidden).length}</span></a>
    <a href="#" class="tab active" aria-current="page"><span>Org-Einheit ALP</span><span class="tab-count">${unitTabCount()}</span></a></nav>`;
  const toolbar = `<div class="toolbar"><div class="toolbar__search">${icon('search')}<input type="search" data-input="unit-search" value="${esc(UNIT_VIEW.search)}" placeholder="Schiff, Typ oder Hersteller suchen" aria-label="Suchen"></div><span class="toolbar__spacer"></span>
    <span class="hangar-legend" aria-hidden="true"><span class="hangar-legend__item"><span class="hangar-legend__swatch hangar-legend__swatch--ready"></span><span>einsatzbereit</span></span><span class="hangar-legend__item"><span class="hangar-legend__swatch"></span><span>nicht bereit</span></span></span></div>`;
  const thead = '<thead><tr><th>Schiffstyp</th><th class="num">Anzahl</th><th>Einsatzbereit</th></tr></thead>';
  const shipRows = ships.map((g, i) => typeRow(g, `t${i}`, o)).join('');
  const conceptBody = concepts.map((g, i) => conceptRow(g, `c${i}`, o.officer)).join('');
  const sectionHint = 'Konzeptschiffe: nicht flugbereit, nicht in den Summen, nicht zuteilbar.';
  let content;
  if (o.section === 'B') {
    content = `<div class="card card--flush"><div class="table-responsive"><table class="data-table data-table--stack hangar-tree">${thead}${shipRows}</table></div><div class="list-foot"><span>1–${ships.length} von ${ships.length}</span></div></div>
      ${concepts.length ? `<h2 class="ln-section-title">Nicht flugbereit <span class="count-badge count-badge--sm">${concepts.reduce((n, g) => n + g.items.length, 0)}</span></h2><p class="ln-section-hint">${sectionHint}</p>
      <div class="card card--flush"><div class="table-responsive"><table class="data-table data-table--stack hangar-tree">${thead.replace('Einsatzbereit', '')}${conceptBody}</table></div></div>` : ''}`;
  } else {
    const sep = concepts.length ? `<tbody class="ln-tree-sep"><tr><td colspan="3"><span class="ln-tree-sep__title">Nicht flugbereit</span><span class="ln-tree-sep__hint">${sectionHint}</span></td></tr></tbody>` : '';
    content = `<div class="card card--flush"><div class="table-responsive"><table class="data-table data-table--stack hangar-tree">${thead}${shipRows}${sep}${conceptBody}</table></div><div class="list-foot"><span>1–${ships.length} von ${ships.length} · Nicht flugbereit ohne Seiten</span></div></div>`;
  }
  const roleNote = o.officer ? '' : protoNote('Ansicht als Mitglied ohne Offiziersreichweite: keine Besitzerzeilen. Im Prototyp-Balken auf „Offizier" stellen.');
  return `${head}${tabs}<section class="list-panel">${toolbar}${roleNote}${content}</section>`;
}

/**
 * Handles the unit overview's clicks and inputs.
 * @param {Event} ev
 * @returns {boolean} whether the page needs a re-render
 */
function unitEvent(ev) {
  const t = /** @type {HTMLElement} */ (ev.target);
  if (ev.type === 'input' && t instanceof HTMLInputElement && t.dataset.input === 'unit-search') { UNIT_VIEW.search = t.value; return true; }
  const btn = /** @type {HTMLElement|null} */ (t.closest('[data-act="toggle-owners"]'));
  if (!btn || ev.type !== 'click') return false;
  const id = String(btn.dataset.id);
  if (UNIT_VIEW.open.has(id)) UNIT_VIEW.open.delete(id); else UNIT_VIEW.open.add(id);
  return true;
}
