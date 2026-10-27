#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/policy-tests
java -m jdk.compiler/com.sun.tools.javac.Main --release 8 -d build/policy-tests \
  app/src/main/java/io/github/jamatnat/morningskip/DismissController.java app/src/main/java/io/github/jamatnat/morningskip/McuGreetingProtocol.java app/src/main/java/io/github/jamatnat/morningskip/McuCloseController.java app/src/main/java/io/github/jamatnat/morningskip/GreetingRecoveryPolicy.java tests/DismissControllerTest.java tests/McuGreetingTest.java tests/GreetingRecoveryTest.java
java -cp build/policy-tests io.github.jamatnat.morningskip.DismissControllerTest
java -cp build/policy-tests io.github.jamatnat.morningskip.McuGreetingTest
java -cp build/policy-tests io.github.jamatnat.morningskip.GreetingRecoveryTest
