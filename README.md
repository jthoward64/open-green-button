# Open Green Button

An open-source [Green Button](https://www.greenbuttondata.org/) (NAESB ESPI) third-party application that bridges utility energy data into [Home Assistant](https://www.home-assistant.io/).

The hosted server is a **stateless OAuth proxy**: utilities require a stable public callback URL, but your data never lives on our server. Refresh tokens are stored encrypted on your Home Assistant instance, and every request flows through the proxy carrying the token from your side.

## Status

🚧 **Live!** Burlington Hydro (Ontario, Canada).
🚧 **Registration in progress** London Hydro (Ontario, Canada).
🚧 **Registration in progress** Niagara Peninsula Energy (Ontario, Canada).
🚧 **Registration in progress** Pacific Gas & Electric (California).

### New Utility Support

Request a new utility here:

https://github.com/rocketraman/open-green-button/issues/new?template=new-utility-request.md

#### Managed by London Hydro

Burlington Hydro Green Button support is live, but implementation and verification is actually provided by London Hydro.
London Hydro also runs Green Button support for the following utilities, so approval for these utilities will be easier than usual, as London Hydro has already reviewed the system and approved it.

* ELK Energy
* Enwin Utilities
* Festival Hydro
* London Hydro (registration in progress)
* Newmarket Tay
* Niagara Peninsula Energy (registration in progress)
* Oakville Hydro
* Oshawa Power

If you use one of these utilities, use the new utility issue template to request it, and note in the issue that it is a London Hydro-managed utility.

## Components

- `server/` — Kotlin/Ktor proxy server (deployed to Fly.io with scale-to-zero)
- `docs/` — Architecture, deployment, per-utility notes
- `branding/` — Logo and brand assets

The **Home Assistant custom integration** lives in its own repository so HACS validation finds the canonical `custom_components/` + `hacs.json` layout at the repo root: [rocketraman/open-green-button-homeassistant](https://github.com/rocketraman/open-green-button-homeassistant).

## Privacy

The hosted server holds **no per-user durable state**. Per-utility OAuth client credentials are configured globally; every other piece of state (your refresh token, your usage data) lives only on your Home Assistant instance.

## Community

Discuss the add-on, ask questions, and share feedback on the Home Assistant community forum:

https://community.home-assistant.io/t/utility-energy-data-integration-via-green-button-connect/1016031

## Support

Open Green Button is free to use. If it saves you time or you'd like to help keep it maintained and hosted (there's a small Fly.io bill and ongoing time spent adding new utilities and keeping up with Home Assistant changes), donations are welcome.

**Suggested: $5 / month** — roughly enough to cover hosting plus a contribution toward maintenance time. Anything above that funds new utility integrations.

- [Sponsor on GitHub](https://github.com/sponsors/rocketraman)
- [Buy Me a Coffee](https://www.buymeacoffee.com/rocketraman)

## License

[MIT](LICENSE)
