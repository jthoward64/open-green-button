# Burlington Hydro (Ontario, Canada)

Burlington Hydro is the first targeted utility for Open Green Button.

## Registration

URLs to supply to Burlington's Green Button Support team:

- **Redirect URI**: `https://greenbutton.<your-domain>/connect/burlington_hydro/callback`
- **NotificationURI**: `https://greenbutton.<your-domain>/notify/burlington_hydro`
- **App name**: Open Green Button
- **Contact**: see project repository
- **Logo / branding**: see `branding/` in repo root

## Scope

ESPI scope template (function blocks for usage + billing data, sub-hourly intervals, historical depth):

```
FB=1_3_4_5_8_13_14_18_19_34_35_39_51;IntervalDuration=900_3600;BlockDuration=daily;HistoryLength=34128000;SubscriptionFrequency=daily;AccountCollection=2
```

Burlington may grant less than requested (e.g. a shorter `HistoryLength`). The proxy persists the *granted* scope from the token response and the HA client surfaces it via the dashboard.

## Pre-submission readiness check

Before sending the URLs to Burlington GB Support, run the readiness check against the live
deployment — it exercises the endpoints their reviewer will probe and flags anything broken:

```sh
scripts/utility-readiness-check.sh https://api.opengreenbutton.org burlington_hydro
```

Checks: DNS resolution, TLS cert validity, `/health` + `/ready`, landing page renders, security
headers, OAuth callback handles missing / hostile / unknown-state inputs without 5xx or stack
trace leaks, NotificationURI accepts POST and rejects GET, OAuth start redirects with all five
required query params (`response_type`, `client_id`, `redirect_uri`, `scope`, `state`), the
redirect_uri matches what's been registered, and unknown claim codes return 410 Gone.

Exits non-zero if anything fails. WARNs are informational. The script is safe to share with
the utility's review team if they want to see what we tested.

## Test lab

Burlington routes test-lab onboarding through their Green Button Support partner. They configure the application in their test environment using the URLs above, then coordinate a session with us to walk through OAuth + first data fetch.

The minimum bar for the test session: OAuth completes against their sandbox, and our server can fetch one `IntervalBlock` from the granted subscription.

## Notes

- Geographic region for Fly.io: `yyz` (Toronto) — closest to Burlington customers and the Burlington Hydro servers.
- Burlington supplies electricity primarily; the integration also models natural gas / water but those depend on what the utility actually serves.
