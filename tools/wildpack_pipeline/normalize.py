"""Track validation, outlier/gap analysis, normalization, and replay offset calculation."""

from __future__ import annotations

import math
import statistics
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from typing import Any


EARTH_RADIUS_KM = 6371.0

DEFAULT_MAX_SPEED_BY_STUDY = {
    "albatross": 140.0,
    "blue_whale": 60.0,
    "caribou": 70.0,
    "cuckoo": 110.0,
    "fruit_bat": 70.0,
    "godwit": 120.0,
    "goose": 110.0,
    "green_turtle": 25.0,
    "gull": 110.0,
    "osprey": 120.0,
    "seal": 40.0,
    "stork": 110.0,
}


@dataclass(frozen=True)
class NormalizationRules:
    max_speed_kmh: float = 90.0
    max_step_km: float = 1000.0
    gap_threshold_hours: float = 48.0
    sort_chronologically: bool = False
    allow_null_island: bool = False
    interval_start_utc: str | None = None
    interval_end_utc: str | None = None


@dataclass(frozen=True)
class NormalizedObservation:
    lon: float
    lat: float
    timestamp_ms: int
    timestamp_utc: str
    replay_offset_ms: int

    def to_compact_array(self) -> list[Any]:
        return [self.lon, self.lat, self.timestamp_utc, self.replay_offset_ms]


@dataclass(frozen=True)
class GapInterval:
    start_utc: str
    end_utc: str
    start_offset_ms: int
    end_offset_ms: int
    duration_hours: float


@dataclass(frozen=True)
class TrackCleaningReport:
    raw_count: int
    valid_count: int
    invalid_coord_count: int
    invalid_timestamp_count: int
    duplicates_removed: int
    outliers_removed: int
    out_of_interval_removed: int
    outlier_details: tuple[dict[str, Any], ...]


@dataclass(frozen=True)
class MovementSummary:
    observation_count: int
    first_observation_utc: str
    last_observation_utc: str
    replay_duration_ms: int
    duration_days: float
    distance_km: float
    mean_speed_kmh: float
    median_interval_hours: float
    max_gap_hours: float
    gap_count: int
    bbox: tuple[float, float, float, float]


@dataclass(frozen=True)
class NormalizedTrackResult:
    animal_id: str
    observations: tuple[NormalizedObservation, ...]
    gaps: tuple[GapInterval, ...]
    cleaning_report: TrackCleaningReport
    movement_summary: MovementSummary
    rules: NormalizationRules


def haversine_km(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    lat1_r = math.radians(lat1)
    lat2_r = math.radians(lat2)
    a = (
        math.sin(d_lat / 2.0) ** 2
        + math.cos(lat1_r) * math.cos(lat2_r) * (math.sin(d_lon / 2.0) ** 2)
    )
    a = min(1.0, max(0.0, a))
    return 2.0 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def parse_utc_timestamp_ms(raw: Any) -> int | None:
    """Parse numeric epoch millis or UTC ISO-8601 / Movebank timestamp strings to epoch ms."""
    if raw is None or isinstance(raw, bool):
        return None
    if isinstance(raw, (int, float)):
        if not math.isfinite(raw) or raw <= 0:
            return None
        return int(round(raw))
    if isinstance(raw, str):
        text = raw.strip()
        if not text:
            return None
        # Numeric string
        try:
            num = float(text)
            if math.isfinite(num) and num > 0 and text.replace(".", "", 1).isdigit():
                return int(round(num))
        except ValueError:
            pass

        # Normalize Movebank space-separated UTC timestamps and trailing Z
        normalized = text
        if "T" not in normalized and " " in normalized:
            normalized = normalized.replace(" ", "T", 1)
        if normalized.endswith("Z"):
            normalized = normalized[:-1] + "+00:00"
        elif "+" not in normalized[10:] and "-" not in normalized[10:]:
            normalized = normalized + "+00:00"
        try:
            dt = datetime.fromisoformat(normalized)
        except ValueError:
            return None
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        else:
            dt = dt.astimezone(timezone.utc)
        ms = int(round(dt.timestamp() * 1000))
        return ms if ms > 0 else None
    return None


def format_utc_iso(ms: int) -> str:
    dt = datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)
    millis = ms % 1000
    if millis == 0:
        return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{millis:03d}Z"


