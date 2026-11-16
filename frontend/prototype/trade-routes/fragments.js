/** PROTOTYPE fragments shared by the variants: ratings, stop facts, cargo lines, key figures and markers. */

/**
 * Renders a danger or bugs aggregate.
 * @param {string} label the scale name
 * @param {{m: number, n: number, d: number}} agg the aggregate
 * @param {number} min the minimum count of members
 * @returns {string} markup
 */
function rateChip(label, agg, min) {
  const few = agg.n < min;
  const cls = few ? '' : agg.m >= 3.5 ? 'tr-rate--high' : agg.m >= 2.5 ? 'tr-rate--mid' : '';
  return `<span class="tr-rate ${cls}" title="${label}: Mittel aus ${agg.n} Meldern, neueste Meldung vor ${agg.d} Tagen"><span class="tr-cap-k">${label}</span><b>${dec(agg.m)}</b><small>${agg.n} Melder · vor ${agg.d} T.</small>${few ? ' <span class="tr-mark tr-mark--muted">wenige Meldungen</span>' : ''}</span>`;
}

/**
 * Renders the availability reliability of one cargo line.
 * @param {object} j the cargo line
 * @returns {string} markup
 */
function availChip(j) {
  if (!j.avail) return '<span class="tr-hint">keine Meldungen</span>';
  const few = j.avail.n < MIN_RATERS.avail;
  const cls = few ? '' : j.avail.pct < 50 ? 'tr-rate--high' : j.avail.pct < 75 ? 'tr-rate--mid' : '';
  return `<span class="tr-rate ${cls}"><b>${j.avail.pct} %</b><small>${j.avail.n} Melder</small>${few ? ' <span class="tr-mark tr-mark--muted">wenige</span>' : ''}</span>`;
}

/**
 * Renders the symbols and ratings of a stop.
 * @param {object} s the computed stop
 * @returns {string} markup
 */
function stopFacts(s) {
  const mine = state.myReports.filter((x) => x.t === s.t).length;
  return `<div class="tr-stoptile__meta">
    <span class="tr-sym" title="Schnellster erlaubter Lademodus an diesem Stopp">${esc(s.mode)}</span>
    <span class="tr-sym ${s.term.refuel ? '' : 'tr-sym--off'}">Tanken</span>
    <span class="tr-sym ${s.place.armistice ? '' : 'tr-sym--off'}">Waffenstillstand</span>
    <span class="tr-sym ${s.place.monitored ? '' : 'tr-sym--off'}">Überwacht</span>
    ${s.forced ? '<span class="tr-mark tr-mark--forced" title="Erzwungenes Terminal aus der Anfrage">erzwungen</span>' : ''}
  </div>
  <div class="tr-stoptile__meta">
    ${rateChip('Gefahr', s.place.danger, MIN_RATERS.danger)}
    ${rateChip('Bugs', s.term.bugs, MIN_RATERS.bugs)}
    ${mine ? `<span class="tr-mark tr-mark--ok">${mine} eigene Meldung${mine > 1 ? 'en' : ''}</span>` : ''}
    <button class="btn btn-ghost btn-xs" data-act="go-place" data-val="${s.term.place}" title="Stoppbewertungen dieses Orts">Alle Bewertungen</button>
  </div>`;
}

/**
 * Formats the change from an old to a new value in percent.
 * @param {number} now the new value
 * @param {number} was the old value
 * @returns {string} the signed change
 */
function pctChange(now, was) {
  const p = ((now - was) / was) * 100;
  return `${p > 0 ? '+' : p < 0 ? '−' : '±'}${dec(Math.abs(p))} %`;
}

/**
 * Renders the deviation note of a cargo line against the route snapshot.
 * @param {object} j the cargo line
 * @param {string} cell price or qty
 * @returns {string} markup
 */
function devNote(j, cell) {
  if (!j.dev) return '';
  if (cell === 'qty') return j.dev.wasQty ? `<div class="tr-was">vorher ${fmt(j.dev.wasQty)}</div>` : '';
  if (!j.dev.was) return '';
  const big = Math.abs((j.p - j.dev.was) / j.dev.was) >= 0.1;
  return `<div class="tr-was ${big ? 'tr-was--big' : ''}">vorher ${fmt(j.dev.was)} · ${pctChange(j.p, j.dev.was)}</div>`;
}

/**
 * Renders the stop time with its parts.
 * @param {object} s the computed stop
 * @returns {string} markup
 */
