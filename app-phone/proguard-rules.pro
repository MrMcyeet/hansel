# kotlinx.serialization: keep generated serializers and @Serializable classes' companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.hansel.**$$serializer { *; }
-keepclassmembers class dev.hansel.** { *** Companion; }
-keepclasseswithmembers class dev.hansel.** { kotlinx.serialization.KSerializer serializer(...); }
# Room entities / DAOs are referenced by generated code; keep model names for the lenient Json.
-keep class dev.hansel.storage.** { *; }
-keep class dev.hansel.model.** { *; }
# MapLibre native bridge + GeoJSON (Gson reflection)
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
-keep class com.google.gson.** { *; }
# Android for Cars app library host validator uses reflection on templates
-keep class androidx.car.app.** { *; }
# GraphHopper spike isn't in the app; OkHttp/okio ship their own rules.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
