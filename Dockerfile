# Dockerfile
#
# Builds the GPS Waypoint Navigator Android app using the
# docker-android-build-box image (budtmo/docker-android-build-box).
#
# Usage (from the project root):
#   docker build -t gps-waypoint-builder .
#   docker run --rm -v "$(pwd)":/app gps-waypoint-builder
#
# The resulting APK will appear in:
#   app/build/outputs/apk/debug/app-debug.apk

FROM mingc/android-build-box:latest

# Set working directory inside the container to /app.
# The host project directory is mounted here at docker run time.
WORKDIR /app

# Copy only the Gradle wrapper first to leverage layer caching —
# dependencies are re-downloaded only when build files change.
COPY gradle/          ./gradle/
COPY gradlew          ./gradlew
COPY build.gradle     ./build.gradle
COPY settings.gradle  ./settings.gradle
COPY gradle.properties ./gradle.properties

# Ensure the Gradle wrapper is executable.
RUN chmod +x gradlew

# Pre-download dependencies (separate layer for caching).
RUN ./gradlew dependencies --no-daemon || true

# Copy the rest of the source tree.
COPY app/ ./app/

# Run the unit tests and assemble the debug APK.
# --no-daemon avoids a background process lingering in a container.
CMD ["./gradlew", "test", "assembleDebug", "--no-daemon", "--stacktrace"]
