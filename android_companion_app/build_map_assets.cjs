const fs = require('fs');
const path = require('path');
const d3 = require('d3-geo');
const topo = require('topojson-client');
const world = require('world-atlas/countries-110m.json');
const esbuild = require('esbuild');

const MAP_W = 1000;
const MAP_H = 500;

// Natural Earth 1 projection matching geo.js
const projection = d3.geoNaturalEarth1().fitExtent(
  [
    [12, 12],
    [MAP_W - 12, MAP_H - 12],
  ],
  { type: 'Sphere' }
);

const pathGen = d3.geoPath(projection);
const land = topo.feature(world, world.objects.countries);
const landPath = pathGen(land);
const spherePath = pathGen({ type: 'Sphere' });
const graticulePath = pathGen(d3.geoGraticule10());

const outDir = path.join(__dirname, 'app/src/main/assets/map');
fs.mkdirSync(outDir, { recursive: true });

// Create source map_src.js
const mapSrcPath = path.join(__dirname, 'map_src.js');
const mapSrcContent = `
import { geoNaturalEarth1 } from 'd3-geo';

const MAP_W = 1000;
const MAP_H = 500;

const spherePath = ${JSON.stringify(spherePath)};
const graticulePath = ${JSON.stringify(graticulePath)};
const landPath = ${JSON.stringify(landPath)};

const projection = geoNaturalEarth1().fitExtent(
  [
    [12, 12],
    [MAP_W - 12, MAP_H - 12],
  ],
  { type: 'Sphere' }
);

let currentTrack = null;
let projected = null;
let progress = 0;
let isPlaying = false;
let animFrame = null;
let zoom = 1;
let panX = 0;
let panY = 0;
let isDragging = false;
let startDragX = 0;
let startDragY = 0;
let initialPinchDist = null;
let initialPinchZoom = 1;

function projectTrack(points) {
  const pts = points.map(([lon, lat, ts]) => {
    const [x, y] = projection([lon, lat]);
    return { x, y, ts };
  });
  const cum = [0];
  for (let i = 1; i < pts.length; i++) {
    const dx = pts[i].x - pts[i - 1].x;
    const dy = pts[i].y - pts[i - 1].y;
    cum.push(cum[i - 1] + Math.hypot(dx, dy));
  }
  return { pts, cum, total: cum[cum.length - 1] || 1 };
}

function fullPath(proj) {
  return proj.pts.map((p, i) => \`\${i ? 'L' : 'M'}\${p.x.toFixed(1)},\${p.y.toFixed(1)}\`).join(' ');
}

function traceAtProgress(proj, prog) {
  const { pts, cum, total } = proj;
  const target = Math.max(0, Math.min(1, prog)) * total;

  let i = 1;
  while (i < cum.length && cum[i] < target) i++;
  if (i >= cum.length) {
    const last = pts[pts.length - 1];
    return { head: last, path: fullPath(proj) };
  }

  const segLen = cum[i] - cum[i - 1] || 1;
  const t = (target - cum[i - 1]) / segLen;
  const a = pts[i - 1];
  const b = pts[i];
  const head = {
    x: a.x + (b.x - a.x) * t,
    y: a.y + (b.y - a.y) * t,
    ts: a.ts + (b.ts - a.ts) * t,
  };

  let d = \`M\${pts[0].x.toFixed(1)},\${pts[0].y.toFixed(1)}\`;
  for (let k = 1; k < i; k++) {
    d += \` L\${pts[k].x.toFixed(1)},\${pts[k].y.toFixed(1)}\`;
  }
  d += \` L\${head.x.toFixed(1)},\${head.y.toFixed(1)}\`;
  return { head, path: d };
}

function formatDate(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return String(ts);
  return d.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  });
}

function updateTransform() {
  const g = document.getElementById('map-content');
  if (g) {
    g.setAttribute('transform', \`translate(\${panX},\${panY}) scale(\${zoom})\`);
  }
}

function renderFrame() {
  if (!projected) return;
  const trace = traceAtProgress(projected, progress);
  const traveledPathEl = document.getElementById('route-traveled');
  const haloEl = document.getElementById('marker-halo');
  const dotEl = document.getElementById('marker-dot');
  const dateEl = document.getElementById('current-date');
  const sliderEl = document.getElementById('timeline-slider');
  const progressTextEl = document.getElementById('progress-text');

  if (traveledPathEl) traveledPathEl.setAttribute('d', trace.path);
  if (haloEl) {
    haloEl.setAttribute('cx', trace.head.x);
    haloEl.setAttribute('cy', trace.head.y);
  }
  if (dotEl) {
    dotEl.setAttribute('cx', trace.head.x);
    dotEl.setAttribute('cy', trace.head.y);
  }
  if (dateEl) {
    dateEl.textContent = formatDate(trace.head.ts);
  }
  if (sliderEl) {
    sliderEl.value = progress;
  }
  if (progressTextEl) {
    progressTextEl.textContent = \`\${Math.round(progress * 100)}% of recorded route\`;
  }
}

function tick() {
  if (!isPlaying) return;
  progress += 0.002;
  if (progress >= 1) {
    progress = 1;
    isPlaying = false;
    updatePlayButton();
  }
  renderFrame();
  if (isPlaying) {
    animFrame = requestAnimationFrame(tick);
  }
}

function updatePlayButton() {
  const btn = document.getElementById('btn-play');
  if (btn) {
    btn.innerHTML = isPlaying ? '&#10074;&#10074; <span>Pause</span>' : '&#9654; <span>Play</span>';
  }
}

function togglePlay() {
  if (!projected) return;
  if (progress >= 1) progress = 0;
  isPlaying = !isPlaying;
  updatePlayButton();
  if (isPlaying) {
    animFrame = requestAnimationFrame(tick);
  } else if (animFrame) {
    cancelAnimationFrame(animFrame);
  }
}

function restart() {
  if (!projected) return;
  progress = 0;
  renderFrame();
}

function setZoom(newZoom, centerX = MAP_W / 2, centerY = MAP_H / 2) {
  const clamped = Math.max(1, Math.min(8, newZoom));
  const factor = clamped / zoom;
  panX = centerX - factor * (centerX - panX);
  panY = centerY - factor * (centerY - panY);
  zoom = clamped;
  if (zoom === 1) {
    panX = 0;
    panY = 0;
  }
  updateTransform();
}

function fitRoute() {
  if (!projected || projected.pts.length === 0) return;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of projected.pts) {
    if (p.x < minX) minX = p.x;
    if (p.x > maxX) maxX = p.x;
    if (p.y < minY) minY = p.y;
    if (p.y > maxY) maxY = p.y;
  }
  const padding = 40;
  const w = (maxX - minX) || 100;
  const h = (maxY - minY) || 100;
  const zoomX = (MAP_W - padding * 2) / w;
  const zoomY = (MAP_H - padding * 2) / h;
  const newZoom = Math.max(1, Math.min(6, Math.min(zoomX, zoomY)));

  const midX = (minX + maxX) / 2;
  const midY = (minY + maxY) / 2;

  zoom = newZoom;
  panX = MAP_W / 2 - midX * zoom;
  panY = MAP_H / 2 - midY * zoom;
  updateTransform();
}

function resetWorld() {
  zoom = 1;
  panX = 0;
  panY = 0;
  updateTransform();
}

window.loadTrack = function(data) {
  if (typeof data === 'string') {
    data = JSON.parse(data);
  }
  currentTrack = data;
  if (!data.points || data.points.length === 0) return;

  projected = projectTrack(data.points);
  const accent = data.accent || '#31543d';

  const ghostEl = document.getElementById('route-ghost');
  const traveledEl = document.getElementById('route-traveled');
  const haloEl = document.getElementById('marker-halo');
  const dotEl = document.getElementById('marker-dot');
  const startEl = document.getElementById('start-date');
  const endEl = document.getElementById('end-date');

  if (ghostEl) {
    ghostEl.setAttribute('d', fullPath(projected));
    ghostEl.setAttribute('stroke', accent);
  }
  if (traveledEl) traveledEl.setAttribute('stroke', accent);
  if (haloEl) haloEl.setAttribute('fill', accent);
  if (dotEl) dotEl.setAttribute('fill', accent);

  const startTs = data.startDate || data.points[0][2];
  const endTs = data.endDate || data.points[data.points.length - 1][2];

  if (startEl) startEl.textContent = formatDate(startTs);
  if (endEl) endEl.textContent = formatDate(endTs);

  progress = 0;
  isPlaying = false;
  updatePlayButton();
  renderFrame();
  fitRoute();
};

window.addEventListener('DOMContentLoaded', () => {
  document.getElementById('path-sphere').setAttribute('d', spherePath);
  document.getElementById('path-graticule').setAttribute('d', graticulePath);
  document.getElementById('path-land').setAttribute('d', landPath);

  document.getElementById('btn-play').addEventListener('click', togglePlay);
  document.getElementById('btn-restart').addEventListener('click', restart);
  document.getElementById('btn-zoom-in').addEventListener('click', () => setZoom(zoom + 0.5));
  document.getElementById('btn-zoom-out').addEventListener('click', () => setZoom(zoom - 0.5));
  document.getElementById('btn-fit').addEventListener('click', fitRoute);
  document.getElementById('btn-reset').addEventListener('click', resetWorld);

  const slider = document.getElementById('timeline-slider');
  slider.addEventListener('input', (e) => {
    if (isPlaying) {
      isPlaying = false;
      updatePlayButton();
      if (animFrame) cancelAnimationFrame(animFrame);
    }
    progress = parseFloat(e.target.value);
    renderFrame();
  });

  // Touch and mouse pan & pinch zoom
  const svg = document.getElementById('map-svg');

  svg.addEventListener('pointerdown', (e) => {
    isDragging = true;
    startDragX = e.clientX - panX;
    startDragY = e.clientY - panY;
    svg.setPointerCapture(e.pointerId);
  });

  svg.addEventListener('pointermove', (e) => {
    if (!isDragging) return;
    panX = e.clientX - startDragX;
    panY = e.clientY - startDragY;
    updateTransform();
  });

  const stopDrag = (e) => {
    if (isDragging) {
      isDragging = false;
      try { svg.releasePointerCapture(e.pointerId); } catch(_) {}
    }
  };
  svg.addEventListener('pointerup', stopDrag);
  svg.addEventListener('pointercancel', stopDrag);

  // Wheel zoom
  svg.addEventListener('wheel', (e) => {
    e.preventDefault();
    const rect = svg.getBoundingClientRect();
    const cx = (e.clientX - rect.left) * (MAP_W / rect.width);
    const cy = (e.clientY - rect.top) * (MAP_H / rect.height);
    const delta = e.deltaY < 0 ? 0.3 : -0.3;
    setZoom(zoom + delta, cx, cy);
  }, { passive: false });

  // Check if Android injected initial data
  if (window.AndroidInterface && window.AndroidInterface.getInitialTrack) {
    try {
      const initData = window.AndroidInterface.getInitialTrack();
      if (initData) window.loadTrack(initData);
    } catch(err) {
      console.error(err);
    }
  }
});
`;

