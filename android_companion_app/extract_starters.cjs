const fs = require('fs');
const path = require('path');
const d = require('../src/data/tracks.json');

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

const starters = [];
const codeLines = [];

STARTER_STUDIES.forEach((studyId, index) => {
  const sIdx = d.studies.findIndex(s => s.id === studyId);
  const s = d.studies[sIdx];
  const track = s.tracks[0];
  const displayName = NAMES[index];
  const code = CODES[index];
  const accent = PALETTE[sIdx % PALETTE.length];

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
    points: track.points,
    firstObservation: track.startDate,
    lastObservation: track.endDate,
    observationCount: track.fixCount,
    distanceKm: track.distanceKm
  });

  codeLines.push(`${displayName} (${s.species}): ${code}`);
});

fs.mkdirSync('./app/src/main/assets', { recursive: true });
fs.writeFileSync('./app/src/main/assets/starters.json', JSON.stringify(starters, null, 2));
fs.writeFileSync('./activation_codes.txt', codeLines.join('\n') + '\n');
console.log('Starters updated successfully.');
