# DeskCubby release R8 rules.
#
# R8 (code shrinking + obfuscation) is enabled in the `release` build type together with
# resource shrinking. Most keep rules come from the dependencies' own consumer rules, which
# AGP merges automatically:
#
#   - Room (androidx.room:room-runtime)          keeps RoomDatabase subclasses
#   - Hilt (com.google.dagger:hilt-android)      keeps @EntryPoint components
#   - Health Connect (androidx.health.connect)   keeps proto/parcelable classes used reflectively
#   - PDFium (io.legere:pdfiumandroid)           keeps io.legere.pdfiumandroid.** and native bindings
#   - WorkManager (androidx.work:work-runtime)   keeps ListenableWorker subclasses + constructors
#   - OkHttp / Webkit / Coil                     ship their own -dontwarn / boundary keep rules
#
# The in-app plugin API (android/plugin-api) is a compile-time library wired through Hilt
# multibindings; there is no dynamic class loading, so it needs no dedicated rules.

# DeskCubby persists enum constant NAMES on disk (DataStore, v28 JSON backup, cloud-sync
# codecs and the plugin API all use Enum.valueOf / enumValues { it.name } on strings that
# cross the process boundary). R8 obfuscates enum constant names by default and rewrites
# valueOf()/name() call sites, but it cannot see values deserialized from existing files, so
# renaming them would corrupt previously stored settings/backups and break plugin JSON.
# Keeping every enum field name (the constant's serialized representation) guards this
# without keeping the whole enum or disabling shrinking/obfuscation of the class itself.
# values()/valueOf(String) are also kept explicitly as a belt-and-suspenders guarantee over
# the defaults in proguard-android-optimize.txt.
-keepclassmembers enum * {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep source file + line numbers in release stack traces for easier crash diagnosis.
# Mapping is still produced (AGP writes an obfuscation mapping automatically).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile