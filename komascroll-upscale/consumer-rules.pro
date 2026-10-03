# KomaScroll upscale engine: JNI entry points and the callback invoked from native code by name.
-keepclasseswithmembernames class komascroll.upscale.engine.** {
    native <methods>;
}
-keep interface komascroll.upscale.engine.RealEsrgan$ProgressCallback { *; }
-keepclassmembers class * implements komascroll.upscale.engine.RealEsrgan$ProgressCallback {
    boolean onProgress(int, int);
}
