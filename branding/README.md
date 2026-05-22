# Open Green Button — Brand Assets

The mark is a **meter dial with an energy bolt** — a nod to metering/AMI for an app that reads energy usage and billing data. The palette is electric blue (deliberately *not* green, to stand apart from the Green Button standard's own branding). All logo SVGs have their text converted to outlines, so they render identically everywhere with **no font dependency**.

## Quick start (web)

Everything web-facing is pre-built in [`web/`](web/). Copy the contents of `web/` to your site root and paste the tags from [`web/head-snippet.html`](web/head-snippet.html) into your `<head>`:

```html
<link rel="icon" href="/favicon.ico" sizes="any">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="icon" href="/favicon-32.png" sizes="32x32" type="image/png">
<link rel="icon" href="/favicon-16.png" sizes="16x16" type="image/png">
<link rel="apple-touch-icon" href="/icon-180.png">
<link rel="manifest" href="/site.webmanifest">
<meta name="theme-color" content="#1E5BFF">
```

For the Google Play listing, upload `web/icon-512.png`.

## Asset inventory

| File | Use |
| --- | --- |
| `icon.svg` | Primary mark, full colour. Default icon ≥ 32 px. |
| `icon-mono.svg` | Single-colour mark (`currentColor`, defaults to ink). Print, embroidery, one-ink contexts. |
| `mark-bolt.svg` | Bolt only. Very tight spaces where the dial would be lost. |
| `logo-horizontal.svg` | Primary lockup (icon + wordmark) for light backgrounds. |
| `logo-horizontal-dark.svg` | Lockup for dark backgrounds (white wordmark). |
| `logo-horizontal-mono.svg` | Single-colour lockup (`currentColor`). |
| `favicon.svg` | Round mark, transparent corners — the browser-tab favicon. |
| `favicon-square.svg` | Rounded-square favicon alternative. |
| `icon-maskable.svg` | Adaptive / app icon: full-bleed blue plate, round badge on the keyline. Source for PWA maskable + store icons. |
| `web/` | Built favicons, app icons, `site.webmanifest`, head snippet. |
| `logo-preview.png`, `adaptive-icon-demo.png` | Reference renders (not for distribution). |

## Colour

The mark uses a diagonal (top-left → bottom-right) gradient from cyan to electric blue.

| Token | Hex | Role |
| --- | --- | --- |
| Cyan | `#2DD4FF` | Gradient start (highlight) |
| Electric blue | `#1E5BFF` | Gradient end / brand primary / `theme_color` |
| Ink | `#0B1B2B` | Wordmark on light, dark surfaces, mono default |
| White | `#FFFFFF` | Wordmark on dark, bolt knockout |

The `*-mono.svg` files paint with `currentColor` (default `#0B1B2B`) — set the SVG `color` attribute or inherit a CSS `color` to render in any single ink (e.g. `#1E5BFF`).

## Typography

Wordmark is **Montserrat SemiBold** ([SIL Open Font License](https://fonts.google.com/specimen/Montserrat)), outlined in the SVGs. If you need matching live text in UI or docs, use Montserrat (SemiBold for headings/wordmark, Medium/Regular for body).

## Usage

- **Clear space:** keep free space on all sides ≥ 50% of the icon's height. Don't crowd the lockup.
- **Minimum size:** colour icon down to ~24 px; lockup down to ~28 px tall. Below that, use `favicon.svg` or `mark-bolt.svg`.
- **Backgrounds:** the gradient mark works on light or dark. On busy or low-contrast surfaces, use a mono variant.

Don't: recolour the gradient, stretch or distort, rotate, add drop shadows/outlines, re-typeset the wordmark in another font, or place the gradient mark on a similar-blue background.

## App & favicon notes

- **Round vs. square:** `favicon.svg` is pre-rounded for the browser tab. For app stores and PWAs, ship the **square** `icon-maskable.svg` and let the platform apply its own mask (rounded square on Google Play, circle/squircle on launchers, squircle on iOS).
- **Safe zone:** in `icon-maskable.svg` the round badge sits inside the 72 dp keyline circle of the 108 dp grid, so it's never clipped by any mask. See `adaptive-icon-demo.png`.
- **apple-touch:** `web/icon-180.png` is flattened (no alpha), as iOS prefers.

## Naming & licensing

"Green Button" refers to the energy-data standard and is associated with the Green Button Alliance, which maintains trademark and certification guidelines. Before using the name or any logo publicly, confirm your usage against those guidelines. (This is a practical heads-up, not legal advice.)

Asset licensing is up to the project — decide how you want to license these marks alongside the code, and document it here.
