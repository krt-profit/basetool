/** PROTOTYPE data for the route planner: invented places, prices and routes, nothing is real or stored. */
const PLACES = {
  seraphim: { name: 'Seraphim Station', kind: 'Raumstation', body: 'Crusader', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.4, n: 7, d: 2 } },
  area18: { name: 'Area18', kind: 'Stadt', body: 'ArcCorp', system: 'Stanton', armistice: true, monitored: true, danger: { m: 2.0, n: 9, d: 1 } },
  baijini: { name: 'Baijini Point', kind: 'Raumstation', body: 'ArcCorp', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.2, n: 2, d: 6 } },
  tressler: { name: 'Port Tressler', kind: 'Raumstation', body: 'microTech', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.3, n: 8, d: 3 } },
  levski: { name: 'Levski', kind: 'Stadt', body: 'Delamar', system: 'Nyx', armistice: true, monitored: false, danger: { m: 2.6, n: 6, d: 4 } },
  ruin: { name: 'Ruin Station', kind: 'Raumstation', body: 'Pyro VI', system: 'Pyro', armistice: true, monitored: false, danger: { m: 3.1, n: 11, d: 1 } },
  everus: { name: 'Everus Harbor', kind: 'Raumstation', body: 'Hurston', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.1, n: 10, d: 2 } },
  thedus: { name: 'HDMS-Thedus', kind: 'Außenposten', body: 'Arial', system: 'Stanton', armistice: false, monitored: true, danger: { m: 3.8, n: 5, d: 3 } },
  lorville: { name: 'Lorville', kind: 'Stadt', body: 'Hurston', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.9, n: 12, d: 1 } },
  newbabbage: { name: 'New Babbage', kind: 'Stadt', body: 'microTech', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.2, n: 9, d: 2 } },
  checkmate: { name: 'Checkmate', kind: 'Raumstation', body: 'Pyro II', system: 'Pyro', armistice: true, monitored: false, danger: { m: 3.4, n: 4, d: 2 } },
  pyrogw: { name: 'Pyro Gateway', kind: 'Raumstation', body: 'Gateway', system: 'Stanton', armistice: true, monitored: true, danger: { m: 2.2, n: 6, d: 1 } },
};

const TERMINALS = {
  seraphim: { name: 'Admin – Seraphim Station', place: 'seraphim', modes: ['Auto-Load'], refuel: true, bugs: { m: 2.1, n: 4, d: 5 } },
  area18: { name: 'TDD – Area18', place: 'area18', modes: ['Frachtaufzug', 'Ladedock'], refuel: false, bugs: { m: 3.6, n: 6, d: 1 } },
  baijini: { name: 'Admin – Baijini Point', place: 'baijini', modes: ['Auto-Load'], refuel: true, bugs: { m: 1.8, n: 3, d: 8 } },
  tressler: { name: 'Admin – Port Tressler', place: 'tressler', modes: ['Auto-Load'], refuel: true, bugs: { m: 1.5, n: 5, d: 2 } },
  levski: { name: 'Admin – Levski', place: 'levski', modes: ['Ladedock', 'Frachtaufzug'], refuel: true, bugs: { m: 2.8, n: 4, d: 6 } },
  ruin: { name: 'Admin – Ruin Station', place: 'ruin', modes: ['Ladedock'], refuel: true, bugs: { m: 2.4, n: 7, d: 3 } },
  everus: { name: 'Admin – Everus Harbor', place: 'everus', modes: ['Auto-Load'], refuel: true, bugs: { m: 1.6, n: 6, d: 2 } },
  thedus: { name: 'Admin – HDMS-Thedus', place: 'thedus', modes: ['Frachtaufzug'], refuel: false, bugs: { m: 2.9, n: 2, d: 9 } },
  lorville: { name: 'TDD – Lorville CBD', place: 'lorville', modes: ['Frachtaufzug', 'Ladedock'], refuel: false, bugs: { m: 2.2, n: 8, d: 2 } },
  newbabbage: { name: 'TDD – New Babbage', place: 'newbabbage', modes: ['Frachtaufzug'], refuel: false, bugs: { m: 1.7, n: 5, d: 4 } },
  checkmate: { name: 'Admin – Checkmate', place: 'checkmate', modes: ['Ladedock'], refuel: true, bugs: { m: 2.0, n: 3, d: 2 } },
  pyrogw: { name: 'Admin – Pyro Gateway', place: 'pyrogw', modes: ['Auto-Load'], refuel: true, bugs: { m: 1.9, n: 4, d: 1 } },
};

