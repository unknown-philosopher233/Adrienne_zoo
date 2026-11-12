# Wildpack Format Specification

A `.wildpack` file is a ZIP archive containing wildlife tracking records, animal profiles, optional activation codes, optional field-journal entries, and attribution data consumed by the Adrienne Zoo Android companion for offline-first content distribution.

---

## Schema Versions

- **Version 1 (Legacy):** Self-contained offline packs without synchronized replay timing. Preserved for backward compatibility. Missing `replayStartAt` is allowed in v1 and is **never** silently interpreted as a synchronized replay pack.
- **Version 2 (Replay-Enabled):** Synchronized historical replay packs. Requires `packId`, `packVersion`, `publishedAt` (UTC), and `replayStartAt` (UTC), plus chronological observation timestamps and replay offsets.

---

## Archive Structure

```
sample-pack.wildpack
├── manifest.json
├── ATTRIBUTION.txt
├── animals/
│   └── <safe-id>.json
├── tracks/
│   └── <safe-id>.json
├── journals/ (optional)
│   └── <safe-id>.json
└── images/ (optional)
    └── <safe-id>.png
```

---

## `manifest.json`

### Version 2 (Replay-Enabled)

```json
{
  "format": "wildlife-pack",
  "version": 2,
  "packId": "starter-pack-1",
  "packVersion": 1,
  "title": "Starter Pack 1",
  "publishedAt": "2026-11-12T00:00:00Z",
  "replayStartAt": "2026-11-12T00:00:00Z",
  "animals": [
    {
      "animalId": "blue_whale:2008CA-Bmu-00830",
      "timelineId": "blue_whale:2008CA-Bmu-00830",
      "segmentOrder": 0,
      "profile": "animals/blue-whale.json",
      "track": "tracks/blue-whale.json",
      "journal": "journals/blue-whale.json",
      "activationCodes": [
        "WILD-B7K4-M2Q9"
      ]
    }
  ]
}
```

### Mandatory Manifest Fields

- `format`: String, must be `"wildlife-pack"`.
- `version`: Integer (`1` or `2`).
- `packId`: Stable unique identifier string for the pack (mandatory in v2).
- `packVersion`: Positive integer release version (mandatory in v2). Same `(packId, packVersion)` with different content hash is rejected to preserve release immutability.
- `title`: Human-readable collection title.
- `publishedAt`: UTC ISO-8601 timestamp or epoch millis (mandatory in v2).
- `replayStartAt`: Absolute UTC ISO-8601 timestamp or epoch millis anchoring synchronized replay (mandatory in v2; never inferred from local installation time).
- `animals`: Array of animal entries:
  - `animalId`: Unique research identifier (`<studyId>:<trackId>`). Duplicate `animalId` values in a single manifest are rejected.
  - `timelineId`: Optional stable timeline identifier (defaults to `animalId`).
  - `segmentOrder`: Optional non-negative segment index (defaults to `0`).
  - `profile`: Relative path to the animal profile JSON inside the archive.
  - `track`: Relative path to the tracking coordinates JSON inside the archive.
  - `journal`: Optional relative path to pre-generated field-journal entries JSON. Validated for existence and JSON syntax on import.
  - `activationCodes`: Optional array of uppercase alphanumeric activation strings (`WILD-XXXX-XXXX`).

---

## Animal Profile JSON (`animals/<safe-id>.json`)

```json
{
  "animalId": "blue_whale:2008CA-Bmu-00830",
  "species": "Blue Whale",
  "scientificName": "Balaenoptera musculus",
  "researchId": "2008CA-Bmu-00830",
  "sourceStudy": "Behavioural estimation of blue whale movements in the Northeast Pacific",
  "license": "CC-BY",
  "source": "Migration Watch / Movebank study: ...",
  "displayName": "Mara",
  "description": "A real blue whale track preserved from the research dataset.",
  "intro": "Hello, I'm Mara! Follow my real recorded journey across the eastern North Pacific.",
  "accent": "#2f5d6e",
  "distanceKm": 8939,
  "image": "images/mara.png"
}
```

---

## Track JSON (`tracks/<safe-id>.json`)

Contains normalized historical observations ordered chronologically:

```json
{
  "animalId": "blue_whale:2008CA-Bmu-00830",
  "points": [
    [-120.068, 34.121, "2008-07-25T12:00:00Z", 0],
    [-120.102, 34.145, "2008-07-25T14:00:00Z", 7200000]
  ]
}
```

### Coordinate & Timing Order
- Version 1: `[longitude, latitude, originalTimestamp]`
- Version 2: `[longitude, latitude, originalTimestamp, replayOffsetMs]` (or normalized observation object with `lon`, `lat`, `timestamp`, `replayOffsetMs`)
- Longitude: `-180` to `+180`
- Latitude: `-90` to `+90`
- `originalTimestamp`: Original research observation time (ISO-8601 UTC or epoch ms), preserved independently from replay offsets.
- `replayOffsetMs`: Non-negative elapsed milliseconds from the timeline origin, validated for non-decreasing chronological order.

---

## Compatibility Fixture

A v1 compatibility sample fixture is provided at `fixtures/sample-pack.wildpack` with activation code `WILD-B7K4-M2Q9`.
