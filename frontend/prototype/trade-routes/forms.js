/** PROTOTYPE request fields: every parameter of the route request, grouped, shared by the variants. */

/**
 * Renders toggle buttons for a multi-select list of the request.
 * @param {string} key the request list
 * @param {Array<string>} values the options
 * @returns {string} markup
 */
function toggles(key, values) {
  const cur = state.request[key];
  return `<div class="tr-toggles" role="group">${values.map((v) => `<button type="button" class="tr-toggle" data-act="toggle" data-key="${key}" data-val="${esc(v)}" aria-pressed="${cur.includes(v)}">${esc(v)}</button>`).join('')}</div>`;
}

/**
 * Renders a switch bound to a Boolean of the request.
 * @param {string} key the request field
 * @param {string} label the label
 * @param {string} hint an optional hint
 * @returns {string} markup
 */
function sw(key, label, hint) {
  return `<label class="krt-switch"><input type="checkbox" role="switch" data-bind="${key}" ${state.request[key] ? 'checked' : ''}> ${label}${hint ? ` <span class="tr-hint">${hint}</span>` : ''}</label>`;
}

/**
 * Renders a picker for terminals or commodities with its chosen entries.
 * @param {string} key the request field
 * @param {string} kind terminal or commodity
 * @param {boolean} single whether the field holds one entry
 * @param {string} empty text shown without entries
 * @returns {string} markup
 */
function picker(key, kind, single, empty) {
  const cur = single ? (state.request[key] ? [state.request[key]] : []) : state.request[key];
  const name = (v) => (kind === 'terminal' ? TERMINALS[v].name : v);
  const tags = cur.map((v) => `<span class="filter-chip">${esc(name(v))}<button class="filter-chip__remove" data-act="list-remove" data-key="${key}" data-val="${esc(v)}" aria-label="${esc(name(v))} entfernen">×</button></span>`).join('');
  const disabled = key === 'end' && state.request.roundTrip;
  const hide = single && cur.length;
  return `<div class="tr-tags" data-empty="${esc(empty)}">${tags}</div>${hide ? '' : `<div class="tr-picker"><input type="search" autocomplete="off" data-picker="${key}" data-kind="${kind}" placeholder="${kind === 'terminal' ? 'Terminal oder Ort suchen …' : 'Ware suchen …'}" ${disabled ? 'disabled' : ''}><div class="tr-picker__list" hidden></div></div>`}`;
}

/**
 * Renders the picker options matching a query.
 * @param {string} key the request field
 * @param {string} kind terminal or commodity
 * @param {string} q the query
 * @returns {string} markup
 */
function pickerOptions(key, kind, q) {
  const needle = q.trim().toLowerCase();
  if (kind === 'terminal') {
    const places = Object.entries(PLACES).filter(([, p]) => !needle || p.name.toLowerCase().includes(needle));
    const terms = Object.entries(TERMINALS).filter(([, t]) => !needle || t.name.toLowerCase().includes(needle) || PLACES[t.place].name.toLowerCase().includes(needle));
    const placeOpts = ['forced', 'excluded'].includes(key) ? places.filter(([id]) => Object.values(TERMINALS).filter((t) => t.place === id).length > 1).map(([id, p]) => `<button type="button" class="tr-picker__opt" data-act="pick-place" data-key="${key}" data-val="${id}"><span>Ganzer Ort: ${esc(p.name)}</span><small>alle Terminals</small></button>`) : [];
    const termOpts = terms.map(([id, t]) => {
      const p = PLACES[t.place];
      return `<button type="button" class="tr-picker__opt" data-act="pick" data-key="${key}" data-val="${id}"><span>${esc(t.name)}</span><small>${p.kind} · ${p.system} · Gefahr ${dec(p.danger.m)} · Bugs ${dec(t.bugs.m)}</small></button>`;
    });
    return [...placeOpts, ...termOpts].join('') || '<div class="tr-hint" style="padding:8px">Keine Treffer. Signal verloren.</div>';
  }
  return COMMODITIES.filter((c) => !needle || c.name.toLowerCase().includes(needle)).map((c) => `<button type="button" class="tr-picker__opt" data-act="pick" data-key="${key}" data-val="${esc(c.name)}"><span>${esc(c.name)}</span><small>${c.cat}${c.warn ? ` · ⚠ ${c.warn}` : ''}${c.illegal ? ' · illegal' : ''}</small></button>`).join('') || '<div class="tr-hint" style="padding:8px">Keine Treffer.</div>';
}

