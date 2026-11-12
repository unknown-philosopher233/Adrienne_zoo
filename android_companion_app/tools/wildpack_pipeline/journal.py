"""Field Journal Generator."""

from __future__ import annotations

from typing import Any
from .normalize import NormalizedTrackResult

def generate_journal(animal_name: str, track: NormalizedTrackResult) -> dict[str, Any]:
    entries = []

    # 1. Start of tracking
    first = track.observations[0]
    entries.append({
        "id": f"{track.animal_id}-start",
        "replayOffsetMs": first.replay_offset_ms,
        "timestampUtc": first.timestamp_utc,
        "title": "Journey Begins",
        "body": f"Tracking of {animal_name} started on {first.timestamp_utc[:10]}.",
        "type": "FIRST_OBSERVATION",
        "location": [first.lon, first.lat]
    })

    # 2. Add gaps as entries
    for i, gap in enumerate(track.gaps):
        entries.append({
            "id": f"{track.animal_id}-gap-{i}",
            "replayOffsetMs": gap.start_offset_ms,
            "timestampUtc": gap.start_utc,
            "title": "Signal Lost",
            "body": f"No signal from {animal_name} for {gap.duration_hours:.1f} hours.",
            "type": "SIGNAL_LOST"
        })

    # 3. Add end of tracking
    last = track.observations[-1]
    entries.append({
        "id": f"{track.animal_id}-end",
        "replayOffsetMs": last.replay_offset_ms,
        "timestampUtc": last.timestamp_utc,
        "title": "Journey Ends",
        "body": f"Tracking of {animal_name} concluded on {last.timestamp_utc[:10]} after traveling {track.movement_summary.distance_km:.1f} km over {track.movement_summary.duration_days:.1f} days.",
        "type": "LAST_OBSERVATION",
        "location": [last.lon, last.lat]
    })

    # Sort entries by replayOffsetMs
    entries.sort(key=lambda e: e["replayOffsetMs"])

    return {
        "animalId": track.animal_id,
        "entries": entries
    }
