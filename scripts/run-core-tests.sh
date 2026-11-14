#!/usr/bin/env bash
# Runs the pure-Java unit tests (state machine, motion/fall detection, queue)
# without the ATAK SDK or Android SDK. Needs a JDK (11+) and network access to
# Maven Central the first time (downloads JUnit 4 + Hamcrest into .test-libs/).
#
# With the SDK installed you can instead run:  ./gradlew testCivDebugUnitTest
set -euo pipefail
cd "$(dirname "$0")/.."

LIBS=.test-libs
OUT=build/core-tests
mkdir -p "$LIBS" "$OUT"

fetch() {
  local url=$1 dest=$2
  [ -s "$dest" ] || curl -sSfL -o "$dest" "$url"
}
fetch https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar "$LIBS/junit.jar"
fetch https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar "$LIBS/hamcrest.jar"
CP="$LIBS/junit.jar:$LIBS/hamcrest.jar"

SRC=app/src/main/java/com/atakmap/android/lonewolf
javac -d "$OUT" -cp "$CP" \
  "$SRC/core/AlarmStateMachine.java" \
  "$SRC/sensor/MotionDetector.java" \
  "$SRC/sensor/FallDetector.java" \
  "$SRC/cot/OutboundQueue.java" \
  $(find app/src/test/java -name '*Test.java')

java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
  com.atakmap.android.lonewolf.core.AlarmStateMachineTest \
  com.atakmap.android.lonewolf.sensor.MotionDetectorTest \
  com.atakmap.android.lonewolf.sensor.FallDetectorTest \
  com.atakmap.android.lonewolf.cot.OutboundQueueTest
