# One-command corrected-demo validation: hash/packaging/NPU verify + install -r + on-device synthetic sweep.
# Never uninstalls, never clears app data; aborts on any signature/install failure.
param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$ExpectedSha256,
    [string]$DeviceSerial = '192.168.100.223:40193',
    [string]$PackageId = 'app.kanade.tachiyomi.vibe.debug',
    [string]$AndroidTestApkPath = '',
    [switch]$RunSweep,
    [switch]$SkipInstall,
    [switch]$ClearLogcat
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\ADMIN\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$script:failed = $false

function Step([string]$name, [bool]$ok, [string]$detail) {
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Output ("[{0}] {1}: {2}" -f $tag, $name, $detail)
    if (-not $ok) { $script:failed = $true }
}

# 1. APK hash
$apkItem = Get-Item $ApkPath
$apkHash = (Get-FileHash -Algorithm SHA256 $ApkPath).Hash.ToLower()
Step 'apk-hash' ($apkHash -eq $ExpectedSha256.ToLower()) ("{0} ({1} bytes, built {2})" -f $apkHash, $apkItem.Length, $apkItem.LastWriteTime)
if ($script:failed) { Write-Output 'VALIDATION_FAIL (apk hash mismatch — refusing install)'; exit 1 }

# 2. Packaging: NPU artifact + QNN libs pinned inside the APK; dex carries the shader fix
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pinned = @{
    'manga_colorize_npu_sm8650_fp32.tflite' = 'd3d26e5e1af43213a8b6d9a9cca23b30e1f432e5f2dbf02398ef31199a7ff1f4'
    'libQnnHtp.so' = '09e930b6af975bae23bcf6965ac2ce60f6b999aff37cb7eabe9b4d7c1bc3f264'
    'libQnnSystem.so' = 'b30bd9880dc48f6c5349185a7d45b326f8a064b10072e60094354fa23d9a2027'
    'libQnnHtpV75Stub.so' = '8be2bf0b0c6839d3e3bf9d8435e9b92d3fde0ccdea343d89960d75285ad28dab'
    'libQnnHtpV75Skel.so' = '2f6cfe5aae553d8ec88b6741bf8598a557375eacfc58056c2667fc43fda64f6a'
}
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("opencode\dexcheck-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkItem.FullName)
try {
    foreach ($name in $pinned.Keys) {
        $entry = $zip.Entries | Where-Object { $_.FullName -match [regex]::Escape($name) } | Select-Object -First 1
        if ($null -eq $entry) { Step "packaging/$name" $false 'entry missing from APK'; continue }
        $dest = Join-Path $work $name
        $in = $entry.Open(); $out = [System.IO.File]::Create($dest); $in.CopyTo($out); $out.Dispose(); $in.Dispose()
        $entryHash = (Get-FileHash -Algorithm SHA256 $dest).Hash.ToLower()
        Step "packaging/$name" ($entryHash -eq $pinned[$name]) ("sha256 {0} ({1} bytes)" -f $entryHash, $entry.Length)
    }
    foreach ($entry in ($zip.Entries | Where-Object { $_.FullName -match '^classes.*\.dex$' })) {
        $dest = Join-Path $work $entry.FullName
        $in = $entry.Open(); $out = [System.IO.File]::Create($dest); $in.CopyTo($out); $out.Dispose(); $in.Dispose()
    }
} finally { $zip.Dispose() }
$dexHit = (Get-ChildItem $work -Filter 'classes*.dex' | ForEach-Object {
    Select-String -Path $_.FullName -Pattern 'setLocalMatrix' -Quiet
}) -contains $true
Step 'packaging/shader-fix-dex' $dexHit 'setLocalMatrix present in dex (uOrig local-matrix fix embedded)'
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue

# 3. Device reachability (explicit targeting, never falls back to another device)
& $adb connect $DeviceSerial | Out-Null
$state = (& $adb -s $DeviceSerial get-state 2>&1) | Out-String
Step 'device' ($state.Trim() -eq 'device') ("$DeviceSerial state=$($state.Trim())")
if ($script:failed) { Write-Output 'VALIDATION_FAIL (device unreachable)'; exit 1 }

$pkgInfo = (& $adb -s $DeviceSerial shell "dumpsys package $PackageId | grep -E 'versionName|versionCode|lastUpdateTime|firstInstallTime'") -join "`n"
$preFirstInstall = ([regex]::Matches($pkgInfo, 'firstInstallTime=(.+)') | ForEach-Object { $_.Groups[1].Value.Trim() } | Select-Object -Unique) -join ','
if (-not $SkipInstall) {
    # 4. Install -r only. The script contains no uninstall/clear commands by construction.
    $installOut = (& $adb -s $DeviceSerial install -r $ApkPath) | Out-String
    Step 'install-r' ($installOut -match 'Success') ($installOut.Trim() -replace "`r?`n", ' | ')
    if ($script:failed) { Write-Output 'VALIDATION_FAIL (installer refused — data untouched, nothing cleared; report reason above)'; exit 1 }
}

# 5. Package state: fresh lastUpdateTime, firstInstallTime preserved
$pkgInfo = (& $adb -s $DeviceSerial shell "dumpsys package $PackageId | grep -E 'versionName|versionCode|lastUpdateTime|firstInstallTime'") -join "`n"
Write-Output $pkgInfo.Trim()
$postFirstInstall = ([regex]::Matches($pkgInfo, 'firstInstallTime=(.+)') | ForEach-Object { $_.Groups[1].Value.Trim() } | Select-Object -Unique) -join ','
Step 'package-identity' (($null -ne $pkgInfo) -and ($pkgInfo -match 'versionCode')) "firstInstall before=$preFirstInstall after=$postFirstInstall"

# 6. NPU artifact cache pin on device (informational; engine re-hashes the real file every init)
$cacheInfo = (& $adb -s $DeviceSerial shell "run-as $PackageId cat shared_prefs/mcv2_npu_model_cache.xml 2>/dev/null") | Out-String
$cacheOk = $cacheInfo -match 'd3d26e5e1af43213a8b6d9a9cca23b30e1f432e5f2dbf02398ef31199a7ff1f4'
Step 'npu-cache-pin' $cacheOk $(if ($cacheOk) { 'cached artifact sha256 = pinned d3d26e5e…' } else { 'cache not readable/present (engine re-extracts + re-hashes on first use; not fatal)' })

# 7. Optional on-device synthetic alignment sweep
if ($RunSweep) {
    if ($AndroidTestApkPath -eq '') { Step 'sweep' $false 'requested but -AndroidTestApkPath not given' }
    else {
        $testInstall = (& $adb -s $DeviceSerial install -r $AndroidTestApkPath) | Out-String
        Step 'sweep/install-test-apk' ($testInstall -match 'Success') ($testInstall.Trim() -replace "`r?`n", ' | ')
        if ($ClearLogcat) { & $adb -s $DeviceSerial logcat -c | Out-Null }
        $instrument = (& $adb -s $DeviceSerial shell "am instrument -w -e class eu.kanade.translation.engines.coloring.AgslChromaCombinerAlignmentTest $PackageId.test/androidx.test.runner.AndroidJUnit4") | Out-String
        Write-Output $instrument.Trim()
        Step 'sweep/instrument' (($instrument -match 'OK \(\d+ tests?\)') -and -not ($instrument -match 'Failures: [1-9]')) 'see instrument output above; [AgslAlign] lines via: adb -s DEVICE logcat -d -s System.out'
    }
}

if ($script:failed) { Write-Output 'VALIDATION_FAIL'; exit 1 }
Write-Output 'VALIDATION_PASS'
