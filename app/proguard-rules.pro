# Room/WorkManager instantiate these classes through reflection. R8 full mode
# must retain their constructors explicitly, not just the class names.
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
-keep class androidx.work.OverwritingInputMerger {
    public <init>();
}
-keep class androidx.work.ArrayCreatingInputMerger {
    public <init>();
}
-keep class com.plainnotes.android.sync.SyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
