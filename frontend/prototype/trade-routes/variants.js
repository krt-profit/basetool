/** PROTOTYPE layouts: A cockpit, B assistant and comparison, C result first. */

/**
 * Renders the page head shared by the variants.
 * @returns {string} markup
 */
function pageHead() {
  return `<div class="page-head"><div class="page-head__main"><span class="page-eyebrow">Handel</span><div class="page-title"><h1>Routenplaner</h1></div></div>
    <div class="page-actions"><button class="btn btn-ghost" data-act="go" data-page="routes" data-tab="saved">${ic('list')} Gespeicherte Routen <span class="count-badge count-badge--sm">${state.savedList.length}</span></button><button class="btn btn-ghost" data-act="open-code">Code öffnen</button></div></div>
    ${state.page === 'planner' ? `<p class="proto-note"><strong>PROTOTYP</strong> · Variante ${state.variant} · erfundene Daten, nichts wird gespeichert. Bearbeiten ist nur ein Einstieg; die übrigen Flächen wählst du unten links.</p>` : ''}`;
}

/**
 * Renders the request column of variant A.
 * @returns {string} markup
 */
function requestAside() {
  const groups = GROUPS.map(([k, label, f]) => `<details class="tr-a__group" data-group="${k}" ${state.aOpen.includes(k) ? 'open' : ''}><summary>${label}<span class="tr-sum">${esc(groupSummary(k))}</span>${ic('chevron-down')}</summary><div class="tr-a__group-body">${f()}</div></details>`).join('');
  return `<aside class="tr-a__aside ${state.aCollapsed ? 'is-collapsed' : ''}" aria-label="Anfrage">
    <div class="tr-a__aside-head"><h2>Anfrage</h2><div class="tr-iconbtns"><button class="btn btn-ghost btn-xs" data-act="reset">Zurücksetzen</button><button class="btn btn-ghost btn-xs" data-act="a-collapse" aria-expanded="${!state.aCollapsed}">${state.aCollapsed ? 'Aufklappen' : 'Einklappen'}</button></div></div>
    <div class="tr-a__summary-mobile">${esc(GROUPS.slice(0, 3).map(([k]) => groupSummary(k)).join(' · '))} · ${activeFilters(state.request).length} Filter</div>
    <div class="tr-a__groups">${groups}</div>
    <div class="tr-a__aside-foot"><button class="btn btn--cta" data-act="compute">${ic('search')} Route berechnen</button><button class="btn btn-ghost btn-xs" data-act="overheads">Meine Stoppzeiten</button></div>
  </aside>`;
}

/**
 * Renders variant A: request column on the left, alternatives as tabs and a vertical stop timeline.
 * @returns {string} markup
 */
function variantA() {
  const list = results();
  const sel = list[Math.min(state.sel, list.length - 1)];
  const aside = requestAside();
  const ms = state.missionSearch;
  const forMission = ms ? `<div class="tr-warnline tr-warnline--info" role="status">${ic('info')}<span>${ms.mode === 'edit' ? 'Bearbeiten' : 'Suche'} für die Einsatzroute „${esc(ms.name)}“ im Einsatz „${esc(MISSION.name)}“: Schiffsliste ist die Routenflotte, ${ms.mode === 'edit' ? 'Stoppzeiten und Konvoi-Aufschläge' : 'Filter und Ziel'} kommen aus der Route. Der gespeicherte Plan bleibt, bis du ${ms.mode === 'edit' ? 'die Einsatzroute aktualisierst' : 'ein Ergebnis übernimmst'}.</span></div>` : '';
  if (!sel) return `${pageHead()}<div class="tr-a">${aside}<section class="tr-a__result">${forMission}${statusBar()}<div class="empty-state">Die Suche läuft. Das erste Zwischenergebnis kommt nach dem 2-Stopp-Durchgang.</div></section></div>`;
  const tabs = list.map((r, i) => `<button class="tab ${r === sel ? 'active' : ''}" role="tab" aria-selected="${r === sel}" data-act="sel" data-val="${i}">${i === 0 ? 'Beste Route' : `Alternative ${i}`}<small>${fmt(r.perHour)} aUEC/h · ${fmtDur(r.duration)} · ${r.stops.length} Stopps</small></button>`).join('');
  const timeline = stopTimeline(sel);
  return `${pageHead()}<div class="tr-a">${aside}<section class="tr-a__result" aria-label="Ergebnis">
      ${forMission}${statusBar()}
      <div><div class="tab-nav tr-a__tabs" role="tablist">${tabs}</div>
      <div class="tr-a__routecard">
        <div class="tr-row tr-row--between"><div><div class="tr-stoptile__title">${esc(sel.name)}</div><div class="tr-hint">${sel.convoy ? `Konvoi · ${sel.ships.length} Schiffe` : esc(state.resultShips[0].name)}</div></div>${routeActions(sel)}</div>
        ${kpis(sel)}
        ${warnList(sel)}
        ${shipShares(sel)}
        ${timeline}
      </div></div>
    </section></div>`;
}

