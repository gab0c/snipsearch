# Security Policy

## Reporting a vulnerability

Report vulnerabilities privately through
[GitHub private vulnerability reporting](https://github.com/gab0c/snipsearch/security/advisories/new).
Please do not open a public issue for a security problem.

## Supported versions

SnipSearch has not been released yet. Once releases exist, this section will state which versions receive security fixes.

## Release signing

Release APKs are built unsigned and then signed in a separate CI job that runs in the protected GitHub `release` environment, as described in [`docs/plan.md`](docs/plan.md) §5.2. No signing material is stored in this repository, and Gradle has no release signing configuration (see `AGENTS.md` §3.4).

### Signing certificate

- Package: `com.snipsearch`
- Certificate SHA-256 fingerprint: _not published yet; it will be added here with the first release._

To check a downloaded APK, compare this fingerprint with the output of:

```bash
apksigner verify --print-certs snipsearch.apk
```

### Where the key lives

- The maintainer holds the master copy of the release keystore and its passwords offline. It is never committed, and it is never given to CI outside the `release` environment or to coding agents.
- The `release` environment holds a copy as environment secrets. Only the secret names are documented here:

| Secret | Contents |
| :--- | :--- |
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Alias of the signing key |
| `RELEASE_KEY_PASSWORD` | Password of the signing key |

### Before the first release

- [ ] Generate the release keystore on the maintainer's own machine and back it up offline.
- [ ] Create the `release` environment, restrict it to `v*` tags, and add the maintainer as a required reviewer.
- [ ] Add a tag ruleset that limits who can create `v*` tags.
- [ ] Add the four secrets above to the `release` environment.
- [ ] Record the certificate fingerprint in this file.

### If the key is lost

Updates can no longer be signed with the same certificate, and Android will not install an APK signed with a different certificate over an existing install. A new key means a new fingerprint published here and in the release notes, and existing users have to uninstall and reinstall.

### If the key is compromised

1. Delete the secrets from the `release` environment.
2. Publish a GitHub security advisory that names the last release known to be trustworthy.
3. Move to a new key and publish its fingerprint here. If the old key is still held, the change can use APK Signature Scheme v3 key rotation so updates install over existing installs. Otherwise, follow the key-loss path above.
