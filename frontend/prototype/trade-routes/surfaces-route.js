/** PROTOTYPE opened routes: a saved, unit or shared route recomputed on current data, with its deviations. */

/**
 * Computes the opened route of a source against its snapshot.
 * @param {string} src saved, unit or shared
 * @returns {{o: object, old: object, now: object}} the source, the snapshot and the recomputation
 */
function openedRoute(src) {
  const o = OPEN_SOURCES[src];
  const ships = src === 'saved' && !state.hangarGone ? [{ ...SHIP_C2 }] : o.ships;
  const old = computeRoute(ROUTES.find((x) => x.id === o.old), ships);
  const now = computeRoute(ROUTES.find((x) => x.id === o.now), ships);
  now.compared = true;
  now.frozen = false;
  pageRoutes[now.id] = now;
  return { o, old, now };
}

/**
 * Collects the deviations of a recomputed route against its snapshot.
 * @param {object} now the recomputed route
 * @returns {{hard: Array<string>, price: Array<string>, info: Array<string>}} hard deviations, price changes from 10 % and notes
 */
function deviations(now) {
  const hard = [];
  const price = [];
  const info = [];
  const side = (j) => (j.side === 'buy' ? 'Kauf' : 'Verkauf');
  if (state.hangarGone && state.routeSrc === 'saved') hard.push('Schiff: Das Hangarschiff „Lastesel“ gibt es nicht mehr. Gerechnet mit dem Typ Crusader C2 Hercules.');
  now.stops.forEach((s) => {
    const at = `Stopp ${s.si + 1} · ${s.term.name}`;
    s.gone.forEach((g) => hard.push(`${at}: ${g.c} (${side(g)}) ${g.gone}`));
    s.jobs.forEach((j) => {
      if (j.dev && j.dev.reason) hard.push(`${at}: ${j.c} (${side(j)}) ${j.dev.reason}: ${fmt(j.qty)} statt ${fmt(j.dev.wasQty)} SCU`);
      if (j.age > 360) hard.push(`${at}: Preis für ${j.c} abgelaufen (${ageText(j.age)})`);
      else if (j.age > 168) hard.push(`${at}: Preis für ${j.c} veraltet (${ageText(j.age)})`);
      if (j.dev && j.dev.was && Math.abs((j.p - j.dev.was) / j.dev.was) >= 0.1) price.push(`${at}: ${j.c} (${side(j)}) ${fmt(j.dev.was)} → ${fmt(j.p)} aUEC/SCU, ${pctChange(j.p, j.dev.was)}`);
      if (j.neu) info.push(`${at}: ${j.c} (${side(j)}) neu im Plan`);
    });
  });
  return { hard, price, info };
}

/**
 * Renders profit, duration and aUEC/h of the snapshot beside the recomputation.
 * @param {object} old the snapshot
 * @param {object} now the recomputation
 * @param {string} caption what the snapshot is
 * @returns {string} markup
 */
function compareStrip(old, now, caption) {
  const tile = (k, o, n, show, up) => {
    const d = ((n - o) / o) * 100;
    const good = up ? d > 0 : d < 0;
    const cls = Math.abs(d) < 0.05 ? '' : good ? 'pos' : 'neg';
    return `<div class="tr-kpi"><div class="tr-kpi__k">${k}</div><div class="tr-kpi__v">${show(n)}</div><div class="tr-kpi__s">vorher ${show(o)} · <span class="${cls}">${show(n - o, true)} · ${pctChange(n, o)}</span></div></div>`;
  };
  const money = (v, signed) => `${signed && v > 0 ? '+' : signed && v < 0 ? '−' : ''}${fmt(Math.abs(v))}`;
  const time = (v, signed) => `${signed && v > 0 ? '+' : signed && v < 0 ? '−' : ''}${fmtDur(Math.abs(v))}`;
  return `<div class="tr-compare"><div class="tr-cap">${esc(caption)}</div><div class="tr-compare__tiles">
    ${tile('Gewinn', old.profit, now.profit, money, true)}
    ${tile('Dauer', old.duration, now.duration, time, false)}
    ${tile('Pro Stunde', old.perHour, now.perHour, money, true)}
  </div></div>`;
}

/**
 * Renders the deviation box: hard deviations, price changes from 10 % and notes.
 * @param {object} now the recomputed route
 * @returns {string} markup
 */
