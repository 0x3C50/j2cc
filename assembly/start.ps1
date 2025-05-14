# Check for JAVA_HOME, fallback to java in PATH
if ($env:JAVA_HOME) {
    $javaCmd = Join-Path $env:JAVA_HOME "bin\java.exe"
} else {
    $javaCmd = "java.exe"
}

# Check if Java is accessible
$null = & $javaCmd -version 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Error "Java not found. Please set JAVA_HOME or ensure java.exe is in your PATH."
    exit 1
}

# Launch the Java application with the required argument
$env:PATH = "$PSScriptRoot/natives;$env:PATH"
& $javaCmd --enable-native-access=ALL-UNNAMED -jar "$PSScriptRoot/j2cc-@version@.jar" @args