const B_STEPS = ['Schiffe', 'Route & Ziel', 'Filter', 'Prüfen'];

/**
 * Renders one assistant step of variant B.
 * @param {number} step the step index
 * @returns {string} markup
 */
function bStep(step) {
  const sec = (title, body) => `<div class="card"><h4 class="card-title">${title}</h4><div class="tr-stack">${body}</div></div>`;
  if (step === 0) return sec('Schiffe', `${fShips()}<button class="btn btn-ghost btn-xs" data-act="overheads" style="align-self:flex-start">Meine Stoppzeiten</button>`);
  if (step === 1) return `${sec('Route', fShape())}${sec('Ziel', fObjective())}`;
  if (step === 2) return `${sec('Orte', fPlaces())}${sec('Terminals', fTerminals())}${sec('Sprungpunkte', fJumps())}${sec('Waren', fGoods())}${sec('Daten & Bewertungen', fData())}`;
  const map = { ships: 0, shape: 1, objective: 1, places: 2, terminals: 2, jumps: 2, goods: 2, data: 2 };
  return sec('Prüfen', `<dl class="tr-b__review">${GROUPS.map(([k, label]) => `<dt>${label}</dt><dd>${esc(groupSummary(k))}</dd><dd><button class="btn btn-ghost btn-xs" data-act="b-step" data-val="${map[k]}">Ändern</button></dd>`).join('')}</dl>`);
}

/**
 * Renders a route as a compact card for comparison on phones and in variant C.
 * @param {object} r the computed route
 * @param {number} i its rank
 * @param {boolean} selected whether it is the chosen route
 * @returns {string} markup
 */
function routeCard(r, i, selected) {
  const o = state.request.objective;
  const flags = [r.legs.some((l) => l.detour) ? '<span class="tr-mark tr-mark--warn">Umleitung</span>' : '', r.estimated ? '<span class="tr-mark tr-mark--est">≈ geschätzt</span>' : '', r.stale ? '<span class="tr-mark tr-mark--warn">veraltete Preise</span>' : '', r.round ? '<span class="tr-mark tr-mark--muted">Rundreise</span>' : ''].join(' ');
  return `<button class="tr-c__card ${selected ? 'is-sel' : ''}" data-act="sel" data-val="${i}" aria-pressed="${selected}">
    <span class="tr-row tr-row--between"><span class="tr-cap">${i === 0 ? 'Beste Route' : `Alternative ${i}`}</span><span class="tr-hint">${r.stops.length} Stopps</span></span>
    <span class="tr-c__path">${r.stops.map((s) => esc(s.place.name)).join('<span class="sep">›</span>')}</span>
    <span class="tr-c__nums"><span class="${o === 'maxProfit' ? 'lead' : ''}">Gewinn<b>${fmt(r.profit)}</b></span><span class="${o === 'perHour' ? 'lead' : ''}">Pro h<b>${r.estimated ? '≈' : ''}${fmt(r.perHour)}</b></span><span class="${o === 'fastest' ? 'lead' : ''}">Dauer<b>${r.estimated ? '≈' : ''}${fmtDur(r.duration)}</b></span></span>
    <span class="tr-row">${flags}</span>
  </button>`;
}

/**
 * Renders variant B: a four-step assistant, then a comparison table and a horizontal route line.
 * @returns {string} markup
 */
