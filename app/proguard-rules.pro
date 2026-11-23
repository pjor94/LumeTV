# Media3 and OkHttp ship their own consumer rules.

# NewPipe executes YouTube player JavaScript through Rhino.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
# Rhino runs in interpreted mode on Android; desktop bean/JIT integrations are unused.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
