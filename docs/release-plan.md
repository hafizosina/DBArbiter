# Release and sharing plan (CI/CD)

Goal: share the app easily. Anyone with the link downloads the newest version for Linux or Windows; the trial license
(1 month, then a key) protects it, so the downloads can be public while the code stays private.

## Repositories (GitHub)

**For now (decided 2026-10-22):** one public repo `dbarbiter` — code and Releases together; the link to share is
`https://github.com/<user>/dbarbiter/releases/latest`. CI publishes the Releases in that repo (no token needed).

**Later:** split into a private code repo and a public downloads repo, as below.


| Repo | Visibility | Holds |
|------|-----------|-------|
| `dbarbiter` | private | the source code; CI runs here |
| `dbarbiter-download` | public | no code, only Releases with the downloads |

The link to share never changes and always gives the newest version:
`https://github.com/<user>/dbarbiter-download/releases/latest`

## What CI does

- **A push to main:** nothing runs on GitHub (the local deploy hook still builds and installs your copy).
- **A pushed version tag (`v0.1.12`, …):** builds both downloads and publishes them as a Release in `dbarbiter-download`.
- **One Linux runner** builds both: the Windows version is already built on Linux (`packaging/build-windows.sh`: launch4j exe +
  a Temurin Windows JRE, zipped). No Windows runner needed.
- **Never in CI:** the license generator and the signing key. They stay on the signer's machine only.

## Downloads

| File | Contents | How people run it |
|------|----------|-------------------|
| `DBArbiter-windows-x64.zip` | `DBArbiter.exe` + bundled Java | Unzip, double-click `DBArbiter.exe` (no Java install needed) |
| `DBArbiter-linux-x64.tar.gz` | `DBArbiter` launcher + jar + bundled Java | Extract, run `./DBArbiter` |

- **Windows SmartScreen:** the exe is not code-signed, so Windows shows "Windows protected your PC" the first time; people click
  *More info → Run anyway*. Removing that needs a code-signing certificate (about $100–300 a year) — later, if needed.

## Versions

- Today the local merge hook (`.githooks/reference-transaction` → `packaging/deploy-linux.sh`) numbers each merge to main
  (v0.1.x tags) and installs it in `~/App/DBArbiter`. That stays as it is.
- **A public release:** push one of those tags (`git push origin v0.1.12`); CI publishes that version. Only pushed tags become
  public releases, so not every merge is shared.

## To do

1. **Me:** `.github/workflows/build.yml` (tests on push; on a tag, build both and publish to `dbarbiter-download`).
2. **Me:** `packaging/build-linux.sh` — the `.tar.gz` with a bundled Linux JRE (like `build-windows.sh`, without installing
   anything locally).
3. **Me:** run both builds locally the way CI runs them, before the first push.
4. **You:** create the two repos on GitHub (code private, downloads public).
5. **You:** create a fine-grained token with *Contents: read and write* on `dbarbiter-download` only; save it in the private repo as
   the secret `DOWNLOAD_REPO_TOKEN`.
6. **You:** `git remote add origin …`, push main, then push a tag for the first release.
7. **Before the first push:** rename the app to DBArbiter (window title, exe / launcher, start menu, downloads).

## Name

**DBArbiter** (chosen 2026-10-22): the tool that decides what goes to production. Each tool finds something (rows that differ,
schema changes, test data), you give a verdict, and it tells you how to do it (generated SQL, a report). Web search found no
product by that name (nearest: DBArtisan, a different DB admin tool; Databox "Arbiter", a research component).
Still to check by hand: the GitHub names, a domain, and an Indonesian trademark search (DJKI).

## Open

- **Rename** to DBArbiter before the first push. Keep the settings folder `~/.dbadmin`, the license key format and the
  `.dbcompare` files as they are, so existing users keep their connections, license and saved compares.
- **GitHub username** for the links above.