const COMMODITIES = [
  { name: 'Medical Supplies', cat: 'Medizin' }, { name: 'Stims', cat: 'Genussmittel' }, { name: 'Titanium', cat: 'Metalle' },
  { name: 'Distilled Spirits', cat: 'Genussmittel' }, { name: 'Agricultural Supplies', cat: 'Agrar' }, { name: 'Hephaestanite', cat: 'Mineralien' },
  { name: 'Recycled Material Composite', cat: 'Abfall & Recycling' }, { name: 'Iron', cat: 'Metalle' },
  { name: 'Pressurized Ice', cat: 'Gase', warn: 'zerfällt mit der Zeit' }, { name: 'Laranite', cat: 'Mineralien' }, { name: 'Agricium', cat: 'Metalle' },
  { name: 'Gold', cat: 'Metalle' }, { name: 'Quantanium', cat: 'Mineralien', warn: 'instabil im Quantum-Flug' }, { name: 'Scrap', cat: 'Abfall & Recycling' },
  { name: 'Processed Food', cat: 'Verarbeitete Güter' }, { name: 'Hydrogen', cat: 'Gase' }, { name: 'WiDoW', cat: 'Genussmittel', illegal: true },
];
const CATEGORIES = ['Metalle', 'Mineralien', 'Gase', 'Agrar', 'Medizin', 'Verarbeitete Güter', 'Abfall & Recycling', 'Genussmittel'];
const SYSTEMS = ['Stanton', 'Pyro', 'Nyx'];
const JUMPS = ['Stanton – Pyro', 'Pyro – Nyx', 'Nyx – Stanton'];
const KINDS = ['Raumstation', 'Stadt', 'Außenposten', 'Sonstige'];
const MODES = ['Auto-Load', 'Ladedock', 'Frachtaufzug'];
const DRIVES = ['Standard des Typs', 'XL-1 (S2)', 'Atlas (S2)', 'Voyage (S2)'];
const SURCHARGE = { Raumstation: 60, Stadt: 120, 'Außenposten': 60, Sonstige: 60 };
const OVERHEADS = { Raumstation: 4, Stadt: 8, 'Außenposten': 6, Sonstige: 5 };
const MIN_RATERS = { danger: 3, bugs: 3, avail: 2 };
const CROSSING = 180;
const BASE_HOLD = 696;

const SHIP_C2 = { kind: 'hangar', name: 'C2 Hercules „Lastesel“', type: 'Crusader C2 Hercules', short: 'C2', count: 1, scu: 696, reserve: 0, drive: 'Standard des Typs', capital: 1500000, est: '' };
const SHIP_CAT = { kind: 'type', name: 'Drake Caterpillar', type: 'Drake Caterpillar', short: 'Cat', count: 2, scu: 576, reserve: 0, drive: 'Standard des Typs', capital: 1200000, est: 'Caterpillar: Antrieb des Typs unbekannt, Median der S2-Antriebe' };
const SHIP_MAX = { kind: 'hangar', name: 'Freelancer MAX „Kiste“', type: 'MISC Freelancer MAX', short: 'MAX', count: 1, scu: 120, reserve: 0, drive: 'Standard des Typs', capital: 400000, est: '' };

