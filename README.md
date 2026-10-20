# Android Paper Server Manager

A native Kotlin/Jetpack Compose Android application for managing real Paper Minecraft Java Edition servers from Android.

## What is implemented

- Multiple isolated Paper server directories under app-private storage.
- Guided server creation with live Paper version metadata from PaperMC.
- Automatic latest-build Paper download with `.part` files and finalization checks.
- Explicit Minecraft EULA acceptance.
- Configurable memory, port, player limit, view distance, simulation distance, and MOTD.
- Real Paper process execution through an imported Android-compatible Java runtime.
- Foreground service management for long-running user-controlled server processes.
- Live console log tailing and command input.
- Real `server.properties` editing that writes to the server's actual file.
- Modrinth API plugin search filtered for plugin/Paper/server-side compatibility and Minecraft version, with real downloads into `plugins/`.
- Plugin dependency IDs are exposed by the API model; automatic dependency installation is intentionally not performed.
- Backup ZIP creation with path traversal checks.
- System light/dark theme and persistent creamy accent selection.
- GitHub Actions debug APK workflow and JVM unit tests.

## Important Android/Paper runtime limitation

Android's built-in Android Runtime is not a desktop OpenJDK distribution and cannot simply execute an arbitrary Paper server JAR. This project therefore does **not** silently bundle an unverified desktop JDK. Instead, Settings provides an import path for an Android-compatible JRE ZIP containing `bin/java`.

The imported runtime is executed directly by the app. It must match the device ABI and Android's executable/runtime constraints. A normal desktop Linux JDK archive is not guaranteed to work on Android.

Paper's current documentation states Java 21 for Minecraft 1.20 through 1.21.11 and Java 25 for 26.1+, with older Minecraft versions requiring older Java releases. The app checks the selected Paper version against this mapping and reports the installed runtime version; it does not claim that a runtime is compatible merely because `java -version` succeeds.

Long-running server processes use an Android foreground service with the `specialUse` type because Android 14+ requires explicit foreground-service types and Android 15 places time limits on `dataSync` foreground services. The server is user-controlled and remains subject to device battery, thermal, memory, manufacturer background-process policies, and Android process management.

## Build locally

Requirements:

- Android SDK with API 35 installed.
- JDK 17 for the Gradle/Android build.
- Network access for Gradle dependencies.

From the repository root:

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

The debug APK is produced under `app/build/outputs/apk/debug/`.

## GitHub Actions

The supplied workflow is `.github/workflows/build-apk.yml`. It is **manual-only** and runs only through `workflow_dispatch`; pushes and pull requests do not start a build automatically. The workflow uses current Node.js 24-compatible GitHub Actions releases.

The workflow runs unit tests, builds the debug APK, verifies that an APK exists, and uploads it as `android-paper-server-debug-apk`.

After extracting this project into an existing GitHub repository:

1. Commit and push the files.
2. Open **Actions** and select **Build Android APK**.
3. Choose **Run workflow** to start a build manually.
4. Open a successful run and download the `android-paper-server-debug-apk` artifact.
5. Extract the artifact and install the APK on a compatible Android device. Android may require permission to install apps from the source used to open the APK.
6. If a build fails, inspect the failed step and its Gradle output in the Actions logs.

No signing key is required for the debug artifact. No `.git` directory or GitHub history is included.

## First launch

1. Pick a creamy accent color.
2. Open Settings and import an Android-compatible JRE ZIP containing `bin/java`.
3. Add a server, choose a Paper/Minecraft version, configure resources, and accept the Minecraft EULA.
4. Wait for Paper to finish downloading.
5. Open the server and start it.
6. Use Console for real process output and commands.
7. Use Config to edit the actual `server.properties` file.
8. Use Plugins to search Modrinth and install a compatible Paper plugin.

## Security and file handling

Server files are stored in app-private storage. Server IDs and backup/plugin archive paths are validated to prevent path traversal. Plugin JARs are third-party executable code and are installed only after a user-initiated download action. The app never contains private signing credentials.

## Verification status

The project source, archive layout, manifest, Gradle configuration, workflow, and unit-test sources are generated and statically inspected in this delivery environment. A local Android build is **not claimed as successful** because this environment does not provide the Android SDK/Gradle distribution and cannot resolve external build dependencies. GitHub Actions is the intended reproducible build environment.

A successful CI build verifies compilation and unit tests; it does not prove that Paper can run on every Android device. Real-device testing is required for the imported JRE, Paper process execution, ABI compatibility, thermal/memory behavior, and manufacturer-specific background restrictions.

## License

Apache License 2.0. Third-party software, Paper, Minecraft, and Modrinth remain subject to their respective licenses and terms.