fs.writeFileSync(mapSrcPath, mapSrcContent);

// Build bundle with esbuild
esbuild.buildSync({
  entryPoints: [mapSrcPath],
  bundle: true,
  minify: true,
  outfile: path.join(outDir, 'map.js'),
  format: 'iife',
});

fs.unlinkSync(mapSrcPath);

// Write index.html and map.css
const htmlContent = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <title>Journey Map</title>
  <link rel="stylesheet" href="map.css">
  <script src="map.js" defer></script>
</head>
<body>
  <div class="map-card">
    <div class="map-toolbar">
      <div class="title-block">
        <span class="eyebrow">RECORDED JOURNEY</span>
        <strong id="current-date">—</strong>
      </div>
      <div class="map-actions">
        <button id="btn-restart" title="Restart journey">&#8634;</button>
        <button id="btn-play" class="play">&#9654; <span>Play</span></button>
        <button id="btn-zoom-in" title="Zoom in">+</button>
        <button id="btn-zoom-out" title="Zoom out">&minus;</button>
        <button id="btn-fit" title="Fit route to view">&#x26F6; Fit</button>
        <button id="btn-reset" title="World overview">&#127757;</button>
      </div>
    </div>

    <div class="map-wrap">
      <svg id="map-svg" viewBox="0 0 1000 500" preserveAspectRatio="xMidYMid meet">
        <path id="path-sphere" class="ocean" />
        <g id="map-content">
          <path id="path-graticule" class="graticule" />
          <path id="path-land" class="land" />
          <path id="route-ghost" fill="none" stroke="#2f5d6e" stroke-opacity="0.25" stroke-width="1.6" />
          <path id="route-traveled" fill="none" stroke="#2f5d6e" stroke-width="3.2" stroke-linecap="round" />
          <circle id="marker-halo" cx="-100" cy="-100" r="8" fill="#2f5d6e" opacity="0.2" />
          <circle id="marker-dot" cx="-100" cy="-100" r="4.5" fill="#2f5d6e" stroke="#fffdf8" stroke-width="2" />
        </g>
      </svg>
    </div>

    <input id="timeline-slider" class="timeline" type="range" min="0" max="1" step="0.0001" value="0" aria-label="Journey progress">
    <div class="timeline-labels">
      <span id="start-date">—</span>
      <span id="progress-text">0% of recorded route</span>
      <span id="end-date">—</span>
    </div>
  </div>
