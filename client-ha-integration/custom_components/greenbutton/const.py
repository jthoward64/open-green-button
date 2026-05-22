"""Constants for the Open Green Button integration."""

from __future__ import annotations

from datetime import timedelta

DOMAIN = "greenbutton"

DEFAULT_SCAN_INTERVAL = timedelta(hours=6)

# The hosted proxy server. May be overridden per-config-entry for self-hosters.
DEFAULT_SERVER_BASE_URL = "https://greenbutton.opengb.org"

# Stripe-style API version this client was built against.
# Server dispatches requests to the matching handler version.
API_VERSION = "2026-05-22"

CONF_UTILITY_ID = "utility_id"
CONF_CLAIM_CODE = "claim_code"
CONF_SERVER_BASE_URL = "server_base_url"
CONF_PROXY_TOKEN = "proxy_token"  # noqa: S105 — config key, not a secret
CONF_ENCRYPTED_REFRESH_BLOB = "encrypted_refresh_blob"  # noqa: S105
CONF_SUBSCRIPTION_URI = "subscription_uri"
CONF_SCOPE = "scope"
CONF_LAST_IMPORTED = "last_imported"
