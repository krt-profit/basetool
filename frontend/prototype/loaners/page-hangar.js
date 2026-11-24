/** Filter and selection state of „Meine Schiffe" and the admin member hangar. */
const HANGAR = { fit: '', kind: 'all', showHidden: false, unknownOnly: false, search: '', selected: new Set() };

/**
 * Builds the hangar entries: own ships and held loaners, sorted by type with a type's own ships
 * before its loaner.
 * @returns {Array<{id: string, loaner: boolean, type: string, ship?: typeof MY_SHIPS[number], granting?: string[], state?: typeof MY_LOANER_STATE[string]}>}
 */
function hangarEntries() {
  const entries = MY_SHIPS.map((s) => ({ id: `s${s.id}`, loaner: false, type: s.type, ship: s }));
  for (const [type, granting] of heldLoaners(MY_SHIPS)) {
    entries.push({ id: `l:${type}`, loaner: true, type, granting, state: MY_LOANER_STATE[type] });
  }
  return entries.sort((a, b) => typeSortKey(a.type).localeCompare(typeSortKey(b.type)) || Number(a.loaner) - Number(b.loaner));
}

/**
 * Tells whether an entry counts toward readiness: flight-ready own ships and shown loaners.
 * @param {ReturnType<typeof hangarEntries>[number]} e
 * @returns {boolean}
 */
function countsForReadiness(e) {
  if (e.loaner) return !e.state?.hidden;
  return TYPES[e.type].kind !== 'concept';
}

/**
 * Tells whether an entry is fitted.
 * @param {ReturnType<typeof hangarEntries>[number]} e
 * @returns {boolean}
 */
function isFitted(e) {
  return e.loaner ? Boolean(e.state?.fitted) : Boolean(e.ship?.fitted);
}

/**
 * Applies the toolbar filters.
 * @param {ReturnType<typeof hangarEntries>} entries
 * @returns {ReturnType<typeof hangarEntries>}
 */
