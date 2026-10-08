# Implementation Plan - Switch to Reliable Map Provider

The OpenStreetMap servers are being extremely strict, likely blocking the device's IP address or still caching the old blocked requests on the network level.

To bypass OpenStreetMap's aggressive blocking and avoid Carto's "API Key Required" watermarks altogether, we will switch the map's tile source to **Google Maps** public tile servers. This is highly reliable, fast, and does not block access.

## Proposed Changes

### [MODIFY] [NavigationActivity.kt](file:///C:/Users/Mhiko/AndroidStudioProjects/Comucane/app/src/main/java/com/example/comucane/NavigationActivity.kt)
- **Implement Custom Tile Source**: We will replace `TileSourceFactory.MAPNIK` with a custom `OnlineTileSourceBase` implementation that points directly to Google's public tile servers (`mt0.google.com`, `mt1.google.com`, etc.).
- **Automatic Cache Bypass**: Because we are naming the new map source "GoogleMaps", osmdroid will automatically create a brand new cache database for it. This guarantees that none of the old "Access Blocked" tiles will be loaded.

## Verification Plan
1. **Automated Tests**: Run `gradlew :app:assembleDebug` to verify compilation.
2. **Manual Verification**: Run the app and navigate to the `NavigationActivity`. The map should now successfully download and display the familiar Google Maps street layout without any errors or watermarks.