function variantB() {
  const steps = `<div class="tr-b__steps" role="tablist">${B_STEPS.map((l, i) => `<button class="tr-b__step ${i === state.bStep ? 'is-current' : i < state.bStep ? 'is-done' : ''}" role="tab" aria-selected="${i === state.bStep}" data-act="b-step" data-val="${i}"><span>${i + 1}</span>${l}</button>`).join('')}</div>`;
  if (state.bView === 'form') {
    const last = state.bStep === B_STEPS.length - 1;
    return `${pageHead()}<div class="tr-b"><div class="tr-b__form">${steps}<div class="tr-stack">${bStep(state.bStep)}</div>
      <div class="tr-b__nav"><button class="btn btn-ghost" data-act="b-step" data-val="${state.bStep - 1}" ${state.bStep === 0 ? 'disabled' : ''}>${ic('arrow-left')} Zurück</button>
      <span class="tr-row">${state.results.length ? '<button class="btn btn-ghost" data-act="b-view" data-val="result">Zum letzten Ergebnis</button>' : ''}${last ? `<button class="btn btn--cta" data-act="compute">${ic('search')} Route berechnen</button>` : `<button class="btn btn--cta" data-act="b-step" data-val="${state.bStep + 1}">Weiter ${ic('arrow-right')}</button>`}</span></div></div></div>`;
  }
  const list = results();
  const sel = list[Math.min(state.sel, list.length - 1)];
  const head = `<div class="card tr-row tr-row--between" style="margin-bottom:12px"><div><div class="tr-cap">Anfrage</div><div class="tr-hint">${esc(GROUPS.slice(0, 3).map(([k]) => groupSummary(k)).join(' · '))} · ${activeFilters(state.request).length} Filter</div></div><button class="btn btn-ghost" data-act="b-view" data-val="form">${ic('edit')} Anfrage ändern</button></div>`;
  if (!sel) return `${pageHead()}<div class="tr-b">${head}${statusBar()}</div>`;
  const best = (f, dir) => list.reduce((a, b) => (dir * f(b) > dir * f(a) ? b : a));
  const row = (label, f, show, dir) => {
    const b = dir ? best(f, dir) : null;
    return `<tr><td>${label}</td>${list.map((r) => `<td class="${r === sel ? 'is-sel' : ''} ${r === b ? 'tr-best' : ''}">${show(r)}</td>`).join('')}</tr>`;
  };
  const table = `<div class="tr-b__compare-wrap card card--flush hud-scroll scroll-x"><table class="tr-b__compare"><thead><tr><th></th>${list.map((r, i) => `<th class="${r === sel ? 'is-sel' : ''}"><div class="tr-cap" style="color:var(--color-white)">${i === 0 ? 'Beste Route' : `Alternative ${i}`}</div><div class="tr-hint" style="font-weight:400;text-transform:none">${esc(r.name)}</div><button class="btn ${r === sel ? 'btn-outline' : 'btn-ghost'} btn-xs" data-act="sel" data-val="${i}" style="margin-top:6px">${r === sel ? 'Ausgewählt' : 'Anzeigen'}</button></th>`).join('')}</tr></thead><tbody>
    ${row('Gewinn', (r) => r.profit, (r) => fmt(r.profit), 1)}
    ${row('Pro Stunde', (r) => r.perHour, (r) => `${r.estimated ? '≈ ' : ''}${fmt(r.perHour)}`, 1)}
    ${row('Dauer', (r) => r.duration, (r) => `${r.estimated ? '≈ ' : ''}${fmtDur(r.duration)}`, -1)}
    ${row('Stopps', (r) => r.stops.length, (r) => `${r.stops.length}${r.round ? ' (Rundreise)' : ''}`, 0)}
    ${row('Kapital nötig', (r) => r.capital, (r) => fmt(r.capital), -1)}
    ${row('Bestand reicht', (r) => r.runs, (r) => `~${r.runs}×`, 1)}
    ${row('Datenstand', (r) => r.oldest, (r) => `${ageText(r.oldest)}${r.stale ? ' ⚠' : ''}`, -1)}
    ${row('Höchste Gefahr', (r) => Math.max(...r.stops.map((s) => s.place.danger.m)), (r) => dec(Math.max(...r.stops.map((s) => s.place.danger.m))), -1)}
    ${row('Umleitungen', (r) => r.legs.filter((l) => l.detour).length, (r) => r.legs.filter((l) => l.detour).length || '—', 0)}
    ${row('Hinweise', (r) => r.warnings.length, (r) => r.warnings.length, 0)}
  </tbody></table></div><div class="tr-b__cards">${list.map((r, i) => routeCard(r, i, r === sel)).join('')}</div>`;
  const stopIdx = Math.min(state.bStop, sel.stops.length - 1);
  const line = sel.stops.map((s, i) => {
    const leg = sel.legs[i];
    const legHtml = leg ? `<div class="tr-line__leg ${leg.detour ? 'tr-line__leg--detour' : ''}"><div class="tr-line__rail"></div><div>${leg.est.length ? '≈ ' : ''}${fmtShort(leg.dur)} · ${dec(leg.gm)} Gm</div>
      <div class="tr-hold" title="Laderaum auf der Etappe">${holdBars(sel, i)}</div><div class="tr-hint">${fmt(leg.aboard)} / ${fmt(leg.hold)} SCU</div>${leg.detour ? `<div class="warn-text">⚠ ${esc(leg.detour.text)}</div>` : ''}${leg.gateways ? `<div class="tr-hint">${leg.crossings} Sprung · ${esc(leg.gateways[0])} …</div>` : ''}</div>` : '';
    return `<button class="tr-line__stop ${i === stopIdx ? 'is-sel' : ''}" data-act="b-stop" data-val="${i}"><span class="tr-line__dot">${i + 1}</span><span><span class="tr-line__name">${esc(s.place.name)}</span><br><span class="tr-line__sys">${s.place.kind} · ${s.place.system}</span></span></button>${legHtml}`;
  }).join('');
  const s = sel.stops[stopIdx];
  const detail = `<div class="tr-b__detail"><div class="card"><div class="tr-stoptile__head"><div><div class="tr-stoptile__title">Stopp ${stopIdx + 1}: ${esc(s.term.name)}</div><div class="tr-stoptile__sub">${s.place.kind} · ${s.place.body} · ${s.place.system}</div></div>${stopEdit(s, sel)}</div><div class="tr-stack" style="margin-top:10px">${stopFacts(s)}${jobsTable(s, sel)}${shipLists(s, sel)}${stopTime(s)}<button class="btn btn-ghost btn-xs" data-act="edit" data-what="Stopp einfügen" style="align-self:flex-start">${ic('plus')} Stopp danach einfügen</button></div></div>
    <div class="tr-stack tr-b__side">${routeActions(sel).replace('tr-actions', 'tr-actions tr-stack')}${warnList(sel)}</div></div>`;
  return `${pageHead()}<div class="tr-b">${head}<div class="tr-stack">${statusBar()}${table}
    <div class="card"><h4 class="card-title">Route im Verlauf · Laderaum je Etappe</h4><div class="tr-line"><div class="tr-line__track">${line}</div></div></div>
    ${kpis(sel)}${shipShares(sel)}${detail}</div></div>`;
}

