/** PROTOTYPE data for long routes: more invented stations, their prices, two long stop chains and list entries; nothing is real or stored. */

Object.assign(PLACES, {
  cruL1: { name: 'CRU-L1 Ambitious Dream Station', kind: 'Raumstation', body: 'Crusader L1', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.6, n: 5, d: 3 } },
  arcL1: { name: 'ARC-L1 Wide Forest Station', kind: 'Raumstation', body: 'ArcCorp L1', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.5, n: 6, d: 2 } },
  micL1: { name: 'MIC-L1 Shallow Frontier Station', kind: 'Raumstation', body: 'microTech L1', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.8, n: 4, d: 5 } },
  hurL2: { name: 'HUR-L2 Faithful Dream Station', kind: 'Raumstation', body: 'Hurston L2', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.4, n: 7, d: 2 } },
  orison: { name: 'Orison', kind: 'Stadt', body: 'Crusader', system: 'Stanton', armistice: true, monitored: true, danger: { m: 1.3, n: 8, d: 1 } },
  grimhex: { name: 'GrimHEX', kind: 'Raumstation', body: 'Yela', system: 'Stanton', armistice: true, monitored: false, danger: { m: 3.2, n: 6, d: 2 } },
});

Object.assign(TERMINALS, {
  cruL1: { name: 'Admin – CRU-L1', place: 'cruL1', modes: ['Auto-Load'], refuel: true, bugs: { m: 1.7, n: 4, d: 3 } },
  arcL1: { name: 'Admin – ARC-L1', place: 'arcL1', modes: ['Auto-Load'], refuel: true, bugs: { m: 2.3, n: 5, d: 2 } },
  micL1: { name: 'Admin – MIC-L1', place: 'micL1', modes: ['Auto-Load'], refuel: true, bugs: { m: 1.9, n: 3, d: 6 } },
  hurL2: { name: 'Admin – HUR-L2', place: 'hurL2', modes: ['Auto-Load'], refuel: true, bugs: { m: 1.6, n: 6, d: 1 } },
  orison: { name: 'TDD – Orison', place: 'orison', modes: ['Frachtaufzug'], refuel: false, bugs: { m: 2.7, n: 5, d: 2 } },
  grimhex: { name: 'Admin – GrimHEX', place: 'grimhex', modes: ['Ladedock'], refuel: true, bugs: { m: 3.1, n: 4, d: 4 } },
});

Object.assign(POS, {
  cruL1: [-7, 22], arcL1: [-2, 24], micL1: [18, -26], hurL2: [14, -4], orison: [-14.5, 15.5], grimhex: [-13, 17.5],
});

Object.assign(PRICES, {
  cruL1: { buy: { 'Agricultural Supplies': [90, 900], Hydrogen: [100, 3000] }, sell: { 'Medical Supplies': [2230, 350], 'Processed Food': [228, 500] } },
  arcL1: { buy: { 'Distilled Spirits': [1080, 450], Iron: [330, 1200] }, sell: { Titanium: [1030, 500], 'Agricultural Supplies': [168, 900], Agricium: [2700, 250] } },
  micL1: { buy: { 'Processed Food': [136, 1200], Laranite: [2320, 300] }, sell: { 'Distilled Spirits': [1600, 400], Iron: [462, 1100], Agricium: [2760, 300], Hydrogen: [150, 2000] } },
  hurL2: { buy: { Titanium: [795, 1000], Stims: [325, 600] }, sell: { Laranite: [2980, 400], Gold: [6450, 150], 'Processed Food': [252, 600] } },
  orison: { buy: { 'Processed Food': [132, 1500] }, sell: { Stims: [495, 300], Iron: [445, 600], Hydrogen: [140, 1500] } },
  grimhex: { buy: { Stims: [300, 500] }, sell: { 'Distilled Spirits': [1680, 250], Gold: [6300, 80], 'Processed Food': [210, 400] } },
});

const LONG_CHAINS = [
  ['seraphim', 'cruL1', 'area18', 'baijini', 'arcL1', 'micL1', 'newbabbage', 'tressler', 'hurL2', 'everus'],
  ['orison', 'grimhex', 'baijini', 'arcL1', 'micL1', 'newbabbage', 'hurL2', 'lorville', 'tressler', 'everus'],
];

const SEARCH_LIMIT_DEFAULT = 10;
const ROUTE_LIMIT = 10;
const LONG_JOB_ABOVE = 5;
const SEQ_TEXT_MAX = 80;
const AFTER_SHOWN = 3;

const LONG_SAVED = { id: 's0', name: 'Große Stanton-Schleife', note: 'Nur mit voller Crew, dauert gut zwei Stunden', path: LONG_CHAINS[0].map((t) => PLACES[TERMINALS[t].place].name), ships: 'C2 Hercules „Lastesel“', perHour: 1712000, opened: 'heute, 20:15' };
const LONG_UNIT = { id: 'u0', name: 'Rundflug Staffelabend', note: 'Treffpunkt Orison', unit: 'ALPHA', by: 'Kestrel', path: LONG_CHAINS[1].slice(0, 7).map((t) => PLACES[TERMINALS[t].place].name), ships: 'Konvoi · 3× Crusader C2 Hercules', perHour: 4180000, age: '1 Tag' };
const LONG_SHARE = { code: 'P4XN-7QWD-K2', name: 'Sechs Stationen Stanton', path: LONG_CHAINS[0].slice(0, 6).map((t) => PLACES[TERMINALS[t].place].name), ships: 'Crusader C2 Hercules', at: '15.11., 21:12' };
const LONG_FLIGHT = { id: 'h0', stopIds: LONG_CHAINS[0], name: 'Große Stanton-Schleife', path: LONG_CHAINS[0].map((t) => PLACES[TERMINALS[t].place].name), ships: 'C2 Hercules „Lastesel“', src: 'Gespeicherte Route', outcome: 'abgeschlossen', earned: 1281400, planned: 1275900, ended: 'heute, 18:02', lines: [] };

SAVED_ROUTES.unshift(LONG_SAVED);
UNIT_ROUTES.unshift(LONG_UNIT);
MY_SHARES.unshift(LONG_SHARE);
FLIGHT_HISTORY_SEED.unshift(LONG_FLIGHT);
