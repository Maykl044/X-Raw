# Keep Hilt-generated code
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

# Keep kotlinx.serialization metadata
-keep,includedescriptorclasses class ai.xrav.xravscan.**$$serializer { *; }
-keepclassmembers class ai.xrav.xravscan.** {
    *** Companion;
}
-keepclasseswithmembers class ai.xrav.xravscan.** {
    kotlinx.serialization.KSerializer serializer(...);
}
