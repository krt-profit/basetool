/**
 * Revisions of the RSI article, newest first. `state` is `pending`, `applied` or `kept`.
 * @type {Array<{id: number, stamp: string, read: string, rows: number, state: string, applied?: string}>}
 */
const REVISIONS = [
  { id: 4, stamp: '20.11.2026', read: '24.11.2026 06:00', rows: 53, state: 'pending' },
  { id: 3, stamp: '12.11.2026', read: '13.11.2026 06:00', rows: 52, state: 'applied', applied: '13.11.2026 06:00 · automatisch' },
  { id: 2, stamp: '02.10.2026', read: '03.10.2026 06:00', rows: 51, state: 'kept', applied: '03.10.2026 06:00 · automatisch' },
  { id: 1, stamp: '14.07.2026', read: '15.07.2026 09:12', rows: 50, state: 'kept', applied: '15.07.2026 09:20 · durch Admin' },
];

/**
 * Texts of the article with their mapping. `kind` is `ship` (a granting ship cell) or `loaner`;
 * `state` is `confirmed`, `open`, `ignored` or `resting`; `group` flags a grouped form; `proposal`
 * is the suggested type with its source.
 * @type {Array<{id: number, text: string, kind: string, state: string, mapped?: string, proposal?: string, source?: string, group?: boolean, note?: string, version: number}>}
 */
const TEXTS = [
  { id: 1, text: 'Odin', kind: 'ship', state: 'open', note: 'Kein Schiffstyp vorhanden: bleibt ignoriert, bis die Zeile existiert.', version: 0 },
  { id: 2, text: 'Ironclad & Ironclad Assault', kind: 'loaner', state: 'open', group: true, proposal: 'ironclad', source: 'Namensvergleich', version: 0 },
  { id: 3, text: 'MPUV-1C', kind: 'loaner', state: 'open', proposal: 'mpuv', source: 'p4kx RSI-Store-Kennung', version: 0 },
  { id: 4, text: 'Javelin', kind: 'ship', state: 'confirmed', mapped: 'javelin', version: 2 },
  { id: 5, text: 'Kraken', kind: 'ship', state: 'confirmed', mapped: 'kraken', version: 1 },
  { id: 6, text: 'Hull D', kind: 'ship', state: 'confirmed', mapped: 'hulld', version: 1 },
  { id: 7, text: 'Hull E', kind: 'ship', state: 'confirmed', mapped: 'hulle', version: 1 },
  { id: 8, text: 'Pioneer', kind: 'ship', state: 'confirmed', mapped: 'pioneer', version: 1 },
  { id: 9, text: 'Endeavor (all modules)', kind: 'ship', state: 'confirmed', mapped: 'endeavor', group: true, version: 3 },
  { id: 10, text: 'Idris-P', kind: 'loaner', state: 'confirmed', mapped: 'idrisp', version: 1 },
  { id: 11, text: 'MPUV Cargo', kind: 'loaner', state: 'confirmed', mapped: 'mpuv', version: 1 },
  { id: 12, text: 'Polaris', kind: 'loaner', state: 'confirmed', mapped: 'polaris', version: 1 },
  { id: 13, text: 'Buccaneer', kind: 'loaner', state: 'confirmed', mapped: 'buccaneer', version: 1 },
  { id: 14, text: 'Hull C', kind: 'loaner', state: 'confirmed', mapped: 'hullc', version: 1 },
  { id: 15, text: 'Carrack', kind: 'loaner', state: 'confirmed', mapped: 'carrack', version: 1 },
  { id: 16, text: 'Retaliator', kind: 'loaner', state: 'resting', mapped: 'retaliator', note: 'Typ nicht mehr flugbereit (keine P4K-GUID): gewährt nichts, bis er es wieder ist.', version: 2 },
  { id: 17, text: 'Mustang Alpha', kind: 'loaner', state: 'ignored', note: 'Arena-Commander-Ersatz', version: 1 },
];

/** View state of the matrix page. */
const MATRIX = { tab: 'revisions', textFilter: 'all', textSearch: '', granting: 'kraken', selected: new Set(), editing: 0 };

/**
 * Returns the state label of a text.
 * @param {typeof TEXTS[number]} t
 * @returns {string}
 */
function textState(t) {
  switch (t.state) {
    case 'open': return '<span class="status-pill status-pill--warning">Offen</span>';
    case 'ignored': return '<span class="status-pill">Ignoriert</span>';
    case 'resting': return '<span class="status-pill status-pill--danger" title="Die Zuordnung bleibt, gewährt aber nichts">Ruht</span>';
    default: return '<span class="status-pill status-pill--success">Bestätigt</span>';
  }
}

