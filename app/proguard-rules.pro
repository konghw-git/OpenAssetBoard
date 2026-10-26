# kotlinx.serialization and Room ship their own consumer rules.

# Glance builds a widget's ActionCallback by reflection from its class name, through the empty
# constructor; R8 sees no caller and drops it, and a tap on a release build does nothing.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
