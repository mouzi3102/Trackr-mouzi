# ProGuard and R8 Rules for Trackr

# --- Code Obfuscation Optimization ---
-repackageclasses 'com.trackr.internal'
-allowaccessmodification

# --- Kotlin & Coroutines ---
-dontwarn kotlinx.coroutines.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Kotlinx Serialization ---
# 不再自己写宽规则（第 29 步）：kotlinx-serialization-core 1.11 自带 R8 规则
# （META-INF/com.android.tools/r8/kotlinx-serialization-*.pro），已保留 @Serializable 类的
# Companion 字段、Companion / 具名 Companion / object 的 serializer()、以及运行时注解。
# 本工程的序列化全部走插件生成的 serializer（Json.decodeFromString(X.serializer()) 或 reified
# decodeFromString<T>()，后者编译期内联成同样的调用），序列化名在编译期写成字符串，类名混淆不影响
# 读旧数据。返回栈存进 SavedState 用 TrackrSavedStateConfiguration 显式登记全部 ScreenKey 子类，
# 不走 Android 上按类名反射的 NavKeySerializer，所以 ScreenKey 也不用整类保留。
# 模型包、导航包里没有反射（Class.forName / ::class.java / qualifiedName 都没有），原先的
# -keep class com.trackr.core.model.** { *; } 等整包保留一并去掉。
-dontnote kotlinx.serialization.SerializationKt

# --- OkHttp & Okio（Ktor 的 Android 引擎）---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Android 组件 ---
# Manifest 里声明的 Activity / Service / Receiver / Provider（含合并进来的库组件）由 AGP 生成的
# aapt_rules.txt 保留类和无参构造，不用再按父类整类保留（第 29 步）。


# --- Glance 小组件（第 26 步）---
# RunCallbackAction 用无参构造反射创建 ActionCallback
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }
