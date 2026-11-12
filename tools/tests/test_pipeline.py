"""Automated tests for the Phase P2 historical wildlife data pipeline."""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TOOLS_DIR = REPO_ROOT / "tools"
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from wildpack_pipeline.normalize import (  # noqa: E402
    NormalizationRules,
    normalize_track,
    parse_utc_timestamp_ms,
)
from wildpack_pipeline.pack_builder import build_pack_from_config  # noqa: E402
from wildpack_pipeline.rights import verify_study_rights  # noqa: E402
from wildpack_pipeline.sources import find_track, load_source  # noqa: E402
from wildpack_pipeline.validator import validate_pack  # noqa: E402


class RightsVerificationTests(unittest.TestCase):
    def test_approves_cc0_and_cc_by_with_citation(self) -> None:
        v_cc0 = verify_study_rights(
            study_id="blue_whale",
            license_raw="CC0 1.0 Universal",
            doi="10.5441/001/1.5ph88fk2",
        )
        self.assertTrue(v_cc0.approved)
        self.assertEqual(v_cc0.normalized_license, "CC0-1.0")

        v_ccby = verify_study_rights(
            study_id="study_x",
            license_raw="CC-BY 4.0",
            citation="Author et al. (2025)",
        )
        self.assertTrue(v_ccby.approved)
        self.assertEqual(v_ccby.normalized_license, "CC-BY-4.0")

    def test_rejects_noncommercial_missing_license_or_sensitive_locations(self) -> None:
        v_nc = verify_study_rights(
            study_id="eagle",
            license_raw="Attribution-NonCommercial 4.0 International",
            doi="10.5441/001/1.704",
        )
        self.assertFalse(v_nc.approved)

        v_missing = verify_study_rights(
            study_id="no_lic",
            license_raw="",
            doi="10.1234/abc",
        )
        self.assertFalse(v_missing.approved)

        v_sensitive = verify_study_rights(
            study_id="rhino",
            license_raw="CC0 1.0 Universal",
            doi="10.1234/rhino",
            sensitive_location_restricted=True,
        )
        self.assertFalse(v_sensitive.approved)


class NormalizationTests(unittest.TestCase):
    def test_rejects_invalid_coords_dedupes_timestamps_and_calculates_offsets(self) -> None:
        raw_points = [
            (-120.0, 34.0, "2020-01-01T00:00:00Z"),
            (-120.05, 34.02, "2020-01-01T00:00:00Z"),  # duplicate timestamp
            (185.0, 34.0, "2020-01-01T06:00:00Z"),  # invalid lon
            (-120.1, -95.0, "2020-01-01T06:00:00Z"),  # invalid lat
            (0.0, 0.0, "2020-01-01T06:00:00Z"),  # null island placeholder
            (-120.1, 34.05, "not-a-timestamp"),  # invalid timestamp
            (-120.15, 34.08, "2020-01-01T12:00:00Z"),
            (-120.20, 34.12, "2020-01-04T12:00:00Z"),  # 72h gap
        ]
        res = normalize_track(
            "test:animal",
            raw_points,
            NormalizationRules(max_speed_kmh=60.0, gap_threshold_hours=48.0),
        )
        self.assertEqual(len(res.observations), 3)
        self.assertEqual(res.observations[0].replay_offset_ms, 0)
        self.assertEqual(res.observations[1].replay_offset_ms, 12 * 3600 * 1000)
        self.assertEqual(res.observations[2].replay_offset_ms, 84 * 3600 * 1000)
        self.assertEqual(res.cleaning_report.invalid_coord_count, 3)
        self.assertEqual(res.cleaning_report.invalid_timestamp_count, 1)
        self.assertEqual(res.cleaning_report.duplicates_removed, 1)
        self.assertEqual(len(res.gaps), 1)
        self.assertEqual(res.gaps[0].duration_hours, 72.0)

    def test_rejects_out_of_order_timestamps_unless_sort_enabled(self) -> None:
        raw_points = [
            (-120.0, 34.0, "2020-01-02T00:00:00Z"),
            (-120.1, 34.1, "2020-01-01T00:00:00Z"),
        ]
        with self.assertRaises(ValueError):
            normalize_track("test:unsorted", raw_points, NormalizationRules(sort_chronologically=False))

        sorted_res = normalize_track(
            "test:unsorted",
            raw_points,
            NormalizationRules(sort_chronologically=True),
        )
        self.assertEqual(sorted_res.observations[0].timestamp_utc, "2020-01-01T00:00:00Z")
        self.assertEqual(sorted_res.observations[1].replay_offset_ms, 86400000)

    def test_filters_speed_spike_outliers_from_real_blue_whale_track(self) -> None:
        dataset = load_source(REPO_ROOT / "data" / "tracks.json")
        _, whale = find_track(dataset, "blue_whale", "2008CA-Bmu-00830")
        res = normalize_track(
            "blue_whale:2008CA-Bmu-00830",
            whale.points,
            NormalizationRules(max_speed_kmh=60.0),
        )
        self.assertEqual(res.cleaning_report.raw_count, 130)
        self.assertEqual(res.cleaning_report.outliers_removed, 3)
        self.assertEqual(len(res.observations), 127)
        self.assertEqual(res.observations[0].replay_offset_ms, 0)

    def test_movebank_csv_ingestion(self) -> None:
        csv_content = (
            "timestamp,location-long,location-lat,visible,individual-local-identifier,individual-taxon-canonical-name,study-name\n"
            "2021-05-01 00:00:00.000,-122.4,37.8,true,IND_1,Zalophus californianus,Test Coastal Study\n"
            "2021-05-01 01:00:00.000,-10.0,10.0,false,IND_1,Zalophus californianus,Test Coastal Study\n"
            "2021-05-01 06:00:00.000,-122.5,37.85,true,IND_1,Zalophus californianus,Test Coastal Study\n"
        )
        with tempfile.TemporaryDirectory() as tmp:
            csv_path = Path(tmp) / "movebank_sample.csv"
            csv_path.write_text(csv_content, encoding="utf-8")
            ds = load_source(
                csv_path,
                csv_study_meta={
                    "id": "coastal_test",
                    "license": "CC0 1.0 Universal",
                    "citation": "Test Citation",
                },
            )
            study, track = find_track(ds, "coastal_test", "IND_1")
            self.assertEqual(study.taxon, "Zalophus californianus")
            self.assertEqual(len(track.points), 2)
            res = normalize_track("coastal_test:IND_1", track.points)
            self.assertEqual(res.observations[0].timestamp_utc, "2021-05-01T00:00:00Z")
            self.assertEqual(res.observations[1].replay_offset_ms, 6 * 3600 * 1000)

    def test_parse_utc_timestamp_formats(self) -> None:
        ms_iso = parse_utc_timestamp_ms("2026-11-12T00:00:00Z")
        ms_space = parse_utc_timestamp_ms("2026-11-12 00:00:00")
        self.assertEqual(ms_iso, 1794441600000)
        self.assertEqual(ms_space, 1794441600000)
        self.assertIsNone(parse_utc_timestamp_ms("invalid-date"))
        self.assertIsNone(parse_utc_timestamp_ms(0))