/**
 * Returns a type picker for a text: a ship text takes any type, a loaner text only flight-ready
 * game types.
 * @param {typeof TEXTS[number]} t
 * @param {string} selected
 * @returns {string}
 */
function mappingSelect(t, selected) {
  const keys = Object.keys(TYPES).filter((k) => t.kind === 'ship' || TYPES[k].kind === 'flight').sort((a, b) => typeSortKey(a).localeCompare(typeSortKey(b)));
  return `<select data-map="${t.id}" aria-label="Schiffstyp für ${esc(t.text)}"><option value="">Typ wählen …</option>${keys.map((k) => `<option value="${k}"${k === selected ? ' selected' : ''}>${esc(typeLabel(k))}</option>`).join('')}</select>`;
}

/**
 * Renders the pending revision with its diff and its open texts.
 * @returns {string}
 */
function pendingCard() {
  const rev = REVISIONS.find((r) => r.state === 'pending');
  if (!rev) return '';
  const open = TEXTS.filter((t) => t.state === 'open');
  const selectable = open.filter((t) => !t.group && t.proposal);
  const rows = open.map((t) => {
    const single = t.group || !t.proposal;
    const flag = t.group ? '<span class="chip chip--warning">Gruppierte Form · einzeln bestätigen</span>' : !t.proposal ? '<span class="chip chip--warning">Kein Vorschlag · einzeln bestätigen</span>' : '';
    const pick = single ? '<span class="ln-pick-gap" aria-hidden="true"></span>' : `<input type="checkbox" data-pick="${t.id}"${MATRIX.selected.has(t.id) ? ' checked' : ''} aria-label="${esc(t.text)} auswählen">`;
    const proposal = t.proposal ? `${mappingSelect(t, t.proposal)}<span class="cell-sub">Vorschlag aus ${esc(t.source || '')}</span>` : `${mappingSelect(t, '')}${t.note ? `<span class="cell-sub">${esc(t.note)}</span>` : ''}`;
    const one = single ? `<button type="button" class="btn btn-ghost btn-xs" data-act="confirm-one" data-id="${t.id}">${icon('check')} Bestätigen</button>` : '';
    return `<tr><td class="ln-pick">${pick}</td><td><span class="cell-title">„${esc(t.text)}"</span><span class="cell-sub">${t.kind === 'ship' ? 'Schiff (gewährt)' : 'Loaner'}</span> ${flag}</td><td>${proposal}</td>
      <td class="cell-actions">${one}<button type="button" class="btn btn-ghost btn-xs" data-act="ignore" data-id="${t.id}">Ignorieren</button></td></tr>`;
  }).join('');
  return `<section class="card ln-card ln-card--pending">
    <div class="card-head"><h2 class="card-title">Wartende Revision · Stand ${esc(rev.stamp)}</h2><span class="ln-card-meta">gelesen ${esc(rev.read)} · ${rev.rows} Zeilen</span></div>
    <div class="ln-diff">
      <div><span class="ln-diff__k">Neu</span><span>Odin → Idris-P, Javelin · Kraken → Ironclad &amp; Ironclad Assault (statt Ironclad Assault)</span></div>
      <div><span class="ln-diff__k">Entfällt</span><span>Pioneer → Carrack. Eure Einstellung „Nicht gewähren" für dieses Paar greift danach nicht mehr und wird markiert.</span></div>
      <div><span class="ln-diff__k">Wirkung</span><span>Nach dem Anwenden: 2 Mitglieder, +1 Loaner, −0 Loaner.</span></div>
    </div>
    ${open.length ? `<p class="ln-section-hint">Die Revision wird automatisch angewendet, sobald jeder Text zugeordnet oder ignoriert ist. Bis dahin gilt die Revision vom ${esc(REVISIONS.find((r) => r.state === 'applied')?.stamp || '')}.</p>
    <div class="table-responsive"><table class="data-table ln-table"><thead><tr><th class="ln-pick"><span class="visually-hidden">Auswahl</span></th><th>Text im Artikel</th><th>Zuordnung</th><th class="cell-actions"><span class="visually-hidden">Aktion</span></th></tr></thead><tbody>${rows}</tbody></table></div>
    <div class="ln-card-foot"><span class="muted">${MATRIX.selected.size} von ${selectable.length} ausgewählt · nichts ist vorausgewählt</span><button type="button" class="btn btn--cta" data-act="confirm-selected"${MATRIX.selected.size ? '' : ' disabled'}>${icon('check')} <span>Ausgewählte bestätigen</span></button></div>`
    : '<p class="alert alert-success">Alle Texte sind zugeordnet. Die Revision wird angewendet.</p>'}
  </section>`;
}

