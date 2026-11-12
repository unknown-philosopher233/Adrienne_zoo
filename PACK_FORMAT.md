# Wildpack Format Specification (Version 1)

A `.wildpack` file is a ZIP archive containing wildlife tracking records, animal profiles, activation codes, and attribution data consumed by the Adrienne Zoo Android companion for modular offline content distribution.

## Archive Structure

```
sample-pack.wildpack
├── manifest.json
├── ATTRIBUTION.txt
├── animals/
│   └── <safe-id>.json
├── tracks/
│   └── <safe-id>.json
└── images/ (optional)
    └── <safe-id>.png
```

## `manifest.json`

The root descriptor must be valid JSON:

```json
{
  "format": "wildlife-pack",
  "version": 1,
  "packId": "field-notes-sample-2026",
  "title": "Field Notes Sample Pack",
  "animals": [
    {
      "animalId": "blue_whale:2008CA-Bmu-00830",
      "profile": "animals/blue-whale.json",
      "track": "tracks/blue-whale.json",
      "activationCodes": [
        "WILD-B7K4-M2Q9"
      ]
    }
  ]
}
```

### Manifest Fields

- `format`: String, must be `"wildlife-pack"`.
- `version`: Integer, currently `1`.
- `packId`: Unique identifier string for the pack.
- `title`: Human-readable collection title.
- `animals`: Array of animal entries:
  - `animalId`: Unique research identifier combining study ID and track ID (`<studyId>:<trackId>`).
  - `profile`: Relative path to the animal profile JSON inside the archive.
  - `track`: Relative path to the tracking coordinates JSON inside the archive.
  - `activationCodes`: Array of uppercase alphanumeric activation strings formatted as `WILD-XXXX-XXXX`.

## Animal Profile JSON (`animals/<safe-id>.json`)

Contains presentation details and scientific attribution:

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
  "intro": "Hello, I'm Mara! Follow my real recorded journey.",
  "accent": "#2f5d6e",
  "distanceKm": 48723,
  "image": "images/mara.png"
}
```

## Track JSON (`tracks/<safe-id>.json`)

Contains real GPS/satellite observation points:

```json
{
  "animalId": "blue_whale:2008CA-Bmu-00830",
  "points": [
    [-120.068, 34.121, 1217029500000],
    [-120.102, 34.145, 1217036700000]
  ]
}
```

### Coordinate Order
`[longitude, latitude, timestamp]`
- Longitude: Number between `-180` and `+180`.
- Latitude: Number between `-90` and `+90`.
- Timestamp: Milliseconds since epoch or ISO 8601 string.

## Compatibility Fixture

A sample fixture is provided at `fixtures/sample-pack.wildpack` with activation code `WILD-B7K4-M2Q9`.