/**
 * Renders the ship list editor.
 * @returns {string} markup
 */
function fShips() {
  const ships = state.request.ships;
  const n = ships.reduce((a, s) => a + s.count, 0);
  return `<div>${ships.map((s, i) => `<div class="tr-ship">
      <div><div class="tr-ship__name">${s.count > 1 ? `${s.count}× ` : ''}${esc(s.name)}</div>
        <div class="tr-hint">${s.kind === 'hangar' ? 'Mein Hangar' : 'Katalogtyp'} · ${fmt(s.scu)} SCU${s.count > 1 ? ' je Schiff' : ''}${s.est ? ` · <span class="tr-mark tr-mark--est" title="${esc(s.est)}">≈ Antrieb geschätzt</span>` : ''}</div></div>
      <div class="tr-iconbtns">${s.kind === 'type' ? `<input type="number" min="1" max="10" value="${s.count}" data-ship="${i}" data-sk="count" aria-label="Anzahl" style="width:4.2rem;height:2.2rem">` : ''}<button class="btn btn-quiet-danger btn-xs btn-icon" data-act="ship-remove" data-i="${i}" aria-label="Schiff entfernen" title="Schiff entfernen" ${ships.length === 1 ? 'disabled' : ''}>${ic('trash')}</button></div>
      <div class="tr-ship__fields">
        <div class="tr-field"><label>Quantum-Antrieb</label><select data-ship="${i}" data-sk="drive">${DRIVES.map((d) => `<option ${d === s.drive ? 'selected' : ''}>${d}</option>`).join('')}<option disabled>Gespeichertes Loadout (sobald es Loadouts gibt)</option></select></div>
        <div class="tr-field"><label>Reserve SCU</label><input type="number" min="0" value="${s.reserve}" data-ship="${i}" data-sk="reserve"></div>
        <div class="tr-field"><label>Startkapital</label><input type="number" min="0" placeholder="unbegrenzt" value="${s.capital || ''}" data-ship="${i}" data-sk="capital"></div>
      </div>
    </div>`).join('')}
    <div class="tr-row" style="margin-top:8px"><button class="btn btn-ghost btn-xs" data-act="ship-add" data-val="type">${ic('plus')} Katalogtyp</button><button class="btn btn-ghost btn-xs" data-act="ship-add" data-val="hangar">${ic('plus')} Hangarschiff</button><span class="tr-hint">${n} von höchstens 10 Schiffen${n > 1 ? ' · Konvoi: eine Stoppfolge für alle' : ''}</span></div>
  </div>`;
}

/**
 * Renders the route shape fields.
 * @returns {string} markup
 */
function fShape() {
  const r = state.request;
  return `${stopsField()}
    ${sw('roundTrip', 'Rundreise', 'endet am ersten Stopp')}
    <div class="tr-grid2"><div class="tr-field ${state.bd && state.bd.err ? 'tr-field--err' : ''}" id="f-start"><span class="tr-cap">Startterminal</span>${picker('start', 'terminal', true, 'frei')}${state.bd && state.bd.err ? `<span class="field-error" role="alert">${ic('warning')} „Stopp für Stopp bauen“ braucht ein Startterminal: dort beginnt die Route.</span>` : ''}</div>
    <div class="tr-field"><span class="tr-cap">Zielterminal</span>${r.roundTrip ? '<span class="tr-hint">Rundreise: zurück zum Start</span>' : picker('end', 'terminal', true, 'frei')}</div></div>
    <div class="tr-field" style="max-width:220px"><label for="f-runs">Geplante Durchläufe</label><input id="f-runs" type="number" min="1" value="${r.runs}" data-bind="runs"><span class="tr-hint">Plant mit Bestand ÷ Durchläufe</span></div>`;
}

