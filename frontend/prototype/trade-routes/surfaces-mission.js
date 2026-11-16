/** PROTOTYPE mission routes: a mission's routes tab with order, route fleet, binding plan, deviations and a crew's part. */

const MISSION_ROUTE_LIMIT = 10;
const MY_UNIT = 'ALPHA';

/**
 * Computes a mission route's stored plan and its recomputation on current data.
 * @param {object} mr the mission route
 * @returns {{plan: object, now: object}} the binding plan and the fresh computation
 */
function missionRoute(mr) {
  const fleet = mr.fleet.map((k) => FLEET_SHIPS[k]).filter(Boolean);
  const ships = fleet.length ? fleet : [{ ...SHIP_C2_TYPE }];
  const plan = computeRoute(ROUTES.find((x) => x.id === mr.base), ships);
  const now = computeRoute(ROUTES.find((x) => x.id === mr.now), ships);
  plan.frozen = true;
  now.compared = true;
  pageRoutes[plan.id] = plan;
  return { plan, now };
}

/**
 * Renders the route fleet of a mission route, editable for a mission manager.
 * @param {object} mr the mission route
 * @returns {string} markup
 */
function fleetPanel(mr) {
  const manager = state.missionRole === 'manager';
  const rows = MISSION.units.map((u) => {
    const inFleet = mr.fleet.includes(u.key);
    const ship = u.ship ? `${esc(u.hangar || u.ship)} <span class="tr-hint">${u.hangar ? `Hangarschiff von ${esc(u.owner)}` : 'Typ, kein Hangarschiff zugewiesen'}</span>` : '<span class="muted">kein Schiff</span>';
    const why = !u.ship ? 'kein Schiff' : !u.cargo ? 'kein Frachtschiff' : '';
    const box = `<input type="checkbox" ${inFleet ? 'checked' : ''} ${!manager || why ? 'disabled' : ''} data-act="fleet-toggle" data-route="${mr.id}" data-val="${u.key}" aria-label="${u.key} trägt Fracht">`;
    const fields = inFleet
      ? `<td class="num">${manager ? '<input type="number" value="1500000" style="width:8rem">' : '1.500.000'}</td><td class="num">${manager ? '<input type="number" value="0" style="width:5rem">' : '0'}</td><td>${manager ? `<select>${DRIVES.map((d) => `<option>${d}</option>`).join('')}</select>` : 'Standard des Typs'}</td>`
      : `<td colspan="3" class="tr-hint">${why || 'nicht in dieser Routenflotte'}</td>`;
    return `<tr class="${inFleet ? '' : 'is-off'}"><td>${box}</td><td><span class="squadron-badge">${u.key}</span></td><td>${ship}</td>${fields}</tr>`;
  }).join('');
  const note = mr.fleetNote ? `<div class="tr-warnline tr-warnline--danger">${ic('warning')}<span>Flotte geändert: ${esc(mr.fleetNote)}. Nichts neu gerechnet, bis ein Einsatzleiter die Flotte übernimmt.</span></div>` : '';
  const none = !mr.fleet.length ? `<div class="tr-warnline tr-warnline--info">${ic('info')}<span>Keine Flotte gewählt: Der Plan rechnet mit der Schiffsliste der Quelle (Crusader C2 Hercules, Typ).</span></div>` : '';
  return `<div class="card card--inset tr-fleet"><div class="tr-row tr-row--between"><div class="tr-cap">Routenflotte</div>${manager ? '<span class="tr-hint">Vorausgewählt: Einheiten mit Frachtschiff und Quantum-Antrieb</span>' : ''}</div>
    ${note}${none}
    <div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th></th><th>Einheit</th><th>Schiff</th><th class="num">Startkapital</th><th class="num">Reserve SCU</th><th>Antrieb</th></tr></thead><tbody>${rows}</tbody></table></div>
    ${manager ? `<div class="tr-row"><button class="btn btn-outline btn-xs" data-act="fleet-apply" data-route="${mr.id}">${ic('check')} Flotte übernehmen</button><span class="tr-hint">Plant die Fracht auf den Stopps neu, speichert den Plan und benachrichtigt die Crews.</span></div>` : ''}
  </div>`;
}

/**
 * Renders the stop overheads a mission route carries, editable for a mission manager.
 * @returns {string} markup
 */
