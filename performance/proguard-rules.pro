# Instrumentation discovers test classes by name and JUnit annotations.
-keep class it.ayngallery.performance.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
# Static-analysis annotations have no runtime role.
-dontwarn com.google.errorprone.annotations.**
