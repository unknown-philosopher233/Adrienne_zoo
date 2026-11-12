"""Zoo App's Source dataset loaders for Movebank JSON bundles and Movebank CSV exports."""

from __future__ import annotations

import csv
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class RawStudy:
    id: str
    name: str
    species: str
    taxon: str
    license: str
    doi: str | None
    doi_url: str | None
    citation: str | None
    region: str | None
    blurb: str | None
    study_id: str | int | None
    sensitive_location_restricted: bool
    tracks: tuple["RawTrack", ...]


@dataclass(frozen=True)
class RawTrack:
    id: str
    points: tuple[tuple[Any, Any, Any], ...]  # (lon, lat, timestamp)
    start_date: str | None
    end_date: str | None


@dataclass(frozen=True)
class SourceDataset:
    source_name: str
    fetch_date: str | None
    data_kind: str | None
    source_path: str
    studies: tuple[RawStudy, ...]


def load_source(source_path: str | Path, csv_study_meta: dict[str, Any] | None = None) -> SourceDataset:
    """Load either a Movebank tracks.json bundle or a Movebank CSV export."""
    path = Path(source_path)
    if not path.exists():
        raise FileNotFoundError(f"Source file not found: {path}")

    suffix = path.suffix.lower()
    if suffix == ".json":
        return _load_movebank_json(path)
    if suffix == ".csv":
        return _load_movebank_csv(path, csv_study_meta or {})
    raise ValueError(f"Unsupported source file format '{path.suffix}'. Expected .json or .csv.")


def _load_movebank_json(path: Path) -> SourceDataset:
    raw = json.loads(path.read_text(encoding="utf-8"))
    studies: list[RawStudy] = []
    for s in raw.get("studies", []):
        tracks: list[RawTrack] = []
        for t in s.get("tracks", []):
            pts = tuple(
                (pt[0], pt[1], pt[2])
                for pt in t.get("points", [])
                if isinstance(pt, (list, tuple)) and len(pt) >= 3
            )
            tracks.append(
                RawTrack(
                    id=str(t["id"]),
                    points=pts,
                    start_date=t.get("startDate"),
                    end_date=t.get("endDate"),
                )
            )
        studies.append(
            RawStudy(
                id=str(s["id"]),
                name=str(s.get("name", s["id"])),
                species=str(s.get("species", "Unknown Species")),
                taxon=str(s.get("taxon", "")),
                license=str(s.get("license", "")),
                doi=s.get("doi"),
                doi_url=s.get("doiUrl"),
                citation=s.get("citation"),
                region=s.get("region"),
                blurb=s.get("blurb"),
                study_id=s.get("studyId"),
                sensitive_location_restricted=bool(s.get("sensitiveLocationRestricted", False)),
                tracks=tuple(tracks),
            )
        )
    return SourceDataset(
        source_name=str(raw.get("source", "Movebank (movebank.org)")),
        fetch_date=raw.get("fetchDate"),
        data_kind=raw.get("dataKind"),
        source_path=str(path),
        studies=tuple(studies),
    )


def _load_movebank_csv(path: Path, meta: dict[str, Any]) -> SourceDataset:
    """Parse standard Movebank CSV columns into RawStudy/RawTrack records."""
    tracks_by_individual: dict[str, list[tuple[Any, Any, Any]]] = {}
    detected_study_name = meta.get("name") or path.stem
    detected_taxon = meta.get("taxon") or ""

    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            # Skip marked outlier rows if Movebank 'visible' column is explicitly false
            visible = (row.get("visible") or "").strip().lower()
            if visible == "false":
                continue

            ind_id = (
                row.get("individual-local-identifier")
                or row.get("tag-local-identifier")
                or row.get("individual-id")
                or ""
            ).strip()
            if not ind_id:
                continue

            lon_raw = (row.get("location-long") or "").strip()
            lat_raw = (row.get("location-lat") or "").strip()
            ts_raw = (row.get("timestamp") or "").strip()
            if not lon_raw or not lat_raw or not ts_raw:
                continue

            try:
                lon = float(lon_raw)
                lat = float(lat_raw)
            except ValueError:
                continue

            if not detected_taxon and row.get("individual-taxon-canonical-name"):
                detected_taxon = row["individual-taxon-canonical-name"].strip()
            if row.get("study-name") and not meta.get("name"):
                detected_study_name = row["study-name"].strip()

            tracks_by_individual.setdefault(ind_id, []).append((lon, lat, ts_raw))

    raw_tracks = tuple(
        RawTrack(id=ind_id, points=tuple(pts), start_date=None, end_date=None)
        for ind_id, pts in sorted(tracks_by_individual.items())
    )
    study = RawStudy(
        id=str(meta.get("id", path.stem)),
        name=str(detected_study_name),
        species=str(meta.get("species", detected_taxon or "Unknown Species")),
        taxon=str(detected_taxon),
        license=str(meta.get("license", "")),
        doi=meta.get("doi"),
        doi_url=meta.get("doiUrl"),
        citation=meta.get("citation"),
        region=meta.get("region"),
        blurb=meta.get("blurb"),
        study_id=meta.get("studyId"),
        sensitive_location_restricted=bool(meta.get("sensitiveLocationRestricted", False)),
        tracks=raw_tracks,
    )
    return SourceDataset(
        source_name=str(meta.get("source", "Movebank CSV Export")),
        fetch_date=meta.get("fetchDate"),
        data_kind="Movebank CSV telemetry export",
        source_path=str(path),
        studies=(study,),
    )


def find_track(dataset: SourceDataset, study_id: str, track_id: str) -> tuple[RawStudy, RawTrack]:
    for study in dataset.studies:
        if study.id == study_id:
            for track in study.tracks:
                if track.id == track_id:
                    return study, track
            raise KeyError(f"Track '{track_id}' not found in study '{study_id}'.")
    raise KeyError(f"Study '{study_id}' not found in source '{dataset.source_path}'.")