function routeOverheads() {
  const manager = state.missionRole === 'manager';
  const cell = (v) => (manager ? `<input type="number" min="0" value="${v}" style="width:4.5rem">` : v);
  return `<details class="card card--inset tr-overheads"><summary class="tr-cap">Stoppzeiten dieser Route <span class="tr-hint">aus der Quelle übernommen${manager ? '; ändern rechnet den Plan neu' : ', nicht deine eigenen'}</span></summary>
    <table class="tr-jobs"><thead><tr><th>Ortsart</th><th class="num">Grundzeit (min)</th><th class="num">Konvoi-Aufschlag (min)</th></tr></thead><tbody>${KINDS.map((k) => `<tr><td>${k}</td><td class="num">${cell(OVERHEADS[k])}</td><td class="num">${cell(SURCHARGE[k] / 60)}</td></tr>`).join('')}</tbody></table></details>`;
}

/**
 * Renders one mission route as a tile with its expandable plan.
 * @param {object} mr the mission route
 * @param {number} i its position in the mission's route order
 * @param {number} count the number of routes
 * @returns {string} markup
 */
function missionTile(mr, i, count) {
  const { plan, now } = missionRoute(mr);
  const manager = state.missionRole === 'manager';
  const open = state.missionOpen.includes(mr.id);
  const mineIdx = state.missionRole === 'crew' ? mr.fleet.indexOf(MY_UNIT) : -1;
  const fleet = mr.fleet.length ? mr.fleet.map((k) => `<span class="tr-fleetchip ${k === MY_UNIT && state.missionRole === 'crew' ? 'is-mine' : ''}"><span class="squadron-badge">${k}</span>${esc(k === 'ALPHA' ? 'C2 Hercules „Lastesel“' : 'Drake Caterpillar (Typ)')}</span>`).join('') : '<span class="tr-hint">Schiffsliste der Quelle</span>';
  const marks = mr.marks.map((m) => `<span class="tr-mark tr-mark--${m.kind}">${esc(m.text)}</span>`).join(' ');
  const order = manager ? `<span class="tr-iconbtns">${rowBtn('m-up', 'chevron-up', 'Nach oben', '', `data-val="${i}" ${i === 0 ? 'disabled' : ''}`)}${rowBtn('m-down', 'chevron-down', 'Nach unten', '', `data-val="${i}" ${i === count - 1 ? 'disabled' : ''}`)}</span>` : '';
  const head = `<div class="tr-mroute__head">
      <span class="tr-mroute__n">${i + 1}</span>
      <div class="tr-mroute__title"><div class="tr-stoptile__title">${esc(mr.name)}</div><div class="tr-hint">${esc(plan.name)}</div><div class="tr-row" style="margin-top:6px">${fleet}</div></div>
      <div class="tr-mroute__nums"><span>Gewinn<b>${fmt(plan.profit)}</b></span><span>Dauer<b>${fmtDur(plan.duration)}</b></span><span>aUEC/h<b>${fmt(plan.perHour)}</b></span></div>
      <div class="tr-mroute__side"><div class="tr-row">${marks}</div><div class="tr-row">${order}<button class="btn btn-ghost btn-xs" data-act="m-open" data-val="${mr.id}" aria-expanded="${open}">${open ? 'Plan ausblenden' : 'Plan anzeigen'}</button></div></div>
    </div>`;
  if (!open) return `<article class="tr-mroute">${head}</article>`;
  const deviates = mr.marks.some((m) => m.kind !== 'muted');
  const adopt = manager
    ? `<button class="btn btn-outline btn-xs" data-act="m-adopt" data-val="${mr.id}">${ic('check')} Route aktualisieren</button><span class="tr-hint">übernimmt die Neuberechnung und benachrichtigt die Crews der Flotte</span>`
    : '<span class="tr-hint">Ein Einsatzleiter entscheidet, ob der Plan aktualisiert wird. Bis dahin gilt der gespeicherte Plan.</span>';
  const copyOut = `<div class="tr-actions">
      ${mineIdx >= 0 ? `<button class="btn btn--cta" data-act="fly-part" data-route="${plan.id}" data-ship="${mineIdx}">${ic('arrow-right')} Losfliegen (mein Teil)</button>` : `<button class="btn btn-outline" data-act="fly" data-route="${plan.id}">${ic('arrow-right')} Losfliegen</button>`}
      <button class="btn btn-ghost" data-act="save-copy">${ic('save')} Als meine Route speichern</button>
      <button class="btn btn-ghost" data-act="to-unit">${ic('users')} Für Einheit</button>
      <button class="btn btn-ghost" data-act="share" data-route="${plan.id}">Teilen</button>
    </div>`;
  const managerTools = manager ? `<div class="tr-actions"><button class="btn btn-ghost btn-xs" data-act="m-edit" data-val="${mr.id}">${ic('edit')} Im Planer bearbeiten</button><button class="btn btn-ghost btn-xs" data-act="m-optimise" data-val="${mr.id}">${ic('search')} Für die Flotte optimieren</button><button class="btn btn-quiet-danger btn-xs" data-act="confirm-m-remove" data-val="${mr.id}">${ic('trash')} Aus dem Einsatz entfernen</button></div>` : '';
  return `<article class="tr-mroute is-open">${head}<div class="tr-mroute__body">
      ${deviates ? `${compareStrip(plan, now, 'Gespeicherter Plan (Briefing) gegenüber einer Neuberechnung auf aktuellen Daten')}${devBox(now)}<div class="tr-row">${adopt}</div>` : `<div class="tr-warnline tr-warnline--ok">${ic('check')}<span>Keine Abweichungen: der gespeicherte Plan gilt.</span></div>`}
      ${fleetPanel(mr)}
      ${routeOverheads()}
      ${copyOut}
      ${kpis(plan)}
      ${shipShares(plan)}
      ${stopTimeline(plan, { edit: false, insert: false, mine: mineIdx })}
      ${managerTools}
    </div></article>`;
}

