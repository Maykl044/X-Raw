"""KivyMD-based mobile UI for X-RavScan (Android / desktop debug).

The mobile app re-uses ``x_ravscan.core`` as-is — the asyncio scanner,
SQLite database, providers manager and network updater are all pure-Python
and platform-portable. Only the UI layer differs from the desktop build.
"""
