# R8 rules for WiFi Scout.
#
# NOTE: there used to be a blanket "-keep class com.clicksolutions.wifiscout.** { *; }"
# here. That kept every app class un-obfuscated (Play vitals: 2% obfuscation).
# It is intentionally gone. AGP generates keep rules for manifest components
# and for classes referenced from layouts (e.g. WifiHeatmapView); Firebase and
# Play Billing ship their own consumer rules.

# Readable crash stack traces in Play Console (mapping file is embedded in the AAB)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*

# Compile-time-only annotations referenced by dependencies
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
