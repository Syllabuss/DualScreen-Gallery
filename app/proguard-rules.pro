# RemoteViews methods are invoked by the launcher through reflection.
-keepclassmembers class * extends android.view.View {
    @android.view.RemotableViewMethod <methods>;
}
# Components are retained by Android manifest rules. Test release reflection paths separately.
-dontwarn org.slf4j.impl.StaticLoggerBinder
# SMBJ uses NTLM credentials here, not optional JVM Kerberos or expression-language filters.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
# Cryptographic providers register algorithms by class name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
# Optional JVM LDAP certificate stores are not used by Android NTLM authentication.
-dontwarn javax.naming.**

# MINA's SimpleIoProcessorPool creates this socket processor by reflection.
-keep,allowobfuscation class org.apache.mina.transport.socket.nio.NioProcessor {
    public <init>(java.util.concurrent.Executor);
}

# SMBJ's event bus constructs the default handler through SubscriptionFactory reflection.
-keep,allowobfuscation class net.engio.mbassy.dispatch.ReflectiveHandlerInvocation {
    public <init>(net.engio.mbassy.subscription.SubscriptionContext);
}
