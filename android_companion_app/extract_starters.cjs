const fs = require('fs');
const d = require('../src/data/tracks.json');

const targetIds = ['albatross', 'caribou', 'green_turtle', 'blue_whale', 'seal'];

const starters = [];
const codes = [];

d.studies.filter(s => targetIds.includes(s.id)).forEach((s, index) => {
    // get first track
    const track = s.tracks[0];
    const code = `WILD-000${index}-CODE`;

    starters.push({
        animalId: `${s.id}:${track.id}`,
        species: s.species,
        scientificName: s.taxon,
        researchId: track.id,
        sourceStudy: s.name,
        license: s.license,
        source: d.source,
        displayName: s.species,
        description: s.blurb,
        intro: `Hello! I am a ${s.species}. Follow my journey!`,
        activationCode: code,
        points: track.points,
        firstObservation: track.startDate,
        lastObservation: track.endDate,
        observationCount: track.fixCount
    });

    codes.push(`${s.species}: ${code}`);
});

fs.mkdirSync('./app/src/main/assets', { recursive: true });
fs.writeFileSync('./app/src/main/assets/starters.json', JSON.stringify(starters));
fs.writeFileSync('./activation_codes.txt', codes.join('\n'));
console.log('Done extracting starters.');
