#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/policy-tests
java -m jdk.compiler/com.sun.tools.javac.Main --release 8 -d build/policy-tests \
  app/src/main/java/io/github/jamatnat/morningskip/DismissController.java app/src/main/java/io/github/jamatnat/morningskip/McuGreetingProtocol.java app/src/main/java/io/github/jamatnat/morningskip/McuCloseController.java app/src/main/java/io/github/jamatnat/morningskip/GreetingRecoveryPolicy.java tests/DismissControllerTest.java tests/McuGreetingTest.java tests/GreetingRecoveryTest.java
java -cp build/policy-tests io.github.jamatnat.morningskip.DismissControllerTest
java -cp build/policy-tests io.github.jamatnat.morningskip.McuGreetingTest
java -cp build/policy-tests io.github.jamatnat.morningskip.GreetingRecoveryTest

java -m jdk.compiler/com.sun.tools.javac.Main --release 8 -d build/policy-tests \
  app/src/main/java/io/github/jamatnat/morningskip/NativeReadyGate.java tests/NativeReadyTest.java
java -cp build/policy-tests io.github.jamatnat.morningskip.NativeReadyTest
java -m jdk.compiler/com.sun.tools.javac.Main --release 8 -d build/policy-tests \
  app/src/main/java/io/github/jamatnat/morningskip/HiddenClosePolicy.java tests/HiddenCloseTest.java
java -cp build/policy-tests io.github.jamatnat.morningskip.HiddenCloseTest
# Keep the production service free from direct controller sends and shell recovery paths.
if grep -nE 'sendMessageToMcu|sendMcuCommand|queryMcuGreeting|ProcessBuilder|Runtime.getRuntime' app/src/main/java/io/github/jamatnat/morningskip/SysUiProbeService.java; then
  echo 'FAIL: native-only service contains an external close/restart path' >&2
  exit 1
fi