const ROUTES = [
  {
    id: 'r1',
    round: false,
    stops: [
      { t: 'seraphim', mode: 'Auto-Load', over: 240, load: 170, fee: 1240, jobs: [
        { side: 'buy', c: 'Medical Supplies', qty: 312, p: 1820, age: 2, stock: 1400, avail: { pct: 92, n: 5 } },
        { side: 'buy', c: 'Stims', qty: 384, p: 335, age: 2, stock: 1200, avail: { pct: 60, n: 1 } },
      ] },
      { t: 'area18', mode: 'Frachtaufzug', over: 480, load: 540, fee: 0, jobs: [
        { side: 'sell', c: 'Medical Supplies', qty: 312, p: 2285, age: 5, demand: 1200, avail: { pct: 100, n: 3 } },
        { side: 'buy', c: 'Titanium', qty: 312, p: 805, age: 5, stock: 2600, avail: { pct: 88, n: 4 } },
      ] },
      { t: 'baijini', forced: true, mode: 'Auto-Load', over: 240, load: 160, fee: 980, jobs: [
        { side: 'sell', c: 'Stims', qty: 384, p: 495, age: 9, demand: null },
        { side: 'sell', c: 'Titanium', qty: 312, p: 985, age: 192, demand: 2400, avail: { pct: 70, n: 2 } },
      ] },
    ],
    legs: [
      { qt: 460, gm: 18.4, crossings: 0 },
      { qt: 130, gm: 0.9, crossings: 0 },
    ],
    runs: 3,
  },
  {
    id: 'r2',
    round: false,
    stops: [
      { t: 'tressler', mode: 'Auto-Load', over: 240, load: 175, fee: 1240, jobs: [
        { side: 'buy', c: 'Distilled Spirits', qty: 380, p: 1105, age: 1, stock: 900, avail: { pct: 95, n: 6 } },
        { side: 'buy', c: 'Agricultural Supplies', qty: 316, p: 95, age: 1, stock: 3000 },
      ] },
      { t: 'levski', mode: 'Ladedock', over: 480, load: 600, fee: 0, jobs: [
        { side: 'sell', c: 'Distilled Spirits', qty: 380, p: 1690, age: 3, demand: 1200 },
        { side: 'sell', c: 'Agricultural Supplies', qty: 316, p: 160, age: 3, demand: null },
        { side: 'buy', c: 'Hephaestanite', qty: 696, p: 2210, age: 3, stock: 1900, avail: { pct: 75, n: 3 } },
      ] },
      { t: 'ruin', mode: 'Ladedock', over: 240, load: 600, fee: 0, jobs: [
        { side: 'sell', c: 'Hephaestanite', qty: 696, p: 2960, age: 6, demand: 2000 },
        { side: 'buy', c: 'Recycled Material Composite', qty: 696, p: 210, age: 6, stock: 1500 },
      ] },
      { t: 'everus', mode: 'Auto-Load', over: 240, load: 170, fee: 1240, jobs: [
        { side: 'sell', c: 'Recycled Material Composite', qty: 696, p: 405, age: 50, demand: null },
      ] },
    ],
    legs: [
      { qt: 2100, gm: 96, crossings: 2, est: 'Gateway-Position aus UEX-Entfernung (ganze Gm)',
        gateways: ['Pyro Gateway (Stanton)', 'Stanton Gateway (Pyro)', 'Nyx Gateway (Pyro)', 'Pyro Gateway (Nyx)'],
        detour: { avoid: 'Nyx – Stanton', via: 'Pyro', extra: 660, text: 'Nicht Nyx – Stanton nehmen; über Pyro fliegen' } },
      { qt: 1500, gm: 61, crossings: 1, est: 'Gateway-Position aus UEX-Entfernung (ganze Gm)', gateways: ['Pyro Gateway (Nyx)', 'Nyx Gateway (Pyro)'] },
      { qt: 1620, gm: 70, crossings: 1, gateways: ['Stanton Gateway (Pyro)', 'Pyro Gateway (Stanton)'] },
    ],
    runs: 1,
  },
  {
    id: 'r3',
    round: true,
    stops: [
      { t: 'everus', mode: 'Auto-Load', over: 240, load: 150, fee: 1240, jobs: [
        { side: 'buy', c: 'Iron', qty: 520, p: 342, age: 2, stock: 1500, avail: { pct: 80, n: 3 } },
      ] },
      { t: 'thedus', mode: 'Frachtaufzug', over: 360, load: 720, fee: 0, jobs: [
        { side: 'sell', c: 'Iron', qty: 520, p: 451, age: 96, demand: 1400 },
        { side: 'buy', c: 'Pressurized Ice', qty: 696, p: 210, age: 96, stock: 2000, warn: 'zerfällt mit der Zeit' },
      ] },
      { t: 'everus', mode: 'Auto-Load', over: 240, load: 170, fee: 1240, jobs: [
        { side: 'sell', c: 'Pressurized Ice', qty: 696, p: 318, age: 2, demand: 2000, warn: 'zerfällt mit der Zeit' },
      ] },
    ],
    legs: [
      { qt: 200, gm: 2.4, crossings: 0 },
      { qt: 200, gm: 2.4, crossings: 0 },
    ],
    runs: 1,
  },
];
