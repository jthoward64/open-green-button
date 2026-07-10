# Kentucky Utilities (Kentucky, USA)

Kentucky Utilities (KU) is part of **LG&E and KU Energy** (Louisville Gas and Electric & Kentucky
Utilities), owned by **PPL Corporation**. Their Green Button Connect (GBC — "Connect My Data")
service runs on the **My Meter** platform (`mymeter.lge-ku.com` for OAuth, `services.mymeter.co`
for the ESPI resource API), the same SavageData/My Meter family as the Milton Hydro sandbox.

## Provisioned GBC endpoints

| Item | Value |
|---|---|
| Authorization endpoint | `https://mymeter.lge-ku.com/OAuthServer/authorize` |
| Token endpoint | `https://mymeter.lge-ku.com/OAuthServer/token` |
| Authorization server URI | `https://mymeter.lge-ku.com/OAuthServer/` |
| Resource endpoint (ESPI 1_1) | `https://services.mymeter.co/resourceapi/238/GBC/espi/1_1/resource` |
| Bulk request URI | `https://services.mymeter.co/resourceapi/238/GBC/espi/1_1/resource/Batch/Bulk/*` |
| Client name | `Open Green Button` |
| Client Id issued at | 2026-06-24 23:39:11 |

`238` is our tenant/application number in the My Meter resource API — it's baked into every resource
and bulk URL. The per-customer `Subscription`/`UsagePoint` resource URIs come back in the token
response and live under this same base.

### Registered fields (read back from ApplicationInformation)

Confirmed by GETing the ESPI `ApplicationInformation` resource with the registration access token
(`onboardFetchAppInfo`, or a direct `curl` of the Registration Client URI). Notes on what My Meter
stored vs. what we submitted:

- **`grant_types`**: `authorization_code`, `refresh_token`, and `client_credentials`.
- **ESPI enums** (numeric codes in the resource):
  - `thirdPartyApplicationType = 1` → **Web**, and `thirdPartyApplicationUse = 1` → **Energy
    Management**.
  - `thirdPartyApplicationStatus = 1` (the third-party-side status we submitted — "Development") and
    `dataCustodianApplicationStatus = 2` (the DC-side status). Combined with the `Enabled: True`
    from the onboarding email the app is active; if a customer ever can't authorize, that
    `dataCustodianApplicationStatus` value is the first thing to raise with My Meter.
- `thirdPartyApplicationDescription` got cut off at ~255 chars, so the registered copy ends
  mid-sentence ("…energy re").

### Secrets

KU's `client_secret_expires_at` is `0` — the secret **never expires**, unlike the Milton Hydro
sandbox secret. So there's no scheduled rotation to track.

## Config (`utilities.conf`)

```hocon
id = "kentucky_utilities"
displayName = "Kentucky Utilities"
authorizeUrl = "https://mymeter.lge-ku.com/OAuthServer/authorize"
tokenUrl = "https://mymeter.lge-ku.com/OAuthServer/token"
clientId = "PLACEHOLDER"
clientId = ${?OPENGB_UTILITY_KENTUCKY_UTILITIES_CLIENTID}
clientSecret = "placeholder"
clientSecret = ${?OPENGB_UTILITY_KENTUCKY_UTILITIES_CLIENTSECRET}
defaultScope = "FB=1_3_4_5_15_16_32_37_39;IntervalDuration=900_3600;BlockDuration=Monthly;SubscriptionFrequency=Daily;HistoryLength=94608000"
initialHistory = "2y"
notificationPath = "/notify/kentucky_utilities"
tokenAuthStyle = "HTTP_BASIC"
```

> **Verify mTLS.** `services.mymeter.co` is the SavageData/My Meter platform, which for the Milton
> Hydro sandbox required a **non-self-signed leaf client certificate** for the resource API (a
> per-utility `clientAuth` override rather than the default self-signed cert). LG&E&KU's onboarding
> email didn't mention a client cert, so confirm against the resource endpoint whether the default
> `clientAuth` works or KU needs its own `clientAuth { keystoreBase64 = …; keystorePassword = … }`
> block before first data fetch.

## Activation checklist

1. Fill the real `clientId`/`clientSecret` via the env vars above (Fly secrets in prod, `.env`
   locally):
   ```sh
   fly secrets set \
     OPENGB_UTILITY_KENTUCKY_UTILITIES_CLIENTID="…" \
     OPENGB_UTILITY_KENTUCKY_UTILITIES_CLIENTSECRET="…"
   ```
2. Confirm `tokenAuthStyle` (`HTTP_BASIC` matches the SavageData/My Meter platform; flip to
   `FORM_BODY` only if the token endpoint rejects Basic).
3. Resolve the mTLS question above.
4. Enable the entry (uncomment / add to the `utilities` array) and deploy.
5. Run the readiness check below, then do a live OAuth + one-resource fetch against a real customer
   (see customer onboarding).

## Registration (submitted & approved)

Values registered with LG&E&KU's GBC 3PV form (host = `OPENGB_PUBLIC_BASE_URL`):

- **Redirect URI**: `https://api.opengreenbutton.org/connect/kentucky_utilities/callback`
- **NotificationURI**: `https://api.opengreenbutton.org/notify/kentucky_utilities`
- **App name**: Open Green Button
- **Logo / branding**: `branding/` in repo root

## Scope

Requested ESPI scope:

```
FB=1_3_4_5_15_16_32_37_39;IntervalDuration=900_3600;BlockDuration=Monthly;SubscriptionFrequency=Daily;HistoryLength=94608000
```

Function blocks: **1, 3, 4, 5** (KU-required — Common, ConnectMyData, IntervalMetering,
IntervalElectricMetering), **15, 16** (usage summary + cost, powers HA's cost feature), **32, 37**
(resource-level REST + `since=` query, to fetch data), **39** (PUSH model, for the NotificationURI).

KU quirks learned during registration:

- `HistoryLength` is **required** even though the form doesn't advertise it. Omitting it fails with
  `HistoryLength required`.
- Un-advertised preferences (`AccountCollection`) and multi-fuel / multi-meter FBs were dropped —
  KU's validator rejects scope values it didn't advertise (same behaviour as Burlington's harness).
- Minimal fallback if the additive blocks are ever rejected:
  `FB=1_3_4_5;IntervalDuration=900_3600;BlockDuration=Monthly;HistoryLength=94608000`.

## Customer onboarding (important — non-obvious)

LG&E&KU's flow notes that the customer needs a My Meter **local** account, which is **separate from
their regular "My Account" login**.

1. **Request a registration code.** The customer emails `MyMeter@lge-ku.com` (or uses the
   "Feedback" link on the My Meter site) asking for their My Meter registration code.
2. **Create a local account.** On the My Meter site, "Create an Account" using the registration
   code. **The email for this local account must NOT be the same as their My Account primary
   email**.

Note: Steps 1 and 2 are their recommendation, but I found that it is possible to simply log into My
Meter from  KU's website and invite myself under another email address.

3. **Authorize us (the 3PV).** From our connect flow, the customer signs in at My Meter, chooses
   what to share (specific period, bulk, or subscription — monthly/daily), and authorizes. My Meter
   then issues the customer token to us; **we only receive it after the customer finishes
   authenticating.** All available usage + billing data (VEE and raw readings) becomes downloadable.
4. **Revocation.** Customers can stop sharing anytime from either the My Meter site or our side.

