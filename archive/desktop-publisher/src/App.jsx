import React, { useEffect, useMemo, useState } from 'react';
import JSZip from 'jszip';
import { allTracks, formatDate, formatKm } from './lib/dataset.js';
import AnimalMap from './components/AnimalMap.jsx';

const STARTER_STUDIES = ['blue_whale', 'green_turtle', 'caribou', 'seal', 'albatross'];
const makeId = (study, track) => `${study.id}:${track.id}`;

function buildAnimals() {
  return STARTER_STUDIES.map((studyId, index) => {
    const entry = allTracks.find(({ study }) => study.id === studyId);
    const { study, track } = entry;
    return { animalId: makeId(study, track), study, track, species: study.species, scientificName: study.taxon || study.scientificName || study.species, researchId: track.id, sourceStudy: study.name, license: study.license, source: study.source || `Migration Watch / Movebank study: ${study.name}`, displayName: ['Mara', 'Nalu', 'Kestrel', 'Harbor', 'Sol'][index], description: `A real ${study.species.toLowerCase()} track preserved from the research dataset.`, intro: '', accent: study.color };
  });
}

function makeCode() { const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; const part = () => Array.from({ length: 4 }, () => alphabet[Math.floor(Math.random() * alphabet.length)]).join(''); return `WILD-${part()}-${part()}`; }

export default function App() {
  const [animals, setAnimals] = useState(buildAnimals);
  const [selectedId, setSelectedId] = useState(animals[0].animalId);
  const [query, setQuery] = useState('');
  const [species, setSpecies] = useState('All species');
  const [codes, setCodes] = useState({});
  const [notice, setNotice] = useState('');
  const selected = animals.find((animal) => animal.animalId === selectedId) || animals[0];
  const speciesOptions = ['All species', ...new Set(animals.map((animal) => animal.species))];
  const filtered = useMemo(() => animals.filter((animal) => (species === 'All species' || animal.species === species) && `${animal.displayName} ${animal.species} ${animal.researchId}`.toLowerCase().includes(query.toLowerCase())), [animals, query, species]);
  useEffect(() => { if (!filtered.some((animal) => animal.animalId === selectedId)) setSelectedId(filtered[0]?.animalId || animals[0].animalId); }, [filtered, selectedId, animals]);
  const updateSelected = (field, value) => setAnimals((current) => current.map((animal) => animal.animalId === selectedId ? { ...animal, [field]: value } : animal));
  const generateCode = () => setCodes((current) => ({ ...current, [selectedId]: [...(current[selectedId] || []), makeCode()] }));
  const copyCode = async (code) => { await navigator.clipboard?.writeText(code); setNotice(`Copied ${code}`); setTimeout(() => setNotice(''), 1800); };
  async function exportPack() {
    const zip = new JSZip(); const chosen = animals.filter((animal) => codes[animal.animalId]?.length); const exported = chosen.length ? chosen : [selected];
    const manifest = { format: 'wildlife-pack', version: 1, packId: `field-notes-${Date.now()}`, title: 'Field Notes collection', animals: [] };
    exported.forEach((animal) => { const safe = animal.animalId.replace(/[^a-z0-9]+/gi, '-').toLowerCase(); manifest.animals.push({ animalId: animal.animalId, profile: `animals/${safe}.json`, track: `tracks/${safe}.json`, activationCodes: codes[animal.animalId] || [] }); zip.file(`animals/${safe}.json`, JSON.stringify({ ...animal, study: undefined, track: undefined }, null, 2)); zip.file(`tracks/${safe}.json`, JSON.stringify({ animalId: animal.animalId, points: animal.track.points }, null, 2)); });
    zip.file('manifest.json', JSON.stringify(manifest, null, 2)); zip.file('ATTRIBUTION.txt', 'Tracking records: Migration Watch, using openly licensed Movebank datasets.\nThis pack contains historical observations and does not represent live tracking.\n');
    const blob = await zip.generateAsync({ type: 'blob' }); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'field-notes.wildpack'; link.click(); URL.revokeObjectURL(url); setNotice(`Exported ${exported.length} animal${exported.length === 1 ? '' : 's'}`); setTimeout(() => setNotice(''), 2400);
  }
  return <main className="app-shell"><header className="topbar"><div><p className="eyebrow">WINDOWS PUBLISHER / FIELD NOTES</p><h1>Make a real journey personal.</h1></div><button className="export-button" onClick={exportPack}>Export .wildpack <span>↗</span></button></header><section className="workspace"><aside className="catalog panel"><div className="panel-heading"><div><p className="eyebrow">CATALOG</p><h2>Research animals</h2></div><span className="count">{filtered.length}</span></div><div className="filters"><input aria-label="Search animals" placeholder="Search name or ID" value={query} onChange={(event) => setQuery(event.target.value)} /><select value={species} onChange={(event) => setSpecies(event.target.value)}>{speciesOptions.map((option) => <option key={option}>{option}</option>)}</select></div><div className="animal-list">{filtered.map((animal) => <button className={`animal-row ${animal.animalId === selectedId ? 'active' : ''}`} key={animal.animalId} onClick={() => setSelectedId(animal.animalId)}><span className="animal-dot" style={{ background: animal.accent }} /><span><strong>{animal.displayName}</strong><small>{animal.species}</small></span><span className="row-arrow">→</span></button>)}</div><p className="catalog-foot">{animals.length} records loaded from Migration Watch<br />Historical data · offline-ready</p></aside><section className="detail"><div className="detail-heading"><div><p className="eyebrow">ANIMAL PROFILE</p><h2>{selected.displayName}</h2><p className="subhead">{selected.species} <span>·</span> {selected.researchId}</p></div><div className="source-chip">{selected.license}</div></div><AnimalMap animal={selected} /><div className="detail-grid"><section className="info-block"><p className="eyebrow">IDENTITY</p><label>Display name<input value={selected.displayName} onChange={(event) => updateSelected('displayName', event.target.value)} /></label><label>Short description<textarea rows="3" value={selected.description} onChange={(event) => updateSelected('description', event.target.value)} /></label><label>Personal introduction <span className="optional">OPTIONAL</span><textarea rows="2" placeholder="A note for the person receiving this animal" value={selected.intro} onChange={(event) => updateSelected('intro', event.target.value)} /></label></section><section className="info-block"><div className="block-title"><p className="eyebrow">ACTIVATION CODES</p><button className="text-button" onClick={generateCode}>+ Generate code</button></div>{(codes[selectedId] || []).length === 0 ? <div className="empty-code">Generate a cosmetic code to include this animal in an export.</div> : <div className="codes">{codes[selectedId].map((code) => <button className="code" key={code} onClick={() => copyCode(code)}>{code}<span>copy</span></button>)}</div>}<div className="metadata"><div><span>RECORDED FIXES</span><strong>{selected.track.fixCount.toLocaleString()}</strong></div><div><span>JOURNEY</span><strong>{formatKm(selected.track.distanceKm || 0)}</strong></div><div><span>OBSERVED</span><strong>{formatDate(selected.track.startDate)} — {formatDate(selected.track.endDate)}</strong></div></div><p className="citation">Source study<br /><strong>{selected.sourceStudy}</strong><br />{selected.source}</p></section></div></section></section>{notice && <div className="toast">{notice}</div>}</main>;
}