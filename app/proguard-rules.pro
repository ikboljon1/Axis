# Anthropic SDK uses Jackson + Kotlin reflection
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
