/** PROTOTYPE views for building a route stop by stop: the start screen, the append panel with both keys, the candidate lines and the forced warning. */

const KEY_LABEL = { onward: 'Mit Weiterflug', here: 'Bis hier' };

/**
 * Names the figure candidates are ranked by under the request's objective.
 * @returns {string} the unit label
 */
function rankUnit() {
  return state.request.objective === 'maxProfit' ? 'aUEC Gewinn' : 'aUEC/h';
}

/**
 * Renders a signed aUEC delta.
 * @param {number} n aUEC
 * @returns {string} markup
 */
function signed(n) {
  return `<span class="${n >= 0 ? 'pos' : 'neg'}">${n >= 0 ? '+' : '−'}${fmt(Math.abs(n))}</span>`;
}

/**
 * Renders the markers of a candidate: forced, filter, time limit, minimum profit, stale prices and availability.
 * @param {object} c the candidate
 * @returns {string} markup
 */
function candMarks(c) {
  return [
    c.forced ? '<span class="tr-mark tr-mark--forced">erzwungen</span>' : '',
    c.why ? `<span class="tr-mark tr-mark--warn">verletzt Filter: ${esc(c.why)}</span>` : '',
    c.over ? '<span class="tr-mark tr-mark--danger">über Zeitlimit</span>' : '',
    c.reachesMin ? '<span class="tr-mark tr-mark--ok">erreicht Mindestgewinn</span>' : '',
    ...c.markers.map((m) => `<span class="tr-mark tr-mark--${m.kind}">${esc(m.text)}</span>`),
  ].join(' ');
}

/**
 * Renders the reasons of a candidate as one short line each, for the compact line of variant B.
 * @param {object} c the candidate
 * @param {string} key onward or here
 * @param {boolean} holding whether earlier cargo is held
 * @returns {string} markup
 */
function candWhyShort(c, key, holding) {
  const sells = c.sells.length
    ? `${ic('bank-in')}<span><b>Verkauft hier</b> ${c.sells.map((s) => `${esc(s.c)} ${fmt(s.qty)}`).join(' · ')} SCU · ${signed(c.sells.reduce((a, s) => a + s.sum, 0))}</span>`
    : `${ic('bank-in')}<span class="muted">Verkauft hier nichts</span>`;
  let next = '';
  if (c.nextBuys.length) next = `${ic('arrow-right')}<span><b>Kauft hier für ${esc(pname(c.nextBuys[0].to))}</b> ${c.nextBuys.map((b) => `${esc(b.c)} ${fmt(b.qty)}`).join(' · ')} SCU</span>`;
  else if (key === 'onward' && c.hop) next = `${ic('arrow-right')}<span><b>Kauft hier für weiter</b> <span class="tr-mark tr-mark--est">≈ Schätzung</span> ${esc(c.hop.c)} ${fmt(c.hop.qty)} SCU → ${esc(pname(c.hop.to))} · ${signed(c.hop.profit)} · ${fmtShort(c.hop.dur)}</span>`;
  else if (key === 'onward') next = `${ic('arrow-right')}<span class="muted">Kein lohnender Weiterflug von hier</span>`;
  let effect = '';
  if (holding && c.moved.length) effect = `${ic('swap')}<span>${c.moved.map((m) => `${esc(m.c)} ${fmt(m.qty)} SCU wandert von ${esc(pname(m.from))} hierher`).join(' · ')}</span>`;
  else if (!holding && c.changes) effect = `${ic('swap')}<span>${c.changes} frühere${c.changes > 1 ? '' : 'r'} Ladeauftrag${c.changes > 1 ? 'e ändern' : ' ändert'} sich</span>`;
  return `<div class="tr-cand__why"><div>${sells}</div>${next ? `<div>${next}</div>` : ''}${effect ? `<div>${effect}</div>` : ''}</div>`;
}

