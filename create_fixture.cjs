const fs = require('fs');
const path = require('path');
const JSZip = require('jszip');

let tracksPath = path.resolve(__dirname, 'data/tracks.json');
if (!fs.existsSync(tracksPath)) {
  tracksPath = path.resolve(__dirname, 'src/data/tracks.json');
}
const d = JSON.parse(fs.readFileSync(tracksPath, 'utf8'));

function haversine(lon1, lat1, lon2, lat2) {
  const R = 6371; // km
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180) * Math.cos(lat2*Math.PI/180) * Math.sin(dLon/2)**2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

function cleanTrackPoints(points, maxSpeedKmh = 60) {
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

async function createFixture() {
  const zip = new JSZip();

  const study = d.studies.find(s => s.id === 'blue_whale');
  const track = study.tracks[0];
  const animalId = `${study.id}:${track.id}`;
  const safeId = 'blue-whale';
  const code = 'WILD-B7K4-M2Q9';

  const cleanedPoints = cleanTrackPoints(track.points, 60);

  let realDistKm = 0;
  for (let i = 1; i < cleanedPoints.length; i++) {
    realDistKm += haversine(cleanedPoints[i-1][0], cleanedPoints[i-1][1], cleanedPoints[i][0], cleanedPoints[i][1]);
  }
  realDistKm = Math.round(realDistKm);

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
    distanceKm: realDistKm
  };

  const trackData = {
    animalId: animalId,
    points: cleanedPoints
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

  const androidFixtureDir = path.join(__dirname, 'android_companion_app/app/src/main/assets');
  fs.mkdirSync(androidFixtureDir, { recursive: true });
  fs.writeFileSync(path.join(androidFixtureDir, 'sample-pack.wildpack'), buf);
  console.log('Copied sample fixture to Android assets');
}

createFixture();
