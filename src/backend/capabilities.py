"""Authenticated API contract, not a claim about external OEM or provider deployment."""


def capabilities(
    principal,
    review_enabled=False,
    observations_enabled=False,
    owner_cases_enabled=False,
    photos_enabled=False,
    powertrain_enabled=False,
    measurements_enabled=False,
):
    allowed = bool(principal.vehicles)
    return {
        "schema": "road-guardian.capabilities.v1",
        "api_version": 1,
        "vehicle_access": "allowed" if allowed else "not_granted",
        "features": {
            "case_pages": allowed,
            "case_detail": allowed,
            "case_video": allowed,
            "evidence_export": allowed,
            "private_notes_read": allowed and review_enabled,
            "private_notes_write": allowed
            and review_enabled
            and "events:review" in principal.scopes,
            "sdk_observations_read": allowed and observations_enabled,
            "sdk_observations_write": allowed
            and observations_enabled
            and "events:observe" in principal.scopes,
            "owner_cases_create": allowed
            and owner_cases_enabled
            and "events:create" in principal.scopes,
            "case_photos_read": allowed and photos_enabled,
            "case_photos_export": allowed and photos_enabled,
            "case_photos_write": allowed
            and photos_enabled
            and "events:photos" in principal.scopes,
            "powertrain_read": allowed
            and powertrain_enabled
            and "events:powertrain" in principal.scopes,
            "measurement_runs_read": allowed and measurements_enabled,
            "measurement_runs_write": allowed
            and measurements_enabled
            and "events:measure" in principal.scopes,
            "measurement_runs_export": allowed and measurements_enabled,
        },
    }
