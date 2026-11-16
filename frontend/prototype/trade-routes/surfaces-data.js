/** PROTOTYPE data for the surfaces beside the planner: lists, opened routes, a mission, ratings and the switch; all invented. */
Object.assign(TERMINALS, {
  area18b: { name: 'Admin – Riker Memorial', place: 'area18', modes: ['Ladedock'], refuel: true, bugs: { m: 1.9, n: 4, d: 3 } },
  lorvilleb: { name: 'Admin – Teasa Spaceport', place: 'lorville', modes: ['Ladedock'], refuel: true, bugs: { m: 2.6, n: 2, d: 11 } },
});

/**
 * Copies a planner route under a new id and applies per-stop changes.
 * @param {string} from the id of the route in ROUTES
 * @param {string} id the new id
 * @param {function(object, number): object} change returns the changed stop
 * @returns {object} the new base route
 */
function deriveRoute(from, id, change) {
  const src = ROUTES.find((r) => r.id === from);
  return { ...src, id, stops: src.stops.map((s, i) => change({ ...s, jobs: s.jobs.map((j) => ({ ...j })) }, i)) };
}

const OPENED_BASES = [
  deriveRoute('r1', 'o1', (s, i) => {
    if (i === 0) {
      s.jobs = [
        { ...s.jobs[0], p: 1840, dev: { was: 1820 } },
        { side: 'buy', c: 'Stims', qty: 384, p: 335, gone: 'ausverkauft (Kaufstufe 1), nicht mehr im Plan' },
        { side: 'buy', c: 'Processed Food', qty: 384, p: 140, age: 2, stock: 2200, neu: true },
      ];
    }
    if (i === 1) {
      s.jobs = [
        { ...s.jobs[0], p: 2050, dev: { was: 2285 } },
        { ...s.jobs[1], qty: 200, p: 812, stock: 200, dev: { was: 805, wasQty: 312, reason: 'Bestand reicht nicht mehr' } },
      ];
    }
    if (i === 2) {
      s.jobs = [
        { side: 'sell', c: 'Stims', qty: 384, p: 495, gone: 'nicht mehr im Plan, weil an Stopp 1 nicht gekauft' },
        { side: 'sell', c: 'Processed Food', qty: 384, p: 260, age: 9, demand: 3000, neu: true },
        { ...s.jobs[1], qty: 200, age: 196, dev: { wasQty: 312 } },
      ];
    }
    return s;
  }),
  deriveRoute('r3', 'o3', (s, i) => {
    if (i === 1) s.jobs = [{ ...s.jobs[0], p: 438, dev: { was: 451 } }, { ...s.jobs[1], p: 240, dev: { was: 210 } }];
    if (i === 2) s.jobs = [{ ...s.jobs[0], p: 361, dev: { was: 318 } }];
    return s;
  }),
  deriveRoute('r2', 'o2', (s, i) => {
    if (i === 0) s.jobs = [{ ...s.jobs[0], p: 1150, dev: { was: 1105 } }, s.jobs[1]];
    if (i === 2) s.jobs = [{ ...s.jobs[0], p: 2590, dev: { was: 2960 } }, s.jobs[1]];
    if (i === 3) s.jobs = [{ ...s.jobs[0], age: 380 }];
    return s;
  }),
  deriveRoute('r1', 'm1', (s) => s),
  deriveRoute('r1', 'm1now', (s, i) => {
    if (i === 1) s.jobs = [{ ...s.jobs[0], p: 2030, dev: { was: 2285 } }, s.jobs[1]];
    return s;
  }),
  deriveRoute('r2', 'm2', (s) => s),
  deriveRoute('r3', 'm3', (s) => s),
];
ROUTES.push(...OPENED_BASES);

const SHIP_C2_TYPE = { ...SHIP_C2, kind: 'type', name: 'Crusader C2 Hercules', short: 'C2' };

const OPEN_SOURCES = {
  saved: { label: 'Gespeicherte Route', old: 'r1', now: 'o1', ships: [{ ...SHIP_C2_TYPE }], name: 'Abendrunde Crusader – ArcCorp', note: 'Stims nur, wenn Seraphim mehr als 300 SCU hat. Baijini zuletzt verbuggt.', since: '12.11., 21:14 (dein letztes Öffnen)' },
  unit: { label: 'Einheitsroute', old: 'r3', now: 'o3', ships: [{ ...SHIP_C2_TYPE }], name: 'Eis-Pendel Hurston', note: 'Für Neulinge: kurze Wege, alles Waffenstillstand bis auf Thedus.', since: '09.11., 18:02 (Stand der Einheit)', unit: 'ALPHA', by: 'Vagabund' },
  shared: { label: 'Geteilte Route', old: 'r2', now: 'o2', ships: [{ ...SHIP_C2_TYPE }], name: 'Drei-System-Runde', since: '14.11., 20:41 (beim Teilen)', code: 'R7KQ-2M9X-PD' },
};

