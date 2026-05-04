[app]

# (str) Title of your application
title = X-RavScan

# (str) Package name (no spaces, no dashes)
package.name = xravscan

# (str) Package domain (reverse-DNS — used for the Android applicationId)
package.domain = ai.xrav

# (str) Source code where the main.py lives. Buildozer expects the entry
# script to be named ``main.py`` *inside* this directory, so we point at
# the project root and then symlink ``main.py`` -> ``mobile_main.py`` in
# the CI workflow before invoking buildozer.
source.dir = ../..

# (list) Source files to include
source.include_exts = py,png,jpg,kv,atlas,json,txt

# (list) Source files to exclude
source.exclude_exts = spec

# (list) List of directory to exclude
source.exclude_dirs = tests, packaging, build, dist, __pycache__, .venv, .git

# (str) Application versioning
version = 1.0.0

# (list) Application requirements — pure-python and supported recipes only.
# Heavyweight desktop UIs (customtkinter, matplotlib, tk) are NOT shipped on
# Android; the mobile entrypoint avoids importing them.
requirements = python3==3.10.12, kivy==2.3.1, openssl, cryptography, requests, urllib3, charset-normalizer, idna, certifi, pyjnius, pillow

# (str) Presplash of the application
#presplash.filename = %(source.dir)s/x_ravscan/assets/icon.png

# (str) Icon of the application
#icon.filename = %(source.dir)s/x_ravscan/assets/icon.png

# (list) Supported orientations: landscape, sensorLandscape, portrait
orientation = portrait

# (bool) Indicate if the application should be fullscreen or not
fullscreen = 0

# (list) Permissions
android.permissions = INTERNET, ACCESS_NETWORK_STATE, WRITE_EXTERNAL_STORAGE

# (int) Target Android API level — must be >=33 to publish on the Play Store
android.api = 34

# (int) Minimum Android API supported (corresponds to Android 7.0 Nougat)
android.minapi = 24

# (int) Android NDK API to use (avoids deprecation warnings)
android.ndk_api = 24

# (str) Android arch to build for. arm64-v8a is the modern default — armeabi
# and x86 are available in CI but slow CI down a lot. Single-arch keeps the
# Action runtime under the public 6h limit.
android.archs = arm64-v8a

# (bool) Skip trying to update the Android sdk after the first build.
android.skip_update = False

# (bool) If True, then automatically accept SDK license agreements
android.accept_sdk_license = True

# (str) The Android entry point — defaults to ``main.py``.
android.entrypoint = org.kivy.android.PythonActivity

# (list) Allow custom split flags (avoid 64K methods if multidex is needed)
android.add_aars =

# (list) Java classes to add as activities to the manifest
#android.add_activities =

# (str) Theme override (transparent splash background)
#android.manifest.theme = @android:style/Theme.NoTitleBar

# (str) Bootstrap to use for android builds (sdl2 is the modern default)
p4a.bootstrap = sdl2

[buildozer]

# (int) Log level (0 = error, 1 = info, 2 = debug)
log_level = 2

# (int) Display warning if buildozer is run as root
warn_on_root = 0
