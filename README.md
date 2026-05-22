# Open Green Button

An open-source [Green Button](https://www.greenbuttondata.org/) (NAESB ESPI) third-party application that bridges utility energy data into [Home Assistant](https://www.home-assistant.io/).

The hosted server is a **stateless OAuth proxy**: utilities require a stable public callback URL, but your data never lives on our server. Refresh tokens are stored encrypted on your Home Assistant instance, and every request flows through the proxy carrying the token from your side.

## Status

🚧 **Pre-alpha.** Burlington Hydro (Ontario, Canada) is the first targeted utility. See the implementation plan in `docs/` for the phased roadmap.

## Components

- `server/` — Kotlin/Ktor proxy server (deployed to Fly.io with scale-to-zero)
- `client-ha-integration/` — Home Assistant custom integration (HACS-installable)
- `docs/` — Architecture, deployment, per-utility notes
- `branding/` — Logo and brand assets

## Privacy

The hosted server holds **no per-user durable state**. Per-utility OAuth client credentials are configured globally; every other piece of state (your refresh token, your usage data) lives only on your Home Assistant instance.

## License

[MIT](LICENSE)
