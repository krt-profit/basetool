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
  </div>`;
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
      j.age > 168 ? '<span class="tr-mark tr-mark--warn">veraltet</span>' : '',
      j.warn ? `<span class="tr-mark tr-mark--warn" title="Warnware">⚠ ${esc(j.warn)}</span>` : '',
      sold ? '<span class="tr-mark tr-mark--danger">ausverkauft (nur deine Route)</span>' : '',
    ].join(' ');
    const split = r.convoy ? `<div class="tr-hint">${j.shares.map((q, i) => `${esc(r.ships[i].short)} ${fmt(q)}`).join(' · ')}</div>` : '';
    const action = j.side === 'buy'
      ? `<button class="btn btn-ghost btn-xs" data-act="soldout" data-key="${j.key}" data-route="${r.id}" data-stop="${s.si}">${sold ? 'Markierung aufheben' : 'Ausverkauft'}</button>`
      : '';
    return `<tr class="${sold ? 'is-soldout' : ''}">
      <td class="c-side"><span class="tr-side tr-side--${j.side}">${j.side === 'buy' ? 'Kauf' : 'Verkauf'}</span></td>
      <td class="c-ware"><span class="tr-cname">${esc(j.c)}</span> ${marks}${split}</td>
      <td class="num c-qty">${fmt(j.qty)}<span class="c-unit"> SCU</span></td>
      <td class="num c-price"><span class="c-unit">à </span>${fmt(j.p)}</td>
      <td class="num c-sum ${j.side === 'buy' ? 'neg' : 'pos'}">${j.side === 'buy' ? '−' : '+'}${fmt(j.sum)}</td>
      <td class="nowrap c-age"><span class="tr-hint">${ageText(j.age)}</span></td>
      <td class="c-avail">${availChip(j)}</td>
      <td class="num c-act">${action}</td>
    </tr>`;
  }).join('');
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
 * @returns {string} markup
 */
function shipLists(s, r) {
  if (!r.convoy) return '';
  return `<div class="tr-ships-mini">${r.ships.map((sh, i) => {
    const lines = s.jobs.filter((j) => j.shares[i] > 0).map((j) => `<div>${j.side === 'buy' ? '<span class="neg">Kauf</span>' : '<span class="pos">Verkauf</span>'} ${fmt(j.shares[i])} ${esc(j.c)}</div>`).join('');
    return `<div><b>${esc(sh.label)}</b>${lines || '<div class="muted">fliegt mit, lädt nichts</div>'}</div>`;
  }).join('')}</div>`;
}

/**
 * Renders the route-level actions.
 * @param {object} r the computed route
 * @returns {string} markup
 */
function routeActions(r) {
  const saved = state.saved.includes(r.id);
  return `<div class="tr-actions">
    <button class="btn btn--cta" data-act="save" data-route="${r.id}" ${saved ? 'aria-pressed="true"' : ''}>${ic(saved ? 'check' : 'save')} ${saved ? 'Gespeichert' : 'Speichern'}</button>
    <button class="btn btn-outline" data-act="fly" data-route="${r.id}">${ic('arrow-right')} Losfliegen</button>
    <button class="btn btn-ghost" data-act="share" data-route="${r.id}">Teilen</button>
    <button class="btn btn-ghost" data-act="followup" data-what="Als Einheitsroute übernehmen">${ic('users')} Für Einheit</button>
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
