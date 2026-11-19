# Auto Skip - 无 Gradle 构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
$ErrorActionPreference = 'Stop'

$root   = 'D:\workspace\auto-skip'
$sdk    = 'D:\Android\Sdk'
$bt     = Join-Path $sdk 'build-tools\35.0.1'
$jar    = Join-Path $sdk 'platforms\android-35\android.jar'
$jdk    = 'D:\tools\jdk-21'
$java   = Join-Path $jdk 'bin\java.exe'
$javac  = Join-Path $jdk 'bin\javac.exe'
$keytool= Join-Path $jdk 'bin\keytool.exe'
$build  = Join-Path $root 'build'
$dist   = Join-Path $root 'dist'
$ksDir  = Join-Path $root 'keystore'
$ks     = Join-Path $ksDir 'autoskip.jks'

Write-Host '== 0. clean =='
Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $build, "$build\gen", "$build\classes", "$build\dex", $dist, $ksDir | Out-Null

Write-Host '== 1. aapt2 compile res =='
& "$bt\aapt2.exe" compile --dir (Join-Path $root 'res') -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '== 2. aapt2 link =='
& "$bt\aapt2.exe" link -o "$build\app-unsigned.apk" -I $jar `
    --manifest (Join-Path $root 'AndroidManifest.xml') `
    "$build\res.zip" --java "$build\gen" `
    --min-sdk-version 24 --target-sdk-version 33 `
    --version-code 1 --version-name 1.0
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '== 3. javac =='
$srcs = @()
$srcs += Get-ChildItem -Recurse (Join-Path $root 'java') -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem -Recurse "$build\gen" -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $jar -nowarn -d "$build\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '== 4. d8 =='
$classes = Get-ChildItem -Recurse "$build\classes" -Filter *.class | ForEach-Object { $_.FullName }
& $java -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --release --lib $jar --min-api 24 --output "$build\dex" $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '== 5. add classes.dex =='
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\app-unsigned.apk", 'Update')
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$out = $entry.Open()
$bytes = [System.IO.File]::ReadAllBytes("$build\dex\classes.dex")
$out.Write($bytes, 0, $bytes.Length)
$out.Close()
$zip.Dispose()

Write-Host '== 6. zipalign =='
& "$bt\zipalign.exe" -f -p 4 "$build\app-unsigned.apk" "$build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '== 7. keystore =='
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -v -keystore $ks -storepass android -keypass android `
        -alias autoskip -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Auto Skip, O=AutoSkip, C=CN" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

Write-Host '== 8. apksigner sign =='
& $java -jar "$bt\lib\apksigner.jar" sign `
    --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out (Join-Path $dist 'auto-skip-1.1.apk') "$build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }

Write-Host '== 9. verify =='
& $java -jar "$bt\lib\apksigner.jar" verify --print-certs (Join-Path $dist 'auto-skip-1.1.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$apk = Join-Path $dist 'auto-skip-1.1.apk'
Write-Host ("OK: " + $apk + "  " + (Get-Item $apk).Length + " bytes")