function stopTime(s) {
  const parts = [`Grundzeit ${fmtShort(s.over)}`, `Laden ${fmtShort(s.load)} (${s.mode})`];
  if (s.surcharge) parts.push(`Konvoi-Aufschlag ${s.loaders - 1} × ${fmtShort(SURCHARGE[s.place.kind])}`);
  const fee = s.fee ? ` · Auto-Load-Gebühr ${fmt(s.fee)} aUEC <span class="tr-hint">(nicht abgezogen)</span>` : '';
  return `<div class="tr-hint">Stoppzeit ${fmtShort(s.dur)}: ${parts.join(' + ')}${fee}</div>`;
}

/**
 * Renders the cargo lines of a stop as a table.
 * @param {object} s the computed stop
 * @param {object} r the computed route
 * @returns {string} markup
 */
function jobsTable(s, r) {
  const rows = [...s.jobs].sort((a, b) => (a.side === b.side ? 0 : a.side === 'sell' ? -1 : 1)).map((j) => {
    const sold = state.soldOut[j.key];
    const marks = [
      j.side === 'sell' && j.demand === null ? '<span class="tr-mark tr-mark--info tr-mark--est">Nachfrage unbekannt</span>' : '',
      j.age > 360 ? '<span class="tr-mark tr-mark--danger">abgelaufen</span>' : j.age > 168 ? '<span class="tr-mark tr-mark--warn">veraltet</span>' : '',
      j.warn ? `<span class="tr-mark tr-mark--warn" title="Warnware">⚠ ${esc(j.warn)}</span>` : '',
      sold ? '<span class="tr-mark tr-mark--danger">ausverkauft (nur deine Route)</span>' : '',
      j.neu ? '<span class="tr-mark tr-mark--est">neu im Plan</span>' : '',
      j.dev && j.dev.reason ? `<span class="tr-mark tr-mark--danger">${esc(j.dev.reason)}</span>` : '',
    ].join(' ');
    const split = r.convoy ? `<div class="tr-hint">${j.shares.map((q, i) => `${esc(r.ships[i].short)} ${fmt(q)}`).join(' · ')}</div>` : '';
    const action = j.side === 'buy' && !r.frozen
      ? `<button class="btn btn-ghost btn-xs" data-act="soldout" data-key="${j.key}" data-route="${r.id}" data-stop="${s.si}">${sold ? 'Markierung aufheben' : 'Ausverkauft'}</button>`
      : '';
    return `<tr class="${sold ? 'is-soldout' : ''}">
      <td class="c-side"><span class="tr-side tr-side--${j.side}">${j.side === 'buy' ? 'Kauf' : 'Verkauf'}</span></td>
      <td class="c-ware"><span class="tr-cname">${esc(j.c)}</span> ${marks}${split}</td>
      <td class="num c-qty">${fmt(j.qty)}<span class="c-unit"> SCU</span>${devNote(j, 'qty')}</td>
      <td class="num c-price"><span class="c-unit">à </span>${fmt(j.p)}${devNote(j, 'price')}</td>
      <td class="num c-sum ${j.side === 'buy' ? 'neg' : 'pos'}">${j.side === 'buy' ? '−' : '+'}${fmt(j.sum)}</td>
      <td class="nowrap c-age"><span class="tr-hint">${ageText(j.age)}</span></td>
      <td class="c-avail">${availChip(j)}</td>
      <td class="num c-act">${action}</td>
    </tr>`;
  }).join('') + (s.gone || []).map((g) => `<tr class="is-gone">
      <td class="c-side"><span class="tr-side tr-side--${g.side}">${g.side === 'buy' ? 'Kauf' : 'Verkauf'}</span></td>
      <td class="c-ware"><span class="tr-cname">${esc(g.c)}</span> <span class="tr-mark tr-mark--danger">${esc(g.gone)}</span></td>
      <td class="num c-qty">${fmt(g.qty)}<span class="c-unit"> SCU</span></td>
      <td class="num c-price"><span class="c-unit">à </span>${fmt(g.p)}</td>
      <td class="num c-sum">—</td><td class="c-age"></td><td class="c-avail"></td><td class="c-act"></td>
    </tr>`).join('');
  return `<div class="hud-scroll scroll-x"><table class="tr-jobs tr-jobs--stop"><thead><tr><th>Seite</th><th>Ware</th><th class="num">SCU</th><th class="num">aUEC/SCU</th><th class="num">Summe</th><th>Preis</th><th>Verfügbarkeit</th><th></th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

/**
 * Renders the key figures of a route, the objective's figure leading.
 * @param {object} r the computed route
 * @returns {string} markup
 */
function kpis(r) {
  const o = state.request.objective;
  const est = r.estimated ? '≈ ' : '';
  return `<div class="tr-kpis">
    <div class="tr-kpi ${o === 'maxProfit' ? 'tr-kpi--lead' : ''}"><div class="tr-kpi__k">Gewinn</div><div class="tr-kpi__v">${fmt(r.profit)}</div><div class="tr-kpi__s">aUEC · Umsatz ${fmt(r.revenue)}</div></div>
    <div class="tr-kpi ${o === 'perHour' ? 'tr-kpi--lead' : ''}"><div class="tr-kpi__k">Pro Stunde</div><div class="tr-kpi__v">${est}${fmt(r.perHour)}</div><div class="tr-kpi__s">aUEC/h</div></div>
    <div class="tr-kpi ${o === 'fastest' ? 'tr-kpi--lead' : ''}"><div class="tr-kpi__k">Dauer</div><div class="tr-kpi__v">${est}${fmtDur(r.duration)}</div><div class="tr-kpi__s">${r.stops.length} Stopps${r.round ? ' · Rundreise' : ''}</div></div>
    <div class="tr-kpi"><div class="tr-kpi__k">Kapital nötig</div><div class="tr-kpi__v">${fmt(r.capital)}</div><div class="tr-kpi__s">aUEC höchste Auslage</div></div>
    <div class="tr-kpi"><div class="tr-kpi__k">Bestand reicht</div><div class="tr-kpi__v">~${r.runs}×</div><div class="tr-kpi__s">Durchläufe, geplant ${state.request.runs}</div></div>
    <div class="tr-kpi"><div class="tr-kpi__k">Datenstand</div><div class="tr-kpi__v">${ageShort(r.oldest)}</div><div class="tr-kpi__s">${r.stale ? '<span class="warn-text">ältester Preis veraltet</span>' : 'ältester Preis'}</div></div>
  </div>`;
}

/**
 * Renders the route's warnings and hints.
 * @param {object} r the computed route
 * @returns {string} markup
 */
function warnList(r) {
  const lines = [...r.warnings];
  if (state.search.deadline && state.search.phase === 'done') lines.unshift({ kind: 'info', icon: 'clock', text: 'Suche nach 60 s beendet: bestes bis dahin gefundenes Ergebnis.' });
  if (state.search.stopped && state.search.phase === 'done') lines.unshift({ kind: 'info', icon: 'clock', text: 'Suche gestoppt: bestes bis dahin gefundenes Ergebnis.' });
  if (!lines.length) return '';
  return `<div class="tr-warnbox">${lines.map((w) => `<div class="tr-warnline ${w.kind === 'info' ? 'tr-warnline--info' : ''}">${ic(w.icon)}<span>${w.text}</span></div>`).join('')}</div>`;
}

/**
 * Renders a leg's travel facts, gateways and detour marker.
 * @param {object} l the computed leg
 * @param {boolean} compact whether to leave out the gateway sequence
 * @returns {string} markup
 */
function legFacts(l, compact) {
  const est = l.est.length ? `<span class="tr-mark tr-mark--est" title="${esc(l.est.join('; '))}">≈ geschätzt</span>` : '';
  const cross = l.crossings ? ` · ${l.crossings} Sprung${l.crossings > 1 ? 'e' : ''} à ${fmtShort(CROSSING)}` : '';
  const gw = l.gateways && !compact ? `<div class="tr-hint">Gateways: ${l.gateways.map(esc).join(' → ')}</div>` : '';
  const detour = l.detour ? `<div class="tr-warnline" style="margin-top:6px">${ic('warning')}<span><strong>Umleitung:</strong> ${esc(l.detour.text)} · +${fmtDur(l.detour.extra)}</span></div>` : '';
  return `<div><strong class="num">${l.est.length ? '≈ ' : ''}${fmtShort(l.dur)}</strong> Flug · ${dec(l.gm)} Gm${cross} · an Bord ${fmt(l.aboard)} / ${fmt(l.hold)} SCU ${est}</div>${gw}${detour}`;
}

/**
 * Renders the per-ship money of a convoy route.
 * @param {object} r the computed route
 * @returns {string} markup
 */
function shipShares(r) {
  if (!r.convoy) return '';
  const rows = r.ships.map((s) => {
    const short = s.capital && s.need > s.capital;
    return `<tr><td><span class="tr-cname">${esc(s.label)}</span></td><td class="num">${fmt(s.need)}${short ? ' <span class="tr-mark tr-mark--danger">über Startkapital</span>' : ''}</td><td class="num">${fmt(s.revenue)}</td><td class="num pos">+${fmt(s.profit)}</td><td class="num">${fmt(s.perHour)}</td></tr>`;
  }).join('');
  return `<div class="card card--inset" style="padding:10px 12px"><div class="tr-cap" style="margin-bottom:6px">Pro Schiff</div><div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th>Schiff</th><th class="num">Kapital nötig</th><th class="num">Umsatz</th><th class="num">Gewinn</th><th class="num">aUEC/h</th></tr></thead><tbody>${rows}<tr><td><strong>Konvoi gesamt</strong></td><td class="num">${fmt(r.capital)}</td><td class="num">${fmt(r.revenue)}</td><td class="num pos"><strong>+${fmt(r.profit)}</strong></td><td class="num">${fmt(r.perHour)}</td></tr></tbody></table></div></div>`;
}

/**
 * Renders the per-ship shopping list of one stop.
 * @param {object} s the computed stop
 * @param {object} r the computed route
 * @param {number} mine the index of the viewer's own ship, or -1
 * @returns {string} markup
 */
function shipLists(s, r, mine = -1) {
  if (!r.convoy) return '';
  return `<div class="tr-ships-mini">${r.ships.map((sh, i) => {
    const lines = s.jobs.filter((j) => j.shares[i] > 0).map((j) => `<div>${j.side === 'buy' ? '<span class="neg">Kauf</span>' : '<span class="pos">Verkauf</span>'} ${fmt(j.shares[i])} ${esc(j.c)}</div>`).join('');
    return `<div class="${i === mine ? 'is-mine' : ''}"><b>${esc(sh.label)}</b>${i === mine ? ' <span class="tr-mark tr-mark--forced">dein Schiff</span>' : ''}${lines || '<div class="muted">fliegt mit, lädt nichts</div>'}</div>`;
  }).join('')}</div>`;
}

/**
 * Renders the vertical stop timeline of a route.
 * @param {object} r the computed route
 * @param {{edit: boolean, insert: boolean, mine: number}} opts whether edit entry points show, and the viewer's ship
 * @returns {string} markup
 */
function stopTimeline(r, opts = { edit: true, insert: true, mine: -1 }) {
  const items = r.stops.map((s, i) => {
    const leg = r.legs[i];
    const insert = leg && opts.insert ? `<li class="tr-insert"><button class="btn btn-ghost btn-xs" data-act="edit" data-what="Stopp einfügen">${ic('plus')} Stopp einfügen</button></li>` : '';
    const legHtml = leg ? `<li class="tr-timeline__leg ${leg.detour ? 'tr-timeline__leg--detour' : ''}">${legFacts(leg, false)}</li>${insert}` : '';
    const hard = (s.gone && s.gone.length) || s.jobs.some((j) => (j.dev && j.dev.reason) || j.age > 168);
    const tools = opts.edit ? stopEdit(s, r) : `<div class="tr-iconbtns"><button class="btn btn-ghost btn-xs" data-act="rate" data-route="${r.id}" data-stop="${s.si}">Bewerten</button></div>`;
    return `<li class="tr-timeline__stop" data-n="${i + 1}"><div class="tr-stoptile ${hard && r.compared ? 'tr-stoptile--dev' : ''}">
        <div class="tr-stoptile__head"><div><div class="tr-stoptile__title">${esc(s.term.name)}</div><div class="tr-stoptile__sub">${s.place.kind} · ${s.place.body} · ${s.place.system}${r.round && i === r.stops.length - 1 ? ' · zurück zum Start' : ''}</div></div>${tools}</div>
        ${stopFacts(s)}
        ${jobsTable(s, r)}
        ${shipLists(s, r, opts.mine)}
        ${stopTime(s)}
      </div></li>${legHtml}`;
  }).join('');
  return `<ol class="tr-timeline">${items}</ol>`;
}

/**
 * Renders the route-level actions.
 * @param {object} r the computed route
 * @returns {string} markup
 */
function routeActions(r) {
  const saved = state.saved.includes(r.id);
  if (state.missionSearch) {
    return `<div class="tr-actions">
      <button class="btn btn--cta" data-act="mission-adopt">${ic('check')} ${state.missionSearch.mode === 'edit' ? 'Einsatzroute aktualisieren' : 'In den Einsatz übernehmen'}</button>
      <button class="btn btn-ghost" data-act="go" data-page="mission">${ic('arrow-left')} Zurück zum Einsatz</button>
    </div>`;
  }
  return `<div class="tr-actions">
    <button class="btn btn--cta" data-act="save" data-route="${r.id}" ${saved ? 'aria-pressed="true"' : ''}>${ic(saved ? 'check' : 'save')} ${saved ? 'Gespeichert' : 'Speichern'}</button>
    <button class="btn btn-outline" data-act="fly" data-route="${r.id}">${ic('arrow-right')} Losfliegen</button>
    <button class="btn btn-ghost" data-act="share" data-route="${r.id}">Teilen</button>
    <button class="btn btn-ghost" data-act="to-unit">${ic('users')} Für Einheit</button>
  </div>`;
}

/**
 * Renders the edit entry points of a stop.
 * @param {object} s the computed stop
 * @param {object} r the computed route
 * @returns {string} markup
 */
function stopEdit(s, r) {
  const last = s.si === r.stops.length - 1;
  return `<div class="tr-iconbtns">
    <button class="btn btn-ghost btn-xs" data-act="rate" data-route="${r.id}" data-stop="${s.si}">Bewerten</button>
    <button class="btn btn-ghost btn-xs btn-icon" data-act="edit" data-what="Stopp nach oben" aria-label="Stopp nach oben" title="Stopp nach oben" ${s.si === 0 ? 'disabled' : ''}>${ic('chevron-up')}</button>
    <button class="btn btn-ghost btn-xs btn-icon" data-act="edit" data-what="Stopp nach unten" aria-label="Stopp nach unten" title="Stopp nach unten" ${last ? 'disabled' : ''}>${ic('chevron-down')}</button>
    <button class="btn btn-quiet-danger btn-xs btn-icon" data-act="edit" data-what="Stopp entfernen" aria-label="Stopp entfernen" title="Stopp entfernen">${ic('trash')}</button>
  </div>`;
}

/**
 * Renders the search status with progress and stop.
 * @returns {string} markup
 */
function statusBar() {
  const s = state.search;
  if (s.phase === 'running') {
    const label = ['Suche läuft · 2-Stopp-Durchgang', 'Zwischenergebnis · Suche läuft weiter', 'Verbessert · lokale Suche'][s.step] || 'Suche läuft';
    return `<div class="tr-status tr-status--running" role="status"><span class="krt-spinner" aria-hidden="true"></span><strong>${label}</strong><span class="tr-status__bar"><i style="width:${[15, 45, 80][s.step] || 10}%"></i></span><span class="muted num">${s.elapsed} s / 60 s</span><button class="btn btn-ghost btn-xs" data-act="stop-search">Suche stoppen</button></div>`;
  }
  const how = s.deadline ? 'Frist erreicht nach 60 s' : s.stopped ? `gestoppt nach ${s.elapsed} s` : `abgeschlossen in ${s.elapsed || 6} s`;
  const fresh = state.newPrices ? `<div class="tr-warnline tr-warnline--info" role="status">${ic('info')}<span>Neue Preise seit 15:00 Uhr. Die Route bleibt, bis du sie prüfst.</span><button class="btn btn-ghost btn-xs" data-act="recheck" style="margin-left:auto">Route prüfen</button></div>` : '';
  return `<div class="tr-status" role="status">${ic('check')}<span>Suche ${how} · ${state.results.length} Route${state.results.length > 1 ? 'n' : ''} · Datenstand 14:00 Uhr</span><span class="muted">Neue Preise kommen stündlich.</span></div>${fresh}`;
}

/**
 * Returns the ranked computed results of the last search.
 * @returns {Array<object>} the routes
 */
function results() {
  const ids = state.search.phase === 'running' ? [[], ['r3'], ['r1', 'r3']][state.search.step] || [] : state.results;
  return rank(ids.map((id) => computeRoute(ROUTES.find((x) => x.id === id), state.resultShips)), state.request);
}

const pageRoutes = {};

/**
 * Finds a computed route shown on the current page, falling back to the planner's results.
 * @param {string} id the route id
 * @returns {object|undefined} the computed route
 */
function routeById(id) {
  return pageRoutes[id] || results().find((x) => x.id === id);
}