const SAVED_ROUTES = [
  { id: 's1', name: 'Abendrunde Crusader – ArcCorp', note: 'Stims nur, wenn Seraphim mehr als 300 SCU hat. Baijini zuletzt verbuggt.', path: 'Seraphim › Area18 › Baijini Point', ships: 'C2 Hercules „Lastesel“', perHour: 1243000, opened: 'heute, 19:40' },
  { id: 's2', name: 'Drei-System-Runde', note: '', path: 'Port Tressler › Levski › Ruin Station › Everus Harbor', ships: 'C2 Hercules „Lastesel“', perHour: 988000, opened: 'gestern, 22:10' },
  { id: 's3', name: 'Eis-Pendel Hurston', note: 'Für Alt-Accounts ohne C2', path: 'Everus Harbor › HDMS-Thedus › Everus Harbor', ships: 'Freelancer MAX „Kiste“', perHour: 402000, opened: '13.11., 20:05' },
  { id: 's4', name: 'Konvoi Lorville – New Babbage', note: 'Mit Bravo, Startkapital je 1,2 Mio.', path: 'Lorville › New Babbage › Port Tressler', ships: 'Konvoi · 3 Schiffe', perHour: 2105000, opened: '10.11., 21:30' },
  { id: 's5', name: 'Pyro-Abstecher', note: '', path: 'Pyro Gateway › Checkmate › Ruin Station', ships: 'Drake Caterpillar', perHour: 1580000, opened: '02.11., 19:12', broken: 'Checkmate nicht berechenbar · alte Momentaufnahme' },
  { id: 's6', name: 'Levski-Medizin', note: 'Nur am Wochenende, Levski sonst leer', path: 'Seraphim › Levski', ships: 'C2 Hercules „Lastesel“', perHour: 760000, opened: '28.10., 18:44', fallback: true },
  { id: 's7', name: 'Titan nach Baijini', note: '', path: 'Area18 › Baijini Point', ships: 'Crusader C2 Hercules', perHour: 512000, opened: '21.10., 23:02' },
];

const UNIT_ROUTES = [
  { id: 'u1', name: 'Eis-Pendel Hurston', note: 'Für Neulinge: kurze Wege, alles Waffenstillstand bis auf Thedus.', unit: 'ALPHA', by: 'Vagabund', path: 'Everus Harbor › HDMS-Thedus › Everus Harbor', ships: 'Crusader C2 Hercules', perHour: 395000, age: '7 Tage' },
  { id: 'u2', name: 'Staffelabend Stanton', note: 'Treffpunkt Seraphim, Konvoi aus allen C2', unit: 'ALPHA', by: 'Kestrel', path: 'Seraphim › Area18 › Baijini Point', ships: 'Konvoi · 3× Crusader C2 Hercules', perHour: 3420000, age: '2 Tage' },
  { id: 'u3', name: 'Pyro-Versorgung', note: '', unit: 'ALPHA', by: '', path: 'Port Tressler › Ruin Station › Checkmate', ships: 'Drake Caterpillar', perHour: 1410000, age: '19 Tage' },
  { id: 'u4', name: 'Quantanium-Lauf (Warnware)', note: 'Nur mit Begleitschutz', unit: 'ALPHA-1', by: 'Nachtfalke', path: 'HDMS-Thedus › Lorville', ships: 'MISC Freelancer MAX', perHour: 1820000, age: '4 Tage' },
];

const MY_SHARES = [
  { code: 'R7KQ-2M9X-PD', name: 'Drei-System-Runde', path: 'Port Tressler › Levski › Ruin Station › Everus Harbor', ships: 'Crusader C2 Hercules', at: '14.11., 20:41' },
  { code: 'K7Q2-M9XA-P3', name: 'Seraphim › Area18 › Baijini Point', path: 'Seraphim › Area18 › Baijini Point', ships: 'Crusader C2 Hercules', at: '12.11., 21:30' },
  { code: 'H3WD-8CYT-QV', name: 'Konvoi Lorville – New Babbage', path: 'Lorville › New Babbage › Port Tressler', ships: 'Konvoi · 3 Schiffstypen', at: '04.11., 19:02' },
];

const MY_UNITS = [
  { key: 'ALPHA', label: 'Staffel ALPHA', used: 23 },
  { key: 'ALPHA-1', label: 'Schwarm ALPHA-1', used: 4 },
];

const MISSION = {
  name: 'Frachtkonvoi Pyro',
  units: [
    { key: 'ALPHA', ship: 'Crusader C2 Hercules', hangar: 'C2 Hercules „Lastesel“', owner: 'Vagabund', crew: ['Vagabund', 'Kestrel'], cargo: true },
    { key: 'BRAVO', ship: 'Drake Caterpillar', hangar: '', owner: '', crew: ['Nachtfalke', 'Rost'], cargo: true },
    { key: 'CHARLIE', ship: 'Anvil Gladius', hangar: 'Gladius „Stachel“', owner: 'Funke', crew: ['Funke'], cargo: false },
    { key: 'DELTA', ship: '', hangar: '', owner: '', crew: ['Kiesel', 'Moos'], cargo: false },
  ],
};

