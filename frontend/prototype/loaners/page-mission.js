/**
 * Units of the sample mission: name, type, the assigned ship (owner and loaner flag) and crew.
 * `loanerOf` names the viewer's loaner type a unit holds; `gapped` marks a unit whose loaner left,
 * which keeps its type and name and is not assigned again when the loaner returns.
 * @type {Array<{name: string, type: string, owner: string|null, loaner: boolean, loanerOf?: string, gapped?: boolean, crew: string[]}>}
 */
const MISSION_UNITS = [
  { name: 'Träger', type: 'polaris', owner: ME, loaner: true, loanerOf: 'polaris', crew: [ME, 'Vex'] },
  { name: 'Jäger 1', type: 'arrow', owner: 'Morrow', loaner: false, crew: ['Morrow'] },
  { name: 'Begleitschutz', type: 'buccaneer', owner: ME, loaner: true, loanerOf: 'buccaneer', crew: ['Talon'] },
];

/** Participants registered for the sample mission. */
const PARTICIPANTS = [ME, 'Vex', 'Morrow', 'Talon'];

/**
 * Lists the ships a unit may take: the participants' own ships and shown loaners, never a concept
 * ship.
 * @returns {Array<{type: string, owner: string, loaner: boolean, label: string}>}
 */
function missionShipOptions() {
  const own = MY_SHIPS.filter((s) => TYPES[s.type].kind === 'flight').map((s) => ({ type: s.type, owner: ME, loaner: false, label: s.name || '' }));
  const mine = [...heldLoaners(MY_SHIPS).keys()].filter((t) => !MY_LOANER_STATE[t].hidden).map((t) => ({ type: t, owner: ME, loaner: true, label: '' }));
  const others = UNIT_OTHERS.filter((o) => TYPES[o.type].kind === 'flight' && PARTICIPANTS.includes(o.owner)).map((o) => ({ type: o.type, owner: o.owner, loaner: Boolean(o.loaner), label: '' }));
  return [...own, ...mine, ...others];
}

/**
 * Renders one unit panel of the crew board.
 * @param {typeof MISSION_UNITS[number]} u
 * @param {number} i
 * @returns {string}
 */
function unitPanel(u, i) {
  const gap = Boolean(u.gapped);
  const owner = gap ? null : u.owner;
  const loanerChip = u.loaner && !gap ? ' <span class="chip chip--info ln-chip-loaner chip-xs">Loaner</span>' : '';
  const gapChip = gap ? ' <span class="chip chip--muted chip-xs" title="Kein Schiff zugeteilt, nur der Typ">nur Typ</span>' : '';
  const crew = u.crew.map((c) => `<div class="person-row"><span class="status-dot status-dot--on" aria-hidden="true"></span><span>${esc(c)}</span></div>`).join('');
  return `<div class="kpanel unit-panel mission-unit-box unit-color-${i}"><div class="kpanel-head"><span class="uname"><span>${esc(u.name)}</span>
    <small>— <span>${esc(TYPES[u.type].name)}</span>${loanerChip}${gapChip}${owner ? ` · <span title="Verantwortlich">${esc(owner)}</span>` : ''}</small></span>
    <span class="unit-head-actions"><span class="ucount">${u.crew.length} an Bord</span><button type="button" class="btn btn-ghost btn-icon" data-act="edit-unit" data-i="${i}" title="Bearbeiten" aria-label="Bearbeiten">${icon('edit')}</button></span></div>
    <div class="drop-zone">${crew}</div></div>`;
}

/**
 * Renders the mission's crew board with its units.
 * @param {{picker: string}} o
 * @returns {string}
 */
function renderMission(o) {
  const head = pageHead({ eyebrow: 'Einsätze', back: true, title: 'Bergbau am Aaron Halo', actions: `<button type="button" class="btn btn--cta" data-act="add-unit">${icon('plus')} <span>Einheit hinzufügen</span></button>` });
  const tabs = '<nav class="tab-nav" aria-label="Einsatz"><a href="#" class="tab">Überblick</a><a href="#" class="tab active" aria-current="page">Crew &amp; Einheiten <span class="tab-count">3</span></a><a href="#" class="tab">Finanzen &amp; Auszahlung</a></nav>';
  const gapUnit = MISSION_UNITS.find((u) => u.gapped);
  const notice = gapUnit ? `<div class="alert alert-warning ln-notice">${icon('warning')} <span><strong>Mitteilung an Einsatzleitung und Verantwortliche:</strong> Die Einheit „${esc(gapUnit.name)}" hat ihr Schiff verloren. Der Loaner ${esc(typeLabel(gapUnit.type))} von ${esc(ME)} wird nicht mehr gehalten. Typ und Name der Einheit bleiben. (Code und Wortlaut entscheidet das Audit-Ticket.)</span></div>` : '';
  const begleit = MISSION_UNITS[2];
  const simText = !begleit.gapped ? `${ME} blendet den Loaner Buccaneer aus` : MY_LOANER_STATE.buccaneer.hidden ? 'Buccaneer wieder einblenden (die Einheit bekommt ihn nicht zurück)' : 'Lücke bleibt: ein zurückkehrender Loaner wird nicht wieder zugeteilt';
  const sim = protoNote(`Prototyp: <button type="button" class="ln-linkbtn" data-act="sim-gap">${simText}</button> · Variante der Schiffswahl im Prototyp-Balken (${o.picker === 'B' ? 'Gruppen' : 'Zusatz im Namen'}).`);
  return `${head}${tabs}${sim}${notice}<div class="board-units ln-board">${MISSION_UNITS.map(unitPanel).join('')}</div>`;
}

