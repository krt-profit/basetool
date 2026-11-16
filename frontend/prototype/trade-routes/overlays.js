/** PROTOTYPE overlays: the flight view, the dialogs, the toast and the prototype bar. */

/**
 * Renders the flight view for following a route stop by stop.
 * @returns {string} markup
 */
function flightView() {
  const f = state.flight;
  const r = results().find((x) => x.id === f.route);
  if (!r) return '';
  const stop = r.stops[f.idx];
  const qty = (j) => (r.convoy && f.ship >= 0 ? j.shares[f.ship] : j.qty);
  const shipSel = r.convoy ? `<div class="tr-field"><span class="tr-cap">Ansicht für</span><div class="tr-fly__shipsel">${[-1, ...r.ships.map((_, i) => i)].map((i) => `<button class="tr-toggle" data-act="fly-ship" data-val="${i}" aria-pressed="${f.ship === i}">${i < 0 ? 'Ganzer Konvoi' : esc(r.ships[i].label)}</button>`).join('')}</div></div>` : '';
  let main = '';
  let foot = '';
  if (f.phase === 'stop') {
    const jobs = [...stop.jobs].sort((a, b) => (a.side === b.side ? 0 : a.side === 'sell' ? -1 : 1)).filter((j) => qty(j) > 0);
    const open = jobs.filter((j) => !f.done[j.key]).length;
    main = `<div class="tr-fly__next"><div class="tr-fly__eyebrow">Stopp ${f.idx + 1} von ${r.stops.length} · ${stop.place.kind} · ${stop.place.system}</div>
      <div class="tr-fly__big">${esc(stop.term.name)}</div>
      ${stopFacts(stop)}
      <div class="tr-stack" style="gap:8px">${jobs.map((j) => {
        const sold = state.soldOut[j.key];
        return `<div class="tr-fly__job ${f.done[j.key] ? 'is-done' : ''} ${sold ? 'is-soldout' : ''}"><label><input type="checkbox" data-act="fly-job" data-key="${j.key}" ${f.done[j.key] ? 'checked' : ''}><span><span class="tr-side tr-side--${j.side}" style="font-weight:900;font-size:.7rem;letter-spacing:.08em;text-transform:uppercase;color:${j.side === 'buy' ? 'var(--color-danger-text)' : 'var(--color-success-text)'}">${j.side === 'buy' ? 'Kaufen' : 'Verkaufen'}</span><br><strong style="color:var(--color-white)">${fmt(qty(j))} SCU ${esc(j.c)}</strong> <span class="tr-hint">à ${fmt(j.p)} aUEC${j.side === 'sell' && j.demand === null ? ' · Nachfrage unbekannt' : ''}</span></span></label>${j.side === 'buy' ? `<button class="btn ${sold ? 'btn-outline' : 'btn-ghost'} btn-xs" data-act="soldout" data-key="${j.key}" data-route="${r.id}" data-stop="${stop.si}">${sold ? 'Alternativen' : 'Ausverkauft'}</button>` : ''}</div>`;
      }).join('')}</div>
      ${stopTime(stop)}
      <button class="btn btn-ghost btn-xs" data-act="rate" data-route="${r.id}" data-stop="${stop.si}" style="align-self:flex-start">Stopp bewerten</button></div>`;
    const last = f.idx === r.stops.length - 1;
    foot = `<button class="btn btn--cta" data-act="fly-next">${ic('check')} ${last ? 'Route abschließen' : 'Stopp erledigt'}${open ? ` (${open} offen)` : ''}</button>`;
  } else if (f.phase === 'leg') {
    const leg = r.legs[f.idx];
    const next = r.stops[f.idx + 1];
    main = `<div class="tr-fly__next"><div class="tr-fly__eyebrow">Etappe ${f.idx + 1} von ${r.legs.length}</div>
      <div class="tr-fly__big">Fliege zu ${esc(next.term.name)}</div>
      <div><strong>${leg.est.length ? '≈ ' : ''}${fmtShort(leg.dur)}</strong> · ${dec(leg.gm)} Gm · an Bord ${fmt(leg.aboard)} SCU${leg.est.length ? ` <span class="tr-mark tr-mark--est">≈ geschätzt</span>` : ''}</div>
      ${leg.detour ? `<div class="tr-fly__detour">⚠ ${esc(leg.detour.text)}<div class="tr-hint" style="color:inherit;font-weight:400">Die Navigation im Spiel plottet den kürzeren Weg. +${fmtDur(leg.detour.extra)}</div></div>` : ''}
      ${leg.gateways ? `<ol class="ablauf">${[...leg.gateways, next.place.name].map((g) => `<li class="step"><span class="step-num"></span><span class="step-body"><span class="step-title">${esc(g)}</span></span></li>`).join('')}</ol>` : ''}
    </div>`;
    foot = `<button class="btn btn-ghost" data-act="fly-back">${ic('arrow-left')} Zurück</button><button class="btn btn--cta" data-act="fly-next">${ic('map-pin')} Angekommen</button>`;
  } else {
    main = `<div class="tr-fly__next"><div class="tr-fly__eyebrow">Fertig</div><div class="tr-fly__big">Route abgeschlossen</div><div>Gewinn laut Plan <strong class="pos">+${fmt(r.profit)} aUEC</strong> in ${fmtDur(r.duration)}.</div><span class="tr-hint">Stimmte etwas nicht? Bewerte die Stopps, damit die nächste Crew es weiß.</span></div>`;
    foot = `<button class="btn btn-ghost" data-act="fly-restart">Nochmal fliegen</button><button class="btn btn--cta" data-act="fly-close">Schließen</button>`;
  }
  const later = f.phase === 'done' ? '' : `<div class="card"><h4 class="card-title">Danach</h4>${r.stops.slice(f.idx + 1).map((s) => `<div class="tr-row tr-row--between" style="padding:6px 0;border-top:1px solid var(--color-gray-3)"><span>${s.si + 1} · ${esc(s.term.name)}</span><span class="tr-hint">${s.jobs.map((j) => `${j.side === 'buy' ? 'Kauf' : 'Verkauf'} ${esc(j.c)}`).join(', ')}</span></div>`).join('') || '<span class="tr-hint">Letzter Stopp.</span>'}</div>`;
  return `<div class="tr-fly" role="dialog" aria-modal="true" aria-label="Flugansicht"><div class="tr-fly__inner">
    <div class="tr-fly__head"><div><div class="tr-fly__eyebrow">Flugansicht</div><h1>${esc(r.name)}</h1></div><button class="btn btn-ghost" data-act="fly-close">${ic('close')} Schließen</button></div>
    <div class="tr-fly__progress" aria-hidden="true">${r.stops.map((_, i) => `<i class="${f.phase === 'done' || i < f.idx ? 'is-done' : i === f.idx ? 'is-now' : ''}"></i>`).join('')}</div>
    <p class="proto-note"><strong>Offen:</strong> Der Fortschritt soll am Server liegen und auf allen Geräten gleich sein; wie, entscheidet ein eigenes Ticket. Im Prototyp lebt er nur im Speicher.</p>
    ${shipSel}${main}${later}</div><div class="tr-fly__foot">${foot}</div></div>`;
}

