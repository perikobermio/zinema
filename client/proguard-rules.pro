-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# Android instantiates these activities from the manifest by class name.
-keep public class com.zinema.client.MainActivity {
    public <init>();
}
-keep public class com.zinema.client.PlayerActivity {
    public <init>();
}

# WebView calls this JavaScript bridge by its annotated method name.
-keep public class com.zinema.client.MainActivity$IntroBridge {
    public <init>(com.zinema.client.MainActivity);
    public void onIntroEnded();
}