/**
 * Renders the expanded reasons of a candidate: each commodity sold, the next purchase, the effect on earlier cargo and the markers.
 * @param {object} c the candidate
 * @param {string} key onward or here
 * @param {boolean} holding whether earlier cargo is held
 * @param {string} prev the former last stop's terminal
 * @returns {string} markup
 */
function candDetail(c, key, holding, prev) {
  const sells = c.sells.map((s) => `<tr><td><span class="tr-cname">${esc(s.c)}</span>${s.unknown ? ' <span class="tr-mark tr-mark--est">Nachfrage unbekannt</span>' : ''}${s.avail ? ` <span class="tr-hint">Verfügbarkeit ${s.avail.pct} % (${s.avail.n})</span>` : ''}</td><td class="num">${fmt(s.qty)}</td><td class="num">${fmt(s.p)}</td><td class="num pos">+${fmt(s.sum)}</td></tr>`).join('');
  const sellBlock = `<div class="tr-cap">Verkauft hier</div>${sells ? `<div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th>Ware</th><th class="num">SCU</th><th class="num">à</th><th class="num">Erlös</th></tr></thead><tbody>${sells}</tbody></table></div>` : '<span class="tr-hint">Nichts aus dem Laderaum wird hier verkauft.</span>'}`;
  let nextBlock = '';
  if (c.nextBuys.length) {
    nextBlock = `<div class="tr-cap">Kauft hier für ${esc(pname(c.nextBuys[0].to))}</div><div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th>Ware</th><th class="num">SCU</th><th>Verkauf an</th><th class="num">Gewinn</th></tr></thead><tbody>${c.nextBuys.map((b) => `<tr><td><span class="tr-cname">${esc(b.c)}</span></td><td class="num">${fmt(b.qty)}</td><td>${esc(pname(b.to))}</td><td class="num pos">+${fmt(b.gain)}</td></tr>`).join('')}</tbody></table></div>`;
  } else if (key === 'onward') {
    nextBlock = `<div class="tr-cap">Kauft hier für weiter <span class="tr-mark tr-mark--est">≈ Schätzung</span></div>${c.hop
      ? `<div class="hud-scroll scroll-x"><table class="tr-jobs"><thead><tr><th>Ware</th><th class="num">SCU</th><th>Ziel</th><th class="num">Gewinn</th><th class="num">Dauer</th></tr></thead><tbody><tr><td><span class="tr-cname">${esc(c.hop.c)}</span> <span class="tr-hint">à ${fmt(c.hop.pb)}</span></td><td class="num">${fmt(c.hop.qty)}</td><td>${esc(tname(c.hop.to))} <span class="tr-hint">à ${fmt(c.hop.ps)}${c.hop.unknown ? ' · Nachfrage unbekannt' : ''}</span></td><td class="num pos">+${fmt(c.hop.profit)}</td><td class="num">${fmtShort(c.hop.dur)}</td></tr></tbody></table></div><span class="tr-hint">Der beste einzelne Sprung von hier zu einem erlaubten Terminal, das nicht auf der Route liegt, mit vollem Laderaum und dem Bargeld nach diesem Stopp. Keine Zusage: der nächste Stopp wird erst beim nächsten Anhängen gewählt.</span>`
      : '<span class="tr-hint">Von hier lohnt kein einzelner Sprung zu einem erlaubten Terminal.</span>'}`;
  }
  let effect = '';
  if (prev) {
    if (holding) {
      const moved = c.moved.map((m) => `<li>${esc(m.c)}: ${fmt(m.qty)} SCU wandern von ${esc(pname(m.from))} hierher, ${signed(m.gain)} aUEC</li>`).join('');
      const added = c.added.map((a) => `<li>An ${esc(pname(prev))} kommt ein Kauf dazu: ${esc(a.c)} ${fmt(a.qty)} SCU für ${esc(pname(a.to))}</li>`).join('');
      effect = `<div class="tr-cap">Wirkung auf die bisherige Fracht · halten</div><ul class="tr-cand__list">${moved}${added}${moved || added ? '' : `<li>Nichts ändert sich vor ${esc(pname(prev))}; die Ladeaufträge bis zum vorletzten Stopp bleiben.</li>`}</ul>`;
    } else {
      effect = `<div class="tr-cap">Wirkung auf die bisherige Fracht · neu planen</div><ul class="tr-cand__list"><li>${c.changes ? `${c.changes} Ladeauftr${c.changes > 1 ? 'äge' : 'ag'} an früheren Stopps ${c.changes > 1 ? 'ändern' : 'ändert'} sich; die Änderungen zeigt die Zeitleiste nach dem Anhängen.` : 'Die früheren Ladeaufträge bleiben, wie sie sind.'}</li></ul>`;
    }
  }
  const marks = c.markers.length ? `<div class="tr-row">${c.markers.map((m) => `<span class="tr-mark tr-mark--${m.kind}">${esc(m.text)}</span>`).join(' ')}</div>` : '';
  return `<div class="tr-cand__more">${sellBlock}${nextBlock}${effect}${marks}</div>`;
}

