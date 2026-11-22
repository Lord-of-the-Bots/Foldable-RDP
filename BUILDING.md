# Building Foldable RDP

Use Git, JDK 21, Android SDK Platform 36, Build Tools 36.0.0, Android NDK 27.2.12479018 and CMake 3.22.1. Set ANDROID_HOME to the SDK directory and JAVA_HOME to the JDK directory.

From the repository root, download the prebuilt dependencies from the upstream aRDP release. The script requires Python 3.9 or later. Gradle fetches the pinned FreeRDP source separately and builds WinPR with the Android storage patch.

```sh
python3 download-dependencies.py
./gradlew :freeaRDP-app:assembleDebug
```

On Windows, use `python` and `gradlew.bat`. The APKs are written to `freeaRDP-app/build/outputs/apk/debug/`. For an unsigned release build, run `:freeaRDP-app:assembleRelease` instead and sign the APK with your own Android signing key.

The Android client is derived from [upstream revision 66b81d9](https://github.com/iiordanov/remote-desktop-clients/commit/66b81d9). [FreeRDP and WinPR](https://github.com/FreeRDP/FreeRDP/tree/2.11.7) use the [Apache License 2.0](licenses/FreeRDP.txt). The upstream aRDP release supplies the Android FreeRDP bindings and native dependencies. The client is licensed under the [GNU General Public License](LICENSE). Component copyright and license notices are included with their sources.
