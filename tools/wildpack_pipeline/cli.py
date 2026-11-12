"""Command-line interface for the P2 historical wildlife pack pipeline."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Sequence

from .normalize import (
    DEFAULT_MAX_SPEED_BY_STUDY,
    NormalizationRules,
    normalize_track,
    summarize_track_as_dict,
)
from .pack_builder import build_pack_from_config
from .rights import verify_study_rights
from .sources import find_track, load_source
from .validator import validate_pack


def _cmd_inspect_source(args: argparse.Namespace) -> int:
    dataset = load_source(args.source_path)
    summary = {
        "source": dataset.source_name,
        "fetchDate": dataset.fetch_date,
        "dataKind": dataset.data_kind,
        "studyCount": len(dataset.studies),
        "studies": [],
    }
    for study in dataset.studies:
        verdict = verify_study_rights(
            study_id=study.id,
            license_raw=study.license,
            citation=study.citation,
            doi=study.doi,
            sensitive_location_restricted=study.sensitive_location_restricted,
        )
        summary["studies"].append(
            {
                "id": study.id,
                "species": study.species,
                "taxon": study.taxon,
                "license": study.license,
                "rightsApproved": verdict.approved,
                "rightsReasons": list(verdict.reasons),
                "doi": study.doi,
                "trackCount": len(study.tracks),
                "tracks": [
                    {
                        "id": t.id,
                        "rawPoints": len(t.points),
                        "startDate": t.start_date,
                        "endDate": t.end_date,
                    }
                    for t in study.tracks
                ],
            }
        )

    if args.json:
        print(json.dumps(summary, indent=2, ensure_ascii=False))
    else:
        print(f"Source: {dataset.source_name} (fetchDate={dataset.fetch_date})")
        print(f"Studies: {len(dataset.studies)}")
        for s in summary["studies"]:
            status = "APPROVED" if s["rightsApproved"] else f"EXCLUDED ({'; '.join(s['rightsReasons'])})"
            print(f"  - {s['id']} [{s['species']}] | {s['license']} | {status} | {s['trackCount']} tracks")
    return 0


def _cmd_verify_rights(args: argparse.Namespace) -> int:
    dataset = load_source(args.source_path)
    excluded = 0
    for study in dataset.studies:
        verdict = verify_study_rights(
            study_id=study.id,
            license_raw=study.license,
            citation=study.citation,
            doi=study.doi,
            sensitive_location_restricted=study.sensitive_location_restricted,
        )
        tag = "PASS" if verdict.approved else "FAIL"
        print(f"[{tag}] {study.id} ({study.species}) — {study.license or 'NO LICENSE'}")
        if not verdict.approved:
            excluded += 1
            for reason in verdict.reasons:
                print(f"       Reason: {reason}")
    if args.fail_on_excluded and excluded > 0:
        return 2
    return 0


def _cmd_normalize_track(args: argparse.Namespace) -> int:
    dataset = load_source(args.source_path)
    study, track = find_track(dataset, args.study, args.track)
    verdict = verify_study_rights(
        study_id=study.id,
        license_raw=study.license,
        citation=study.citation,
        doi=study.doi,
        sensitive_location_restricted=study.sensitive_location_restricted,
    )
    if not verdict.approved and not args.allow_unapproved_rights:
        print(
            f"ERROR: Study '{study.id}' failed data-rights check: {'; '.join(verdict.reasons)}",
            file=sys.stderr,
        )
        return 2

    max_speed = (
        args.max_speed
        if args.max_speed is not None
        else DEFAULT_MAX_SPEED_BY_STUDY.get(study.id, 90.0)
    )
    rules = NormalizationRules(
        max_speed_kmh=max_speed,
        max_step_km=args.max_step,
        gap_threshold_hours=args.gap_hours,
        sort_chronologically=args.sort,
        interval_start_utc=args.start_utc,
        interval_end_utc=args.end_utc,
    )
    animal_id = f"{study.id}:{track.id}"
    normalized = normalize_track(animal_id=animal_id, raw_points=track.points, rules=rules)
    payload = {
        **summarize_track_as_dict(normalized),
        "points": [obs.to_compact_array() for obs in normalized.observations],
    }

    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"Wrote normalized track to {out_path} ({len(normalized.observations)} observations)")
    else:
        print(json.dumps(payload, indent=2, ensure_ascii=False))
    return 0


def _cmd_build_pack(args: argparse.Namespace) -> int:
    result = build_pack_from_config(
        config_path=args.config_path,
        output_pack_path=args.output_pack,
        output_report_path=args.output_report,
    )
    report = validate_pack(result["pack_path"])
    if not report.valid:
        print("Built pack failed validation:", file=sys.stderr)
        for err in report.errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    br = result["build_report"]
    print(f"Built pack:   {result['pack_path']}")
    print(f"Build report: {result['report_path']}")
    print(f"Pack ID/Ver:  {br['packId']} v{br['packVersion']} (schema v{br['packSchemaVersion']})")
    print(f"Replay Start: {br['replayStartAt']}")
    print(f"Animals:      {br['animalCount']}")
    print(f"Content Hash: {br['contentHash']}")
    print(f"Archive SHA:  {br['archiveSha256']} ({br['archiveBytes']} bytes)")
    return 0


def _cmd_validate_pack(args: argparse.Namespace) -> int:
    report = validate_pack(args.pack_path)
    if args.json:
        print(json.dumps(asdict(report), indent=2, ensure_ascii=False))
    else:
        status = "VALID" if report.valid else "INVALID"
        print(f"[{status}] {report.pack_path}")
        print(f"  Schema Version: {report.schema_version}")
        print(f"  Pack ID:        {report.pack_id} (packVersion={report.pack_version})")
        print(f"  Animals:        {report.animal_count}")
        print(f"  Content Hash:   {report.content_hash}")
        for w in report.warnings:
            print(f"  WARNING: {w}")
        for e in report.errors:
            print(f"  ERROR:   {e}")
    return 0 if report.valid else 1


def _cmd_inspect_pack(args: argparse.Namespace) -> int:
    report = validate_pack(args.pack_path)
    manifest = report.manifest or {}
    info = {
        "valid": report.valid,
        "errors": list(report.errors),
        "warnings": list(report.warnings),
        "contentHash": report.content_hash,
        "manifest": manifest,
    }
    if args.json:
        print(json.dumps(info, indent=2, ensure_ascii=False))
    else:
        print(f"Pack: {manifest.get('title', 'Unknown')} ({report.pack_id}, schema v{report.schema_version})")
        print(f"Published:    {manifest.get('publishedAt', 'N/A')}")
        print(f"Replay Start: {manifest.get('replayStartAt', 'N/A (legacy unsynchronized)')}")
        print(f"Content Hash: {report.content_hash}")
        print(f"Valid:        {report.valid}")
        for a in manifest.get("animals", []):
            print(
                f"  - {a.get('animalId')} | obs={a.get('observationCount', '?')} "
                f"| durationMs={a.get('replayDurationMs', '?')} | track={a.get('track')}"
            )
    return 0 if report.valid else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="wildpack_cli",
        description="Adrienne Zoo / ZooApp historical wildlife pack processing CLI (Phase P2).",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_ins = sub.add_parser("inspect-source", help="Inspect studies, licenses, and tracks in a source dataset.")
    p_ins.add_argument("source_path")
    p_ins.add_argument("--json", action="store_true")
    p_ins.set_defaults(func=_cmd_inspect_source)

    p_rights = sub.add_parser("verify-rights", help="Audit redistribution licenses and attribution.")
    p_rights.add_argument("source_path")
    p_rights.add_argument("--fail-on-excluded", action="store_true")
    p_rights.set_defaults(func=_cmd_verify_rights)

    p_norm = sub.add_parser("normalize-track", help="Normalize and validate a single track.")
    p_norm.add_argument("source_path")
    p_norm.add_argument("--study", required=True)
    p_norm.add_argument("--track", required=True)
    p_norm.add_argument("--max-speed", type=float, default=None)
    p_norm.add_argument("--max-step", type=float, default=1000.0)
    p_norm.add_argument("--gap-hours", type=float, default=48.0)
    p_norm.add_argument("--sort", action="store_true")
    p_norm.add_argument("--start-utc", default=None)
    p_norm.add_argument("--end-utc", default=None)
    p_norm.add_argument("--allow-unapproved-rights", action="store_true")
    p_norm.add_argument("--output", default=None)
    p_norm.set_defaults(func=_cmd_normalize_track)

    p_build = sub.add_parser("build-pack", help="Build and validate a v2 .wildpack from config.")
    p_build.add_argument("config_path")
    p_build.add_argument("--output-pack", default=None)
    p_build.add_argument("--output-report", default=None)
    p_build.set_defaults(func=_cmd_build_pack)

    p_val = sub.add_parser("validate-pack", help="Validate a .wildpack v1 or v2 archive.")
    p_val.add_argument("pack_path")
    p_val.add_argument("--json", action="store_true")
    p_val.set_defaults(func=_cmd_validate_pack)

    p_ip = sub.add_parser("inspect-pack", help="Inspect metadata and animals inside a .wildpack archive.")
    p_ip.add_argument("pack_path")
    p_ip.add_argument("--json", action="store_true")
    p_ip.set_defaults(func=_cmd_inspect_pack)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)