/**
 * Renders one candidate line in the layout the prototype bar chose: figures first (A) or reasons first (B).
 * @param {object} k the edit context
 * @param {object} c the candidate
 * @param {number} rank the list position, 0 in the forced block
 * @param {{key: string, holding: boolean, prev: string, start: boolean}} o the list's key, hold mode, former last stop and start screen flag
 * @returns {string} markup
 */
function candLine(k, c, rank, o) {
  const term = TERMINALS[c.t];
  const pl = PLACES[term.place];
  const id = `${k.ctx}:${c.t}`;
  const open = state.bd.open.includes(id);
  const value = o.key === 'onward' ? c.onward : c.here;
  const keyFig = `<div class="tr-cand__fig tr-cand__fig--key"><span class="tr-cap-k">${o.key === 'onward' ? '≈ mit Weiterflug' : 'bis hier'}</span><strong>${o.key === 'onward' && c.hop ? '≈ ' : ''}${fmt(value)}</strong><small>${rankUnit()}</small></div>`;
  const toggle = `<button class="btn btn-ghost btn-xs btn-icon tr-cand__exp" data-act="bd-exp" data-val="${id}" aria-expanded="${open}" aria-label="${open ? 'Gründe zuklappen' : 'Gründe je Ware anzeigen'}" title="${open ? 'Gründe zuklappen' : 'Gründe je Ware anzeigen'}">${ic(open ? 'chevron-up' : 'chevron-down')}</button>`;
  const take = `<button class="btn btn-outline btn-xs" data-act="bd-take" data-ctx="${k.ctx}" data-t="${c.t}">${o.start ? 'Als 2. Stopp' : 'Anhängen'}</button>`;
  const marks = candMarks(c).trim();
  const name = `<div class="tr-cand__name"><span class="tr-cname">${esc(term.name)}</span>${marks ? `<div class="tr-cand__marks">${marks}</div>` : ''}<div class="tr-hint">${pl.kind} · ${pl.body} · ${pl.system}</div></div>`;
  const rk = `<span class="tr-cand__rank" aria-hidden="true">${rank || ic('map-pin')}</span>`;
  const cls = `tr-cand tr-cand--${state.bd.line} ${c.over ? 'is-over' : ''} ${rank ? '' : 'is-forced'} ${open ? 'is-open' : ''}`;
  if (state.bd.line === 'B') {
    const figs = `<div class="tr-cand__figline"><span>Δ ${signed(c.dProfit)}</span><span>Δ +${fmtDur(Math.max(0, c.dDur))}</span><span>${fmt(c.e.perHour)} aUEC/h</span><span>Gefahr ${rateMini('Gefahr', pl.danger, MIN_RATERS.danger)}</span><span>Bugs ${rateMini('Bugs', term.bugs, MIN_RATERS.bugs)}</span></div>`;
    return `<li class="${cls}"><div class="tr-cand__main">${rk}<div class="tr-cand__body">${name}${candWhyShort(c, o.key, o.holding)}${figs}</div>${keyFig}<div class="tr-cand__act">${take}${toggle}</div></div>${open ? candDetail(c, o.key, o.holding, o.prev) : ''}</li>`;
  }
  const fig = (label, val) => `<div class="tr-cand__fig"><span class="tr-cap-k">${label}</span>${val}</div>`;
  const hopLine = o.key === 'onward' ? `<small>${c.hop ? `Weiter ≈ ${signed(c.hop.profit)} → ${esc(pname(c.hop.to))}` : 'kein lohnender Weiterflug'}</small>` : '';
  const keyA = `<div class="tr-cand__fig tr-cand__fig--key"><span class="tr-cap-k">${o.key === 'onward' ? '≈ mit Weiterflug' : 'bis hier'}</span><span><strong>${o.key === 'onward' && c.hop ? '≈ ' : ''}${fmt(value)}</strong> <small>${rankUnit()}</small></span>${hopLine}</div>`;
  const keyIsRate = o.key === 'here' && state.request.objective !== 'maxProfit';
  const third = keyIsRate ? fig('Gewinn', fmt(c.e.profit)) : fig('aUEC/h', fmt(c.e.perHour));
  return `<li class="${cls}"><div class="tr-cand__main">${rk}${name}<div class="tr-cand__figs">${keyA}
    ${fig('Δ Gewinn', signed(c.dProfit))}${fig('Δ Dauer', `+${fmtDur(Math.max(0, c.dDur))}`)}${third}
    ${fig('Gefahr · Bugs', `<span>${rateMini('Gefahr', pl.danger, MIN_RATERS.danger)} · ${rateMini('Bugs', term.bugs, MIN_RATERS.bugs)}</span>`)}</div>
    <div class="tr-cand__act">${take}${toggle}</div></div>${open ? candDetail(c, o.key, o.holding, o.prev) : ''}</li>`;
}

