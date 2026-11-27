# Simeji Clean

With Vector preferences set up, filtering starts automatically in Simeji even when the companion app is stopped.

[日本語](README.md)

Hide the supported promotional panel above the Simeji keyboard when entering Google Play search. Normal keys, skins and conversion suggestions remain available. It does not modify Google Play itself or panels in unrelated input fields.

## Requirements

- Android 12 or later, with root and a compatible libxposed API 102 framework such as Vector.
- A supported version of Simeji (`com.adamrocker.android.input.simeji`).

## Installation

1. Install the signed APK from [Releases](https://github.com/OneUI-Mods/simeji-clean/releases).
2. Enable the module in Vector and select only Simeji as its scope.
3. Restart the keyboard app and open Google Play search.
4. Use the app's switch to disable the adjustment. A device reboot is not required for an ordinary module update.

The supported panel is located from the APK's behavior and layout relationships. Discovery is cached for the same APK and repeated after a target update. If the result is missing or ambiguous, the module retains the original behavior. Dynamic discovery does not guarantee compatibility with every future update.

## Recovery

Disable the app's switch or disable the module in Vector, then restart Simeji. Confirmed repeated host crashes can stop the adjustment through its crash protection. Reset protection explicitly from the app before retrying.

## Development and licensing

The application is MIT licensed. Dependency licenses and notices are available in the app. See [development](docs/development.md), [build environment](docs/build-environment.md) and [signed release packaging](docs/releasing.md). Signing keys and third-party APKs are not included in the source repository.
