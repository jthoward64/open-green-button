# Burlington Hydro (Ontario, Canada)

Burlington Hydro is the first targeted utility for Open Green Button.

## Registration

URLs to supply to Burlington's Green Button Support team:

- **Redirect URI**: `https://api.opengreenbutton.org/connect/burlington_hydro/callback`
- **NotificationURI**: `https://api.opengreenbutton.org/notify/burlington_hydro`
- **App name**: Open Green Button
- **Contact**: see project repository
- **Logo / branding**: see `branding/` in repo root

## Scope

ESPI scope we request:

```
FB=1_3_4_5_7_8_10_11_13_14_15_16_18_19_31_32_35_37_39_51;IntervalDuration=900_3600;BlockDuration=Monthly;HistoryLength=94608000;SubscriptionFrequency=Daily;AccountCollection=5
```

Function block breakdown:

| Group | FBs | Grants |
|---|---|---|
| Infrastructure | 1, 3, 13, 14, 31 | Common, Connect My Data, Security/Privacy, both legacy + modern OAuth |
| Electricity | 4, 5, 7, 8 | Interval Metering, Interval Electricity, Net Metering (solar export), Forward/Reverse Metering |
| Other fuels | 10, 11 | Gas, Water — utility grants whichever they support |
| Billing | 15, 16 | Usage Summary, Usage Summary with Cost (powers HA's cost feature) |
| Multi-meter | 18 | Multiple Usage Points (so we can read elec + gas + water together) |
| Updates | 19, 39 | Partial Update Data, PUSH Model (NotificationURI we register) |
| REST API | 32, 37 | Resource Level REST (UsagePoint/MeterReading/IntervalBlock), Query Parameters (`since=` filtering) |
| Bulk | 35 | REST for Bulk (one-shot subscription dump for initial backfill) |
| Customer ref | 51 | Retail Customer Common — lets the utility match the auth to a customer record |

Burlington may grant less than requested — a customer who only has electric service won't get the gas/water FBs, and Burlington may cap `HistoryLength` to less than 36 months. The proxy persists the **granted** scope from the token response (`tokens.scope`, falling back to the requested scope if the utility omits it — handles both pre-negotiated and request-time-negotiated auth styles). The HA client surfaces what was actually granted in its diagnostics.

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