/**
 * Renders a danger or bugs mean as a bare number, coloured by level, with the count in its title.
 * @param {string} label the scale name
 * @param {{m: number, n: number, d: number}} agg the aggregate
 * @param {number} min the minimum count of members
 * @returns {string} markup
 */
function rateMini(label, agg, min) {
  const few = agg.n < min;
  const cls = few ? '' : agg.m >= 3.5 ? 'tr-rate--high' : agg.m >= 2.5 ? 'tr-rate--mid' : '';
  return `<span class="tr-rate ${cls}" title="${label}: Mittel aus ${agg.n} Meldern${few ? ', wenige Meldungen' : ''}"><b>${dec(agg.m)}</b>${few ? '<sup>?</sup>' : ''}</span>`;
}

/**
 * Renders the objective's line in the append panel: the time limit, or the progress to the minimum profit under fastest.
 * @param {object} base the computed current route
 * @returns {string} markup
 */
function objectiveLine(base) {
  const r = state.request;
  if (r.objective === 'fastest') {
    const min = Number(r.minProfit) || 0;
    const got = Math.max(0, base.profit);
    const pct = min ? Math.min(100, Math.round((got / min) * 100)) : 100;
    const done = got >= min;
    return `<div class="tr-bd__goal ${done ? 'is-done' : ''}"><div class="tr-row tr-row--between"><span><strong>${done ? `${ic('check')} Mindestgewinn erreicht` : `Mindestgewinn: ${fmt(got)} von ${fmt(min)} aUEC`}</strong></span><span class="tr-hint">Am schnellsten · beim Bauen sortiert nach aUEC/h</span></div><span class="tr-status__bar"><i style="width:${pct}%"></i></span></div>`;
  }
  const limit = timeLimitSec();
  if (limit) return `<div class="tr-bd__goal"><div class="tr-row tr-row--between"><span><strong>Zeitlimit ${fmtDur(limit)}</strong> · bisher ${fmtDur(base.duration)}</span><span class="tr-hint">Höchster Gewinn · über dem Limit rückt ein Kandidat ans Ende; der Weiterflug zählt nur, wenn er noch passt</span></div><span class="tr-status__bar"><i style="width:${Math.min(100, Math.round((base.duration / limit) * 100))}%"></i></span></div>`;
  return `<span class="tr-hint">Sortiert nach ${r.objective === 'maxProfit' ? 'Gewinn' : 'Gewinn pro Stunde'}, wie in deiner Anfrage.</span>`;
}

