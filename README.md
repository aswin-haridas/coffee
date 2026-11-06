# Coffee

A small Android chat client for the [Coffee backend](https://github.com/aswin-haridas/coffee-backend).

Sign in with the owner’s BigShelf account, send text, browse cached or older history, copy a message, and retry or regenerate replies. Settings contain your name and custom instructions. Old photos, quotes, tool steps and rephrased messages remain readable.

The backend uses one general Bash tool with SQLite records and access to all chat history. New data questions do not need new Android screens or domain-specific handlers.

Automatic location/trip tracking, maps, insights, Google Timeline import, Firebase pushes, voice/photo composition and the in-app updater are removed. Existing local trip/image files and server data are not deleted. Install future APK releases manually from GitHub.

## Build

Use JDK 17 or newer and an installed Android SDK. No Firebase configuration is required.

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleRelease bundleRelease
```

Release signing uses the existing local `keystore.properties`; without it Gradle uses the debug key. Do not publish an update signed with a different key. CI builds and lints debug and runs the cache compatibility tests.
