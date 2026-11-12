const fs = require('fs');
const path = require('path');

// Resolve tracks.json from data/tracks.json or ../src/data/tracks.json
let tracksPath = path.resolve(__dirname, '../data/tracks.json');
if (!fs.existsSync(tracksPath)) {
  tracksPath = path.resolve(__dirname, '../src/data/tracks.json');
}
const d = JSON.parse(fs.readFileSync(tracksPath, 'utf8'));

const PALETTE = [
  '#5a8f63', '#2f5d6e', '#a6643c', '#6b7a3a', '#7d5a8c',
  '#c08a2d', '#4a6b8a', '#8a4b4b', '#3f7d6e', '#9c7b3f',
  '#566246', '#7a6f9b', '#b07a55', '#456b5a', '#8f6f8a', '#705a3a'
];

const STARTER_STUDIES = ['blue_whale', 'green_turtle', 'caribou', 'seal', 'albatross'];
const NAMES = ['Mara', 'Nalu', 'Kestrel', 'Harbor', 'Sol'];
const CODES = [
  'WILD-0003-CODE', // Blue Whale (Mara)
  'WILD-0002-CODE', // Green Turtle (Nalu)
  'WILD-0001-CODE', // Caribou (Kestrel)
  'WILD-0004-CODE', // California Sea Lion (Harbor)
  'WILD-0000-CODE'  // Waved Albatross (Sol)
];

const maxSpeeds = {
  'blue_whale': 60,
  'green_turtle': 25,
  'caribou': 70,
  'seal': 40,
  'albatross': 140
};

function haversine(lon1, lat1, lon2, lat2) {
  const R = 6371; // km
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180) * Math.cos(lat2*Math.PI/180) * Math.sin(dLon/2)**2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

function cleanTrackPoints(points, maxSpeedKmh = 90) {
  if (!points || points.length <= 2) return points;
  let cleaned = [points[0]];

  for (let i = 1; i < points.length; i++) {
    const cur = points[i];
    const prev = cleaned[cleaned.length - 1];
    const distPrev = haversine(prev[0], prev[1], cur[0], cur[1]);
    const dtHours = Math.abs(cur[2] - prev[2]) / 3600000;
    const speed = dtHours > 0 ? distPrev / dtHours : 0;

    if (distPrev > 1000 || speed > maxSpeedKmh) {
      if (i < points.length - 1) {
        const next = points[i + 1];
        const distChord = haversine(prev[0], prev[1], next[0], next[1]);
        const distNext = haversine(cur[0], cur[1], next[0], next[1]);
        const dtChord = Math.abs(next[2] - prev[2]) / 3600000;
        const speedChord = dtChord > 0 ? distChord / dtChord : 0;
        if (speedChord <= maxSpeedKmh || distNext > 1000) {
          continue;
        }
      } else {
        continue;
      }
    }
    cleaned.push(cur);
  }
  return cleaned;
}

const starters = [];
const codeLines = [];

STARTER_STUDIES.forEach((studyId, index) => {
  const sIdx = d.studies.findIndex(s => s.id === studyId);
  const s = d.studies[sIdx];
  const track = s.tracks[0];
  const displayName = NAMES[index];
  const code = CODES[index];
  const accent = PALETTE[sIdx % PALETTE.length];

  const cleanedPoints = cleanTrackPoints(track.points, maxSpeeds[studyId] || 90);

  let realDistKm = 0;
  for (let i = 1; i < cleanedPoints.length; i++) {
    realDistKm += haversine(cleanedPoints[i-1][0], cleanedPoints[i-1][1], cleanedPoints[i][0], cleanedPoints[i][1]);
  }
  realDistKm = Math.round(realDistKm);

  starters.push({
    animalId: `${s.id}:${track.id}`,
    species: s.species,
    scientificName: s.taxon || s.species,
    researchId: track.id,
    sourceStudy: s.name,
    license: s.license,
    source: s.source || `Migration Watch / Movebank study: ${s.name}`,
    displayName: displayName,
    description: `A real ${s.species.toLowerCase()} track preserved from the research dataset.`,
    intro: `Hello, I'm ${displayName}! Follow my real recorded journey across the wild.`,
    accent: accent,
    activationCodes: [code, `WILD-${displayName.toUpperCase().slice(0, 4)}-${track.id.replace(/[^A-Za-z0-9]/g, '').slice(-4).toUpperCase()}`],
    activationCode: code,
    points: cleanedPoints,
    firstObservation: track.startDate,
    lastObservation: track.endDate,
    observationCount: cleanedPoints.length,
    distanceKm: realDistKm
  });

  codeLines.push(`${displayName} (${s.species}): ${code}`);
});

const outAssetsDir = path.resolve(__dirname, 'app/src/main/assets');
fs.mkdirSync(outAssetsDir, { recursive: true });
fs.writeFileSync(path.join(outAssetsDir, 'starters.json'), JSON.stringify(starters, null, 2));
fs.writeFileSync(path.resolve(__dirname, 'activation_codes.txt'), codeLines.join('\n') + '\n');
console.log(`Starters updated successfully (${starters.length} animals, realistic tracking points cleaned).`);
