# ProGuard / R8 Rules for Modern Android WebView Application

# 1. WebView & JavaScript Interface Preservation
# Retain methods annotated with @JavascriptInterface to avoid runtime reflection breakage
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve WebViewClient and WebChromeClient classes and their callback signatures
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, android.webkit.WebResourceRequest);
    public void *(android.webkit.WebView, android.webkit.WebResourceRequest, android.webkit.WebResourceError);
}

-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, int);
    public void *(android.webkit.WebView, java.lang.String);
    public void onGeolocationPermissionsShowPrompt(java.lang.String, android.webkit.GeolocationPermissions$Callback);
    public boolean onShowFileChooser(android.webkit.WebView, android.webkit.ValueCallback, android.webkit.WebChromeClient$FileChooserParams);
}

# 2. General Android & Jetpack Component Rules
# Preserve standard Android components declared in AndroidManifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep Parcelable CREATOR fields
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Serializable class members
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 3. AndroidX Webkit & SwipeRefreshLayout Rules
-dontwarn androidx.webkit.**
-keep class androidx.webkit.** { *; }

-dontwarn androidx.swiperefreshlayout.**
-keep class androidx.swiperefreshlayout.widget.SwipeRefreshLayout { *; }

# 4. Kotlin & Coroutines Rules
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature

# 5. Optimization & Obfuscation Settings
# Preserve line numbers for readable crash logs while obfuscating identifiers
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