/**
 * Renders the cargo aboard a leg as stacked bars per commodity.
 * @param {object} r the computed route
 * @param {number} legIdx the leg
 * @returns {string} markup
 */
function holdBars(r, legIdx) {
  const aboard = {};
  r.stops.slice(0, legIdx + 1).forEach((s) => s.jobs.forEach((j) => { aboard[j.c] = (aboard[j.c] || 0) + (j.side === 'buy' ? j.qty : -j.qty); }));
  const hold = r.legs[legIdx].hold;
  return Object.entries(aboard).filter(([, q]) => q > 0).map(([c, q]) => `<i style="width:${(q / hold) * 100}%" title="${esc(c)}: ${fmt(q)} SCU"></i>`).join('');
}

/**
 * Renders variant C: a compact request bar with a filter drawer, route cards and a cargo-plan matrix.
 * @returns {string} markup
 */
function variantC() {
  const r = state.request;
  const filters = activeFilters(r);
  const bar = `<div class="tr-c__bar" role="search">
    <div class="tr-field"><span class="tr-cap">Schiffe</span><button class="btn btn-ghost tr-c__shipbtn" data-act="drawer" data-val="ships">${esc(groupSummary('ships'))}</button></div>
    <div class="tr-field" style="min-width:auto"><span class="tr-cap">Stopps bis</span><div class="krt-segmented" role="group">${[2, 3, 4, 5].map((n) => `<button type="button" class="krt-segmented__item" data-act="set" data-key="maxStops" data-val="${n}" aria-pressed="${r.maxStops === n}">${n}</button>`).join('')}</div></div>
    <div class="tr-field" style="min-width:auto;justify-content:flex-end">${sw('roundTrip', 'Rundreise')}</div>
    <div class="tr-field"><label for="c-obj">Ziel</label><select id="c-obj" data-bind="objective"><option value="perHour" ${r.objective === 'perHour' ? 'selected' : ''}>Gewinn pro Stunde</option><option value="maxProfit" ${r.objective === 'maxProfit' ? 'selected' : ''}>Höchster Gewinn</option><option value="fastest" ${r.objective === 'fastest' ? 'selected' : ''}>Am schnellsten</option></select></div>
    <div class="tr-field" style="min-width:auto"><span class="tr-cap">&nbsp;</span><button class="btn btn-ghost ${filters.length ? 'is-filtered' : ''}" data-act="drawer" data-val="all">${ic('filter')} Filter${filters.length ? ` <span class="filter-count">${filters.length}</span>` : ''}</button></div>
    <div class="tr-field" style="min-width:auto;margin-left:auto"><span class="tr-cap">&nbsp;</span><button class="btn btn--cta" data-act="compute">${ic('search')} Berechnen</button></div>
  </div>
  <div class="tr-c__chips">${filters.map((f) => `<span class="filter-chip">${esc(f.label)}<button class="filter-chip__remove" data-act="chip-remove" data-key="${f.key}" data-val="${esc(f.val)}" aria-label="Filter entfernen">×</button></span>`).join('')}</div>`;
  const list = results();
  const sel = list[Math.min(state.sel, list.length - 1)];
  if (!sel) return `${pageHead()}${bar}${statusBar()}`;
  const commodities = [...new Set(sel.stops.flatMap((s) => s.jobs.map((j) => j.c)))];
  const head = sel.stops.map((s, i) => `<th><div class="tr-row tr-row--between" style="flex-wrap:nowrap"><span>${i + 1} · ${esc(s.place.name)}</span>${stopEdit(s, sel)}</div><small>${esc(s.term.name)} · ${s.place.kind} · ${s.place.system}</small><div class="tr-stoptile__meta" style="margin-top:6px">${rateChip('Gefahr', s.place.danger, MIN_RATERS.danger)}${rateChip('Bugs', s.term.bugs, MIN_RATERS.bugs)}</div><div class="tr-row" style="margin-top:6px"><span class="tr-sym">${esc(s.mode)}</span>${s.forced ? '<span class="tr-mark tr-mark--forced">erzwungen</span>' : ''}</div></th>`).join('');
  const body = commodities.map((c) => {
    let aboard = 0;
    const cells = sel.stops.map((s) => {
      const js = s.jobs.filter((j) => j.c === c);
      if (!js.length) return `<td>${aboard > 0 ? `<span class="cell-hold">bleibt an Bord · ${fmt(aboard)}</span>` : ''}</td>`;
      return `<td>${js.map((j) => {
        aboard += j.side === 'buy' ? j.qty : -j.qty;
        const sold = state.soldOut[j.key];
        const marks = [j.side === 'sell' && j.demand === null ? '<span class="tr-mark tr-mark--est">Nachfrage ?</span>' : '', j.age > 168 ? '<span class="tr-mark tr-mark--warn">veraltet</span>' : '', sold ? '<span class="tr-mark tr-mark--danger">ausverkauft</span>' : ''].join(' ');
        return `<div class="${j.side === 'buy' ? 'cell-buy' : 'cell-sell'}">${j.side === 'buy' ? '+' : '−'}${fmt(j.qty)} SCU</div><small>${j.side === 'buy' ? 'Kauf' : 'Verkauf'} à ${fmt(j.p)} · ${ageText(j.age)}</small>${sel.convoy ? `<small>${j.shares.map((q, i) => `${esc(sel.ships[i].short)} ${fmt(q)}`).join(' · ')}</small>` : ''}<div>${availChip(j)}</div>${marks ? `<div>${marks}</div>` : ''}${j.side === 'buy' ? `<button class="btn btn-ghost btn-xs" style="margin-top:4px" data-act="soldout" data-key="${j.key}" data-route="${sel.id}" data-stop="${s.si}">${sold ? 'Aufheben' : 'Ausverkauft'}</button>` : ''}`;
      }).join('')}</td>`;
    }).join('');
    const info = COMMODITIES.find((x) => x.name === c) || {};
    return `<tr><td><span class="tr-cname">${esc(c)}</span>${info.warn ? `<small class="warn-text">⚠ ${esc(info.warn)}</small>` : ''}<small>${info.cat || ''}</small></td>${cells}</tr>`;
  }).join('');
  const foot = `<tr><td>Stoppzeit</td>${sel.stops.map((s) => `<td>${fmtShort(s.dur)}${s.fee ? `<small>Gebühr ${fmt(s.fee)} aUEC</small>` : ''}${s.surcharge ? `<small>inkl. Konvoi-Aufschlag</small>` : ''}</td>`).join('')}</tr>`;
  const legs = sel.legs.map((l, i) => `<div class="tr-c__leg ${l.detour ? 'tr-c__leg--detour' : ''}"><div class="tr-cap">Etappe ${i + 1}: ${esc(sel.stops[i].place.name)} → ${esc(sel.stops[i + 1].place.name)}</div>${legFacts(l, false)}</div>`).join('');
  return `${pageHead()}${bar}<div class="tr-stack">${statusBar()}
    <div class="tr-c__cards">${list.map((x, i) => routeCard(x, i, x === sel)).join('')}</div>
    <div class="tr-row tr-row--between"><div><div class="tr-stoptile__title">${esc(sel.name)}</div><div class="tr-hint">Kapital nötig ${fmt(sel.capital)} aUEC · Bestand reicht ~${sel.runs}× · Datenstand ${ageText(sel.oldest)}${sel.convoy ? ` · Konvoi ${sel.ships.length} Schiffe` : ''}</div></div>${routeActions(sel)}</div>
    ${warnList(sel)}
    <div><div class="tr-cap" style="margin-bottom:6px">Frachtplan</div><div class="tr-matrix-wrap"><table class="tr-matrix"><thead><tr><th>Ware</th>${head}</tr></thead><tbody>${body}</tbody><tfoot>${foot}</tfoot></table></div>
    <div class="tr-row" style="margin-top:8px"><button class="btn btn-ghost btn-xs" data-act="edit" data-what="Stopp einfügen">${ic('plus')} Stopp einfügen</button><button class="btn btn-ghost btn-xs" data-act="edit" data-what="Stopps umordnen">${ic('swap')} Stopps umordnen</button></div></div>
    ${shipShares(sel)}
    <div class="tr-c__legs">${legs}</div>
  </div>
  ${state.drawer ? drawer() : ''}`;
}

/**
 * Renders the filter drawer of variant C.
 * @returns {string} markup
 */
function drawer() {
  const only = state.drawer === 'ships' ? GROUPS.filter(([k]) => k === 'ships') : GROUPS;
  return `<div class="tr-drawer-scrim" data-act="drawer-close"></div><aside class="tr-drawer" role="dialog" aria-modal="true" aria-label="Filter"><div class="tr-drawer__head"><h2>${state.drawer === 'ships' ? 'Schiffe' : 'Anfrage & Filter'}</h2><button class="btn btn-ghost btn-xs btn-icon" data-act="drawer-close" aria-label="Schließen">${ic('close')}</button></div>
    <div class="tr-drawer__body">${only.map(([, label, f]) => `<section><h3>${label}</h3><div class="tr-stack">${f()}</div></section>`).join('')}${state.drawer === 'ships' ? '' : '<button class="btn btn-ghost btn-xs" data-act="overheads" style="align-self:flex-start">Meine Stoppzeiten</button>'}</div>
    <div class="tr-drawer__foot"><button class="btn btn-ghost" data-act="reset">${ic('filter-off')} Zurücksetzen</button><button class="btn btn--cta" data-act="compute">${ic('search')} Übernehmen und berechnen</button></div></aside>`;
}