/**
 * Renders the applied revision with the read action.
 * @returns {string}
 */
function appliedCard() {
  const rev = REVISIONS.find((r) => r.state === 'applied');
  if (!rev) return '';
  return `<section class="card ln-card"><div class="card-head"><h2 class="card-title">Angewendete Revision · Stand ${esc(rev.stamp)}</h2></div>
    <div class="ln-facts ln-facts--row">
      <div><span class="ln-facts__k">Angewendet</span><span class="ln-facts__v">${esc(rev.applied || '')}</span></div>
      <div><span class="ln-facts__k">Zeilen</span><span class="ln-facts__v">${rev.rows}</span></div>
      <div><span class="ln-facts__k">Letzter Lesevorgang</span><span class="ln-facts__v">24.11.2026 06:00 · neue Revision gefunden</span></div>
      <div><span class="ln-facts__k">Quelle</span><span class="ln-facts__v">RSI Help Center · Loaner Ship Matrix (en-us)</span></div>
    </div></section>`;
}

/**
 * Renders every text with its mapping, searchable and filterable.
 * @returns {string}
 */
function textsCard() {
  const q = MATRIX.textSearch.trim().toLowerCase();
  const list = TEXTS.filter((t) => (MATRIX.textFilter === 'all' || t.state === MATRIX.textFilter) && (!q || t.text.toLowerCase().includes(q) || (t.mapped && typeLabel(t.mapped).toLowerCase().includes(q))));
  /** @param {string} s */
  const count = (s) => TEXTS.filter((t) => t.state === s).length;
  const rows = list.map((t) => {
    const editing = MATRIX.editing === t.id;
    const mapping = editing ? `${mappingSelect(t, t.mapped || '')}<span class="cell-sub">Wirkt sofort für alle Mitglieder.</span>` : t.mapped ? `<span class="data-value">${esc(typeLabel(t.mapped))}</span>${t.note ? `<span class="cell-sub">${esc(t.note)}</span>` : ''}` : `<span class="muted">${esc(t.note || '–')}</span>`;
    const actions = editing
      ? `<button type="button" class="btn btn-ghost btn-xs" data-act="edit-cancel">Abbrechen</button><button type="button" class="btn btn-outline btn-xs" data-act="edit-save" data-id="${t.id}">${icon('save')} Speichern</button>`
      : t.state === 'open' ? '' : `<button type="button" class="btn btn-ghost btn-icon" data-act="edit-text" data-id="${t.id}" title="Zuordnung ändern" aria-label="Zuordnung ändern">${icon('edit')}</button>${t.state !== 'ignored' ? `<button type="button" class="btn btn-ghost btn-xs" data-act="ignore" data-id="${t.id}">Ignorieren</button>` : ''}`;
    return `<tr><td><span class="cell-title">„${esc(t.text)}"</span><span class="cell-sub">${t.kind === 'ship' ? 'Schiff (gewährt)' : 'Loaner'}${t.group ? ' · gruppierte Form' : ''}</span></td><td>${mapping}</td><td class="cell-status">${textState(t)}</td><td class="cell-actions">${actions}</td></tr>`;
  }).join('');
  return `<section class="card ln-card card--flush-body"><div class="card-head"><h2 class="card-title">Texte <span class="count-badge count-badge--sm">${TEXTS.length}</span></h2></div>
    <div class="toolbar"><div class="toolbar__search">${icon('search')}<input type="search" data-input="text-search" value="${esc(MATRIX.textSearch)}" placeholder="Text oder Schiffstyp suchen" aria-label="Texte suchen"></div>
    ${segmented('textFilter', [['all', 'Alle'], ['open', 'Offen', count('open')], ['confirmed', 'Bestätigt', count('confirmed')], ['ignored', 'Ignoriert', count('ignored')], ['resting', 'Ruht', count('resting')]], MATRIX.textFilter, 'Status')}</div>
    <div class="table-responsive"><table class="data-table ln-table"><thead><tr><th>Text im Artikel</th><th>Schiffstyp</th><th class="cell-status">Status</th><th class="cell-actions"><span class="visually-hidden">Aktion</span></th></tr></thead><tbody>${rows}</tbody></table></div></section>`;
}

