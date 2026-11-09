/**
 * Loads the bundled, real Movebank dataset and decorates each study with the
 * accent colour used on the map and in the legend. Track records are kept as
 * shipped; only presentation metadata is added here.
 */
import data from '../data/tracks.json';

// A restrained, earthy "documentary" palette — one hue per species so the map
// can distinguish them. These are deliberately muted and desaturated to sit
// quietly on the linen canvas; the pale sage mint stays reserved for washes and
// halos, never as a track colour.
const PALETTE = [
  '#5a8f63', // sage green
  '#2f5d6e', // ocean teal
  '#a6643c', // terracotta
  '#6b7a3a', // olive
  '#7d5a8c', // muted plum
  '#c08a2d', // ochre
  '#4a6b8a', // slate blue
  '#8a4b4b', // brick
  '#3f7d6e', // pine
  '#9c7b3f', // bronze
  '#566246', // moss
  '#7a6f9b', // lavender slate
  '#b07a55', // clay tan
  '#456b5a', // deep teal-green
  '#8f6f8a', // dusty mauve
  '#705a3a', // umber
];

export const dataset = {
  ...data,
  studies: data.studies.map((s, i) => {
    const colorHex = PALETTE[i % PALETTE.length];
    return { ...s, color: colorHex, colorHex };
  }),
};

// Flat list of every individual track, each carrying a back-reference to its
// study, plus a stable composite key for selection.
export const allTracks = dataset.studies.flatMap((study) =>
  study.tracks.map((track) => ({
    key: `${study.id}:${track.id}`,
    study,
    track,
  }))
);

export function formatKm(km) {
  return `${km.toLocaleString('en-US')} km`;
}

export function formatDate(iso) {
  return new Date(iso).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}
