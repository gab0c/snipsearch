# SnipSearch

SnipSearch is a privacy-first Android digital assistant for selecting and searching on-screen content. It is designed for compatible Android phones, with GrapheneOS compatibility as a development priority.

See the [development guide](docs/development.md) to set up Android Studio, verify a build, and share a debug APK for phone testing.

OCR uses bundled on-device models in the app process on bounded background workers. Screen content stays in RAM, and searches start only through explicit user actions, as described in the [security model](SECURITY.md#ocr-security-model) and [architecture plan](docs/plan.md).