/**
 * Renders the open dialog.
 * @returns {string} markup
 */
function modal() {
  const m = state.modal;
  if (!m) return '';
  const shell = (title, body, foot, wide) => `<div class="tr-modal-overlay" data-act="modal-scrim"><div class="krt-modal ${wide ? 'krt-modal--wide' : ''}" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="krt-modal-head"><h2>${title}</h2><button class="btn btn-ghost btn-xs btn-icon" data-act="modal-close" aria-label="Schließen">${ic('close')}</button></div><div class="krt-modal-body">${body}</div><div class="krt-modal-foot">${foot}</div></div></div>`;
  const cancel = '<button class="btn btn-ghost" data-act="modal-close">Abbrechen</button>';
  const r = m.route ? results().find((x) => x.id === m.route) : null;
  if (m.kind === 'save') {
    return shell('Route speichern', `<div class="tr-field"><label for="m-name">Name</label><input id="m-name" type="text" value="${esc(r.name)}" maxlength="80"></div><div class="tr-field"><label for="m-note">Notiz <span class="tr-hint">nur für dich, wird nicht geteilt</span></label><textarea id="m-note" rows="3"></textarea></div><span class="tr-hint">Gespeichert werden Anfrage und Ergebnis. Beim Öffnen wird die Fracht auf den gespeicherten Stopps neu geplant und Abweichungen markiert. 12 von 50 Plätzen belegt.</span>`, `${cancel}<button class="btn btn--cta" data-act="save-confirm" data-route="${r.id}">${ic('save')} Speichern</button>`);
  }
  if (m.kind === 'share') {
    return shell('Route teilen', `<span class="tr-hint">Geteilt wird eine eingefrorene Kopie ohne Notiz und ohne deinen Namen; Hangarschiffe werden zu Schiffstypen. Jedes angemeldete Mitglied öffnet sie mit Code oder Link.</span><div class="tr-code">K7Q2-M9XA-P3</div><div class="tr-field"><label for="m-link">Link</label><input id="m-link" type="text" readonly value="…/handel/routen/geteilt/K7Q2M9XAP3"></div><button class="btn btn-quiet-danger btn-xs" data-act="share-revoke" style="align-self:flex-start">${ic('trash')} Teilen widerrufen</button>`, `<button class="btn btn-ghost" data-act="copy" data-val="Link">Link kopieren</button><button class="btn btn--cta" data-act="copy" data-val="Code">Code kopieren</button>`);
  }
  if (m.kind === 'rate') {
    const s = r.stops[m.stop];
    const scale = (key, labels) => `<div class="tr-scale" role="group">${labels.map((l, i) => `<button type="button" class="tr-toggle" data-act="rate-val" data-key="${key}" data-val="${i + 1}" aria-pressed="${m.vals[key] === i + 1}">${i + 1} · ${l}</button>`).join('')}</div>`;
    const avail = s.jobs.map((j) => `<div class="tr-field"><span class="tr-cap">${esc(j.c)} · ${j.side === 'buy' ? 'Bestand' : 'Nachfrage'}</span><div class="tr-scale" role="group">${['wie angezeigt oder mehr', 'weniger', 'nichts da'].map((l, i) => `<button type="button" class="tr-toggle" data-act="rate-val" data-key="avail:${j.key}" data-val="${i + 1}" aria-pressed="${m.vals[`avail:${j.key}`] === i + 1}">${l}</button>`).join('')}</div></div>`).join('');
    return shell(`Stopp bewerten`, `<div class="tr-hint">${esc(s.term.name)} · Meldungen sind anonym und zählen organisationsweit; sichtbar sind nur Durchschnitte. Eine zweite Meldung am selben Tag ersetzt die erste.</div>
      <div class="tr-field"><span class="tr-cap">Gefahr am Ort ${esc(s.place.name)}</span>${scale('danger', ['sicher', 'gering', 'mittel', 'hoch', 'sehr hoch'])}</div>
      <div class="tr-field"><span class="tr-cap">Bugs am Terminal</span>${scale('bugs', ['läuft', 'selten', 'häufig', 'meistens', 'unbrauchbar'])}</div>
      <div class="tr-cap">Verfügbarkeit, wie du sie vorgefunden hast</div>${avail}`, `${cancel}<button class="btn btn--cta" data-act="rate-send">Melden</button>`, true);
  }
  if (m.kind === 'soldout') {
    return shell('Als ausverkauft markiert', `<div>${esc(m.c)} bei ${esc(m.term)} ist für <strong>deine</strong> Route als ausverkauft markiert. Die Markierung wird nicht geteilt.</div>
      <div class="card card--inset"><div class="tr-cap">Verfügbarkeit melden?</div><div class="tr-hint" style="margin:4px 0 8px">Vorbelegt mit „nichts da“. Anonym, hilft der nächsten Crew.</div><button class="btn btn-ghost btn-xs" data-act="soldout-report">Melden: nichts da</button></div>
      <button class="btn btn-outline" data-act="edit" data-what="Alternative Waren an diesem Stopp">Alternative Waren anzeigen</button>`, '<button class="btn btn-ghost" data-act="modal-close">Schließen</button>');
  }
  if (m.kind === 'overheads') {
    return shell('Meine Stoppzeiten', `<span class="tr-hint">Gelten für alle deine Berechnungen, auch über Clients. Am Server gespeichert.</span><table class="tr-jobs"><thead><tr><th>Ortsart</th><th class="num">Grundzeit (min)</th><th class="num">Konvoi-Aufschlag je Schiff (min)</th></tr></thead><tbody>${KINDS.map((k) => `<tr><td>${k}</td><td class="num"><input type="number" min="0" value="${OVERHEADS[k]}" style="width:5rem"></td><td class="num"><input type="number" min="0" value="${SURCHARGE[k] / 60}" style="width:5rem"></td></tr>`).join('')}</tbody></table>`, `<button class="btn btn-ghost" data-act="modal-close">Standard</button><button class="btn btn--cta" data-act="modal-ok" data-val="Stoppzeiten gespeichert">${ic('save')} Speichern</button>`, true);
  }
  if (m.kind === 'code') {
    return shell('Geteilte Route öffnen', '<div class="tr-field"><label for="m-code">Code</label><input id="m-code" type="text" placeholder="z. B. K7Q2-M9XA-P3" maxlength="12"></div>', `${cancel}<button class="btn btn--cta" data-act="followup" data-what="Geteilte Route öffnen">Öffnen</button>`);
  }
  return '';
}