/**
 * Returns the options of the unit-ship picker for one type.
 * @param {string} type
 * @param {string} variant A suffix „· Loaner", B option groups
 * @param {string} selectedKey
 * @returns {string}
 */
function shipOptions(type, variant, selectedKey) {
  const opts = missionShipOptions().filter((s) => s.type === type);
  /** @param {typeof opts[number]} s */
  const key = (s) => `${s.owner}|${s.type}|${s.loaner}`;
  /** @param {typeof opts[number]} s @param {boolean} suffix */
  const opt = (s, suffix) => `<option value="${esc(key(s))}"${key(s) === selectedKey ? ' selected' : ''}>${esc(`${TYPES[s.type].name}${s.label ? ` „${s.label}"` : ''} (${s.owner})${suffix && s.loaner ? ' · Loaner' : ''}`)}</option>`;
  const none = '<option value="">— keines · nur Typ verwenden —</option>';
  if (variant === 'B') {
    const ships = opts.filter((s) => !s.loaner);
    const loaners = opts.filter((s) => s.loaner);
    return `${none}${ships.length ? `<optgroup label="Schiffe">${ships.map((s) => opt(s, false)).join('')}</optgroup>` : ''}${loaners.length ? `<optgroup label="Loaner">${loaners.map((s) => opt(s, false)).join('')}</optgroup>` : ''}`;
  }
  return `${none}${opts.map((s) => opt(s, true)).join('')}`;
}

/**
 * Opens the add or edit dialog of a mission unit.
 * @param {typeof MISSION_UNITS[number]|null} unit
 * @param {string} variant
 */
function openUnitShipDialog(unit, variant) {
  const types = Object.keys(TYPES).filter((k) => TYPES[k].kind === 'flight').sort((a, b) => typeSortKey(a).localeCompare(typeSortKey(b)));
  const type = unit ? unit.type : 'arrow';
  const gap = Boolean(unit?.gapped);
  const selected = unit && unit.owner && !gap ? `${unit.owner}|${unit.type}|${unit.loaner}` : '';
  const body = `
    <div class="form-group"><label for="f-mtype">Schiffstyp</label><select id="f-mtype" data-input="mission-type">${types.map((k) => `<option value="${k}"${k === type ? ' selected' : ''}>${esc(typeLabel(k))}</option>`).join('')}</select>
      <p class="form-hint">Konzeptschiffe (Javelin, Kraken, Hull D …) stehen nicht zur Wahl.</p></div>
    <div class="form-group"><label for="f-mship">Schiff (optional)</label><select id="f-mship" data-variant="${variant}">${shipOptions(type, variant, selected)}</select>
      <p class="form-hint">Angeboten werden die Schiffe und eingeblendeten Loaner der angemeldeten Teilnehmer.</p></div>
    <div class="form-group"><label for="f-mname">Anzeigename</label><input type="text" id="f-mname" value="${esc(unit?.name || '')}" placeholder="leer: Name des Typs"></div>`;
  openDialog({ title: unit ? 'Einheit bearbeiten' : 'Einheit hinzufügen', body, foot: `<button type="button" class="btn btn-ghost" data-act="close-dialog">Abbrechen</button><button type="button" class="btn btn--cta" data-act="close-dialog">${icon('save')} <span>Speichern</span></button>`, wide: true });
}

/**
 * Handles the mission page's clicks and inputs.
 * @param {Event} ev
 * @param {{picker: string}} o
 * @returns {boolean} whether the page needs a re-render
 */
function missionEvent(ev, o) {
  const t = /** @type {HTMLElement} */ (ev.target);
  if (ev.type === 'change' && t instanceof HTMLSelectElement && t.dataset.input === 'mission-type') {
    const ship = /** @type {HTMLSelectElement} */ (document.getElementById('f-mship'));
    ship.innerHTML = shipOptions(t.value, o.picker, '');
    return false;
  }
  const btn = /** @type {HTMLElement|null} */ (t.closest('[data-act]'));
  if (!btn || ev.type !== 'click') return false;
  switch (btn.dataset.act) {
    case 'add-unit': openUnitShipDialog(null, o.picker); return false;
    case 'edit-unit': openUnitShipDialog(MISSION_UNITS[Number(btn.dataset.i)], o.picker); return false;
    case 'sim-gap': {
      const u = MISSION_UNITS[2];
      if (!u.gapped) { MY_LOANER_STATE.buccaneer.hidden = true; u.gapped = true; } else if (MY_LOANER_STATE.buccaneer.hidden) { MY_LOANER_STATE.buccaneer.hidden = false; }
      return true;
    }
    default: return false;
  }
}
