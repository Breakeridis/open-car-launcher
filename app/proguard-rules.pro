# org.json is part of the framework, nothing to keep.
# ViewBinding classes are referenced directly, R8 handles them.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# osmdroid references optional classes (SQLite/GEMF archive providers) that are not bundled.
-dontwarn org.osmdroid.**