/**
 * Renders the objective fields.
 * @returns {string} markup
 */
function fObjective() {
  const r = state.request;
  const opts = [['perHour', 'Gewinn pro Stunde'], ['maxProfit', 'Höchster Gewinn'], ['fastest', 'Am schnellsten']];
  const extra = r.objective === 'maxProfit'
    ? `<div class="tr-field" style="max-width:220px"><label for="f-tl">Zeitlimit (min)</label><input id="f-tl" type="number" min="5" placeholder="ohne" value="${r.timeLimit}" data-bind="timeLimit"></div>`
    : r.objective === 'fastest'
      ? `<div class="tr-field" style="max-width:220px"><label for="f-mp">Mindestgewinn (aUEC)</label><input id="f-mp" type="number" min="0" value="${r.minProfit}" data-bind="minProfit" required><span class="tr-hint">Pflicht beim schnellsten Weg</span></div>`
      : '';
  return `<div class="tr-field"><span class="tr-cap">Optimierungsziel</span><div class="krt-segmented krt-segmented--sm" role="group" aria-label="Optimierungsziel">${opts.map(([k, l]) => `<button type="button" class="krt-segmented__item" data-act="set" data-key="objective" data-val="${k}" aria-pressed="${r.objective === k}">${l}</button>`).join('')}</div></div>${extra}`;
}

/**
 * Renders the place filters.
 * @returns {string} markup
 */
function fPlaces() {
  const r = state.request;
  return `<div class="tr-field"><span class="tr-cap">Systeme <span class="tr-hint">leer = alle</span></span>${toggles('systems', SYSTEMS)}</div>
    <div class="tr-field"><span class="tr-cap">Ortsarten</span>${toggles('kinds', KINDS)}</div>
    <div class="tr-field"><span class="tr-cap">Erlaubte Lademodi</span>${toggles('modes', MODES)}</div>
    <div class="tr-switch-row">${sw('refuel', 'Nur mit Tanken')}${sw('armistice', 'Nur Waffenstillstand')}</div>
    <div class="tr-field"><span class="tr-cap">Überwachung</span><div class="krt-segmented krt-segmented--sm" role="group">${[['any', 'Egal'], ['yes', 'Nur überwacht'], ['no', 'Nur unüberwacht']].map(([k, l]) => `<button type="button" class="krt-segmented__item" data-act="set" data-key="monitoring" data-val="${k}" aria-pressed="${r.monitoring === k}">${l}</button>`).join('')}</div></div>`;
}

/**
 * Renders the forced and excluded terminal lists.
 * @returns {string} markup
 */
function fTerminals() {
  return `<div class="tr-field"><span class="tr-cap">Erzwungene Terminals <span class="tr-hint">Position wählt die Suche</span></span>${picker('forced', 'terminal', false, 'keine')}</div>
    <div class="tr-field"><span class="tr-cap">Ausgeschlossene Terminals</span>${picker('excluded', 'terminal', false, 'keine')}</div>`;
}

/**
 * Renders the avoided jump connections and systems.
 * @returns {string} markup
 */
function fJumps() {
  return `<div class="tr-field"><span class="tr-cap">Gemiedene Sprungverbindungen <span class="tr-hint">schließt Tunnel und beide Gateways</span></span>${toggles('avoidConn', JUMPS)}</div>
    <div class="tr-field"><span class="tr-cap">Gemiedene Systeme <span class="tr-hint">kein Stopp, kein Durchflug</span></span>${toggles('avoidSys', SYSTEMS)}</div>`;
}

/**
 * Renders the commodity filters.
 * @returns {string} markup
 */