/**
 * Renders the toast.
 * @returns {string} markup
 */
function toast() {
  return state.toast ? `<div class="tr-toast" role="status"><strong>${esc(state.toast.title)}</strong>${esc(state.toast.text)}</div>` : '';
}

const VARIANTS = { A: 'Cockpit', B: 'Assistent', C: 'Ergebnis zuerst' };

/**
 * Renders the prototype bar and the state panel.
 * @returns {string} markup
 */
function protoBar() {
  const dump = state.showState ? `<div class="proto-state">${esc(JSON.stringify({
    variant: state.variant, scenario: state.scenario, search: state.search, selected: state.sel,
    results: results().map((r) => ({ id: r.id, name: r.name, profit: Math.round(r.profit), perHour: Math.round(r.perHour), minutes: Math.round(r.duration / 60) })),
    flight: state.flight, saved: state.saved, soldOut: Object.keys(state.soldOut), myReports: state.myReports, modal: state.modal && state.modal.kind,
    activeFilters: activeFilters(state.request).map((f) => f.label),
  }, null, 2))}</div>` : '';
  return `${dump}<div class="proto-bar" role="toolbar" aria-label="Prototyp">
    <button data-act="variant" data-val="-1" aria-label="Vorige Variante">◀</button><span class="proto-bar__label">${state.variant} · ${VARIANTS[state.variant]}</span><button data-act="variant" data-val="1" aria-label="Nächste Variante">▶</button>
    <span class="proto-bar__sep"></span>
    <button data-act="scenario" data-val="single" aria-pressed="${state.scenario === 'single'}">1 Schiff</button><button data-act="scenario" data-val="convoy" aria-pressed="${state.scenario === 'convoy'}">Konvoi</button>
    <span class="proto-bar__sep"></span>
    <button data-act="deadline" aria-pressed="${state.search.deadline}" title="Suche endet an der 60-s-Frist">Frist</button><button data-act="state" aria-pressed="${state.showState}">Zustand</button>
  </div>`;
}