/**
 * Renders the pairs per granting type with the three-state override.
 * @returns {string}
 */
function pairsCard() {
  const granting = Object.keys(PAIRS).sort((a, b) => typeSortKey(a).localeCompare(typeSortKey(b)));
  const g = MATRIX.granting;
  const list = granting.map((k) => `<button type="button" class="master-row${k === g ? ' is-active' : ''}" data-act="granting" data-key="${k}"><span class="cell-title">${esc(typeLabel(k))}</span> ${typeChip(k)}<span class="cell-sub">${grantsOf(k).length} Loaner wirksam${PAIRS[k].some((p) => p.flag) ? ' · Hinweis' : ''}</span></button>`).join('');
  const rows = (PAIRS[g] || []).map((p, i) => {
    const state = p.source === 'own' ? 'grant' : p.source === 'off' ? 'deny' : 'auto';
    const src = p.source === 'own' ? '<span class="chip chip--primary">Eigener Eintrag</span>' : p.source === 'off' ? '<span class="chip chip--muted">Abgeschaltet</span>' : '<span class="chip">Artikel</span>';
    const flags = [
      p.flag === 'covered' ? '<span class="chip chip--info" title="Die angewendete Revision gewährt das Paar inzwischen selbst">Artikel deckt ab · Eintrag kann weg</span>' : '',
      p.flag === 'resting' ? '<span class="chip chip--danger" title="Der Loaner-Typ ist nicht flugbereit">Ruht · Typ nicht flugbereit</span>' : '',
      g === 'pioneer' && p.loaner === 'carrack' ? '<span class="chip chip--warning" title="Die wartende Revision enthält das Paar nicht mehr">Greift nach der wartenden Revision nicht mehr</span>' : '',
    ].join(' ');
    const seg = segmented(`ov-${g}-${i}`, [['auto', 'Automatisch'], ['grant', 'Gewähren'], ['deny', 'Nicht gewähren']], state, 'Einstellung');
    return `<tr><td><span class="cell-title">${esc(typeLabel(p.loaner))}</span> ${flags}</td><td>${src}</td><td class="ln-ov">${seg}</td></tr>`;
  }).join('');
  return `<section class="card ln-card"><div class="card-head"><h2 class="card-title">Paare je gewährendem Typ</h2></div>
    <p class="ln-section-hint">Automatisch: der Artikel entscheidet. Gewähren: euer eigener Eintrag. Nicht gewähren: ein Artikelpaar abgeschaltet. Jede Änderung wirkt sofort und wird protokolliert.</p>
    <div class="master-detail ln-md"><div class="master-list ln-master">${list}</div>
    <div class="detail-pane"><h3 class="ln-detail-title">${esc(typeLabel(g))} ${typeChip(g)}</h3>
      <div class="table-responsive"><table class="data-table ln-table"><thead><tr><th>Loaner</th><th>Quelle</th><th>Einstellung</th></tr></thead><tbody>${rows}</tbody></table></div>
      <div class="ln-add-pair"><select aria-label="Loaner-Typ">${Object.keys(TYPES).filter((k) => TYPES[k].kind === 'flight' && !(PAIRS[g] || []).some((p) => p.loaner === k)).map((k) => `<option value="${k}">${esc(typeLabel(k))}</option>`).join('')}</select>
      <button type="button" class="btn btn-outline btn-xs" data-act="add-pair">${icon('plus')} <span>Loaner gewähren</span></button></div>
    </div></div></section>`;
}

/**
 * Renders the revision history with the rollback action.
 * @returns {string}
 */
function historyCard() {
  const rows = REVISIONS.map((r) => {
    const state = r.state === 'pending' ? '<span class="status-pill status-pill--warning">Wartet</span>' : r.state === 'applied' ? '<span class="status-pill status-pill--success">Angewendet</span>' : '<span class="status-pill">Früher angewendet</span>';
    const action = r.state === 'kept' ? `<button type="button" class="btn btn-ghost btn-xs" data-act="rollback" data-id="${r.id}">${icon('reset')} Diese Revision anwenden</button>` : '';
    return `<tr><td><span class="cell-title">Stand ${esc(r.stamp)}</span><span class="cell-sub">gelesen ${esc(r.read)}${r.applied ? ` · angewendet ${esc(r.applied)}` : ''}</span></td><td class="num">${r.rows}</td><td class="cell-status">${state}</td><td class="cell-actions">${action}</td></tr>`;
  }).join('');
  return `<section class="card ln-card"><div class="card-head"><h2 class="card-title">Revisionsverlauf</h2></div>
    <div class="table-responsive"><table class="data-table ln-table"><thead><tr><th>Revision</th><th class="num">Zeilen</th><th class="cell-status">Status</th><th class="cell-actions"><span class="visually-hidden">Aktion</span></th></tr></thead><tbody>${rows}</tbody></table></div></section>`;
}