/**
 * Renders the crew's part: the routes their unit flies in order, with its cargo per stop and the transfer legs.
 * @returns {string} markup
 */
function myPart() {
  const chain = state.missionRoutes.filter((mr) => mr.fleet.includes(MY_UNIT));
  if (!chain.length) return '<div class="empty-state"><div class="empty-title">Kein Teil</div><div class="empty-text">Deine Einheit ist in keiner Routenflotte dieses Einsatzes.</div></div>';
  let total = 0;
  const parts = chain.map((mr, ci) => {
    const { plan } = missionRoute(mr);
    const idx = mr.fleet.indexOf(MY_UNIT);
    total += plan.duration;
    const stops = plan.stops.map((s) => {
      const jobs = s.jobs.filter((j) => (plan.convoy ? j.shares[idx] : j.qty) > 0).map((j) => `<span class="${j.side === 'buy' ? 'neg' : 'pos'}">${j.side === 'buy' ? 'Kauf' : 'Verkauf'} ${fmt(plan.convoy ? j.shares[idx] : j.qty)} ${esc(j.c)}</span>`).join(' · ');
      return `<li><b>${esc(s.term.name)}</b><div class="tr-hint">${jobs || 'fliegt mit, lädt nichts'}</div></li>`;
    }).join('');
    const transfer = ci < chain.length - 1 ? (() => { total += TRANSFER.dur; return `<div class="tr-transfer">${ic('arrow-right')}<span><strong>Leerflug</strong> ${esc(TRANSFER.from)} → ${esc(TRANSFER.to)} · ≈ ${fmtShort(TRANSFER.dur)} · ${dec(TRANSFER.gm)} Gm · mit dem Antrieb deiner C2</span></div>`; })() : '';
    const pos = state.missionRoutes.indexOf(mr) + 1;
    return `<div class="tr-chain__route"><div class="tr-row tr-row--between"><div><span class="tr-cap">Route ${pos}</span> <strong>${esc(mr.name)}</strong> <span class="tr-hint">${fmtDur(plan.duration)}${plan.convoy ? ` · im Konvoi mit ${plan.ships.length - 1} Schiff` : ''}</span></div><button class="btn btn-ghost btn-xs" data-act="fly-part" data-route="${plan.id}" data-ship="${idx}">${ic('arrow-right')} Losfliegen</button></div><ol class="tr-chain__stops">${stops}</ol></div>${transfer}`;
  }).join('');
  return `<section class="card card--accent tr-chain"><div class="tr-row tr-row--between"><h4 class="card-title">Dein Teil · <span class="squadron-badge">${MY_UNIT}</span> C2 Hercules „Lastesel“</h4><span class="tr-hint">Kette ${fmtDur(total)} · ${chain.length} Routen in Einsatz-Reihenfolge</span></div>${parts}
    <div class="tr-hint">Nur Information: die Kette wird nicht optimiert, die nächste Route muss nicht dort beginnen, wo die letzte endet.</div></section>`;
}

