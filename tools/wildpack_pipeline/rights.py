"""Data rights and redistribution license verification (Section 5.3 & 16)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable


APPROVED_LICENSES = {
    "CC0 1.0 Universal": "CC0-1.0",
    "CC0-1.0": "CC0-1.0",
    "CC0": "CC0-1.0",
    "CC-BY 4.0": "CC-BY-4.0",
    "CC BY 4.0": "CC-BY-4.0",
    "Attribution 4.0 International": "CC-BY-4.0",
    "CC-BY-4.0": "CC-BY-4.0",
}

RESTRICTED_LICENSE_MARKERS = (
    "noncommercial",
    "nc",
    "nd",
    "all rights reserved",
    "proprietary",
    "restricted",
    "unknown",
)


@dataclass(frozen=True)
class RightsVerdict:
    study_id: str
    license_raw: str
    normalized_license: str | None
    approved: bool
    reasons: tuple[str, ...]


def verify_study_rights(
    study_id: str,
    license_raw: str | None,
    citation: str | None = None,
    doi: str | None = None,
    sensitive_location_restricted: bool = False,
    require_citation: bool = True,
) -> RightsVerdict:
    """Verify whether a wildlife study may be redistributed in an open-source pack."""
    reasons: list[str] = []
    cleaned_license = (license_raw or "").strip()

    if sensitive_location_restricted:
        reasons.append("Study is flagged as location-sensitive and cannot be redistributed.")

    normalized_license: str | None = None
    if not cleaned_license:
        reasons.append("Missing dataset redistribution license.")
    else:
        normalized_license = APPROVED_LICENSES.get(cleaned_license)
        if normalized_license is None:
            lowered = cleaned_license.lower()
            if any(marker in lowered for marker in RESTRICTED_LICENSE_MARKERS):
                reasons.append(
                    f"Restricted license '{cleaned_license}' is not eligible for unrestricted open-source redistribution."
                )
            else:
                reasons.append(
                    f"Unrecognized or unapproved redistribution license '{cleaned_license}'."
                )

    if require_citation and not (citation and citation.strip()) and not (doi and doi.strip()):
        reasons.append("Missing attribution citation and DOI.")

    return RightsVerdict(
        study_id=study_id,
        license_raw=cleaned_license,
        normalized_license=normalized_license,
        approved=(len(reasons) == 0),
        reasons=tuple(reasons),
    )


def verify_studies_rights(studies: Iterable[dict]) -> list[RightsVerdict]:
    """Evaluate rights for a collection of source study metadata dictionaries."""
    verdicts: list[RightsVerdict] = []
    for study in studies:
        verdicts.append(
            verify_study_rights(
                study_id=str(study.get("id", "unknown")),
                license_raw=study.get("license"),
                citation=study.get("citation"),
                doi=study.get("doi"),
                sensitive_location_restricted=bool(study.get("sensitiveLocationRestricted", False)),
            )
        )
    return verdicts
