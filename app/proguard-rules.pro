# Keep Shizuku UserService, AIDL interfaces, ModemHardwareBridge, ModemRootCli, and Shizuku IPC intact under R8
-keep class ro.netstabil.app.privileged.** { *; }
-keep interface ro.netstabil.app.privileged.** { *; }
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Keep Android Service, Quick Settings Tile, Home Widget, Network Callbacks & BroadcastReceiver entrypoints
-keep class ro.netstabil.app.service.** { *; }
-keep class ro.netstabil.app.receiver.** { *; }
-keep class ro.netstabil.app.tile.** { *; }
-keep class ro.netstabil.app.widget.** { *; }
-keep class ro.netstabil.app.network.** { *; }
