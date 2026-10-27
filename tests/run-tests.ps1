$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    New-Item -ItemType Directory -Force 'build/policy-tests' | Out-Null
    java -m jdk.compiler/com.sun.tools.javac.Main --release 8 -d build/policy-tests app/src/main/java/io/github/jamatnat/morningskip/DismissController.java app/src/main/java/io/github/jamatnat/morningskip/McuGreetingProtocol.java app/src/main/java/io/github/jamatnat/morningskip/McuCloseController.java app/src/main/java/io/github/jamatnat/morningskip/GreetingRecoveryPolicy.java tests/DismissControllerTest.java tests/McuGreetingTest.java tests/GreetingRecoveryTest.java
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    java -cp build/policy-tests io.github.jamatnat.morningskip.DismissControllerTest
    if ($LASTEXITCODE -ne 0) { throw 'Regression checks failed' }
    java -cp build/policy-tests io.github.jamatnat.morningskip.McuGreetingTest
    if ($LASTEXITCODE -ne 0) { throw 'MCU regression checks failed' }
    java -cp build/policy-tests io.github.jamatnat.morningskip.GreetingRecoveryTest
    if ($LASTEXITCODE -ne 0) { throw 'UI/MCU recovery checks failed' }
} finally { Pop-Location }