/**
 * Renders the append panel: keys, hold switch, objective line, the forced block, ten candidates and the terminal search.
 * @param {object} k the edit context
 * @param {string} where dialog, inline or start
 * @returns {string} markup
 */
function buildPanel(k, where) {
  const start = k.w.stops.length === 1;
  const holdShown = k.ctx === 'plan' && appendPos(k.w) >= 2;
  const mode = holdShown ? state.bd.hold : 'replan';
  const res = buildCandidates(k, mode);
  const key = res.oneKey ? 'here' : state.bd.key;
  const list = key === 'onward' ? res.byOnward : res.byHere;
  const prevT = k.w.stops[res.pos - 1].t;
  const endT = res.pos < k.w.stops.length ? k.w.stops[k.w.stops.length - 1].t : '';
  const o = { key, holding: mode === 'hold' && holdShown, prev: start ? '' : prevT, start };
  const keys = res.oneKey
    ? `<div class="tr-hint">${ic('info')} ${endT
      ? `Ziel steht fest: der Wert jedes Kandidaten enthält schon die Etappe nach ${esc(pname(endT))}. Darum gibt es nur eine Sortierung.`
      : `Letzter Stopp vor der Routengrenze (${ROUTE_LIMIT}${k.ctx === 'flight' ? ', erledigte mitgezählt' : ''}): danach folgt kein Weiterflug mehr. Darum gibt es nur „Bis hier“.`}</div>`
    : `<div class="tr-field"><span class="tr-cap">Sortieren</span><div class="krt-segmented krt-segmented--sm" role="group" aria-label="Sortieren">${['onward', 'here'].map((x) => `<button type="button" class="krt-segmented__item" data-act="bd-key" data-val="${x}" aria-pressed="${key === x}">${KEY_LABEL[x]}</button>`).join('')}</div><span class="tr-hint">${key === 'onward' ? 'Wert bis hier plus der beste einzelne Sprung danach (Schätzung). Ein Stopp zählt auch mit dem, was er für die nächste Etappe bietet.' : 'Genauer Wert der Route mit diesem Terminal als letztem Stopp, Fracht geplant.'}</span></div>`;
  const hold = holdShown
    ? `<div class="tr-field"><span class="tr-cap">Bisherige Fracht</span><div class="krt-segmented krt-segmented--sm" role="group" aria-label="Bisherige Fracht">${[['hold', 'Halten'], ['replan', 'Neu planen']].map(([x, l]) => `<button type="button" class="krt-segmented__item" data-act="bd-hold" data-val="${x}" aria-pressed="${state.bd.hold === x}">${l}</button>`).join('')}</div><span class="tr-hint">${state.bd.hold === 'hold' ? `Die Ladeaufträge bis zum vorletzten Stopp bleiben; geplant werden nur ${esc(pname(prevT))} und der neue Stopp.` : 'Der ganze Frachtplan wird neu geplant; auch frühere Stopps können sich ändern.'}</span></div>`
    : k.ctx === 'flight' ? `<div class="tr-hint">${ic('info')} Im Flug ist der erledigte Teil fest: Ladung an Bord und Bargeld gehen so ein; Verkäufe an ${esc(pname(prevT))}, die noch nicht abgehakt sind, dürfen zum neuen Stopp wandern.</div>` : '';
  const now = start
    ? `<span class="tr-hint">Start an ${esc(tname(prevT))}. Eine Route hat mindestens zwei Terminals: wähle den zweiten Stopp.</span>`
    : `<span class="tr-hint">Jetzt: ${fmt(res.base.profit)} aUEC · ${fmtDur(res.base.duration)} · ${fmt(res.base.perHour)} aUEC/h · ${k.w.stops.length} Stopps</span>`;
  const convoy = res.base.convoy ? `<span class="tr-hint">Konvoi: der Weiterflug ist mit gemeinsamem Laderaum und Bargeld geschätzt; „Bis hier“ ist pro Schiff genau.</span>` : '';
  const forced = res.forced.length
    ? `<div class="tr-bd__block tr-bd__block--forced"><div class="tr-cap">${ic('map-pin')} Erzwungen · fehlt noch</div><ol class="tr-cands2">${res.forced.map((c) => candLine(k, c, 0, o)).join('')}</ol></div>`
    : '';
  const rows = list.map((c, i) => candLine(k, c, i + 1, o)).join('');
  const title = start ? `Zweiten Stopp wählen · ab ${esc(tname(prevT))}` : endT ? `Stopp anhängen vor dem Ziel ${esc(tname(endT))}` : `Stopp anhängen nach ${esc(tname(prevT))}`;
  const close = where === 'inline' ? `<button class="btn btn-ghost btn-xs" data-act="bd-inline" data-val="0">${ic('chevron-up')} Einklappen</button>` : '';
  return `<div class="tr-panel tr-bd tr-bd--${where}" role="group" aria-label="${title}">
    ${where === 'dialog' ? '' : `<div class="tr-row tr-row--between"><div class="tr-cap tr-bd__title">${title}</div>${close}</div>`}
    ${now}
    <div class="tr-bd__ctrl">${keys}${hold}</div>
    ${objectiveLine(res.base)}${convoy}
    ${forced}
    <div class="tr-bd__block"><div class="tr-cap">Die ${list.length} besten · ${KEY_LABEL[key]}</div>${rows ? `<ol class="tr-cands2">${rows}</ol>` : '<span class="tr-hint">Kein Terminal passt zu den Filtern deiner Anfrage.</span>'}</div>
    <div class="tr-field tr-picker"><label for="bd-search-${where}">Oder ein Terminal nennen</label><input id="bd-search-${where}" type="search" autocomplete="off" placeholder="Terminal oder Ort suchen …" data-bdsearch="${k.ctx}"><div class="tr-picker__list" hidden></div><span class="tr-hint">Ein genanntes Terminal gilt auch gegen einen Filter und wird markiert. Ein angehängter Stopp ist fest.</span></div>
  </div>`;
}