const MISSION_ROUTES = [
  { id: 'M1', name: 'Stanton-Runde', base: 'm1', now: 'm1now', fleet: ['ALPHA', 'BRAVO'], marks: [{ kind: 'warn', text: 'Preis geändert −11,2 %' }] },
  { id: 'M2', name: 'Pyro-Lauf', base: 'm2', now: 'm2', fleet: ['ALPHA'], marks: [{ kind: 'danger', text: 'Flotte geändert' }], fleetNote: 'Hangarschiff von ALPHA getauscht, jetzt C2 Hercules „Lastesel“' },
  { id: 'M3', name: 'Rückweg Everus', base: 'm3', now: 'm3', fleet: [], marks: [{ kind: 'muted', text: 'keine Flotte gewählt' }] },
];

const FLEET_SHIPS = {
  ALPHA: { ...SHIP_C2, name: 'ALPHA · C2 Hercules „Lastesel“', short: 'ALPHA', capital: 1500000 },
  BRAVO: { ...SHIP_CAT, count: 1, name: 'BRAVO · Drake Caterpillar (Typ)', short: 'BRAVO', est: '' },
};

const TRANSFER = { from: 'Baijini Point', to: 'Port Tressler', dur: 760, gm: 31.4, jumps: 0 };

const DISTRIBUTION = {
  danger: { seraphim: [72, 21, 7, 0, 0], area18: [28, 49, 16, 7, 0], baijini: [80, 20, 0, 0, 0], tressler: [70, 25, 5, 0, 0], levski: [10, 38, 38, 14, 0], ruin: [5, 22, 40, 25, 8], everus: [88, 12, 0, 0, 0], thedus: [0, 6, 24, 52, 18], lorville: [30, 50, 20, 0, 0], newbabbage: [78, 18, 4, 0, 0], checkmate: [0, 20, 30, 40, 10], pyrogw: [20, 45, 30, 5, 0] },
  bugs: [18, 41, 27, 14, 0],
};
const DANGER_STEPS = ['sicher', 'gering', 'mittel', 'hoch', 'sehr hoch'];
const BUG_STEPS = ['läuft', 'selten Probleme', 'häufig Probleme', 'meistens Probleme', 'unbrauchbar'];
const AVAIL_STEPS = ['wie angezeigt oder mehr', 'weniger', 'nichts da'];

const AVAILABILITY = {
  area18: [
    { c: 'Medical Supplies', side: 'sell', pct: 100, n: 3, d: 1, shares: [100, 0, 0] },
    { c: 'Titanium', side: 'buy', pct: 88, n: 4, d: 2, shares: [76, 24, 0] },
    { c: 'Processed Food', side: 'buy', pct: 40, n: 1, d: 6, shares: [0, 80, 20] },
  ],
  area18b: [
    { c: 'Agricium', side: 'sell', pct: 63, n: 2, d: 4, shares: [38, 50, 12] },
    { c: 'Hydrogen', side: 'buy', pct: 95, n: 5, d: 1, shares: [90, 10, 0] },
  ],
};

const MY_REPORTS_SEED = [
  { id: 1, when: 'heute, 19:52', obj: 'Area18', scale: 'Gefahr', val: '2 · gering', counts: 'voll · bis 11.01.' },
  { id: 2, when: 'heute, 19:52', obj: 'TDD – Area18', scale: 'Bugs', val: '3 · häufig Probleme', counts: 'voll · bis zum nächsten Patch' },
  { id: 3, when: 'heute, 19:53', obj: 'Medical Supplies · Verkauf · TDD – Area18', scale: 'Verfügbarkeit', val: 'wie angezeigt oder mehr', counts: 'voll · bis 14.12.' },
  { id: 4, when: '12.11., 21:30', obj: 'Seraphim Station', scale: 'Gefahr', val: '1 · sicher', counts: '81 % · bis 07.01.' },
  { id: 5, when: '04.11., 20:11', obj: 'Stims · Kauf · Admin – Seraphim Station', scale: 'Verfügbarkeit', val: 'nichts da', counts: '21 % · bis 02.12.' },
];

const DATA_JOBS = [
  { name: 'UEX-Preise', every: 'stündlich', last: 'heute, 14:00', result: '1.284 Preise: 1.102 frisch · 160 veraltet · 22 abgelaufen', ok: true },
  { name: 'Entfernungen', every: 'täglich', last: 'heute, 03:10', result: '4.258 Paare, 12 nur aus UEX', ok: true },
  { name: 'Sprungpunkte', every: 'täglich', last: 'heute, 03:20', result: '6 Verbindungen', ok: true },
  { name: 'Bewertungen bereinigen', every: 'täglich', last: 'heute, 04:00', result: '37 Meldungen beendet (31 Alter, 6 Patch)', ok: true },
];
