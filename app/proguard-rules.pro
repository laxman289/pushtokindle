# Keep Jakarta Mail and Jsoup classes
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
