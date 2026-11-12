"""Deterministic .wildpack v2 builder and reproducible build report generator."""

from __future__ import annotations

import hashlib
import json
import re
import zipfile
from dataclasses import asdict
from pathlib import Path
from typing import Any

from . import DEFAULT_ZIP_DATE_TIME, PACK_SCHEMA_VERSION, PIPELINE_VERSION
from .normalize import (
    DEFAULT_MAX_SPEED_BY_STUDY,
    NormalizationRules,
    format_utc_iso,
    normalize_track,
    parse_utc_timestamp_ms,
)
from .rights import verify_study_rights
from .sources import SourceDataset, find_track, load_source
from .journal import generate_journal


def sanitize_id(animal_id: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_-]", "_", animal_id)


def compute_archive_content_hash(entries_map: dict[str, bytes]) -> str:
    """Match Android PackImporter.computeArchiveContentHash byte-for-byte."""
    digest = hashlib.sha256()
    for key in sorted(entries_map.keys()):
        digest.update(key.encode("utf-8"))
        digest.update(b"\x00")
        digest.update(entries_map[key])
        digest.update(b"\x00")
    return digest.hexdigest()


def _json_bytes(obj: Any) -> bytes:
    return (json.dumps(obj, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def _write_deterministic_zip(output_path: Path, entries_map: dict[str, bytes]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_path, mode="w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for arcname in sorted(entries_map.keys()):
            info = zipfile.ZipInfo(filename=arcname, date_time=DEFAULT_ZIP_DATE_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o644 & 0xFFFF) << 16
            zf.writestr(info, entries_map[arcname])


def build_pack_from_config(
    config_path: str | Path,
    output_pack_path: str | Path | None = None,
    output_report_path: str | Path | None = None,
    repo_root: str | Path | None = None,
) -> dict[str, Any]:
    """Execute the full P2 pipeline from config -> validated v2 .wildpack + build report."""
    cfg_path = Path(config_path).resolve()
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    base_dir = Path(repo_root).resolve() if repo_root else cfg_path.parent.parent.parent

    pack_id = (cfg.get("packId") or "").strip()
    if not pack_id:
        raise ValueError("Pack config must specify a non-empty 'packId'.")
    pack_version = int(cfg.get("packVersion", 0))
    if pack_version < 1:
        raise ValueError("Pack config must specify positive integer 'packVersion'.")
    title = (cfg.get("title") or "").strip() or pack_id

    published_raw = cfg.get("publishedAt")
    published_ms = parse_utc_timestamp_ms(published_raw)
    if published_ms is None:
        raise ValueError(f"Pack config 'publishedAt' is missing or invalid UTC timestamp: {published_raw!r}")
    published_utc = format_utc_iso(published_ms)

    replay_start_raw = cfg.get("replayStartAt")
    replay_start_ms = parse_utc_timestamp_ms(replay_start_raw)
    if replay_start_ms is None:
        raise ValueError(f"Pack config 'replayStartAt' is missing or invalid UTC timestamp: {replay_start_raw!r}")
    replay_start_utc = format_utc_iso(replay_start_ms)

    source_rel = cfg.get("sourcePath") or "data/tracks.json"
    source_path = (base_dir / source_rel).resolve()
    dataset: SourceDataset = load_source(source_path)

    default_rules_cfg = cfg.get("defaultNormalization") or {}
    animal_specs = cfg.get("animals") or []
    if not animal_specs:
        raise ValueError("Pack config 'animals' array cannot be empty.")

    seen_animal_ids: set[str] = set()
    entries_map: dict[str, bytes] = {}
    manifest_animals: list[dict[str, Any]] = []
    animal_reports: list[dict[str, Any]] = []
    attribution_blocks: list[str] = []
    seen_studies_for_attr: set[str] = set()

    for spec in animal_specs:
        study_id = spec["studyId"]
        track_id = spec["trackId"]
        study, raw_track = find_track(dataset, study_id, track_id)

        verdict = verify_study_rights(
            study_id=study.id,
            license_raw=study.license,
            citation=study.citation,
            doi=study.doi,
            sensitive_location_restricted=study.sensitive_location_restricted,
        )
        if not verdict.approved:
            raise PermissionError(
                f"Study '{study.id}' failed data-rights verification: {'; '.join(verdict.reasons)}"
            )

        animal_id = spec.get("animalId") or f"{study.id}:{raw_track.id}"
        if animal_id in seen_animal_ids:
            raise ValueError(f"Duplicate animalId in config: '{animal_id}'")
        seen_animal_ids.add(animal_id)

        safe_slug = spec.get("slug") or sanitize_id(animal_id).lower()
        timeline_id = spec.get("timelineId") or animal_id
        segment_order = int(spec.get("segmentOrder", 0))

        norm_cfg = {**default_rules_cfg, **(spec.get("normalization") or {})}
        rules = NormalizationRules(
            max_speed_kmh=float(
                norm_cfg.get("maxSpeedKmh", DEFAULT_MAX_SPEED_BY_STUDY.get(study.id, 90.0))
            ),
            max_step_km=float(norm_cfg.get("maxStepKm", 1000.0)),
            gap_threshold_hours=float(norm_cfg.get("gapThresholdHours", 48.0)),
            sort_chronologically=bool(norm_cfg.get("sortChronologically", False)),
            allow_null_island=bool(norm_cfg.get("allowNullIsland", False)),
            interval_start_utc=norm_cfg.get("intervalStartUtc"),
            interval_end_utc=norm_cfg.get("intervalEndUtc"),
        )

        normalized = normalize_track(animal_id=animal_id, raw_points=raw_track.points, rules=rules)

        profile_rel = f"animals/{safe_slug}.json"
        track_rel = f"tracks/{safe_slug}.json"
        journal_rel = f"journals/{safe_slug}.json"

        display_name = spec.get("displayName") or raw_track.id
        description = spec.get("description") or (
            f"A real {study.species.lower()} journey recorded by wildlife researchers."
        )
        intro = spec.get("intro") or (
            f"Follow {display_name}, a {study.species.lower()} tracked across {study.region or 'the wild'}."
        )
        accent = spec.get("accent") or "#2f5d6e"

        profile_json = {
            "animalId": animal_id,
            "timelineId": timeline_id,
            "species": study.species,
            "scientificName": study.taxon or study.species,
            "researchId": raw_track.id,
            "sourceStudy": study.name,
            "sourceDoi": study.doi,
            "sourceCitation": study.citation,
            "license": verdict.normalized_license or study.license,
            "source": f"Movebank ({study.doi or study.name})",
            "region": study.region,
            "displayName": display_name,
            "description": description,
            "intro": intro,
            "accent": accent,
            "firstObservation": normalized.movement_summary.first_observation_utc,
            "lastObservation": normalized.movement_summary.last_observation_utc,
            "replayDurationMs": normalized.movement_summary.replay_duration_ms,
            "observationCount": normalized.movement_summary.observation_count,
            "distanceKm": normalized.movement_summary.distance_km,
            "movementSummary": asdict(normalized.movement_summary),
            "gapIntervals": [asdict(g) for g in normalized.gaps],
            "journal": journal_rel,
        }

        track_json = {
            "animalId": animal_id,
            "timelineId": timeline_id,
            "schemaVersion": PACK_SCHEMA_VERSION,
            "points": [obs.to_compact_array() for obs in normalized.observations],
        }

        journal_json = generate_journal(display_name, normalized)

        entries_map[profile_rel] = _json_bytes(profile_json)
        entries_map[track_rel] = _json_bytes(track_json)
        entries_map[journal_rel] = _json_bytes(journal_json)

        manifest_entry: dict[str, Any] = {
            "animalId": animal_id,
            "timelineId": timeline_id,
            "segmentOrder": segment_order,
            "profile": profile_rel,
            "track": track_rel,
            "journal": journal_rel,
            "firstObservation": normalized.movement_summary.first_observation_utc,
            "lastObservation": normalized.movement_summary.last_observation_utc,
            "replayDurationMs": normalized.movement_summary.replay_duration_ms,
            "observationCount": normalized.movement_summary.observation_count,
        }
        activation_codes = spec.get("activationCodes")
        if activation_codes:
            manifest_entry["activationCodes"] = [str(c).strip().upper() for c in activation_codes if str(c).strip()]

        manifest_animals.append(manifest_entry)

        if study.id not in seen_studies_for_attr:
            seen_studies_for_attr.add(study.id)
            attribution_blocks.append(
                "\n".join(
                    [
                        f"Study: {study.name} ({study.species} / {study.taxon})",
                        f"License: {study.license}",
                        f"DOI: {study.doi or 'N/A'}",
                        f"Citation: {study.citation or 'N/A'}",
                    ]
                )
            )

        animal_reports.append(
            {
                "animalId": animal_id,
                "displayName": display_name,
                "studyId": study.id,
                "trackId": raw_track.id,
                "license": study.license,
                "doi": study.doi,
                "normalizationRules": asdict(rules),
                "cleaning": asdict(normalized.cleaning_report),
                "movementSummary": asdict(normalized.movement_summary),
                "gaps": [asdict(g) for g in normalized.gaps],
            }
        )

    manifest_json = {
        "format": "wildlife-pack",
        "version": PACK_SCHEMA_VERSION,
        "packId": pack_id,
        "packVersion": pack_version,
        "title": title,
        "publishedAt": published_utc,
        "replayStartAt": replay_start_utc,
        "pipelineVersion": PIPELINE_VERSION,
        "sourceDataset": {
            "source": dataset.source_name,
            "fetchDate": dataset.fetch_date,
            "dataKind": dataset.data_kind,
        },
        "animals": manifest_animals,
    }
    entries_map["manifest.json"] = _json_bytes(manifest_json)

    attribution_text = "\n".join(
        [
            f"{title} ({pack_id} v{pack_version})",
            "Historical wildlife tracking observations presented as a time-released journey.",
            "Locations shown are historical research observations and NOT live animal positions.",
            "",
            "--- Source Studies & Licenses ---",
            "\n\n".join(attribution_blocks),
            "",
        ]
    )
    entries_map["ATTRIBUTION.txt"] = attribution_text.encode("utf-8")

    content_hash = compute_archive_content_hash(entries_map)

    out_pack = Path(output_pack_path) if output_pack_path else (base_dir / (cfg.get("outputPack") or f"packs/{pack_id}.wildpack"))
    if not out_pack.is_absolute():
        out_pack = (base_dir / out_pack).resolve()
    _write_deterministic_zip(out_pack, entries_map)

    archive_bytes = out_pack.read_bytes()
    archive_sha256 = hashlib.sha256(archive_bytes).hexdigest()

    # Record excluded studies from the same source bundle for audit transparency
    excluded_studies = []
    for s in dataset.studies:
        v = verify_study_rights(
            study_id=s.id,
            license_raw=s.license,
            citation=s.citation,
            doi=s.doi,
            sensitive_location_restricted=s.sensitive_location_restricted,
        )
        if not v.approved:
            excluded_studies.append(
                {
                    "studyId": s.id,
                    "species": s.species,
                    "license": s.license,
                    "reasons": list(v.reasons),
                }
            )

    build_report = {
        "pipelineVersion": PIPELINE_VERSION,
        "packSchemaVersion": PACK_SCHEMA_VERSION,
        "packId": pack_id,
        "packVersion": pack_version,
        "title": title,
        "publishedAt": published_utc,
        "replayStartAt": replay_start_utc,
        "sourceDataset": {
            "path": cfg.get("sourcePath") or "data/tracks.json",
            "source": dataset.source_name,
            "fetchDate": dataset.fetch_date,
        },
        "outputPack": out_pack.name,
        "archiveBytes": len(archive_bytes),
        "archiveSha256": archive_sha256,
        "contentHash": content_hash,
        "animalCount": len(animal_reports),
        "animals": animal_reports,
        "excludedSourceStudies": excluded_studies,
    }

    out_report = (
        Path(output_report_path)
        if output_report_path
        else out_pack.with_suffix(".build-report.json")
    )
    if not out_report.is_absolute():
        out_report = (base_dir / out_report).resolve()
    out_report.parent.mkdir(parents=True, exist_ok=True)
    out_report.write_bytes(_json_bytes(build_report))

    return {
        "pack_path": str(out_pack),
        "report_path": str(out_report),
        "build_report": build_report,
    }
