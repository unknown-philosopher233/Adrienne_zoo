"""Template Field-Journal Generation."""

from __future__ import annotations

from typing import Any
from .normalize import NormalizedTrackResult


def generate_journal(animal_name: str, track: NormalizedTrackResult) -> dict[str, Any]:
    """Generate evidence-based field-journal entries based on the track's movement."""
    entries = []

    if not track.observations:
        return {"animalId": track.animal_id, "entries": []}

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

    # 2. Significant gaps
    for i, gap in enumerate(track.gaps):
        entries.append({
            "id": f"{track.animal_id}-gap-{i}",
            "replayOffsetMs": gap.start_offset_ms,
            "timestampUtc": gap.start_utc,
            "title": "Signal Lost",
            "body": f"No signal from {animal_name} for {gap.duration_hours:.1f} hours. This is common when animals move into areas without satellite coverage or spend extended time underwater.",
            "type": "SIGNAL_LOST"
        })

    # 3. Add milestone for halfway point
    if len(track.observations) > 2:
        mid_idx = len(track.observations) // 2
        mid = track.observations[mid_idx]
        entries.append({
            "id": f"{track.animal_id}-midway",
            "replayOffsetMs": mid.replay_offset_ms,
            "timestampUtc": mid.timestamp_utc,
            "title": "Midway Point",
            "body": f"{animal_name} continues the journey, navigating through the wilderness. At this point, the track is halfway complete.",
            "type": "MILESTONE",
            "location": [mid.lon, mid.lat]
        })

    # 4. End of tracking
    last = track.observations[-1]
    entries.append({
        "id": f"{track.animal_id}-end",
        "replayOffsetMs": last.replay_offset_ms,
        "timestampUtc": last.timestamp_utc,
        "title": "Journey Concludes",
        "body": f"Tracking of {animal_name} concluded on {last.timestamp_utc[:10]} after traveling an estimated {track.movement_summary.distance_km:.1f} km over {track.movement_summary.duration_days:.1f} days.",
        "type": "LAST_OBSERVATION",
        "location": [last.lon, last.lat]
    })

    # Sort entries by replayOffsetMs to maintain chronological order
    entries.sort(key=lambda e: e["replayOffsetMs"])

    return {
        "animalId": track.animal_id,
        "entries": entries
    }
