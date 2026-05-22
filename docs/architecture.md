# Architecture

Open Green Button is a two-component system: a hosted Kotlin/Ktor proxy server and a Home Assistant custom integration.

## Stateless invariant

The hosted server holds **zero per-user durable state**. The only data it persists across restarts is global config:

- Per-utility OAuth `client_id` / `client_secret` (shared across all users of a utility, injected via env / `fly secrets`)
- An AES-GCM key for encrypting refresh-token blobs
- An HMAC pepper for deriving `proxy_token`s

Per-user data lives only on Home Assistant. Every utility API call flows HA → server → utility → server → HA, with the encrypted refresh token blob travelling in the request body each time. The server decrypts on receipt, exchanges with the utility, returns normalized JSON, and forgets.

The server's ephemeral in-memory state (Caffeine caches with ≤10 minute TTL) holds OAuth CSRF state during the callback window, claim codes during the pickup window, rate-limit buckets keyed by `sha256(proxy_token)[:16]`, and a ring buffer of the last N requests per client for the dashboard.

## Authentication handoff (claim code flow)

Because the utility's OAuth `redirect_uri` is fixed on our server (HA is not internet-reachable), we need to ship tokens from the server's callback handler back to HA. The mechanism is a one-time **claim code**:

1. User clicks an authorize link in HA's config flow → `https://greenbutton.<domain>/connect/<utility>/start`
2. Server generates CSRF `state`, redirects to the utility's authorize URL
3. User logs into utility, consents, utility redirects to `https://greenbutton.<domain>/connect/<utility>/callback`
4. Server exchanges code for `access_token` + `refresh_token`
5. Server encrypts `{refresh_token, subscription_uri, utility_id, scope}` with AES-GCM into an opaque blob
6. Server generates a claim code (random base32, 10-minute TTL, single-use), keyed in Caffeine to the encrypted blob
7. Server renders an HTML page showing the claim code
8. User pastes claim code into HA's config flow
9. HA `POST /claim/{code}` → server returns `{encrypted_refresh_blob, proxy_token, subscription_uri, utility_id, scope, current_api_version}`. The Caffeine entry is removed atomically (single-use enforced).
10. HA stores entry data; the claim code is now spent.

`proxy_token` is derived as `HMAC-SHA256(server_pepper, refresh_token || utility_id)`. The server never persists it — on each subsequent proxy call, the server decrypts the blob from the request body, recomputes the HMAC, and constant-time compares against the `Authorization: Bearer ...` header. Mismatch → 401.

## Refresh-token rotation

The biggest threat to the stateless invariant. See the [implementation plan](../README.md) for the full discussion. V1 strategy: every `/proxy/*` response includes `new_encrypted_refresh_blob?` whenever the utility rotated the refresh token; HA's API layer writes the new blob to `entry.data` *before* processing the data payload. A `refresh_token_rotations_total` metric is recorded for monitoring.

## API versioning

No `/api/v1/` URL prefix. Clients send `OpenGB-Api-Version: YYYY-MM-DD` and the server dispatches to the matching handler. When breaking changes happen, the version date bumps and the old handler stays available for a deprecation window. The HA client embeds its API version as a constant.