/**
 * Lists terminals matching a query for the append panel's search.
 * @param {string} q the query
 * @param {string} ctx plan or flight
 * @returns {string} option markup
 */
function bdSearchOptions(q, ctx) {
  const k = edWork(ctx);
  const on = k.w.stops.map((s) => s.t);
  const needle = q.trim().toLowerCase();
  return Object.entries(TERMINALS).filter(([, t]) => !needle || `${t.name} ${PLACES[t.place].name}`.toLowerCase().includes(needle)).slice(0, 12).map(([id, t]) => {
    if (on.includes(id)) return `<button type="button" class="tr-picker__opt" disabled><span>${esc(t.name)}</span><small>schon auf der Route</small></button>`;
    const why = failsFilter(id);
    return `<button type="button" class="tr-picker__opt" data-act="bd-take" data-ctx="${ctx}" data-t="${id}"><span>${esc(t.name)}</span><small>${PLACES[t.place].kind} · ${PLACES[t.place].system}${why ? ` · <span class="warn-text">verletzt Filter: ${esc(why)}</span>` : ''}</small></button>`;
  }).join('') || '<div class="tr-hint" style="padding:8px">Nichts gefunden.</div>';
}

/**
 * Renders the start screen of building: the start terminal and the candidates for the second stop.
 * @param {object} k the edit context
 * @returns {string} markup
 */
