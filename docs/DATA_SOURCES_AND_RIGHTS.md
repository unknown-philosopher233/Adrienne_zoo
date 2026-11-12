# Historical Wildlife Data Sources & Rights Evaluation (P2)

This document records the Phase P2 source evaluation, redistribution rights audit, and initial Starter Pack 1 selection for **ZooApp** (Adrienne Zoo).

## 1. Primary Source: Movebank (`movebank.org`)

- **Role:** Primary historical wildlife tracking provider (`data/tracks.json` + Movebank CSV exports).
- **Observation Semantics:** Primarily timestamped animal-borne GPS and Argos/Doppler telemetry fixes (`longitude`, `latitude`, UTC `timestamp`, individual `researchId`, study DOI/citation).
- **Redistribution Principle:** Public visibility on Movebank does **not** imply redistribution permission. Every study must declare an explicit open license permitting redistribution (e.g., `CC0 1.0 Universal` or `CC-BY 4.0` with full citation/DOI preservation).

## 2. Candidate Secondary Source: Motus Wildlife Tracking System

- **Role:** Evaluated as a candidate future source for P5+ adapters.
- **Observation Semantics Difference:** Motus records **receiver-station radio detections** (detections occur only when a tagged animal passes within range of a fixed terrestrial/coastal receiving tower), **not** continuous GPS fixes.
- **Architectural Implication:** Future Motus adapters must represent observations as receiver-station detection events with station coordinates and large detection-gap intervals, and must not interpolate continuous travel paths across multi-day unmonitored gaps (consistent with Architecture Directive Section 4.1 & 5.2).
- **Status:** Deferred from Starter Pack 1; Movebank CC0 studies are used for P2.

## 3. Rights Audit of Bundled Source (`data/tracks.json`, fetchDate `2026-06-27`)

| Study ID | Species | DOI | License | Redistribution Status | Notes |
| --- | --- | --- | --- | --- | --- |
| `albatross` | Waved Albatross (*Phoebastria irrorata*) | `10.5441/001/1.3hp3s250` | `CC0 1.0 Universal` | **Approved** | Clean 84–147 day marine/coastal tracks |
| `stork` | White Stork (*Ciconia ciconia*) | `10.5441/001/1.ck04mn78` | `CC0 1.0 Universal` | **Approved** | Multi-year tracks; large gaps in downsampled subset |
| `osprey` | Osprey (*Pandion haliaetus*) | `10.5441/001/1.sv6335t3` | `CC0 1.0 Universal` | **Approved** | Multi-year; requires jump filtering on Argos outliers |
| `gull` | Lesser Black-backed Gull (*Larus fuscus*) | `10.5441/001/1.q986rc29` | `CC0 1.0 Universal` | **Approved** | Multi-year; large seasonal gaps |
| `godwit` | Black-tailed Godwit (*Limosa limosa*) | `10.5441/001/1.m3b75054` | `CC0 1.0 Universal` | **Approved** | `Bordeaux` track is 113 days, 0 jumps, max gap 58h |
| `cuckoo` | African Cuckoo (*Cuculus gularis*) | `10.5441/001/1.b800b7c3` | `CC0 1.0 Universal` | **Approved** | Multi-year; multi-month dry-season gaps |
| `goose` | White-fronted Goose (*Anser albifrons*) | `10.5441/001/1.kk38017f` | `CC0 1.0 Universal` | **Approved** | Multi-year; seasonal gaps |
| `eagle` | Bald Eagle (*Haliaeetus leucocephalus*) | `10.5441/001/1.704` | `Attribution-NonCommercial 4.0 International` | **EXCLUDED** | `CC-BY-NC 4.0` restricts commercial/open-source downstream redistribution |
| `caribou` | Caribou (*Rangifer tarandus*) | `10.5441/001/1.p5bn656k` | `CC0 1.0 Universal` | **Approved** | Low observation density (2151 days across 127 fixes) |
| `green_turtle` | Green Sea Turtle (*Chelonia mydas*) | `10.5441/001/1.313` | `CC0 1.0 Universal` | **Approved** | 222-day Indian Ocean foraging/migration track |
| `blue_whale` | Blue Whale (*Balaenoptera musculus*) | `10.5441/001/1.5ph88fk2` | `CC0 1.0 Universal` | **Approved** | 110-day NE Pacific track after 4 Argos spike outliers cleaned |
| `seal` | California Sea Lion (*Zalophus californianus*) | `10.5441/001/1.hm5nk220` | `CC0 1.0 Universal` | **Approved** | 223-day California Current track, 0 jumps, max gap 42h |
| `fruit_bat` | Straw-coloured Fruit Bat (*Eidolon helvum*) | `10.5441/001/1.62s17b4v` | `CC0 1.0 Universal` | **Approved** | High-density 5-day nocturnal commuting track |

## 4. Starter Pack 1 (`starter-pack-1`) Selection Rationale

Selected 5 CC0 individuals prioritizing observation density, geographic diversity, clear attribution, and distinct journey durations (97–223 days):

1. **Mara** — `blue_whale:2008CA-Bmu-00830` (Blue Whale, Eastern North Pacific, ~110 days)
2. **Harbor** — `seal:seal 2004025` (California Sea Lion, California Current / NE Pacific, ~223 days)
3. **Nalu** — `green_turtle:52189` (Green Sea Turtle, Chagos Archipelago / Western Indian Ocean, ~222 days)
4. **Sol** — `albatross:1163-1163` (Waved Albatross, Galápagos ↔ Peru & Ecuador coast, ~97 days)
5. **Bordeaux** — `godwit:Bordeaux` (Black-tailed Godwit, Europe ↔ West Africa flyway, ~113 days)

All 5 tracks are licensed under **CC0 1.0 Universal** and retain full Movebank study DOIs, citations, and original UTC observation timestamps.