function devBox(now) {
  const d = deviations(now);
  if (!d.hard.length && !d.price.length && !d.info.length) return `<div class="tr-warnline tr-warnline--ok">${ic('check')}<span>Keine Abweichungen gegenüber der Momentaufnahme.</span></div>`;
  const block = (title, list, cls, icon) => (list.length ? `<div class="tr-devgroup"><div class="tr-cap">${title} (${list.length})</div>${list.map((t) => `<div class="tr-warnline ${cls}">${ic(icon)}<span>${esc(t)}</span></div>`).join('')}</div>` : '');
  return `<div class="tr-devbox">
    ${block('Harte Abweichungen', d.hard, 'tr-warnline--danger', 'warning')}
    ${block('Preise ab 10 % geändert', d.price, '', 'warning')}
    ${block('Hinweise', d.info, 'tr-warnline--info', 'info')}
    <div class="tr-hint">Kleinere Preisänderungen stehen nur als Zahl an der Frachtzeile.</div>
  </div>`;
}

/**
 * Whether the viewer may write back the opened unit route.
 * @returns {boolean} true for its contributor, officers and mission managers of its unit
 */
function mayEditUnitRoute() {
  return state.role === 'officer';
}

/**
 * Renders the actions of an opened route for its source, where it is shown and its edit state.
 * @param {string} src saved, unit or shared
 * @param {object} now the recomputed route
 * @param {boolean} inPlanner whether the route is the planner's working state
 * @returns {string} markup
 */
function openedActions(src, now, inPlanner) {
  const fly = (cls) => `<button class="btn ${cls}" data-act="fly" data-route="${now.id}">${ic('arrow-right')} Losfliegen</button>`;
  const mine = (cls) => `<button class="btn ${cls}" data-act="save-copy">${ic('save')} Als meine Route speichern</button>`;
  const myShip = '<button class="btn btn-ghost" data-act="my-ship">Mit meinem Schiff berechnen</button>';
  const toUnit = `<button class="btn btn-ghost" data-act="to-unit">${ic('users')} Für Einheit</button>`;
  const share = `<button class="btn btn-ghost" data-act="share" data-route="${now.id}">Teilen</button>`;
  const edit = `<button class="btn btn-ghost" data-act="edit-in-planner">${ic('edit')} Im Planer bearbeiten</button>`;
  let main = '';
  let more = '';
  if (src === 'saved') {
    main = state.edited
      ? `<button class="btn btn--cta" data-act="saved-update">${ic('save')} Gespeicherte Route aktualisieren</button><button class="btn btn-ghost" data-act="save-new">Als neue speichern</button>${fly('btn-outline')}`
      : `${fly('btn--cta')}${share}${toUnit}${inPlanner ? '' : edit}`;
    more = `<button class="btn btn-ghost btn-xs" data-act="research">${ic('search')} Mit diesen Einstellungen neu suchen</button><button class="btn btn-ghost btn-xs" data-act="pin-optimise">Mit festen Stopps optimieren</button>`;
  } else if (src === 'unit') {
    if (inPlanner && state.edited && mayEditUnitRoute()) main = `<button class="btn btn--cta" data-act="unit-update">${ic('save')} Einheitsroute aktualisieren</button><button class="btn btn-ghost" data-act="to-unit">Als neue Einheitsroute speichern</button>${mine('btn-ghost')}`;
    else if (inPlanner && state.edited) main = `<button class="btn btn--cta" data-act="to-unit">${ic('users')} Als neue Einheitsroute speichern</button>${mine('btn-ghost')}<span class="tr-hint">Zurückschreiben dürfen nur Vagabund (eingetragen), Offiziere und Einsatzleiter von ALPHA.</span>`;
    else main = `${fly('btn--cta')}${mine('btn-ghost')}${inPlanner ? '' : `${myShip}${edit}`}${share}`;
  } else {
    main = `${mine('btn--cta')}${fly('btn-outline')}${inPlanner ? '<span class="tr-hint">Eine geteilte Route bleibt eingefroren; eine geänderte Fassung teilst du neu.</span>' : `${edit}${myShip}`}${toUnit}`;
  }
  return `<div class="tr-actions">${main}</div>${more ? `<div class="tr-actions">${more}</div>` : ''}`;
}