function buildStart(k) {
  const s = evaluate(k.w, k.w.ships).stops[0];
  const buys = Object.entries(PRICES[s.t]?.buy || {}).map(([c, [p, n]]) => `${esc(c)} <span class="tr-hint">à ${fmt(p)} · ${fmt(n)} SCU</span>`).join(' · ');
  return `<div class="tr-bdstart">
    <div class="tr-row tr-row--between"><div><div class="tr-stoptile__title">Eigene Route ab ${esc(s.place.name)}</div><div class="tr-hint">${esc(k.w.ships.length > 1 || k.w.ships[0].count > 1 ? `Konvoi · ${expandShips(k.w.ships).length} Schiffe` : k.w.ships[0].name)} · noch keine Route</div></div><div class="tr-actions"><button class="btn btn--cta" disabled title="Erst mit zwei Stopps">${ic('save')} Speichern</button><button class="btn btn-outline" disabled title="Erst mit zwei Stopps">${ic('arrow-right')} Losfliegen</button></div></div>
    <ol class="tr-timeline"><li class="tr-timeline__stop" data-n="1"><div class="tr-stoptile tr-stoptile--pinned">
      <div class="tr-stoptile__head"><div><div class="tr-stoptile__title">${esc(s.term.name)} <span class="tr-mark tr-mark--forced">Start</span></div><div class="tr-stoptile__sub">${s.place.kind} · ${s.place.body} · ${s.place.system}</div></div></div>
      ${stopFacts(s)}
      <div class="tr-hint">Kaufbar hier: ${buys || 'nichts'}</div>
    </div></li>
    <li class="tr-insert tr-insert--build">${buildPanel(k, 'start')}</li></ol>
  </div>`;
}

/**
 * Renders the warning about forced terminals of the working request not yet on a built route.
 * @param {object} k the edit context
 * @returns {string} markup, empty when none is missing
 */
function forcedMissing(k) {
  const on = k.w.stops.map((s) => s.t);
  const miss = state.request.forced.filter((t) => !on.includes(t) && !state.request.excluded.includes(t));
  if (!miss.length) return '';
  return `<div class="tr-warnline" role="status">${ic('warning')}<span><strong>${miss.length} erzwungene${miss.length > 1 ? '' : 's'} Terminal${miss.length > 1 ? 's' : ''} fehl${miss.length > 1 ? 'en' : 't'} noch:</strong> ${miss.map((t) => esc(tname(t))).join(', ')}. Sie führen jede Kandidatenliste an. Speichern, Teilen und Losfliegen gehen trotzdem.</span></div>`;
}

/**
 * Renders the append position of a timeline: the button that opens the panel, or the open panel on a built tab in layout B.
 * @param {object} k the edit context
 * @param {number} pos the append index
 * @returns {string} markup
 */
function appendRow(k, pos) {
  const full = k.w.stops.length >= ROUTE_LIMIT;
  const before = pos < k.w.stops.length;
  const label = before ? 'Stopp vor dem Ziel anhängen' : 'Stopp anhängen';
  if (!full && !k.busy && k.ctx === 'plan' && k.tab.built && state.bd.append === 'B' && state.bd.inlineOpen) return `<li class="tr-insert tr-insert--build">${buildPanel(k, 'inline')}</li>`;
  const btn = `<li class="tr-insert"><button class="btn ${k.tab && k.tab.built ? 'btn-outline' : 'btn-ghost'} btn-xs" data-act="bd-open" data-ctx="${k.ctx}" ${full || k.busy ? 'disabled' : ''} title="${full ? `Routengrenze: höchstens ${ROUTE_LIMIT} Stopps` : 'Kandidaten mit „Mit Weiterflug“ und „Bis hier“'}">${ic('plus')} ${label}</button></li>`;
  return btn + routeLimitHint(k);
}

/**
 * Names the building choices of the prototype bar for the page's prototype note.
 * @returns {string} one sentence
 */
function buildNote() {
  return `Stopp für Stopp: Anhängen ${state.bd.append === 'A' ? 'A (Dialog)' : 'B (offen am Ende der Zeitleiste)'}, Kandidatenzeile ${state.bd.line === 'A' ? 'A (Zahlen zuerst)' : 'B (Gründe zuerst)'}, Ziel ${state.request.end ? 'fest' : 'offen'}.`;
}
