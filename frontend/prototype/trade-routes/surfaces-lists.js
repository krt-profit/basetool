/** PROTOTYPE route lists: saved routes, unit routes and the member's shared routes as tabs of one page. */

const ME = 'Kestrel';
const SAVED_LIMIT = 50;
const UNIT_LIMIT = 100;

/**
 * Renders a list row's icon action button.
 * @param {string} act the action
 * @param {string} icon the icon name
 * @param {string} label the accessible label
 * @param {string} cls extra button classes
 * @param {string} data extra data attributes
 * @returns {string} markup
 */
function rowBtn(act, icon, label, cls, data) {
  return `<button class="btn ${cls || 'btn-ghost'} btn-xs btn-icon" data-act="${act}" ${data || ''} aria-label="${esc(label)}" title="${esc(label)}">${ic(icon)}</button>`;
}

/**
 * Renders the saved-route list.
 * @returns {string} markup
 */
function savedTab() {
  const list = state.savedList;
  if (!list.length) return '<div class="empty-state"><div class="empty-title">Keine gespeicherten Routen</div><div class="empty-text">Speichere eine Route im Routenplaner. Beim Öffnen wird ihre Fracht auf aktuellen Daten neu geplant.</div></div>';
  const rows = list.map((s) => `<tr>
      <td><a class="row-link" href="#" data-act="open-route" data-src="saved"><span class="cell-title">${esc(s.name)}</span><span class="cell-sub">${esc(seqText(s.path))}${s.note ? ` · ${esc(s.note)}` : ''}</span></a></td>
      <td class="cell-status">${s.broken ? `<span class="tr-mark tr-mark--danger">${esc(s.broken)}</span>` : s.fallback ? '<span class="tr-mark tr-mark--warn">Hangarschiff weg · als Typ</span>' : ''}</td>
      <td data-label="Schiffe">${esc(s.ships)}</td>
      <td class="num" data-label="Pro Stunde">${fmt(s.perHour)}</td>
      <td data-label="Geöffnet">${esc(s.opened)}</td>
      <td class="cell-actions">${rowBtn('share-saved', 'external-link', 'Teilen', '', `data-val="${s.id}"`)}${rowBtn('rename', 'edit', 'Umbenennen', '', 'data-what="Gespeicherte Route"')}${rowBtn('confirm-delete', 'trash', 'Löschen', 'btn-quiet-danger', `data-val="${s.id}"`)}<svg class="krt-icon row-chevron" aria-hidden="true"><use href="#krt-icon-chevron-right"></use></svg></td>
    </tr>`).join('');
  const full = list.length >= SAVED_LIMIT ? `<div class="alert alert-warning">${ic('warning')}<p>Alle ${SAVED_LIMIT} Plätze belegt. Lösche eine Route, bevor du eine neue speicherst.</p></div>` : '';
  return `${full}<div class="tr-row tr-row--between"><span class="tr-hint">Zuletzt geöffnet zuerst. Beim Öffnen wird die Fracht auf den gespeicherten Stopps mit aktuellen Daten neu geplant und gegen den Stand vom letzten Öffnen verglichen. Gewinn pro Stunde: Stand beim letzten Öffnen.</span><span class="tr-hint">${list.length} von ${SAVED_LIMIT} Plätzen</span></div>
    <div class="card card--flush"><table class="data-table data-table--stack"><thead><tr><th>Route</th><th class="cell-status">Hinweis</th><th>Schiffe</th><th class="num">aUEC/h</th><th>Geöffnet</th><th class="cell-actions"><span class="visually-hidden">Aktionen</span></th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

/**
 * Renders the unit route list with per-entry permissions.
 * @returns {string} markup
 */
function unitTab() {
  const officer = state.role === 'officer';
  const rows = state.unitList.map((u) => {
    const own = u.by === ME;
    const may = own || officer;
    const by = u.by ? esc(u.by) + (own ? ' (du)' : '') : '<span class="muted">ehemaliges Mitglied</span>';
    const tools = `${rowBtn('save-copy', 'save', 'Als meine Route speichern')}${rowBtn('share-saved', 'external-link', 'Teilen', '', `data-val="${u.id}"`)}${may ? `${rowBtn('rename', 'edit', 'Umbenennen', '', 'data-what="Einheitsroute"')}${rowBtn('confirm-delete-unit', 'trash', 'Löschen', 'btn-quiet-danger', `data-val="${u.id}"`)}` : ''}`;
    return `<tr>
      <td><a class="row-link" href="#" data-act="open-route" data-src="unit"><span class="cell-title">${esc(u.name)}</span><span class="cell-sub">${esc(seqText(u.path))}${u.note ? ` · ${esc(u.note)}` : ''}</span></a></td>
      <td class="cell-status"><span class="squadron-badge">${u.unit}</span></td>
      <td data-label="Eingetragen von">${by}</td>
      <td data-label="Schiffe">${esc(u.ships)}</td>
      <td class="num" data-label="Pro Stunde">${fmt(u.perHour)}</td>
      <td data-label="Stand">vor ${esc(u.age)}</td>
      <td class="cell-actions">${tools}<svg class="krt-icon row-chevron" aria-hidden="true"><use href="#krt-icon-chevron-right"></use></svg></td>
    </tr>`;
  }).join('');
  return `<div class="tr-row tr-row--between"><span class="tr-hint">Einheit ALPHA mit ihrem Schwarm ALPHA-1 (aktive Einheit, wechselbar im Menü). Jedes Mitglied trägt Routen ein und ändert oder löscht seine eigenen; Offiziere und Einsatzleiter der Einheit verwalten alle. Öffnen schreibt nichts zurück.</span><span class="tr-hint">ALPHA: 23 von ${UNIT_LIMIT} · ALPHA-1: 4 von ${UNIT_LIMIT}</span></div>
    <div class="card card--flush"><table class="data-table data-table--stack"><thead><tr><th>Route</th><th class="cell-status">Einheit</th><th>Eingetragen von</th><th>Schiffe</th><th class="num">aUEC/h</th><th>Stand</th><th class="cell-actions"><span class="visually-hidden">Aktionen</span></th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

/**
 * Renders the member's shared routes with copying and revoking.
 * @returns {string} markup
 */
function sharedTab() {
  const list = state.shares;
  if (!list.length) return '<div class="empty-state"><div class="empty-title">Nichts geteilt</div><div class="empty-text">„Teilen“ an einer Route erzeugt einen Code und einen Link. Jedes angemeldete Mitglied öffnet damit eine eingefrorene Kopie.</div></div>';
  const rows = list.map((s) => `<tr>
      <td><a class="row-link" href="#" data-act="open-route" data-src="shared"><span class="cell-title">${esc(s.name)}</span><span class="cell-sub">${esc(seqText(s.path))}</span></a></td>
      <td class="cell-status"><span class="data-value">${s.code}</span></td>
      <td data-label="Schiffe">${esc(s.ships)}</td>
      <td data-label="Geteilt">${esc(s.at)}</td>
      <td class="cell-actions"><button class="btn btn-ghost btn-xs" data-act="copy" data-val="Link">Link kopieren</button><button class="btn btn-ghost btn-xs" data-act="copy" data-val="Code">Code kopieren</button>${rowBtn('confirm-revoke', 'trash', 'Teilen widerrufen', 'btn-quiet-danger', `data-val="${s.code}"`)}<svg class="krt-icon row-chevron" aria-hidden="true"><use href="#krt-icon-chevron-right"></use></svg></td>
    </tr>`).join('');
  return `<span class="tr-hint">Eingefrorene Kopien ohne Notiz und ohne deinen Namen; Hangarschiffe sind Typen. Kein Ablauf, keine Höchstzahl. Widerrufen löscht die Kopie sofort; was Empfänger als eigene Route gespeichert haben, bleibt.</span>
    <div class="card card--flush"><table class="data-table data-table--stack"><thead><tr><th>Route</th><th class="cell-status">Code</th><th>Schiffe</th><th>Geteilt</th><th class="cell-actions"><span class="visually-hidden">Aktionen</span></th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

/**
 * Renders the routes page with its three tabs.
 * @returns {string} markup
 */
function routesPage() {
  const tabs = [['saved', 'Gespeichert', `${state.savedList.length}/${SAVED_LIMIT}`], ['unit', 'Einheitsrouten', String(state.unitList.length)], ['shared', 'Von mir geteilt', String(state.shares.length)], ['flights', 'Meine Flüge', String(state.history.length)]];
  const body = { saved: savedTab, unit: unitTab, shared: sharedTab, flights: flightsTab }[state.routesTab]();
  return `<div class="page-head"><div class="page-head__main"><span class="page-eyebrow">Handel</span><div class="page-title"><h1>Routen</h1></div></div>
      <div class="page-actions"><button class="btn btn-ghost" data-act="open-code">Code öffnen</button><button class="btn btn--cta" data-act="go" data-page="planner">${ic('plus')} Neue Route</button></div></div>
    <p class="proto-note"><strong>PROTOTYP</strong> · Gespeicherte Routen, Einheitsrouten und deine geteilten Routen als Tabs einer Seite. Erfundene Daten; jede Zeile öffnet dasselbe Beispiel.</p>
    <div class="tab-nav" role="tablist">${tabs.map(([k, l, c]) => `<button class="tab ${state.routesTab === k ? 'active' : ''}" role="tab" aria-selected="${state.routesTab === k}" data-act="routes-tab" data-val="${k}">${l} <span class="tab-count">${c}</span></button>`).join('')}</div>
    <div class="tr-stack tr-tabbody">${body}</div>`;
}
