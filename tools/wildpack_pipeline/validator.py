"""Standalone validator and inspector for .wildpack v1 and v2 archives (Section 7.5)."""

from __future__ import annotations

import json
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .normalize import is_valid_coordinate, parse_utc_timestamp_ms
from .pack_builder import compute_archive_content_hash
from .rights import verify_study_rights


MAX_ARCHIVE_ENTRIES = 200
MAX_UNCOMPRESSED_BYTES = 50 * 1024 * 1024


@dataclass(frozen=True)
class ValidationReport:
    valid: bool
    pack_path: str
    schema_version: int | None
    pack_id: str | None
    pack_version: int | None
    content_hash: str | None
    animal_count: int
    errors: tuple[str, ...]
    warnings: tuple[str, ...]
    manifest: dict[str, Any] | None


def _find_entry(entries_map: dict[str, bytes], relative_path: str) -> bytes | None:
    normalized = relative_path.replace("\\", "/").lstrip("/")
    if normalized in entries_map:
        return entries_map[normalized]
    suffix = f"/{normalized}"
    for key, data in entries_map.items():
        if key.endswith(suffix):
            return data
    return None


def _parse_positive_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value if value >= 1 else None


def validate_pack(pack_path: str | Path) -> ValidationReport:
    path = Path(pack_path)
    errors: list[str] = []
    warnings: list[str] = []

    if not path.exists():
        return ValidationReport(
            valid=False,
            pack_path=str(path),
            schema_version=None,
            pack_id=None,
            pack_version=None,
            content_hash=None,
            animal_count=0,
            errors=(f"Pack file not found: {path}",),
            warnings=(),
            manifest=None,
        )

    entries_map: dict[str, bytes] = {}
    total_bytes = 0
    try:
        with zipfile.ZipFile(path, mode="r") as zf:
            infos = zf.infolist()
            if len(infos) > MAX_ARCHIVE_ENTRIES:
                errors.append(f"Archive contains too many entries ({len(infos)} > {MAX_ARCHIVE_ENTRIES}).")
            for info in infos:
                name = info.filename.replace("\\", "/")
                if ".." in name or name.startswith("/") or ":/" in name:
                    errors.append(f"Unsafe or invalid entry name in archive: {name}")
                    continue
                if info.is_dir():
                    continue
                data = zf.read(info.filename)
                total_bytes += len(data)
                if total_bytes > MAX_UNCOMPRESSED_BYTES:
                    errors.append(f"Uncompressed archive size exceeds {MAX_UNCOMPRESSED_BYTES} bytes.")
                    break
                entries_map[name] = data
    except Exception as exc:
        return ValidationReport(
            valid=False,
            pack_path=str(path),
            schema_version=None,
            pack_id=None,
            pack_version=None,
            content_hash=None,
            animal_count=0,
            errors=(f"Corrupted or invalid ZIP archive: {exc}",),
            warnings=(),
            manifest=None,
        )

    if errors:
        return ValidationReport(
            valid=False,
            pack_path=str(path),
            schema_version=None,
            pack_id=None,
            pack_version=None,
            content_hash=None,
            animal_count=0,
            errors=tuple(errors),
            warnings=tuple(warnings),
            manifest=None,
        )

    content_hash = compute_archive_content_hash(entries_map)

    manifest_bytes = _find_entry(entries_map, "manifest.json")
    if manifest_bytes is None:
        return ValidationReport(
            valid=False,
            pack_path=str(path),
            schema_version=None,
            pack_id=None,
            pack_version=None,
            content_hash=content_hash,
            animal_count=0,
            errors=("Missing manifest.json in archive.",),
            warnings=(),
            manifest=None,
        )

    try:
        manifest = json.loads(manifest_bytes.decode("utf-8"))
        if not isinstance(manifest, dict):
            raise ValueError("manifest.json must be a JSON object")
    except Exception as exc:
        return ValidationReport(
            valid=False,
            pack_path=str(path),
            schema_version=None,
            pack_id=None,
            pack_version=None,
            content_hash=content_hash,
            animal_count=0,
            errors=(f"Malformed manifest.json: {exc}",),
            warnings=(),
            manifest=None,
        )

    if manifest.get("format") != "wildlife-pack":
        errors.append(f"Unsupported format: {manifest.get('format')!r}. Expected 'wildlife-pack'.")

    schema_version = _parse_positive_int(manifest.get("version"))
    if schema_version not in (1, 2):
        errors.append(f"Unsupported schema version: {manifest.get('version')!r}. Expected 1 or 2.")

    raw_pack_id = manifest.get("packId")
    pack_id = raw_pack_id.strip() if isinstance(raw_pack_id, str) and raw_pack_id.strip() else None
    if schema_version == 2 and not pack_id:
        errors.append("Missing required 'packId' in version 2 manifest.")

    pack_version = _parse_positive_int(manifest.get("packVersion"))
    if schema_version == 2 and pack_version is None:
        errors.append("Missing or invalid positive integer 'packVersion' in version 2 manifest.")

    if schema_version == 2:
        if parse_utc_timestamp_ms(manifest.get("publishedAt")) is None:
            errors.append(f"Missing or invalid UTC 'publishedAt': {manifest.get('publishedAt')!r}")
        if parse_utc_timestamp_ms(manifest.get("replayStartAt")) is None:
            errors.append(f"Missing or invalid UTC 'replayStartAt': {manifest.get('replayStartAt')!r}")
    elif manifest.get("replayEnabled") is True and parse_utc_timestamp_ms(manifest.get("replayStartAt")) is None:
        errors.append("Pack declares replayEnabled=true but is missing a valid UTC 'replayStartAt'.")

    attr_bytes = _find_entry(entries_map, "ATTRIBUTION.txt")
    if attr_bytes is None or not attr_bytes.decode("utf-8", errors="replace").strip():
        if schema_version == 2:
            errors.append("Missing or empty ATTRIBUTION.txt in version 2 pack.")
        else:
            warnings.append("Missing ATTRIBUTION.txt in legacy version 1 pack.")

    animals = manifest.get("animals")
    if not isinstance(animals, list) or len(animals) == 0:
        errors.append("manifest.json must contain a non-empty 'animals' array.")
        animals = []

    seen_ids: set[str] = set()
    for idx, entry in enumerate(animals):
        if not isinstance(entry, dict):
            errors.append(f"Animal manifest entry {idx} must be an object.")
            continue
        animal_id = (entry.get("animalId") or "").strip() if isinstance(entry.get("animalId"), str) else ""
        if not animal_id:
            errors.append(f"Animal entry {idx} is missing 'animalId'.")
            continue
        if animal_id in seen_ids:
            errors.append(f"Duplicate animalId '{animal_id}' in manifest.")
            continue
        seen_ids.add(animal_id)

        profile_path = entry.get("profile") or f"animals/{animal_id}.json"
        track_path = entry.get("track") or f"tracks/{animal_id}.json"

        profile_bytes = _find_entry(entries_map, profile_path)
        if profile_bytes is None:
            errors.append(f"Missing profile file '{profile_path}' for '{animal_id}'.")
            profile_json = None
        else:
            try:
                profile_json = json.loads(profile_bytes.decode("utf-8"))
                if not isinstance(profile_json, dict):
                    raise ValueError("profile JSON must be an object")
            except Exception as exc:
                errors.append(f"Malformed profile JSON '{profile_path}' for '{animal_id}': {exc}")
                profile_json = None

        if profile_json is not None and schema_version == 2:
            verdict = verify_study_rights(
                study_id=animal_id,
                license_raw=profile_json.get("license"),
                citation=profile_json.get("sourceCitation") or profile_json.get("source"),
                doi=profile_json.get("sourceDoi"),
            )
            if not verdict.approved:
                errors.append(
                    f"Attribution/license validation failed for '{animal_id}': {'; '.join(verdict.reasons)}"
                )

        journal_path = entry.get("journal") or (profile_json.get("journal") if profile_json else None)
        if isinstance(journal_path, str) and journal_path.strip():
            journal_bytes = _find_entry(entries_map, journal_path)
            if journal_bytes is None:
                errors.append(f"Missing referenced journal file '{journal_path}' for '{animal_id}'.")
            else:
                try:
                    json.loads(journal_bytes.decode("utf-8"))
                except Exception as exc:
                    errors.append(f"Malformed journal JSON '{journal_path}' for '{animal_id}': {exc}")

        track_bytes = _find_entry(entries_map, track_path)
        if track_bytes is None:
            errors.append(f"Missing track file '{track_path}' for '{animal_id}'.")
            continue

        try:
            track_json = json.loads(track_bytes.decode("utf-8"))
        except Exception as exc:
            errors.append(f"Malformed track JSON '{track_path}' for '{animal_id}': {exc}")
            continue

        points = track_json.get("points") if isinstance(track_json, dict) else None
        if points is None and isinstance(track_json, dict):
            points = track_json.get("observations")
        if not isinstance(points, list) or len(points) == 0:
            errors.append(f"Track JSON '{track_path}' for '{animal_id}' has no observations.")
            continue

        prev_ts: int | None = None
        prev_offset: int | None = None
        for p_idx, pt in enumerate(points):
            if isinstance(pt, list):
                if len(pt) < 2:
                    errors.append(f"Observation {p_idx} for '{animal_id}' has fewer than 2 coordinates.")
                    break
                lon, lat = pt[0], pt[1]
                raw_ts = pt[2] if len(pt) >= 3 else None
                raw_offset = pt[3] if len(pt) >= 4 else None
            elif isinstance(pt, dict):
                lon = pt.get("lon", pt.get("longitude"))
                lat = pt.get("lat", pt.get("latitude"))
                raw_ts = pt.get("timestamp", pt.get("observedAt"))
                raw_offset = pt.get("replayOffsetMs")
            else:
                errors.append(f"Invalid observation format at index {p_idx} for '{animal_id}'.")
                break

            if not is_valid_coordinate(lon, lat, allow_null_island=True):
                errors.append(f"Out-of-bounds or invalid coordinate at index {p_idx} for '{animal_id}': ({lon}, {lat})")
                break

            if schema_version == 2:
                ts_ms = parse_utc_timestamp_ms(raw_ts)
                if ts_ms is None:
                    errors.append(f"Invalid or missing timestamp at index {p_idx} for '{animal_id}': {raw_ts!r}")
                    break
                if prev_ts is not None and ts_ms < prev_ts:
                    errors.append(f"Chronology violation at index {p_idx} for '{animal_id}': {raw_ts!r}")
                    break
                prev_ts = ts_ms

                if raw_offset is None or isinstance(raw_offset, bool) or not isinstance(raw_offset, int):
                    errors.append(f"Missing or non-integer replayOffsetMs at index {p_idx} for '{animal_id}'.")
                    break
                if raw_offset < 0:
                    errors.append(f"Negative replayOffsetMs at index {p_idx} for '{animal_id}': {raw_offset}")
                    break
                if prev_offset is not None and raw_offset < prev_offset:
                    errors.append(f"Non-monotonic replayOffsetMs at index {p_idx} for '{animal_id}'.")
                    break
                prev_offset = raw_offset

    return ValidationReport(
        valid=(len(errors) == 0),
        pack_path=str(path),
        schema_version=schema_version,
        pack_id=pack_id,
        pack_version=pack_version,
        content_hash=content_hash,
        animal_count=len(seen_ids),
        errors=tuple(errors),
        warnings=tuple(warnings),
        manifest=manifest,
    )
