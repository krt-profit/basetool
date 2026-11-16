/** PROTOTYPE data for the edit flows: invented positions, prices and the flight history; nothing is real or stored. */

const POS = {
  seraphim: [-14, 16], area18: [0, 28], baijini: [0.6, 28.7], tressler: [22.4, -31.8], newbabbage: [22, -32],
  everus: [12.2, 0.3], lorville: [12, 0], thedus: [12.8, 1.2], pyrogw: [-30, -20],
};

const PRICES = {
  seraphim: { buy: { 'Medical Supplies': [1820, 1400], Stims: [335, 1200], 'Processed Food': [140, 2200] }, sell: { Titanium: [1010, 600], Agricium: [2650, 300] } },
  area18: { buy: { Titanium: [805, 2600] }, sell: { 'Medical Supplies': [2285, 400], Stims: [470, 250], 'Processed Food': [235, 600], 'Agricultural Supplies': [160, null] } },
  area18b: { buy: { Hydrogen: [105, 5000] }, sell: { Agricium: [2780, 200], 'Medical Supplies': [2190, 600] } },
  baijini: { buy: { 'Processed Food': [140, 2000], Agricium: [2340, 350] }, sell: { Stims: [640, null], Titanium: [985, 2400], 'Medical Supplies': [1950, 300] } },
  tressler: { buy: { 'Distilled Spirits': [1105, 900], 'Agricultural Supplies': [95, 3000] }, sell: { 'Medical Supplies': [2240, 800], Titanium: [1040, 1500], Stims: [510, 600] } },
  everus: { buy: { Iron: [342, 1500], Laranite: [2380, 400] }, sell: { Stims: [480, 900], 'Processed Food': [205, 1200], 'Distilled Spirits': [1560, 500] } },
  thedus: { buy: { Laranite: [2290, 600] }, sell: { 'Medical Supplies': [2520, 500], Iron: [451, 1400], Titanium: [1120, 800] } },
  lorville: { buy: { Iron: [330, 2500] }, sell: { 'Medical Supplies': [2330, 700], Agricium: [2810, 400], 'Distilled Spirits': [1620, 700] } },
  lorvilleb: { buy: { Gold: [5900, 120] }, sell: { Titanium: [990, 900], Stims: [505, 400] } },
  newbabbage: { buy: { Agricium: [2295, 500], Gold: [5800, 200] }, sell: { Laranite: [3010, 800], Iron: [470, 1600], 'Processed Food': [240, 1000] } },
  pyrogw: { buy: { Hydrogen: [98, 6000] }, sell: { 'Medical Supplies': [2410, 350], 'Processed Food': [260, 900] } },
};

const EDIT_TABS = [
  { label: 'Beste Route', stops: ['seraphim', 'area18', 'baijini'], forced: ['baijini'] },
  { label: 'Alternative 1', stops: ['tressler', 'everus', 'lorville'], forced: [] },
];

const FLIGHT_UNDO_MAX = 20;

const FLIGHT_HISTORY_SEED = [
  { id: 'h1', stopIds: ['seraphim', 'area18', 'baijini'], name: 'Abendrunde Crusader – ArcCorp', path: 'Seraphim Station › Area18 › Baijini Point', ships: 'C2 Hercules „Lastesel“', src: 'Gespeicherte Route', outcome: 'abgeschlossen', earned: 351200, planned: 348040, ended: 'gestern, 22:41', lines: [
    ['Seraphim Station', 'Kauf', 'Medical Supplies', 400, 400, 1820, 1820], ['Seraphim Station', 'Kauf', 'Stims', 296, 296, 335, 335],
    ['Area18', 'Verkauf', 'Medical Supplies', 400, 400, 2285, 2290], ['Area18', 'Kauf', 'Titanium', 400, 400, 805, 805],
    ['Baijini Point', 'Verkauf', 'Stims', 296, 296, 640, 652], ['Baijini Point', 'Verkauf', 'Titanium', 400, 400, 985, 985],
  ] },
  { id: 'h2', stopIds: ['tressler', 'everus', 'lorville'], name: 'Tressler › Everus › Lorville', path: 'Port Tressler › Everus Harbor › Lorville', ships: 'C2 Hercules „Lastesel“', src: 'Berechnete Route', outcome: 'abgebrochen an Stopp 2', earned: 0, planned: 296500, ended: '14.11., 23:58', lines: [
    ['Port Tressler', 'Kauf', 'Distilled Spirits', 380, 380, 1105, 1105], ['Everus Harbor', 'Verkauf', 'Distilled Spirits', 380, 0, 1560, null],
  ] },
  { id: 'h3', stopIds: ['everus', 'thedus'], name: 'Eis-Pendel Hurston', path: 'Everus Harbor › HDMS-Thedus › Everus Harbor', ships: 'Freelancer MAX „Kiste“', src: 'Einheitsroute', outcome: 'abgelaufen an Stopp 3', earned: 18400, planned: 62100, ended: '09.11., 04:00', lines: [] },
];
