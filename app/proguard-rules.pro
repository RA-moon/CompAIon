-keepclasseswithmembers class * {
    native <methods>;
}

-keep class org.apache.tvm.** { *; }
-keepclassmembers class org.apache.tvm.Base$RefLong { long value; }
-keep class ai.mlc.** { *; }