/**
 * Renders the mission page with its routes tab.
 * @returns {string} markup
 */
function missionPage() {
  const manager = state.missionRole === 'manager';
  const crew = state.missionRole === 'crew';
  const list = state.missionRoutes;
  const head = `<div class="page-head"><div class="page-head__main"><a class="page-eyebrow" href="#" data-act="followup" data-what="Einsätze">${ic('arrow-left')}<span>Einsätze</span></a><div class="page-title"><h1>${esc(MISSION.name)}</h1><span class="squadron-badge">ALP</span><span class="status-badge status-planned">Geplant</span></div></div>
      <div class="page-actions">${manager ? `<button class="btn btn-ghost">${ic('edit')} Bearbeiten</button>` : ''}<button class="btn btn--cta">${ic('user-plus')} Anmelden</button></div></div>
    <div class="facts-bar"><span class="fact"><span class="fact-k">TS</span> <span class="fact-v">20:00</span></span><span class="fact"><span class="fact-k">Server-Join</span> <span class="fact-v">20:15</span></span><span class="fact"><span class="fact-k">Ende</span> <span class="fact-v">23:30</span></span><span class="fact"><span class="fact-k">Treffpunkt</span> <span class="fact-v">Seraphim Station</span></span><span class="fact"><span class="fact-k">Leiter</span> <span class="fact-v">Nachtfalke</span></span><span class="fact"><span class="fact-k">Teilnehmer</span> <span class="fact-v">9 · 0 eingecheckt</span></span></div>
    <div class="tab-nav" role="tablist"><button class="tab" data-act="followup" data-what="Übersicht des Einsatzes">Übersicht</button><button class="tab" data-act="followup" data-what="Teilnehmer & Einheiten">Teilnehmer &amp; Einheiten <span class="tab-count">0/9</span></button><button class="tab" data-act="followup" data-what="Finanzen & Auszahlung">Finanzen &amp; Auszahlung</button><button class="tab active" aria-selected="true">Routen <span class="tab-count">${list.length}</span></button></div>`;
  const note = `<p class="proto-note"><strong>PROTOTYP</strong> · Ansicht als ${{ manager: 'Einsatzleiter', crew: `Crew von ${MY_UNIT} (${ME})`, reader: 'Mitglied ohne Rolle im Einsatz' }[state.missionRole]} · ${state.missionAlt === 'top' ? 'Alternative A: „Dein Teil“ steht über den Routen' : 'Alternative B: Umschalter „Alle Routen | Mein Teil“'}.</p>`;
  const toolbar = `<div class="tr-row tr-row--between"><div><div class="tr-cap">Routen des Einsatzes</div><span class="tr-hint">${list.length} von ${MISSION_ROUTE_LIMIT} · Reihenfolge von Hand. Der gespeicherte Plan ist das Briefing und sieht für alle gleich aus.</span></div>${manager ? `<button class="btn btn-outline" data-act="add-route">${ic('plus')} Route hinzufügen</button>` : ''}</div>`;
  const tiles = list.length ? list.map((mr, i) => missionTile(mr, i, list.length)).join('') : `<div class="empty-state"><div class="empty-title">Keine Routen</div><div class="empty-text">${manager ? 'Füge eine gespeicherte, eine Einheits- oder eine geteilte Route hinzu oder suche eine neue für die Flotte.' : 'Dieser Einsatz hat keine Handelsrouten.'}</div></div>`;
  let body = `${toolbar}${tiles}`;
  if (crew && state.missionAlt === 'top') body = `${myPart()}${body}`;
  if (crew && state.missionAlt === 'switch') {
    const seg = `<div class="krt-segmented" role="group"><button type="button" class="krt-segmented__item" data-act="m-view" data-val="all" aria-pressed="${state.missionView === 'all'}">Alle Routen</button><button type="button" class="krt-segmented__item" data-act="m-view" data-val="mine" aria-pressed="${state.missionView === 'mine'}">Mein Teil</button></div>`;
    body = `${seg}${state.missionView === 'mine' ? myPart() : body}`;
  }
  return `${head}${note}<div class="tr-stack tr-tabbody">${body}<span class="tr-hint">Eine Operation zeigt die Routen ihrer verknüpften Einsätze nur lesend.</span></div>`;
}