function filterEntries(entries) {
  const q = HANGAR.search.trim().toLowerCase();
  return entries.filter((e) => {
    if (e.loaner && e.state?.hidden && !HANGAR.showHidden) return false;
    if (HANGAR.kind === 'ships' && e.loaner) return false;
    if (HANGAR.kind === 'loaners' && !e.loaner) return false;
    if (HANGAR.unknownOnly && (e.loaner || e.ship?.origin !== null)) return false;
    if (HANGAR.fit !== '') {
      if (!countsForReadiness(e)) return false;
      if ((HANGAR.fit === 'true') !== isFitted(e)) return false;
    }
    if (q) {
      const hay = `${typeLabel(e.type)} ${e.ship?.name || ''}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });
}

/**
 * Returns the origin chip of an own ship.
 * @param {string|null} origin
 * @returns {string}
 */
function originChip(origin) {
  if (origin === 'PLEDGE') return '<span class="chip chip--primary">Gepledged</span>';
  if (origin === 'IN_GAME') return '<span class="chip chip--warning">Ingame gekauft</span>';
  return '<span class="chip chip--muted">Herkunft unbekannt</span>';
}

/**
 * Returns the insurance cell of an own ship.
 * @param {string|null} ins
 * @returns {string}
 */
function insuranceCell(ins) {
  if (ins === 'LTI') return '<span class="chip chip--primary">LTI</span>';
  if (ins === '0') return '<span class="chip chip--muted">Keine</span>';
  if (ins) return `<span class="chip">${esc(ins)} Mon.</span>`;
  return '<span class="hangar-ins-unset">–</span>';
}

/**
 * Renders one hangar row.
 * @param {ReturnType<typeof hangarEntries>[number]} e
 * @param {string} variant row look: A tag in the sub line, B stripe and tag chip
 * @returns {string}
 */
function hangarRow(e, variant) {
  const checked = HANGAR.selected.has(e.id) ? ' checked' : '';
  const pick = `<label class="hangar-row__pick"><input type="checkbox" data-sel="${e.id}"${checked} aria-label="Auswählen"></label>`;
  if (e.loaner && e.state && e.granting) {
    const tag = loanerFor(e.granting);
    const hidden = e.state.hidden;
    const title = `<span class="cell-title">${esc(typeLabel(e.type))}</span>`;
    const sub = variant === 'B' ? '<span class="cell-sub">Ohne Namen</span>' : `<span class="cell-sub ln-loaner-for" title="${esc(tag.title)}">${esc(tag.text)}</span>`;
    const meta = variant === 'B'
      ? `<span class="chip chip--info ln-chip-loaner" title="${esc(tag.title)}">${esc(tag.text)}</span>`
      : '<span class="chip chip--info ln-chip-loaner">Loaner</span>';
    const hiddenChip = hidden ? '<span class="chip chip--muted">Ausgeblendet</span>' : '';
    const toggle = hidden
      ? `<button type="button" class="btn btn-ghost btn-xs" data-act="unhide" data-type="${e.type}">${icon('eye')} Einblenden</button>`
      : `<button type="button" class="btn btn-ghost btn-icon" data-act="hide" data-type="${e.type}" title="Ausblenden" aria-label="Ausblenden">${icon('eye-off')}</button>`;
    return `<tr class="ln-row--loaner${variant === 'B' ? ' ln-row--stripe' : ''}${hidden ? ' ln-row--hidden' : ''}">
      <td><div class="hangar-row__ship">${pick}<span class="hangar-row__name ln-dim">${title}${sub}<span class="hangar-row__meta">${meta}${hiddenChip}</span></span></div></td>
      <td class="hangar-cell-loc"><span class="ln-dim">${esc(e.state.loc || '–')}</span></td>
      <td class="hangar-cell-ins"><span class="hangar-ins-unset ln-dim" title="Ein Loaner hat keine Versicherung">–</span></td>
      <td class="cell-status"><span class="ln-dim">${fitBadge(e.state.fitted)}</span></td>
      <td class="hangar-cell-unit"><span class="ln-dim">${unitBadge(e.state.unit)}</span></td>
      <td class="cell-actions">
        <button type="button" class="btn btn-ghost btn-icon" data-act="unit" data-id="${e.id}" title="Einheit ändern" aria-label="Einheit ändern">${icon('users')}</button>
        <button type="button" class="btn btn-ghost btn-icon" data-act="edit-loaner" data-type="${e.type}" title="Bearbeiten" aria-label="Bearbeiten">${icon('edit')}</button>
        ${toggle}
      </td></tr>`;
  }
  const s = /** @type {typeof MY_SHIPS[number]} */ (e.ship);
  const concept = TYPES[s.type].kind === 'concept';
  const grants = grantsOf(s.type);
  const grantsLine = grants.length ? `<span class="ln-grants">${icon('arrow-right')}<span>Loaner: ${grants.map((g) => esc(TYPES[g].name)).join(' · ')}</span></span>` : '';
  const pledge = s.pledge ? `<span class="hangar-row__pledge">${esc(s.pledge)}</span>` : '';
  const status = concept ? '<span class="ln-nofit" title="Ein Konzeptschiff hat keinen Einsatzstatus">–</span>' : fitBadge(s.fitted);
  return `<tr>
    <td><div class="hangar-row__ship">${pick}<span class="hangar-row__name">
      <span class="cell-title">${esc(s.name || typeLabel(s.type))}</span> ${typeChip(s.type)}
      <span class="cell-sub">${esc(s.name ? typeLabel(s.type) : 'Ohne Namen')}</span>
      <span class="hangar-row__meta">${originChip(s.origin)}${pledge}</span>${grantsLine}</span></div></td>
    <td class="hangar-cell-loc">${esc(s.loc || '–')}</td>
    <td class="hangar-cell-ins">${insuranceCell(s.ins)}</td>
    <td class="cell-status">${status}</td>
    <td class="hangar-cell-unit">${unitBadge(s.unit)}</td>
    <td class="cell-actions">
      <button type="button" class="btn btn-ghost btn-icon" data-act="unit" data-id="${e.id}" title="Einheit ändern" aria-label="Einheit ändern">${icon('users')}</button>
      <button type="button" class="btn btn-ghost btn-icon" data-act="edit-ship" data-id="${s.id}" title="Bearbeiten" aria-label="Bearbeiten">${icon('edit')}</button>
    </td></tr>`;
}

/**
 * Renders „Meine Schiffe" or, for `admin`, the admin member hangar.
 * @param {{admin: boolean, row: string}} o
 * @returns {string}
 */
function renderHangar(o) {
  const all = hangarEntries();
  const rows = filterEntries(all);
  const shownLoaners = all.filter((e) => e.loaner && !e.state?.hidden);
  const hiddenCount = all.filter((e) => e.loaner && e.state?.hidden).length;
  const readiness = all.filter(countsForReadiness);
  const fitted = readiness.filter(isFitted).length;
  const tabCount = MY_SHIPS.length + shownLoaners.length;
  const actions = o.admin ? '' : `
    <button type="button" class="btn btn-ghost" data-act="import">${icon('upload')} <span>Schiffsliste importieren</span></button>
    <button type="button" class="btn btn--cta" data-act="add-ship">${icon('plus')} <span>Schiff hinzufügen</span></button>`;
  const head = o.admin
    ? pageHead({ eyebrow: 'Mitglieder', back: true, title: `Hangar von ${ADMIN_MEMBER}` })
    : pageHead({ eyebrow: 'Einsatz & Flotte', title: 'Hangar', actions });
  const tabs = o.admin ? '' : `<nav class="tab-nav hangar-tabs" aria-label="Hangar">
      <a href="#" class="tab active" aria-current="page"><span>Meine Schiffe</span><span class="tab-count">${tabCount}</span></a>
      <a href="#" class="tab" data-go="unit"><span>Org-Einheit ALP</span><span class="tab-count">${unitTabCount()}</span></a></nav>`;
  const adminNote = o.admin ? `<p class="alert alert-info">${ADMIN_MEMBER} wird über jede Änderung benachrichtigt, auch über ausgeblendete und eingeblendete Loaner.</p>` : '';
  const toolbar = `<div class="toolbar">
      <div class="toolbar__search">${icon('search')}<input type="search" data-input="search" value="${esc(HANGAR.search)}" placeholder="Schiff, Typ oder Hersteller suchen" aria-label="Suchen"></div>
      ${segmented('fit', [['', 'Alle'], ['true', 'Bereit'], ['false', 'Nicht bereit']], HANGAR.fit, 'Einsatzbereit')}
      ${segmented('kind', [['all', 'Alle'], ['ships', 'Schiffe', MY_SHIPS.length], ['loaners', 'Loaner', shownLoaners.length]], HANGAR.kind, 'Art')}
      <label class="krt-switch"><input type="checkbox" role="switch" data-input="unknown"${HANGAR.unknownOnly ? ' checked' : ''}><span>Herkunft unbekannt</span></label>
      <label class="krt-switch"><input type="checkbox" role="switch" data-input="showHidden"${HANGAR.showHidden ? ' checked' : ''}><span>Ausgeblendete Loaner zeigen</span>${hiddenCount ? `<span class="count-badge count-badge--sm" title="${hiddenCount} Loaner ausgeblendet">${hiddenCount}</span>` : ''}</label>
      <span class="toolbar__spacer"></span>
      <span class="toolbar__count" title="Konzeptschiffe zählen nicht; ausgeblendete Loaner zählen nicht">${fitted} von ${readiness.length} einsatzbereit</span>
    </div>`;
  const table = rows.length ? `<div class="table-responsive"><table class="data-table data-table--stack hangar-table">
      <thead><tr><th><div class="hangar-row__ship"><label class="hangar-row__pick"><input type="checkbox" data-sel="all" aria-label="Alle auswählen"></label><span>Schiff</span></div></th><th>Standort</th><th>Versicherung</th><th class="cell-status">Status</th><th>Einheit</th><th class="cell-actions"><span class="visually-hidden">Aktion</span></th></tr></thead>
      <tbody>${rows.map((e) => hangarRow(e, o.row)).join('')}</tbody></table></div>`
    : '<div class="empty-state"><span class="empty-title">Keine Treffer</span><span class="empty-text">Kein Eintrag passt zum Filter.</span></div>';
  const foot = `<div class="list-foot"><span>1–${rows.length} von ${rows.length}</span></div>`;
  return `${head}${tabs}${adminNote}<section class="list-panel">${toolbar}<div class="card card--flush">${table}${foot}</div></section>${selectionBar()}`;
}

/**
 * Renders the selection bar for the selected entries.
 * @returns {string}
 */
function selectionBar() {
  if (!HANGAR.selected.size) return '';
  const sel = hangarEntries().filter((e) => HANGAR.selected.has(e.id));
  const canHide = sel.some((e) => e.loaner && !e.state?.hidden);
  const canShow = sel.some((e) => e.loaner && e.state?.hidden);
  return `<div class="form-actions--sticky hangar-selection-bar ln-selection" role="region" aria-label="Auswahl">
    <span class="form-actions__summary"><strong>${sel.length}</strong> ausgewählt</span>
    <button type="button" class="btn btn-ghost" data-act="clear-sel">Auswahl aufheben</button>
    <button type="button" class="btn btn-outline" data-act="bulk-origin" data-origin="PLEDGE">Als gepledged markieren</button>
    <button type="button" class="btn btn-outline" data-act="bulk-origin" data-origin="IN_GAME">Als ingame gekauft markieren</button>
    <button type="button" class="btn btn-outline" data-act="bulk-unit">${icon('users')} <span>Einheit ändern</span></button>
    <button type="button" class="btn btn-outline" data-act="bulk-hide"${canHide ? '' : ' disabled'}>${icon('eye-off')} <span>Ausblenden</span></button>
    <button type="button" class="btn btn-outline" data-act="bulk-unhide"${canShow ? '' : ' disabled'}>${icon('eye')} <span>Einblenden</span></button>
  </div>`;
}

/**
 * Returns the options of the ship-type picker in the add and edit dialogs.
 * @param {string} variant A one list with the status as suffix, B a group „Nicht flugbereit"
 * @param {string} selected
 * @returns {string}
 */
function typeOptions(variant, selected) {
  const keys = Object.keys(TYPES).filter((k) => TYPES[k].kind !== 'notInGame').sort((a, b) => typeSortKey(a).localeCompare(typeSortKey(b)));
  /** @param {string} k @param {boolean} suffix */
  const opt = (k, suffix) => {
    const t = TYPES[k];
    const status = t.status === 'IN_PRODUCTION' ? 'In Produktion' : t.status === 'IN_CONCEPT' ? 'Im Konzept' : 'Konzeptschiff';
    const label = suffix && t.kind === 'concept' ? `${typeLabel(k)} · ${status}` : typeLabel(k);
    return `<option value="${k}"${k === selected ? ' selected' : ''}>${esc(label)}</option>`;
  };
  if (variant === 'B') {
    return `<option value="">Bitte wählen …</option><optgroup label="Flugbereit">${keys.filter((k) => TYPES[k].kind === 'flight').map((k) => opt(k, false)).join('')}</optgroup><optgroup label="Nicht flugbereit (immer gepledged)">${keys.filter((k) => TYPES[k].kind === 'concept').map((k) => opt(k, true)).join('')}</optgroup>`;
  }
  return `<option value="">Bitte wählen …</option>${keys.map((k) => opt(k, true)).join('')}`;
}

/**
 * Returns the location picker.
 * @param {string|null} selected
 * @returns {string}
 */
function locationSelect(selected) {
  return `<select id="f-loc"><option value="">Bitte wählen …</option>${LOCATIONS.map((l) => `<option${l === selected ? ' selected' : ''}>${esc(l)}</option>`).join('')}</select>`;
}

/**
 * Opens the add or edit dialog of an own ship.
 * @param {typeof MY_SHIPS[number]|null} ship
 * @param {string} variant
 */
function openShipDialog(ship, variant) {
  const type = ship ? ship.type : '';
  const body = `
    <div class="form-group"><label for="f-type">Schiffstyp</label><select id="f-type" data-input="dialog-type">${typeOptions(variant, type)}</select>
      <p class="form-hint ln-concept-hint" data-concept-hint hidden></p></div>
    <div class="form-group"><label for="f-name">Name</label><input type="text" id="f-name" value="${esc(ship?.name || '')}"></div>
    <fieldset class="form-group ship-insurance"><legend>Versicherung</legend>${segmented('ins', [['NONE', 'Keine'], ['MONTHS', 'Monate'], ['LTI', 'LTI']], ship?.ins === 'LTI' ? 'LTI' : ship?.ins ? 'MONTHS' : '')}</fieldset>
    <div class="form-group"><label for="f-loc">Ort</label>${locationSelect(ship?.loc || null)}</div>
    <div class="form-group flex-gap-sm" data-fitted-row><input type="checkbox" id="f-fit"${ship?.fitted ? ' checked' : ''}><label for="f-fit" class="ship-fitted-label">Fitted (Einsatzbereit)</label></div>
    <fieldset class="form-group ship-origin" data-origin-row><legend>Herkunft</legend>
      <div data-origin-free>${segmented('origin', [['PLEDGE', 'Gepledged'], ['IN_GAME', 'Ingame gekauft'], ['', 'Unbekannt']], ship?.origin || '')}</div>
      <div data-origin-fixed hidden>${segmented('origin-fixed', [['PLEDGE', 'Gepledged'], ['IN_GAME', 'Ingame gekauft'], ['', 'Unbekannt']], 'PLEDGE')}<p class="form-hint">Ein Konzeptschiff ist immer gepledged.</p></div>
    </fieldset>
    <div class="form-group"><label for="f-unit">Einheit</label><select id="f-unit"><option value="">Keine Einheit</option>${Object.entries(UNITS).map(([k, v]) => `<option value="${k}"${ship?.unit === k ? ' selected' : ''}>${esc(v)} (${k})</option>`).join('')}</select></div>`;
  const foot = `${ship ? `<button type="button" class="btn btn-quiet-danger foot-start" data-act="close-dialog">${icon('trash')} <span>Löschen</span></button>` : ''}
    <button type="button" class="btn btn--cta" data-act="save-ship" data-id="${ship?.id ?? ''}">${icon('save')} <span>Speichern</span></button>`;
  const d = openDialog({ title: ship ? 'Schiff bearbeiten' : 'Schiff hinzufügen', body, foot, wide: true });
  d.querySelectorAll('[data-origin-fixed] input').forEach((i) => { /** @type {HTMLInputElement} */ (i).disabled = true; });
  syncShipDialog(d);
}

/**
 * Adapts the ship dialog to the chosen type: a concept ship has no fitted switch and a fixed origin.
 * @param {ParentNode} d
 */
function syncShipDialog(d) {
  const sel = /** @type {HTMLSelectElement|null} */ (d.querySelector('#f-type'));
  const key = sel?.value || '';
  const concept = key !== '' && TYPES[key].kind === 'concept';
  /** @param {string} q @param {boolean} hidden */
  const hide = (q, hidden) => { const el = /** @type {HTMLElement|null} */ (d.querySelector(q)); if (el) el.hidden = hidden; };
  hide('[data-fitted-row]', concept);
  hide('[data-origin-free]', concept);
  hide('[data-origin-fixed]', !concept);
  const hint = /** @type {HTMLElement|null} */ (d.querySelector('[data-concept-hint]'));
  if (hint) {
    const grants = concept ? grantsOf(key) : [];
    hint.hidden = !concept;
    hint.innerHTML = concept ? `${typeChip(key)} Nicht flugbereit: zählt nirgends mit und kann keinem Einsatz zugeteilt werden.${grants.length ? ` Gewährt Loaner: ${grants.map((g) => esc(typeLabel(g))).join(' · ')}.` : ''}` : '';
  }
}

/**
 * Opens the edit dialog of a loaner: location and fitted only.
 * @param {string} type
 * @param {boolean} admin
 */
function openLoanerDialog(type, admin) {
  const st = MY_LOANER_STATE[type];
  const granting = heldLoaners(MY_SHIPS).get(type) || [];
  const tag = loanerFor(granting);
  const body = `
    <div class="ln-facts">
      <div><span class="ln-facts__k">Typ</span><span class="ln-facts__v">${esc(typeLabel(type))}</span></div>
      <div><span class="ln-facts__k">Loaner für</span><span class="ln-facts__v">${granting.map((g) => esc(typeLabel(g))).join(', ')}</span></div>
    </div>
    <div class="form-group"><label for="f-loc">Ort</label>${locationSelect(st.loc)}</div>
    <div class="form-group flex-gap-sm"><input type="checkbox" id="f-fit"${st.fitted ? ' checked' : ''}><label for="f-fit" class="ship-fitted-label">Fitted (Einsatzbereit)</label></div>
    <p class="form-hint">Den Loaner vergibt das System aus der Loaner-Matrix, solange ${admin ? ADMIN_MEMBER : 'du'} ${esc(tag.title.replace('Loaner für ', ''))} ${admin ? 'hält' : 'hältst'}. Er hat keinen Namen, keine Versicherung und keine Herkunft und lässt sich nicht löschen, nur ausblenden.</p>`;
  const foot = `<button type="button" class="btn btn-ghost foot-start" data-act="${st.hidden ? 'unhide' : 'hide'}" data-type="${type}">${icon(st.hidden ? 'eye' : 'eye-off')} <span>${st.hidden ? 'Einblenden' : 'Ausblenden'}</span></button>
    <button type="button" class="btn btn--cta" data-act="save-loaner" data-type="${type}">${icon('save')} <span>Speichern</span></button>`;
  openDialog({ title: 'Loaner bearbeiten', body, foot });
}

/**
 * Opens the unit dialog for one entry or the selection.
 * @param {string[]} ids
 */
function openUnitDialog(ids) {
  const msg = ids.length > 1 ? `<p class="ship-unit-bulk-message">Welcher Einheit sollen die ${ids.length} markierten Einträge zugeordnet sein? Loaner zählen mit.</p>` : '';
  const body = `${msg}<div class="form-group"><label for="f-unit">Einheit</label><select id="f-unit"><option value="">Keine Einheit</option>${Object.entries(UNITS).map(([k, v]) => `<option value="${k}">${esc(v)} (${k})</option>`).join('')}</select></div><p class="form-hint m-0">Das Schiff erscheint im Hangar der gewählten Einheit. Ein Loaner behält seine Einheit, auch wenn das gewährende Schiff die Einheit wechselt.</p>`;
  openDialog({ title: 'Einheit ändern', body, foot: `<button type="button" class="btn btn-ghost" data-act="close-dialog">Abbrechen</button><button type="button" class="btn btn--cta" data-act="save-unit" data-ids="${ids.join(',')}">${icon('save')} <span>Speichern</span></button>` });
}

/**
 * Sets the hidden mark of loaner types and reports it.
 * @param {string[]} types
 * @param {boolean} hidden
 * @param {boolean} admin
 */
function setHidden(types, hidden, admin) {
  for (const t of types) MY_LOANER_STATE[t].hidden = hidden;
  const names = types.map((t) => TYPES[t].name).join(', ');
  const who = admin ? ` ${ADMIN_MEMBER} wird benachrichtigt.` : '';
  toast(hidden ? `Ausgeblendet: ${names}. Zählt nirgends mehr und wird nirgends angeboten.${who}` : `Eingeblendet: ${names}.${who}`);
}

/**
 * Handles the hangar page's clicks and inputs.
 * @param {Event} ev
 * @param {{admin: boolean, add: string}} o
 * @returns {boolean} whether the page needs a re-render
 */
function hangarEvent(ev, o) {
  const t = /** @type {HTMLElement} */ (ev.target);
  if (ev.type === 'input' || ev.type === 'change') {
    const inp = /** @type {HTMLInputElement} */ (t);
    if (inp.dataset.input === 'search') { HANGAR.search = inp.value; return true; }
    if (inp.dataset.input === 'unknown') { HANGAR.unknownOnly = inp.checked; return true; }
    if (inp.dataset.input === 'showHidden') { HANGAR.showHidden = inp.checked; return true; }
    if (inp.dataset.input === 'dialog-type') { syncShipDialog(/** @type {ParentNode} */ (inp.closest('dialog'))); return false; }
    if (inp.name === 'fit') { HANGAR.fit = inp.value; return true; }
    if (inp.name === 'kind') { HANGAR.kind = inp.value; return true; }
    if (inp.dataset.sel === 'all') {
      const visible = filterEntries(hangarEntries());
      visible.forEach((e) => (inp.checked ? HANGAR.selected.add(e.id) : HANGAR.selected.delete(e.id)));
      return true;
    }
    if (inp.dataset.sel) { if (inp.checked) HANGAR.selected.add(inp.dataset.sel); else HANGAR.selected.delete(inp.dataset.sel); return true; }
    return false;
  }
  const btn = /** @type {HTMLElement|null} */ (t.closest('[data-act]'));
  if (!btn) return false;
  const act = btn.dataset.act;
  const sel = hangarEntries().filter((e) => HANGAR.selected.has(e.id));
  switch (act) {
    case 'add-ship': openShipDialog(null, o.add); return false;
    case 'edit-ship': openShipDialog(MY_SHIPS.find((s) => s.id === Number(btn.dataset.id)) || null, o.add); return false;
    case 'edit-loaner': openLoanerDialog(String(btn.dataset.type), o.admin); return false;
    case 'save-ship': closeDialog(); toast(btn.dataset.id ? 'Schiff aktualisiert.' : 'Schiff hinzugefügt.'); return false;
    case 'save-loaner': {
      const d = /** @type {HTMLElement} */ (btn.closest('dialog'));
      const st = MY_LOANER_STATE[String(btn.dataset.type)];
      st.loc = /** @type {HTMLSelectElement} */ (d.querySelector('#f-loc')).value || null;
      st.fitted = /** @type {HTMLInputElement} */ (d.querySelector('#f-fit')).checked;
      closeDialog();
      toast(`Loaner aktualisiert.${o.admin ? ` ${ADMIN_MEMBER} wird benachrichtigt.` : ''}`);
      return true;
    }
    case 'hide': closeDialog(); setHidden([String(btn.dataset.type)], true, o.admin); return true;
    case 'unhide': closeDialog(); setHidden([String(btn.dataset.type)], false, o.admin); return true;
    case 'unit': openUnitDialog([String(btn.dataset.id)]); return false;
    case 'bulk-unit': openUnitDialog(sel.map((e) => e.id)); return false;
    case 'save-unit': {
      const d = /** @type {HTMLElement} */ (btn.closest('dialog'));
      const unit = /** @type {HTMLSelectElement} */ (d.querySelector('#f-unit')).value || null;
      for (const id of String(btn.dataset.ids).split(',')) {
        if (id.startsWith('l:')) MY_LOANER_STATE[id.slice(2)].unit = unit;
        else { const s = MY_SHIPS.find((x) => `s${x.id}` === id); if (s) s.unit = unit; }
      }
      closeDialog();
      toast('Einheit geändert.');
      return true;
    }
    case 'bulk-origin': {
      const target = String(btn.dataset.origin);
      const ok = sel.filter((e) => !e.loaner && !(target === 'IN_GAME' && TYPES[e.type].kind === 'concept'));
      const skipped = sel.length - ok.length;
      toast(`Herkunft bei ${ok.length} Schiffen gesetzt${skipped ? `, ${skipped} übersprungen (Loaner und Konzeptschiffe)` : ''}.`);
      return false;
    }
    case 'bulk-hide': setHidden(sel.filter((e) => e.loaner && !e.state?.hidden).map((e) => e.type), true, o.admin); return true;
    case 'bulk-unhide': setHidden(sel.filter((e) => e.loaner && e.state?.hidden).map((e) => e.type), false, o.admin); return true;
    case 'clear-sel': HANGAR.selected.clear(); return true;
    default: return false;
  }
}