/**
 * Renders the result side of an opened route: status, comparison, deviations, figures and timeline.
 * @param {string} src saved, unit or shared
 * @param {boolean} inPlanner whether the route is the planner's working state
 * @returns {string} markup
 */
function openedResult(src, inPlanner) {
  const { o, old, now } = openedRoute(src);
  const after = {
    saved: 'Die Momentaufnahme steht jetzt auf diesem Stand; das nächste Öffnen vergleicht hiermit.',
    unit: 'Nichts wird zurückgeschrieben. Die Momentaufnahme der Einheit ändert nur „Einheitsroute aktualisieren“.',
    shared: 'Eingefrorene Kopie: verglichen wird mit dem Stand beim Teilen, nichts wird zurückgeschrieben.',
  }[src];
  const edited = state.edited && inPlanner ? `<div class="tr-warnline tr-warnline--info">${ic('edit')}<span>Bearbeitet: Stopps geändert. Die ${{ saved: 'gespeicherte Route', unit: 'Einheitsroute', shared: 'geteilte Route' }[src]} bleibt, bis du ${src === 'shared' ? 'eine eigene speicherst' : 'sie aktualisierst'}.</span></div>` : '';
  return `<div class="tr-status" role="status">${ic('check')}<span>Fracht auf den ${src === 'shared' ? 'geteilten' : 'gespeicherten'} Stopps neu geplant · schnelle Spur · 0,8 s · Datenstand 14:00 Uhr</span><span class="muted">${after}</span></div>
    ${edited}
    ${compareStrip(old, now, `Vergleich mit der Momentaufnahme vom ${o.since}`)}
    ${devBox(now)}
    <div class="tr-a__routecard tr-routecard--open">
      <div class="tr-row tr-row--between"><div><div class="tr-stoptile__title">${esc(o.name)}</div><div class="tr-hint">${esc(now.name)}</div><div class="tr-hint">${esc(state.hangarGone && src === 'saved' ? 'Crusader C2 Hercules (Typ, statt Hangarschiff)' : (src === 'saved' ? SHIP_C2.name : 'Crusader C2 Hercules (Typ)'))}</div></div></div>
      ${openedActions(src, now, inPlanner)}
      ${kpis(now)}
      ${warnList(now)}
      ${shipShares(now)}
      ${stopTimeline(now, { edit: inPlanner, insert: inPlanner, mine: -1 })}
    </div>`;
}

/**
 * Renders the context column of a route page: what the route is, and its request read-only.
 * @param {string} src saved, unit or shared
 * @returns {string} markup
 */
function contextAside(src) {
  const o = OPEN_SOURCES[src];
  const rows = {
    saved: [['Notiz', `${esc(o.note)} <span class="tr-hint">(nur für dich)</span>`], ['Schiffe', esc(state.hangarGone ? 'Crusader C2 Hercules (Typ)' : SHIP_C2.name)], ['Zuletzt geöffnet', '12.11., 21:14']],
    unit: [['Einheit', `<span class="squadron-badge">${o.unit}</span>`], ['Eingetragen von', esc(o.by)], ['Notiz', esc(o.note)], ['Schiffe', 'Crusader C2 Hercules (Typ)'], ['Stand', '09.11., 18:02']],
    shared: [['Code', `<span class="data-value">${o.code}</span>`], ['Geteilt am', '14.11., 20:41'], ['Schiffe', 'Crusader C2 Hercules (Typ)'], ['Inhalt', 'Eingefrorene Kopie ohne Notiz und ohne Namen']],
  }[src];
  const summary = GROUPS.map(([k, label]) => `<dt>${label}</dt><dd>${esc(groupSummary(k))}</dd>`).join('');
  return `<aside class="tr-a__aside tr-ctx" aria-label="Route">
    <div class="tr-a__aside-head"><h2>${o.label}</h2>${src === 'unit' && mayEditUnitRoute() ? '<button class="btn btn-ghost btn-xs" data-act="rename" data-what="Einheitsroute">Umbenennen</button>' : src === 'saved' ? '<button class="btn btn-ghost btn-xs" data-act="rename" data-what="Gespeicherte Route">Umbenennen</button>' : ''}</div>
    <div class="tr-ctx__body">
      <dl class="kv-list">${rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('')}</dl>
      <div class="tr-cap">Anfrage <span class="tr-hint">${src === 'saved' ? 'gespeichert, Bezüge neu aufgelöst' : 'Schiffe als Typen, deine Stoppzeiten'}</span></div>
      <dl class="tr-ctx__req">${summary}</dl>
    </div>
    <div class="tr-a__aside-foot"><button class="btn btn-ghost" data-act="edit-in-planner">${ic('edit')} Im Planer bearbeiten</button></div>
  </aside>`;
}

