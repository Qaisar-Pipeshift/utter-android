# Release builds carry no verbose/debug/info logging (docs/spec.md §10).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
