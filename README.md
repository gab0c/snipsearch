# SnipSearch

SnipSearch is a privacy-first Android digital assistant for selecting and searching on-screen content. It is designed for compatible Android phones, with GrapheneOS compatibility as a development priority.

See the [development guide](docs/development.md) to set up Android Studio, verify a build, and share a debug APK for phone testing.

The intended OCR architecture uses bundled on-device models in the app process, with screen content kept in RAM and searches initiated explicitly by the user. Process isolation for OCR is no longer part of the [security model](SECURITY.md#ocr-security-model); migration from the legacy isolated implementation is pending. See the [architecture plan](docs/plan.md) for execution and lifecycle requirements.