/**
 * Renders the page a revoked or unknown share code opens.
 * @returns {string} markup
 */
function deadCodePage() {
  return `<div class="page-head"><div class="page-head__main"><a class="page-eyebrow" href="#" data-act="go" data-page="planner">${ic('arrow-left')}<span>Routenplaner</span></a><div class="page-title"><h1>Geteilte Route</h1></div></div></div>
    <div class="tr-dead hud-box"><div class="tr-dead__title">Signal verloren</div><p>Dieser Code wurde widerrufen oder ist unbekannt.</p>
      <div class="tr-field" style="max-width:320px;margin:0 auto"><label for="dead-code">Anderen Code öffnen</label><input id="dead-code" type="text" placeholder="z. B. K7Q2-M9XA-P3" maxlength="12"></div>
      <div class="tr-row" style="justify-content:center"><button class="btn btn--cta" data-act="open-code-go" data-input="dead-code">Öffnen</button><button class="btn btn-ghost" data-act="go" data-page="planner">Zum Routenplaner</button></div></div>`;
}

/**
 * Renders an opened route, in the planner or on a page of its own.
 * @returns {string} markup
 */
function routePage() {
  const src = state.routeSrc;
  if (src === 'dead') return deadCodePage();
  const o = OPEN_SOURCES[src];
  const inPlanner = (src === 'saved' && state.openMode === 'planner') || state.editInPlanner;
  const how = src === 'saved' ? (state.openMode === 'planner' ? 'Alternative A: eigene Route öffnet im Planer' : 'Alternative B: jede Route öffnet auf einer eigenen Routenseite') : (inPlanner ? 'im Planer bearbeitet' : 'Routenseite; bearbeitet wird im Planer');
  const note = `<p class="proto-note"><strong>PROTOTYP</strong> · Geöffnete ${o.label} · ${how}${inPlanner ? ' · ↑, ↓, Entfernen setzen den Zustand „bearbeitet“' : ''}.</p>`;
  if (inPlanner) {
    const back = src === 'saved' ? `<button class="btn btn-ghost btn-xs" data-act="go" data-page="routes" data-tab="saved" style="margin-left:auto">${ic('list')} Alle gespeicherten</button>` : `<button class="btn btn-ghost btn-xs" data-act="leave-planner" style="margin-left:auto">${ic('arrow-left')} Zur Routenseite</button>`;
    const ctx = `<div class="tr-ctxbar"><span class="chip chip--primary">${o.label}</span><strong>${esc(o.name)}</strong><span class="tr-hint">${esc(src === 'shared' ? `Code ${o.code}` : o.note)}</span>${back}</div>`;
    return `${pageHead()}${note}<div class="tr-a">${requestAside()}<section class="tr-a__result" aria-label="Ergebnis">${ctx}${openedResult(src, true)}</section></div>`;
  }
  const back = src === 'shared' ? `<a class="page-eyebrow" href="#" data-act="go" data-page="planner">${ic('arrow-left')}<span>Routenplaner</span></a>` : `<a class="page-eyebrow" href="#" data-act="go" data-page="routes" data-tab="${src}">${ic('arrow-left')}<span>Routen</span></a>`;
  const badge = src === 'unit' ? `<span class="squadron-badge">${o.unit}</span>` : '';
  const head = `<div class="page-head"><div class="page-head__main">${back}<div class="page-title"><h1>${esc(o.name)}</h1>${badge}<span class="chip chip--primary">${o.label}</span></div></div>
    <div class="page-actions"><button class="btn btn-ghost" data-act="open-code">Code öffnen</button></div></div>`;
  return `${head}${note}<div class="tr-a">${contextAside(src)}<section class="tr-a__result" aria-label="Route">${openedResult(src, false)}</section></div>`;
}
