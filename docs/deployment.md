# Deploying the Open Green Button server

The reference deployment is [Fly.io](https://fly.io/) with scale-to-zero.

## One-time setup

```sh
# 1. Pick a domain. Anywhere with a DNS panel works.
#    Suggested host: greenbutton.<your-domain>
#
# 2. Sign up at fly.io, install flyctl.

cd server/
fly launch --no-deploy --copy-config --name open-green-button --region yyz

# 3. Generate and set secrets. Both are 32-byte random base64 strings.
fly secrets set \
  OPENGB_CRYPTO_AESKEYBASE64=$(openssl rand -base64 32) \
  OPENGB_CRYPTO_HMACPEPPERBASE64=$(openssl rand -base64 32) \
  OPENGB_PUBLIC_BASE_URL=https://greenbutton.<your-domain>

# 4. Attach your custom hostname.
fly certs add greenbutton.<your-domain>
# Add the AAAA + A records that fly prints, then:
fly certs check greenbutton.<your-domain>

# 5. Build and push the image with Jib.
gradle :app:jib \
  -Popengb.image.name="registry.fly.io/open-green-button"

# 6. Deploy.
fly deploy --image registry.fly.io/open-green-button:latest
```

## Verifying scale-to-zero

After 5 minutes of idle:

```sh
fly status   # machine state should be "stopped"
curl -i https://greenbutton.<your-domain>/health
#  first request wakes the machine (~5-15s on shared-cpu-1x 256MB);
#  subsequent calls within the keep-alive window respond in ms.
```

## Per-utility secrets

When you register the app with a new utility (Burlington Hydro, etc.), the utility issues a `client_id` and `client_secret`. Set them as Fly secrets named per the utility:

```sh
fly secrets set \
  OPENGB_UTILITY_BURLINGTON_HYDRO_CLIENTID="..." \
  OPENGB_UTILITY_BURLINGTON_HYDRO_CLIENTSECRET="..."
```

The server's `utilities.conf` reads these via Hoplite env substitution.

## Continuous deploy from GitHub Actions

[.github/workflows/deploy.yml](../.github/workflows/deploy.yml) runs on every push to `master` that touches `server/**`. It builds, tests, pushes the Jib image to `registry.fly.io/open-green-button:<sha>`, and runs `flyctl deploy` against that immutable SHA-tagged image.

One-time setup — generate a deploy token and store it as a repo secret:

```sh
fly tokens create deploy -x 8760h   # 1 year, scoped to deploy only
# copy the token (starts with "FlyV1 ...")
```

Then in GitHub: **Settings → Secrets and variables → Actions → New repository secret**

- Name: `FLY_API_TOKEN`
- Value: paste the token from `fly tokens create`

The token can be rotated at any time with `fly tokens revoke` + a fresh `fly tokens create`. Avoid using your personal `fly auth token` for CI — deploy tokens are scoped narrower and easier to revoke.

To deploy manually without a commit: Actions tab → "deploy" workflow → "Run workflow".

To roll back: `flyctl releases --app open-green-button` to find a prior image, then `flyctl deploy --app open-green-button --image registry.fly.io/open-green-button:<old-sha>`.
