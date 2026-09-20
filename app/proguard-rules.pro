# =============================================================================
# LiquidAgent — app 混淆/缩减规则
#
# 原则：保守。开荒阶段优先「能构建、能运行」，再谈极致体积。
# 配合 AGP 自带的 proguard-android-optimize.txt 使用（AGP 9 默认开启 R8 全模式）。
#
# 注意：release 构建在没有签名配置时会退回 debug 签名，但仍然会执行 minify/shrink，
# 所以这里的 keep 规则必须在无密钥的 CI 上也能独立生效。
# =============================================================================

# -----------------------------------------------------------------------------
# 1. LiteRT-LM 推理引擎（关键：JNI + 反射 + @ExperimentalApi）
# -----------------------------------------------------------------------------
# 引擎内部通过 JNI 加载原生库，且部分类型会被反射构造，整包保留最安全。
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keep enum com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class com.google.ai.edge.litertlm.** {
    native <methods>;
}
# 保留 @ExperimentalApi 注解本身，避免 R8 剥离后反射判断失效
-keep @interface com.google.ai.edge.litertlm.ExperimentalApi
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# 一旦升级 litertlm 到 0.17.x，若 API 漂移导致 -keep 命中不到类，
# 会出现「规则未命中」警告而非错误；届时需按新包名调整本节。

# -----------------------------------------------------------------------------
# 2. 可能随引擎一起进来的邻近包（存在即生效，不存在不报错）
# -----------------------------------------------------------------------------
-keep class com.google.ai.edge.** { *; }
-dontwarn com.google.ai.edge.**
# MediaPipe 已不作为主引擎依赖，保留规则仅防止传递依赖被误删
-dontwarn com.google.mediapipe.**

# -----------------------------------------------------------------------------
# 3. kotlinx.serialization
# -----------------------------------------------------------------------------
# @Serializable 类的序列化器由编译器生成并通过反射（serializersModule）查找，
# 必须保留 companion 对象与 INSTANCE。
-keepclassmembers class com.rickeal.agent.** {
    *** Companion;
}
-keepclasseswithmembers class com.rickeal.agent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.rickeal.agent.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.rickeal.agent.**$$serializer { *; }
-keepclassmembers class com.rickeal.agent.** {
    static com.rickeal.agent.**$* *;
    static *** INSTANCE;
    static *** $instance;
}
-keepattributes InnerClasses, EnclosingMethod, Signature
-dontwarn kotlinx.serialization.**

# -----------------------------------------------------------------------------
# 4. Jetpack Compose
# -----------------------------------------------------------------------------
# Compose 编译器在 AGP 9 / Kotlin 2.3 下会自动注入必要的 keep 规则，
# 这里只补「容易被 R8 误删」的两类：Composable 函数与稳定类型。
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
# Runtime 内部的 SlotTable / Composition 依赖字段名，不能被重命名
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-dontwarn androidx.compose.**

# -----------------------------------------------------------------------------
# 5. OkHttp（远程后端 / SSE）
# -----------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-keep class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# -----------------------------------------------------------------------------
# 6. Android 组件与反射入口
# -----------------------------------------------------------------------------
# AndroidManifest 中声明的组件由框架反射实例化
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
# 自定义 View / Composition 入口若被 XML 引用也需保留
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# -----------------------------------------------------------------------------
# 7. 日志清理
# -----------------------------------------------------------------------------
# 移除 release 包中的日志调用（assumenosideeffects 会连同字符串拼接一起删掉）。
# 注意：只在 -optimizations 生效（proguard-android-optimize.txt）时才会真正去除。
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
# 打印到 stdout 的调试输出也一并去掉
-assumenosideeffects class java.io.PrintStream {
    public void println(java.lang.String);
    public void println(java.lang.Object);
}

# -----------------------------------------------------------------------------
# 8. 其他通用属性
# -----------------------------------------------------------------------------
# 保留行号：崩溃栈能定位到源码行（体积代价可接受，开荒阶段值得）
-keepattributes SourceFile, LineNumberTable
# 保留异常表，保证 stacktrace 可读
-keepattributes Exceptions
# 保留泛型签名：kotlinx.serialization 与 Compose 的 @Stable 推断会用到
-keepattributes Signature

# -----------------------------------------------------------------------------
# 9. 兜底：不要因为缺类就让构建失败
# -----------------------------------------------------------------------------
# 第三方 AAR 常带有对可选依赖的引用（如 Play Services 子集）。
# 开荒阶段先把构建跑通，后续再逐个精修。
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn java.lang.invoke.**
-dontwarn kotlinx.coroutines.internal.**
-dontwarn kotlin.reflect.**

# -----------------------------------------------------------------------------
# 10. 液态玻璃引擎
# -----------------------------------------------------------------------------
# 原因：core-design/liquid 下全是 ModifierNodeElement / Modifier.Node 子类。
# R8 full mode 会：a) 内联/移除 ModifierNodeElement.equals()/hashCode() → 重组时
# 节点不 update，玻璃参数改了不重绘；b) 移除 @Stable 数据类未被直接读的字段 →
# equals 恒真 → Compose 强跳过误判。代价是全量保留几百 KB，换正确性，值得。
-keep class com.rickeal.agent.core.design.** { *; }
-keep interface com.rickeal.agent.core.design.** { *; }

# -----------------------------------------------------------------------------
# 11. 业务数据模型
# -----------------------------------------------------------------------------
# enum 的 values()/valueOf() 一旦被删，DarkMode/GlassMaterial 反序列化直接崩
-keep class com.rickeal.agent.core.model.** { *; }
-keepclassmembers enum com.rickeal.agent.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -----------------------------------------------------------------------------
# 12. 属性汇总
# -----------------------------------------------------------------------------
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,Exceptions,SourceFile,LineNumberTable
