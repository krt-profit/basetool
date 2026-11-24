/** View state of /ship-data. */
const SHIPDATA = { search: '', open: new Set() };

/**
 * Returns the granting types of a loaner type under the effective pairs.
 * @param {string} loaner
 * @returns {string[]}
 */
function grantersOf(loaner) {
  return Object.keys(PAIRS).filter((g) => grantsOf(g).includes(loaner));
}

/**
 * Renders the loaner facts of a type: what it grants, or what it is a loaner for; for
 * administrators each pair's source.
 * @param {string} key
 * @param {boolean} admin
 * @returns {string}
 */
function loanerFacts(key, admin) {
  if (TYPES[key].kind === 'concept') {
    const pairs = (PAIRS[key] || []).filter((p) => admin || (p.source !== 'off' && TYPES[p.loaner].kind === 'flight'));
    if (!pairs.length) return '<span class="muted">gewährt keine Loaner</span>';
    const items = pairs.map((p) => {
      const off = p.source === 'off' || TYPES[p.loaner].kind !== 'flight';
      const src = !admin ? '' : p.source === 'own' ? ' <small class="ln-src ln-src--own">eigener Eintrag</small>' : p.source === 'off' ? ' <small class="ln-src">abgeschaltet</small>' : TYPES[p.loaner].kind !== 'flight' ? ' <small class="ln-src">ruht</small>' : ' <small class="ln-src">Artikel</small>';
      return `<span class="ln-pair${off ? ' ln-pair--off' : ''}">${esc(TYPES[p.loaner].name)}${src}</span>`;
    }).join('');
    return `<span class="ln-facts-line"><span class="ln-facts-line__k">Gewährt</span>${items}</span>`;
  }
  const granting = grantersOf(key);
  if (granting.length) return `<span class="ln-facts-line"><span class="ln-facts-line__k">Loaner für</span>${granting.map((g) => `<span class="ln-pair">${esc(TYPES[g].name)}</span>`).join('')}</span>`;
  return '<span class="muted">–</span>';
}

/**
 * Renders the three-state concept-ship override with what the sources say.
 * @param {string} key
 * @returns {string}
 */
function conceptOverride(key) {
  const t = TYPES[key];
  const state = t.override === 'FLIGHT' ? 'flight' : t.override === 'CONCEPT' ? 'concept' : 'auto';
  const sources = [
    t.p4kx ? `p4kx: ${t.p4kx === 'IN_PRODUCTION' ? 'In Produktion' : 'Im Konzept'}` : `p4kx: ${t.guid ? 'flugbereit' : 'kein Eintrag'}`,
    t.matrix ? 'Matrix: gewährt Loaner' : null,
    `P4K-GUID: ${t.guid ? 'ja' : 'nein'}`,
  ].filter(Boolean).join(' · ');
  const sourcesSay = Boolean(t.p4kx) || (Boolean(t.matrix) && !t.guid);
  const disagree = (state === 'flight' && sourcesSay) || (state === 'concept' && !sourcesSay);
  return `<div class="ln-override">${segmented(`co-${key}`, [['auto', 'Automatisch'], ['concept', 'Konzeptschiff'], ['flight', 'Flugbereit']], state, 'Konzeptschiff')}
    <span class="cell-sub">${esc(sources)}${disagree ? ' <span class="chip chip--warning">Widerspricht den Quellen</span>' : ''}</span></div>`;
}

/**
 * Renders /ship-data's ship-type list.
 * @param {{admin: boolean, layout: string}} o A loaners as a column, B in an expandable detail row
 * @returns {string}
 */
function renderShipData(o) {
  const q = SHIPDATA.search.trim().toLowerCase();
  const keys = Object.keys(TYPES).filter((k) => !q || typeLabel(k).toLowerCase().includes(q)).sort((a, b) => typeSortKey(a).localeCompare(typeSortKey(b)));
  const actions = o.admin ? `<a href="#" class="btn btn-ghost" data-go="matrix">${icon('list')} <span>Loaner-Matrix</span></a>` : '';
  const head = pageHead({ eyebrow: 'Einsatz & Flotte', title: 'Schiffsdaten', actions });
  const banner = o.admin ? matrixBanner().replace('</span></div>', ' <a href="#" data-go="matrix">Zur Loaner-Matrix</a></span></div>') : '';
  const nameCell = (/** @type {string} */ k) => `<span class="cell-title">${esc(TYPES[k].name)}</span> ${typeChip(k)}<span class="cell-sub">${esc(TYPES[k].maker)}</span>`;
  const hideBtn = '<button type="button" class="btn btn-ghost btn-xs">Ausblenden</button>';
  let table;
  if (o.layout === 'B') {
    const rows = keys.map((k) => {
      const open = SHIPDATA.open.has(k);
      const has = TYPES[k].kind === 'concept' || grantersOf(k).length > 0 || o.admin;
      const detail = open ? `<tr class="ln-detail-row"><td colspan="${o.admin ? 3 : 2}"><div class="ln-detail-grid"><div><p class="ln-detail-k">Loaner</p>${loanerFacts(k, o.admin)}</div>${o.admin ? `<div><p class="ln-detail-k">Konzeptschiff</p>${conceptOverride(k)}</div>` : ''}</div></td></tr>` : '';
      return `<tr><td><div class="hangar-tree__type"><span class="hangar-tree__toggle-slot">${has ? `<button type="button" class="btn-icon hangar-tree__toggle" aria-expanded="${open}" data-act="sd-toggle" data-key="${k}" aria-label="Details">${icon('chevron-right')}</button>` : ''}</span><span>${nameCell(k)}</span></div></td>
        <td class="ln-desc">${TYPES[k].kind === 'concept' ? `Gewährt ${grantsOf(k).length} Loaner` : grantersOf(k).length ? `Loaner für ${grantersOf(k).map((g) => esc(TYPES[g].name)).join(', ')}` : '–'}</td>${o.admin ? `<td class="cell-actions">${hideBtn}</td>` : ''}</tr>${detail}`;
    }).join('');
    table = `<table class="data-table hangar-tree"><thead><tr><th>Name</th><th>Loaner</th>${o.admin ? '<th class="cell-actions">Aktion</th>' : ''}</tr></thead><tbody>${rows}</tbody></table>`;
  } else {
    const rows = keys.map((k) => `<tr><td>${nameCell(k)}</td><td>${loanerFacts(k, o.admin)}</td>${o.admin ? `<td>${conceptOverride(k)}</td><td class="cell-actions">${hideBtn}</td>` : ''}</tr>`).join('');
    table = `<table class="data-table data-table--stack"><thead><tr><th>Name</th><th>Loaner</th>${o.admin ? '<th>Konzeptschiff</th><th class="cell-actions">Aktion</th>' : ''}</tr></thead><tbody>${rows}</tbody></table>`;
  }
  const memberNote = o.admin ? '' : protoNote('Ansicht als Mitglied: nur die wirksamen Loaner, keine Quellen und keine Einstellungen. Im Prototyp-Balken auf „Admin" stellen.');
  return `${head}${banner}${memberNote}<section class="sd-section"><div class="sd-section__head"><h2 class="sd-section__title"><span>Schiffstypen</span><span class="count-badge count-badge--sm">${keys.length}</span></h2></div>
    <section class="list-panel"><div class="toolbar"><div class="toolbar__search">${icon('search')}<input type="search" data-input="sd-search" value="${esc(SHIPDATA.search)}" placeholder="Nach Namen filtern" aria-label="Nach Namen filtern"></div></div>
    <div class="card card--flush"><div class="table-responsive">${table}</div></div></section></section>`;
}

/**
 * Handles /ship-data's clicks and inputs.
 * @param {Event} ev
 * @returns {boolean} whether the page needs a re-render
 */
function shipDataEvent(ev) {
  const t = /** @type {HTMLElement} */ (ev.target);
  if (ev.type === 'input' && t instanceof HTMLInputElement && t.dataset.input === 'sd-search') { SHIPDATA.search = t.value; return true; }
  if (ev.type === 'change' && t instanceof HTMLInputElement && t.name.startsWith('co-')) {
    const key = t.name.slice(3);
    TYPES[key].override = t.value === 'flight' ? 'FLIGHT' : t.value === 'concept' ? 'CONCEPT' : undefined;
    toast(`${TYPES[key].name}: ${t.value === 'auto' ? 'Quellen entscheiden' : t.value === 'concept' ? 'als Konzeptschiff erzwungen' : 'als flugbereit erzwungen'}. Wirkt sofort.`);
    return true;
  }
  const btn = /** @type {HTMLElement|null} */ (t.closest('[data-act="sd-toggle"]'));
  if (!btn || ev.type !== 'click') return false;
  const key = String(btn.dataset.key);
  if (SHIPDATA.open.has(key)) SHIPDATA.open.delete(key); else SHIPDATA.open.add(key);
  return true;
}