class PackBuildAndValidationTests(unittest.TestCase):
    def test_starter_pack_1_build_is_byte_reproducible_and_valid(self) -> None:
        cfg_path = TOOLS_DIR / "configs" / "starter_pack_1.json"
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            p1 = tmp_dir / "build1.wildpack"
            r1 = tmp_dir / "build1.json"
            p2 = tmp_dir / "build2.wildpack"
            r2 = tmp_dir / "build2.json"

            res1 = build_pack_from_config(cfg_path, output_pack_path=p1, output_report_path=r1, repo_root=REPO_ROOT)
            res2 = build_pack_from_config(cfg_path, output_pack_path=p2, output_report_path=r2, repo_root=REPO_ROOT)

            self.assertEqual(p1.read_bytes(), p2.read_bytes())
            self.assertEqual(
                hashlib.sha256(p1.read_bytes()).hexdigest(),
                res1["build_report"]["archiveSha256"],
            )
            self.assertEqual(res1["build_report"]["contentHash"], res2["build_report"]["contentHash"])

            v_report = validate_pack(p1)
            self.assertTrue(v_report.valid, msg=f"Errors: {v_report.errors}")
            self.assertEqual(v_report.schema_version, 2)
            self.assertEqual(v_report.pack_id, "starter-pack-1")
            self.assertEqual(v_report.pack_version, 1)
            self.assertEqual(v_report.animal_count, 5)
            self.assertEqual(v_report.content_hash, res1["build_report"]["contentHash"])

    def test_build_refuses_restricted_noncommercial_study(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            cfg = {
                "packId": "blocked-pack",
                "packVersion": 1,
                "title": "Blocked Pack",
                "publishedAt": "2026-11-12T00:00:00Z",
                "replayStartAt": "2026-11-12T00:00:00Z",
                "sourcePath": "data/tracks.json",
                "animals": [
                    {
                        "studyId": "eagle",
                        "trackId": "HY14_Richardson",
                        "displayName": "Eagle",
                    }
                ],
            }
            cfg_file = tmp_dir / "blocked.json"
            cfg_file.write_text(json.dumps(cfg), encoding="utf-8")
            with self.assertRaises(PermissionError):
                build_pack_from_config(
                    cfg_file,
                    output_pack_path=tmp_dir / "blocked.wildpack",
                    repo_root=REPO_ROOT,
                )

    def test_validator_rejects_missing_replay_start_and_non_monotonic_offsets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            bad_pack = Path(tmp) / "bad.wildpack"
            manifest = {
                "format": "wildlife-pack",
                "version": 2,
                "packId": "bad-pack",
                "packVersion": 1,
                "publishedAt": "2026-11-12T00:00:00Z",
                # Missing replayStartAt
                "animals": [
                    {
                        "animalId": "test:1",
                        "profile": "animals/a.json",
                        "track": "tracks/a.json",
                    }
                ],
            }
            profile = {
                "animalId": "test:1",
                "species": "Blue Whale",
                "license": "CC0 1.0 Universal",
                "sourceCitation": "Test citation",
            }
            track = {
                "animalId": "test:1",
                "points": [
                    [-120.0, 34.0, "2020-01-01T00:00:00Z", 1000],
                    [-120.1, 34.1, "2020-01-01T01:00:00Z", 500],  # non-monotonic offset
                ],
            }
            with zipfile.ZipFile(bad_pack, "w") as zf:
                zf.writestr("manifest.json", json.dumps(manifest))
                zf.writestr("ATTRIBUTION.txt", "Test attribution")
                zf.writestr("animals/a.json", json.dumps(profile))
                zf.writestr("tracks/a.json", json.dumps(track))

            report = validate_pack(bad_pack)
            self.assertFalse(report.valid)
            combined = " ".join(report.errors)
            self.assertIn("replayStartAt", combined)
            self.assertIn("Non-monotonic replayOffsetMs", combined)

    def test_validator_accepts_legacy_v1_fixture(self) -> None:
        fixture = REPO_ROOT / "fixtures" / "sample-pack.wildpack"
        report = validate_pack(fixture)
        self.assertTrue(report.valid, msg=f"Errors: {report.errors}")
        self.assertEqual(report.schema_version, 1)


if __name__ == "__main__":
    unittest.main()