</body>
</html>`;

const cssContent = `* { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  background: #e2e7dc;
  color: #242820;
  user-select: none;
  -webkit-user-select: none;
  overflow: hidden;
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.map-card {
  padding: 12px 14px 10px;
  background: #e2e7dc;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.map-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
  gap: 6px;
}

.title-block {
  display: flex;
  flex-direction: column;
}

.eyebrow {
  color: #5d675b;
  font-size: 10px;
  font-family: monospace;
  letter-spacing: 0.1em;
  font-weight: 600;
}

.title-block strong {
  font-size: 14px;
  color: #242820;
}

.map-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.map-actions button {
  min-width: 32px;
  height: 30px;
  border: 1px solid #c3cec0;
  color: #405449;
  background: #f7f8f0;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 8px;
  touch-action: manipulation;
}

.map-actions button:active {
  background: #e4e8dd;
}

.map-actions .play {
  padding: 0 12px;
  color: #f8f6ef;
  background: #405b4a;
  border-color: #405b4a;
}

.map-actions .play span {
  margin-left: 4px;
  font-size: 11px;
}

.map-wrap {
  flex: 1;
  overflow: hidden;
  background: #dce6d8;
  border-radius: 6px;
  border: 1px solid #cdd7c8;
  position: relative;
  touch-action: none;
}

#map-svg {
  display: block;
  width: 100%;
  height: 100%;
  cursor: grab;
}

#map-svg:active {
  cursor: grabbing;
}

.ocean { fill: #dce6d8; }
.land { fill: #f5f2e9; stroke: #b7c5b4; stroke-width: 0.5; }
.graticule { fill: none; stroke: #b6c8b3; stroke-opacity: 0.55; stroke-width: 0.5; }

.timeline {
  display: block;
  width: 100%;
  margin-top: 10px;
  accent-color: #405b4a;
  height: 20px;
}

.timeline-labels {
  display: flex;
  justify-content: space-between;
  color: #687363;
  font-size: 10px;
  font-family: monospace;
  margin-top: 4px;
}
`;

fs.writeFileSync(path.join(outDir, 'index.html'), htmlContent);
fs.writeFileSync(path.join(outDir, 'map.css'), cssContent);
console.log('Map assets generated successfully in', outDir);
