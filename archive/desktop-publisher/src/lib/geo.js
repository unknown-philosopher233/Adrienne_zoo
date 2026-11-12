/**
 * Projection + geometry helpers for the world map.
 *
 * We use d3-geo directly (rather than a map component) so we have full control
 * over two things the design needs: the progressive "tracing" of each recorded
 * path, and the exact interpolated marker position at any journey progress.
 */
import { geoNaturalEarth1, geoPath, geoGraticule10 } from 'd3-geo';
import { feature } from 'topojson-client';
import worldTopo from 'world-atlas/countries-110m.json';

// Fixed internal coordinate space; the SVG scales responsively via viewBox.
export const MAP_W = 1000;
export const MAP_H = 500;

// Natural Earth 1 gives a calm, documentary-atlas look. Fit the whole globe
// into the frame with a little breathing room.
export const projection = geoNaturalEarth1().fitExtent(
  [
    [12, 12],
    [MAP_W - 12, MAP_H - 12],
  ],
  { type: 'Sphere' }
);

export const pathGen = geoPath(projection);

// Land polygons (countries) and the surrounding sphere/graticule, projected once.
const land = feature(worldTopo, worldTopo.objects.countries);
export const landPath = pathGen(land);
export const spherePath = pathGen({ type: 'Sphere' });
export const graticulePath = pathGen(geoGraticule10());

/**
 * Project a track's [lon, lat, ts] points into screen space and precompute
 * cumulative pixel lengths, so we can interpolate a marker along the polyline
 * and slice off the "already travelled" portion cheaply on every frame.
 */
export function projectTrack(points) {
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

/** Full path string for the faint "ghost" of the complete recorded route. */
export function fullPath(projected) {
  return projected.pts.map((p, i) => `${i ? 'L' : 'M'}${p.x},${p.y}`).join(' ');
}

/**
 * Interpolate the head position at `progress` (0..1) measured along pixel
 * length, and return the SVG path of the route travelled up to that head.
 * Also returns the interpolated real timestamp at the head.
 */
export function traceAtProgress(projected, progress) {
  const { pts, cum, total } = projected;
  const target = Math.max(0, Math.min(1, progress)) * total;

  // Find the segment containing the target distance.
  let i = 1;
  while (i < cum.length && cum[i] < target) i++;
  if (i >= cum.length) {
    const last = pts[pts.length - 1];
    return { head: last, path: fullPath(projected) };
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

  let d = `M${pts[0].x},${pts[0].y}`;
  for (let k = 1; k < i; k++) d += ` L${pts[k].x},${pts[k].y}`;
  d += ` L${head.x},${head.y}`;
  return { head, path: d };
}