/**
 * Returns the banner shown while a revision waits.
 * @returns {string}
 */
function matrixBanner() {
  const open = TEXTS.filter((t) => t.state === 'open').length;
  const pending = REVISIONS.find((r) => r.state === 'pending');
  if (!pending || !open) return '';
  return `<div class="alert alert-warning ln-banner">${icon('warning')}<span><strong>Loaner-Matrix wartet.</strong> Die Revision vom ${esc(pending.stamp)} braucht ${open} Bestätigungen. Bis dahin gilt die angewendete Revision.</span></div>`;
}

/**
 * Renders the admin page „Loaner-Matrix".
 * @param {{layout: string}} o A one page of stacked sections, B tabs
 * @returns {string}
 */
function renderMatrix(o) {
  const head = pageHead({ eyebrow: 'Stammdaten', title: 'Loaner-Matrix', actions: `<a href="#" class="btn btn-ghost" data-go="shipdata">${icon('list')} <span>Schiffsdaten</span></a><button type="button" class="btn btn-ghost" data-act="read-now">${icon('download')} <span>Jetzt lesen</span></button>` });
  if (o.layout === 'B') {
    const open = TEXTS.filter((t) => t.state === 'open').length;
    const tabs = `<nav class="tab-nav" aria-label="Loaner-Matrix">${[['revisions', 'Revisionen', open], ['texts', 'Texte', TEXTS.length], ['pairs', 'Paare', Object.keys(PAIRS).length]].map(([k, l, n]) => `<a href="#" class="tab${MATRIX.tab === k ? ' active' : ''}" data-act="mtab" data-tab="${k}"><span>${l}</span><span class="tab-count">${n}</span></a>`).join('')}</nav>`;
    const body = MATRIX.tab === 'texts' ? textsCard() : MATRIX.tab === 'pairs' ? pairsCard() : `${pendingCard()}${appliedCard()}${historyCard()}`;
    return `${head}${matrixBanner()}${tabs}<div class="ln-stack">${body}</div>`;
  }
  return `${head}${matrixBanner()}<div class="ln-stack">${pendingCard()}${appliedCard()}${textsCard()}${pairsCard()}${historyCard()}</div>`;
}

/**
 * Applies the pending revision once every text is resolved.
 */
function applyPendingIfComplete() {
  if (TEXTS.some((t) => t.state === 'open')) return;
  const pending = REVISIONS.find((r) => r.state === 'pending');
  if (!pending) return;
  for (const r of REVISIONS) if (r.state === 'applied') r.state = 'kept';
  pending.state = 'applied';
  pending.applied = '24.11.2026 · automatisch nach der letzten Bestätigung';
  toast(`Revision vom ${pending.stamp} angewendet: 2 Mitglieder, +1 Loaner, −0 Loaner.`);
}

/**
 * Handles the matrix page's clicks and inputs.
 * @param {Event} ev
 * @returns {boolean} whether the page needs a re-render
 */