function fGoods() {
  return `<div class="tr-field"><span class="tr-cap">Kategorien <span class="tr-hint">leer = alle</span></span>${toggles('categories', CATEGORIES)}</div>
    <div class="tr-field"><span class="tr-cap">Nur diese Waren <span class="tr-hint">schlägt Kategorien</span></span>${picker('only', 'commodity', false, 'alle')}</div>
    <div class="tr-field"><span class="tr-cap">Ausgeschlossene Waren</span>${picker('exclCommodities', 'commodity', false, 'keine')}</div>
    <div class="tr-switch-row">${sw('illegal', 'Illegale Waren erlauben')}${sw('warning', 'Warnwaren erlauben', 'instabil, zerfallend, explosiv, fehlerhaft')}</div>`;
}

/**
 * Renders the data-quality switch and the rating thresholds.
 * @returns {string} markup
 */
function fData() {
  const r = state.request;
  const half = ['1', '1.5', '2', '2.5', '3', '3.5', '4', '4.5'];
  const sel = (key, opts, unit) => `<select data-bind="${key}"><option value="">aus</option>${opts.map((o) => `<option value="${o}" ${r[key] === o ? 'selected' : ''}>${o.replace('.', ',')}${unit}</option>`).join('')}</select>`;
  return `${sw('probable', 'Nur wahrscheinlich verfügbar', 'frische Preise mit Luft im Bestand')}
    <div class="tr-grid3">
      <div class="tr-field"><label>Gefahr höchstens</label>${sel('dangerMax', half, '')}</div>
      <div class="tr-field"><label>Bugs höchstens</label>${sel('bugsMax', half, '')}</div>
      <div class="tr-field"><label>Verfügbarkeit mind.</label>${sel('availMin', ['10', '20', '30', '40', '50', '60', '70', '80', '90'], ' %')}</div>
    </div><span class="tr-hint">Schwellen gelten erst ab 3 / 3 / 2 Meldern. Bewertungen werden immer angezeigt.</span>`;
}

/**
 * Summarises a field group in one line.
 * @param {string} g the group key
 * @returns {string} the summary
 */
function groupSummary(g) {
  const r = state.request;
  const n = (a) => (a.length ? a.join(', ') : '');
  switch (g) {
    case 'ships': return r.ships.map((s) => `${s.count > 1 ? `${s.count}× ` : ''}${s.type}`).join(' + ');
    case 'shape': return `bis ${r.maxStops} Stopps${r.roundTrip ? ', Rundreise' : ''}${r.start ? `, ab ${TERMINALS[r.start].name}` : ''}`;
    case 'objective': return { perHour: 'Gewinn pro Stunde', maxProfit: 'Höchster Gewinn', fastest: 'Am schnellsten' }[r.objective];
    case 'places': return [n(r.systems) || 'alle Systeme', r.refuel ? 'Tanken' : '', r.armistice ? 'Waffenstillstand' : ''].filter(Boolean).join(', ');
    case 'terminals': return `${r.forced.length} erzwungen, ${r.excluded.length} ausgeschlossen`;
    case 'jumps': return n(r.avoidConn) || 'keine';
    case 'goods': return [n(r.categories) || 'alle Kategorien', r.illegal ? 'illegal' : '', r.warning ? 'Warnwaren' : ''].filter(Boolean).join(', ');
    case 'data': return [r.probable ? 'nur wahrscheinlich' : '', r.dangerMax ? `Gefahr ≤ ${r.dangerMax}` : '', r.bugsMax ? `Bugs ≤ ${r.bugsMax}` : '', r.availMin ? `Verf. ≥ ${r.availMin} %` : ''].filter(Boolean).join(', ') || 'keine Schwellen';
    default: return '';
  }
}

const GROUPS = [
  ['ships', 'Schiffe', fShips],
  ['shape', 'Route', fShape],
  ['objective', 'Ziel', fObjective],
  ['places', 'Orte', fPlaces],
  ['terminals', 'Terminals', fTerminals],
  ['jumps', 'Sprungpunkte', fJumps],
  ['goods', 'Waren', fGoods],
  ['data', 'Daten & Bewertungen', fData],
];
