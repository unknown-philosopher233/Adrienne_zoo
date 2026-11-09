const fs = require('fs');
const path = require('path');
const JSZip = require('jszip');
const d = require('./src/data/tracks.json');

async function createFixture() {
  const zip = new JSZip();

  const study = d.studies.find(s => s.id === 'blue_whale');
  const track = study.tracks[0];
  const animalId = `${study.id}:${track.id}`;
  const safeId = 'blue-whale';
  const code = 'WILD-B7K4-M2Q9';

  const manifest = {
    format: 'wildlife-pack',
    version: 1,
    packId: 'field-notes-sample-2026',
    title: 'Field Notes Sample Pack',
    animals: [
      {
        animalId: animalId,
        profile: `animals/${safeId}.json`,
        track: `tracks/${safeId}.json`,
        activationCodes: [code]
      }
    ]
  };

  const profile = {
    animalId: animalId,
    species: study.species,
    scientificName: study.taxon || study.species,
    researchId: track.id,
    sourceStudy: study.name,
    license: study.license,
    source: study.source || `Migration Watch / Movebank study: ${study.name}`,
    displayName: 'Mara',
    description: 'A real blue whale track preserved from the research dataset.',
    intro: "Hello, I'm Mara! Follow my real recorded journey across the eastern North Pacific.",
    accent: '#2f5d6e',
    distanceKm: track.distanceKm
  };

  const trackData = {
    animalId: animalId,
    points: track.points
  };

  zip.file('manifest.json', JSON.stringify(manifest, null, 2));
  zip.file(`animals/${safeId}.json`, JSON.stringify(profile, null, 2));
  zip.file(`tracks/${safeId}.json`, JSON.stringify(trackData, null, 2));
  zip.file(
    'ATTRIBUTION.txt',
    'Tracking records: Migration Watch, using openly licensed Movebank datasets.\nThis pack contains historical observations and does not represent live tracking.\n'
  );

  const fixtureDir = path.join(__dirname, 'fixtures');
  fs.mkdirSync(fixtureDir, { recursive: true });

  const buf = await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' });
  const outPath = path.join(fixtureDir, 'sample-pack.wildpack');
  fs.writeFileSync(outPath, buf);
  console.log(`Created sample fixture at: ${outPath} (${buf.length} bytes)`);

  // Also copy to android_companion_app/app/src/main/assets for tests or bundled testing
  const androidFixtureDir = path.join(__dirname, 'android_companion_app/app/src/main/assets');
  fs.mkdirSync(androidFixtureDir, { recursive: true });
  fs.writeFileSync(path.join(androidFixtureDir, 'sample-pack.wildpack'), buf);
  console.log('Copied sample fixture to Android assets');
}

createFixture();