def is_valid_coordinate(lon: Any, lat: Any, allow_null_island: bool = False) -> bool:
    if isinstance(lon, bool) or isinstance(lat, bool):
        return False
    try:
        lon_f = float(lon)
        lat_f = float(lat)
    except (TypeError, ValueError):
        return False
    if not (math.isfinite(lon_f) and math.isfinite(lat_f)):
        return False
    if lon_f < -180.0 or lon_f > 180.0 or lat_f < -90.0 or lat_f > 90.0:
        return False
    if not allow_null_island and lon_f == 0.0 and lat_f == 0.0:
        return False
    return True


def normalize_track(
    animal_id: str,
    raw_points: list[tuple[Any, Any, Any]] | tuple[tuple[Any, Any, Any], ...],
    rules: NormalizationRules | None = None,
) -> NormalizedTrackResult:
    """Validate, deduplicate, filter outliers, compute replay offsets, and summarize a track."""
    rules = rules or NormalizationRules()
    raw_count = len(raw_points)

    interval_start_ms = (
        parse_utc_timestamp_ms(rules.interval_start_utc) if rules.interval_start_utc else None
    )
    interval_end_ms = (
        parse_utc_timestamp_ms(rules.interval_end_utc) if rules.interval_end_utc else None
    )
    if rules.interval_start_utc and interval_start_ms is None:
        raise ValueError(f"Invalid interval_start_utc: {rules.interval_start_utc}")
    if rules.interval_end_utc and interval_end_ms is None:
        raise ValueError(f"Invalid interval_end_utc: {rules.interval_end_utc}")

    invalid_coord_count = 0
    invalid_timestamp_count = 0
    out_of_interval_removed = 0
    parsed_points: list[tuple[float, float, int]] = []

    for pt in raw_points:
        if not isinstance(pt, (list, tuple)) or len(pt) < 3:
            invalid_coord_count += 1
            continue
        raw_lon, raw_lat, raw_ts = pt[0], pt[1], pt[2]
        if not is_valid_coordinate(raw_lon, raw_lat, allow_null_island=rules.allow_null_island):
            invalid_coord_count += 1
            continue
        ts_ms = parse_utc_timestamp_ms(raw_ts)
        if ts_ms is None:
            invalid_timestamp_count += 1
            continue
        if interval_start_ms is not None and ts_ms < interval_start_ms:
            out_of_interval_removed += 1
            continue
        if interval_end_ms is not None and ts_ms > interval_end_ms:
            out_of_interval_removed += 1
            continue
        parsed_points.append((round(float(raw_lon), 6), round(float(raw_lat), 6), ts_ms))

    if not parsed_points:
        raise ValueError(f"Track '{animal_id}' has 0 valid observations after coordinate/timestamp checks.")

    if rules.sort_chronologically:
        parsed_points.sort(key=lambda item: item[2])
    else:
        for i in range(1, len(parsed_points)):
            if parsed_points[i][2] < parsed_points[i - 1][2]:
                raise ValueError(
                    f"Track '{animal_id}' is not chronologically ordered at valid index {i}: "
                    f"{format_utc_iso(parsed_points[i][2])} < {format_utc_iso(parsed_points[i - 1][2])}"
                )

    # Deduplicate identical timestamps (preserve first occurrence)
    deduped: list[tuple[float, float, int]] = []
    duplicates_removed = 0
    for pt in parsed_points:
        if deduped and pt[2] == deduped[-1][2]:
            duplicates_removed += 1
            continue
        deduped.append(pt)

    # Outlier / speed-spike filtering
    cleaned: list[tuple[float, float, int]] = [deduped[0]]
    outlier_details: list[dict[str, Any]] = []

    for i in range(1, len(deduped)):
        cur = deduped[i]
        prev = cleaned[-1]
        dist_prev = haversine_km(prev[0], prev[1], cur[0], cur[1])
        dt_hours = (cur[2] - prev[2]) / 3600000.0
        speed_kmh = (dist_prev / dt_hours) if dt_hours > 0 else 0.0

        is_spike = dist_prev > rules.max_step_km or speed_kmh > rules.max_speed_kmh
        if is_spike:
            drop_current = True
            if i < len(deduped) - 1:
                nxt = deduped[i + 1]
                dist_chord = haversine_km(prev[0], prev[1], nxt[0], nxt[1])
                dist_next = haversine_km(cur[0], cur[1], nxt[0], nxt[1])
                dt_chord = (nxt[2] - prev[2]) / 3600000.0
                speed_chord = (dist_chord / dt_chord) if dt_chord > 0 else 0.0
                # Keep current only if chord fails while current->next is plausible
                if not (speed_chord <= rules.max_speed_kmh or dist_next > rules.max_step_km):
                    drop_current = False

            if drop_current:
                outlier_details.append(
                    {
                        "timestampUtc": format_utc_iso(cur[2]),
                        "lon": cur[0],
                        "lat": cur[1],
                        "stepKm": round(dist_prev, 2),
                        "impliedSpeedKmh": round(speed_kmh, 2),
                    }
                )
                continue

        cleaned.append(cur)

    if not cleaned:
        raise ValueError(f"Track '{animal_id}' has 0 observations after outlier filtering.")

    origin_ms = cleaned[0][2]
    observations: list[NormalizedObservation] = []
    for lon, lat, ts_ms in cleaned:
        offset_ms = ts_ms - origin_ms
        if offset_ms < 0:
            raise AssertionError("Replay offset cannot be negative.")
        observations.append(
            NormalizedObservation(
                lon=lon,
                lat=lat,
                timestamp_ms=ts_ms,
                timestamp_utc=format_utc_iso(ts_ms),
                replay_offset_ms=offset_ms,
            )
        )

    # Gap analysis and movement summary
    gaps: list[GapInterval] = []
    interval_hours_list: list[float] = []
    total_distance_km = 0.0
    max_gap_hours = 0.0

    for i in range(1, len(observations)):
        prev_obs = observations[i - 1]
        cur_obs = observations[i]
        step_km = haversine_km(prev_obs.lon, prev_obs.lat, cur_obs.lon, cur_obs.lat)
        total_distance_km += step_km
        dt_h = (cur_obs.timestamp_ms - prev_obs.timestamp_ms) / 3600000.0
        interval_hours_list.append(dt_h)
        if dt_h > max_gap_hours:
            max_gap_hours = dt_h
        if dt_h >= rules.gap_threshold_hours:
            gaps.append(
                GapInterval(
                    start_utc=prev_obs.timestamp_utc,
                    end_utc=cur_obs.timestamp_utc,
                    start_offset_ms=prev_obs.replay_offset_ms,
                    end_offset_ms=cur_obs.replay_offset_ms,
                    duration_hours=round(dt_h, 2),
                )
            )

    replay_duration_ms = observations[-1].replay_offset_ms
    duration_days = replay_duration_ms / 86400000.0
    duration_hours = replay_duration_ms / 3600000.0
    mean_speed_kmh = (total_distance_km / duration_hours) if duration_hours > 0 else 0.0
    median_interval_hours = statistics.median(interval_hours_list) if interval_hours_list else 0.0

    lons = [o.lon for o in observations]
    lats = [o.lat for o in observations]
    bbox = (min(lons), min(lats), max(lons), max(lats))

    cleaning_report = TrackCleaningReport(
        raw_count=raw_count,
        valid_count=len(observations),
        invalid_coord_count=invalid_coord_count,
        invalid_timestamp_count=invalid_timestamp_count,
        duplicates_removed=duplicates_removed,
        outliers_removed=len(outlier_details),
        out_of_interval_removed=out_of_interval_removed,
        outlier_details=tuple(outlier_details),
    )

    summary = MovementSummary(
        observation_count=len(observations),
        first_observation_utc=observations[0].timestamp_utc,
        last_observation_utc=observations[-1].timestamp_utc,
        replay_duration_ms=replay_duration_ms,
        duration_days=round(duration_days, 2),
        distance_km=round(total_distance_km, 2),
        mean_speed_kmh=round(mean_speed_kmh, 2),
        median_interval_hours=round(median_interval_hours, 2),
        max_gap_hours=round(max_gap_hours, 2),
        gap_count=len(gaps),
        bbox=bbox,
    )

    return NormalizedTrackResult(
        animal_id=animal_id,
        observations=tuple(observations),
        gaps=tuple(gaps),
        cleaning_report=cleaning_report,
        movement_summary=summary,
        rules=rules,
    )


def summarize_track_as_dict(result: NormalizedTrackResult) -> dict[str, Any]:
    return {
        "animalId": result.animal_id,
        "cleaning": asdict(result.cleaning_report),
        "summary": asdict(result.movement_summary),
        "gaps": [asdict(g) for g in result.gaps],
        "rules": asdict(result.rules),
    }
