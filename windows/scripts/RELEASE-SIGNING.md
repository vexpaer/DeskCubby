# DeskCubby Windows release signing

This directory supports two deliberately separate build modes:

- `SignedRelease` requires a Tauri updater private key and one Authenticode
  identity. It creates and verifies the NSIS updater signature and refuses to
  continue if any production signing input is missing.
- `AllowUnsignedTestBuild` must be selected explicitly. Its artifacts are for
  local testing only and must not be published.

The checked-in `tauri.conf.json` has `createUpdaterArtifacts: false`, an empty
updater public key, and no endpoints. Those explicit empty values let the
plugin deserialize its configuration without creating a trust anchor; the Rust
command boundary rejects update checks before any updater network request. A
signed build generates a temporary Tauri config outside the repository, passes
it with `--config`, and removes it after the build. No private key or PFX
belongs in this repository or in a `.env` file.

## Local unsigned test

```powershell
cd windows
.\scripts\build-release.ps1 -Mode AllowUnsignedTestBuild
```

## Production updater identity

Generate the long-lived updater key outside the repository and back it up
securely:

```powershell
pnpm tauri signer generate --write-keys <secure-path-outside-the-repository>
```

The public key is safe to distribute. The private key and its password are
release secrets:

```text
DESKCUBBY_UPDATER_PUBLIC_KEY
DESKCUBBY_UPDATER_ENDPOINT
TAURI_SIGNING_PRIVATE_KEY (or TAURI_SIGNING_PRIVATE_KEY_PATH)
TAURI_SIGNING_PRIVATE_KEY_PASSWORD
```

Losing this private key prevents future updates for clients that trust its
public key. Rotate it only by first shipping an update signed by the old key
that embeds the new public key.

## Authenticode identity

For an exportable PFX, set the following without putting their values on the
command line:

```text
WINDOWS_CERTIFICATE_BASE64
WINDOWS_CERTIFICATE_PASSWORD
DESKCUBBY_WINDOWS_PUBLISHER
DESKCUBBY_WINDOWS_TIMESTAMP_URL
DESKCUBBY_WINDOWS_TIMESTAMP_TSP=true
```

Import the certificate before building:

```powershell
.\scripts\import-code-signing-certificate.ps1
```

For a modern hardware-backed or cloud certificate, configure the provider's
tool and set a one-line Tauri custom command containing the `%1` placeholder:

```text
DESKCUBBY_WINDOWS_SIGN_COMMAND
DESKCUBBY_WINDOWS_SIGNER_SUBJECT
```

Exactly one of the imported certificate thumbprint and custom sign command is
accepted. A custom command also requires the expected signer subject so the
post-build check is pinned to the intended publisher. The custom command must
sign during Tauri bundling; modifying an installer after its `.sig` file is
generated invalidates updater verification.

## GitHub production environment

The `windows-release.yml` workflow expects an environment named
`windows-production`, preferably with required reviewers and protected
`windows-v*` tags.

Environment secrets:

```text
TAURI_UPDATER_PUBLIC_KEY
TAURI_SIGNING_PRIVATE_KEY
TAURI_SIGNING_PRIVATE_KEY_PASSWORD
WINDOWS_CERTIFICATE_BASE64
WINDOWS_CERTIFICATE_PASSWORD
```

For custom signing, leave the PFX secrets empty and provide
`WINDOWS_SIGN_COMMAND` plus the authentication variables required by that
signing provider.

Environment variables:

```text
WINDOWS_PUBLISHER
WINDOWS_SIGNER_SUBJECT
WINDOWS_TIMESTAMP_URL
WINDOWS_TIMESTAMP_TSP
```

The workflow creates a **draft** GitHub Release. Review both Authenticode
signatures, the timestamp, installer `.sig`, checksums, and `latest.json` before
publishing it. The release script independently verifies the generated updater
signature against `DESKCUBBY_UPDATER_PUBLIC_KEY` before it writes
`latest.json`. After the versioned draft has passed review and is published,
replace the `latest.json` asset on the dedicated `windows-stable` channel
release. Windows builds deliberately read that platform-specific channel
instead of GitHub's repository-wide `/releases/latest/` alias, because Android
and Windows releases share this repository. Never promote a draft or an
unverified manifest to `windows-stable`.

The original Windows 0.1.0 build did not contain the updater plugin. Any user
already running that build must manually install the first updater-enabled
release; automatic updates only work after that bootstrap installation.
