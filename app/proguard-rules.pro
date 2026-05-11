# ProGuard rules for GPS Waypoint Navigator.
# The release build has minifyEnabled=false so these rules are not applied by
# default.  Add rules here if you enable minification in future.

# Keep all data model classes (used in LiveData / parcelisation)
-keep class com.example.gpswaypoint.model.** { *; }

# Keep the service so the system can bind to it by name
-keep class com.example.gpswaypoint.service.NavigationService { *; }
