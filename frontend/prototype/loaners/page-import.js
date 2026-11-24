/**
 * Entries of a CCU Game Fleetview file that CCU Game's reading of the matrix makes loaners: the
 * possible loaners with the granting types the matrix names.
 * @type {Array<{type: string, label?: string, granting: string}>}
 */
const POSSIBLE_LOANERS = [
  { type: 'idrisp', granting: 'Javelin' },
  { type: 'mpuv', granting: 'Javelin' },
  { type: 'polaris', granting: 'Kraken' },
  { type: 'ironclad', granting: 'Kraken' },
  { type: 'buccaneer', granting: 'Kraken' },
  { type: 'hullc', granting: 'Hull D, Hull E, Pioneer' },
  { type: 'arrow', granting: 'Hull E' },
  { type: 'mustang', label: 'C.O. Mustang Alpha', granting: 'Cyclone, Nova (Arena-Commander-Ersatz, gewährt hier nichts)' },
];

/** Opens the import dialog with the help on the export settings. */
function openImportStart() {
  const body = `
    <p class="m-0">Lade die Schiffsliste deines RSI-Hangars hoch. Erkannt werden HangarXPLOR, StarJump / Hangar Link, Hangar Extension (JSON, CSV), CCU Game Fleetview und Fleetyards.</p>
    <div class="ln-help">
      <p class="ln-help__title">${icon('info')} Exporteinstellungen</p>
      <ul>
        <li><strong>CCU Game:</strong> „Apply: Saved CCU Chains" ausschalten, sonst stehen geplante Upgrades als Schiffe in der Datei.</li>
        <li><strong>Fleetyards:</strong> im Hangar-Filter „Loaner: Ausblenden" lassen.</li>
        <li>CCU Game führt Loaner wie eigene Schiffe auf. Das Basetool erkennt sie und fragt nach; Loaner selbst vergibt es aus der Loaner-Matrix.</li>
      </ul>
    </div>
    ${protoNote('Prototyp: keine echte Datei. Wähle ein Beispiel.')}`;
  const foot = `<button type="button" class="btn btn-ghost" data-act="import-run" data-format="extension">${icon('upload')} <span>Beispiel: Hangar Extension</span></button>
    <button type="button" class="btn btn--cta" data-act="import-run" data-format="ccu">${icon('upload')} <span>Beispiel: CCU Game Fleetview</span></button>`;
  openDialog({ title: 'Schiffsliste importieren', body, foot, wide: true });
}

/**
 * Opens the import result dialog of a simulated upload.
 * @param {string} format `ccu` or `extension`
 */
function openImportResult(format) {
  const ccu = format === 'ccu';
  const candidates = MY_SHIPS.filter((s) => s.formerLoaner);
  const stat = (/** @type {string} */ k, /** @type {number} */ v) => `<div class="stat-row"><span>${esc(k)}</span><span>${v}</span></div>`;
  const possible = ccu ? `
    <p class="import-list-title">Mögliche Loaner</p>
    <p class="form-hint">Diese Einträge sind laut Loaner-Matrix Loaner deiner Schiffe in der Datei. CCU Game führt sie wie eigene Schiffe. Übersprungen entsteht nichts; deine Loaner vergibt das Basetool selbst.</p>
    <div class="ln-choice-list">${POSSIBLE_LOANERS.map((p, i) => `
      <div class="ln-choice">
        <div class="ln-choice__name"><span class="cell-title">${esc(p.label || typeLabel(p.type))}</span><span class="cell-sub">Laut Matrix Loaner für ${esc(p.granting)}</span></div>
        ${segmented(`pl-${i}`, [['skip', 'Als Loaner überspringen'], ['own', 'Als eigenes Schiff importieren']], 'skip', 'Auswahl')}
      </div>`).join('')}</div>` : '';
  const former = candidates.length ? `
    <p class="import-list-title">Im Hangar, vermutlich frühere Loaner</p>
    <p class="form-hint">Ein früherer Import hat diese Einträge als eigene Schiffe angelegt. Du hältst ihren Typ jetzt als Loaner. Gelöscht wird nur, was du löschst.</p>
    <div class="ln-choice-list">${candidates.map((s) => `
      <div class="ln-choice">
        <div class="ln-choice__name"><span class="cell-title">${esc(s.name || typeLabel(s.type))}</span><span class="cell-sub">${esc(s.name ? typeLabel(s.type) : 'Ohne Namen')} · ${esc(s.loc || '–')} · ${unitBadge(s.unit)}</span></div>
        <button type="button" class="btn btn-quiet-danger btn-xs" data-act="former-delete" data-id="${s.id}">${icon('trash')} <span>Löschen</span></button>
      </div>`).join('')}</div>` : '';
  const body = `
    ${stat('Importiert', ccu ? 2 : 3)}${stat('Aktualisiert', 7)}${stat('Nicht erkannt oder nicht im Spiel', 0)}${stat('Nicht eindeutig', 0)}${stat('Duplikate übersprungen', 0)}${ccu ? stat('Mögliche Loaner (nicht importiert)', POSSIBLE_LOANERS.length) : ''}
    <p class="alert alert-info ln-new-loaners">${icon('info')} Neue Loaner durch diesen Import: Aegis Idris-P, Argo MPUV Cargo.</p>
    ${possible}${former}`;
  const foot = `<button type="button" class="btn btn-ghost" data-act="close-dialog">Schließen</button>${ccu ? '<button type="button" class="btn btn--cta" data-act="import-apply" disabled>Auswahl importieren</button>' : ''}`;
  openDialog({ title: 'Import abgeschlossen', body, foot, wide: true, id: 'import-result-modal' });
}

/**
 * Handles the import dialogs' clicks and choices.
 * @param {Event} ev
 * @returns {boolean} whether the page needs a re-render
 */
function importEvent(ev) {
  const t = /** @type {HTMLElement} */ (ev.target);
  if (ev.type === 'change' && t instanceof HTMLInputElement && t.name.startsWith('pl-')) {
    const d = /** @type {HTMLElement} */ (t.closest('dialog'));
    const any = [...d.querySelectorAll('input[name^="pl-"]:checked')].some((i) => /** @type {HTMLInputElement} */ (i).value === 'own');
    const apply = /** @type {HTMLButtonElement} */ (d.querySelector('[data-act="import-apply"]'));
    apply.disabled = !any;
    return false;
  }
  const btn = /** @type {HTMLElement|null} */ (t.closest('[data-act]'));
  if (!btn || ev.type !== 'click') return false;
  switch (btn.dataset.act) {
    case 'import': openImportStart(); return false;
    case 'import-run': openImportResult(String(btn.dataset.format)); return false;
    case 'import-apply': closeDialog(); toast('Gewählte Einträge als eigene Schiffe importiert.'); return true;
    case 'former-delete': {
      const id = Number(btn.dataset.id);
      const i = MY_SHIPS.findIndex((s) => s.id === id);
      if (i >= 0) MY_SHIPS.splice(i, 1);
      const row = btn.closest('.ln-choice');
      if (row) row.remove();
      toast('Schiff gelöscht. Dein Loaner dieses Typs bleibt.');
      return true;
    }
    default: return false;
  }
}