function matrixEvent(ev) {
  const t = /** @type {HTMLElement} */ (ev.target);
  if (ev.type === 'input' && t instanceof HTMLInputElement && t.dataset.input === 'text-search') { MATRIX.textSearch = t.value; return true; }
  if (ev.type === 'change' && t instanceof HTMLInputElement) {
    if (t.name === 'textFilter') { MATRIX.textFilter = t.value; return true; }
    if (t.dataset.pick) { const id = Number(t.dataset.pick); if (t.checked) MATRIX.selected.add(id); else MATRIX.selected.delete(id); return true; }
    if (t.name.startsWith('ov-')) {
      const [, g, i] = t.name.split('-');
      const p = PAIRS[g][Number(i)];
      if (t.value === 'grant' && p.source === 'article') { toast('Abgelehnt: der Artikel gewährt dieses Paar schon.', 'info'); return true; }
      p.source = t.value === 'grant' ? 'own' : t.value === 'deny' ? 'off' : 'article';
      delete p.flag;
      toast(`${TYPES[g].name} → ${TYPES[p.loaner].name}: ${t.value === 'deny' ? 'nicht gewährt' : t.value === 'grant' ? 'gewährt' : 'automatisch'}. Wirkt sofort: 3 Mitglieder, Lückenhinweis an 1 Einsatz.`);
      return true;
    }
  }
  const btn = /** @type {HTMLElement|null} */ (t.closest('[data-act]'));
  if (!btn || ev.type !== 'click') return false;
  const id = Number(btn.dataset.id);
  const text = TEXTS.find((x) => x.id === id);
  switch (btn.dataset.act) {
    case 'mtab': ev.preventDefault(); MATRIX.tab = String(btn.dataset.tab); return true;
    case 'granting': MATRIX.granting = String(btn.dataset.key); return true;
    case 'read-now': toast('Artikel gelesen: unverändert seit dem letzten Lesevorgang (304).', 'info'); return false;
    case 'confirm-selected':
      for (const x of TEXTS) {
        if (!MATRIX.selected.has(x.id)) continue;
        const sel = /** @type {HTMLSelectElement|null} */ (document.querySelector(`select[data-map="${x.id}"]`));
        x.state = 'confirmed';
        x.mapped = sel?.value || x.proposal;
      }
      toast(`${MATRIX.selected.size} Zuordnungen bestätigt.`);
      MATRIX.selected.clear();
      applyPendingIfComplete();
      return true;
    case 'confirm-one': {
      const sel = /** @type {HTMLSelectElement|null} */ (document.querySelector(`select[data-map="${id}"]`));
      if (!text || !sel?.value) { toast('Erst einen Schiffstyp wählen – oder „Ignorieren".', 'info'); return false; }
      text.state = 'confirmed'; text.mapped = sel.value; text.note = undefined;
      toast(`„${text.text}" bestätigt.`);
      applyPendingIfComplete();
      return true;
    }
    case 'ignore':
      if (text) { text.state = 'ignored'; MATRIX.selected.delete(id); toast(`„${text.text}" wird ignoriert. Wirkt sofort.`); }
      applyPendingIfComplete();
      return true;
    case 'edit-text': MATRIX.editing = id; return true;
    case 'edit-cancel': MATRIX.editing = 0; return true;
    case 'edit-save': {
      const sel = /** @type {HTMLSelectElement|null} */ (document.querySelector(`select[data-map="${id}"]`));
      if (text && sel?.value) { text.mapped = sel.value; text.state = TYPES[sel.value].kind === 'flight' || text.kind === 'ship' ? 'confirmed' : 'resting'; text.version += 1; }
      MATRIX.editing = 0;
      toast('Zuordnung geändert. Wirkt sofort: 4 Mitglieder, −1 Loaner, Lückenhinweis an 1 Einsatz.');
      return true;
    }
    case 'add-pair': {
      const sel = /** @type {HTMLSelectElement|null} */ (btn.parentElement?.querySelector('select') || null);
      if (sel?.value) { PAIRS[MATRIX.granting].push({ loaner: sel.value, source: 'own' }); toast(`Eigener Eintrag: ${TYPES[MATRIX.granting].name} → ${TYPES[sel.value].name}. Wirkt sofort.`); }
      return true;
    }
    case 'rollback': {
      const r = REVISIONS.find((x) => x.id === id);
      if (!r) return false;
      openDialog({ title: 'Revision anwenden', body: `<p class="m-0">Die Revision vom ${esc(r.stamp)} gilt dann wieder, bis der Artikel sich ändert; die nächste neue Revision wird wie gewohnt automatisch angewendet. Betroffen: 5 Mitglieder, −3 Loaner, Lückenhinweis an 2 Einsätze.</p>`, foot: `<button type="button" class="btn btn-ghost" data-act="close-dialog">Abbrechen</button><button type="button" class="btn btn-danger" data-act="rollback-ok" data-id="${r.id}">Revision anwenden</button>`, danger: true });
      return false;
    }
    case 'rollback-ok': {
      for (const r of REVISIONS) if (r.state === 'applied') r.state = 'kept';
      const r = REVISIONS.find((x) => x.id === id);
      if (r) { r.state = 'applied'; r.applied = '24.11.2026 · durch Admin'; }
      closeDialog();
      toast('Revision angewendet.');
      return true;
    }
    default: return false;
  }
}
