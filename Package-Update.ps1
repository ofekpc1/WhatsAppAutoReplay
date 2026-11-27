param(
 [Parameter(Mandatory=$true)][string]$Apk,
 [Parameter(Mandatory=$true)][string]$PreviousApk,
 [Parameter(Mandatory=$true)][string]$OutputDirectory,
 [Parameter(Mandatory=$true)][string]$BuildTools
)
$ErrorActionPreference='Stop'
$repo=$PSScriptRoot
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
function Identity([string]$Path){
 $data=& (Join-Path $BuildTools 'aapt.exe') dump badging $Path
 if($LASTEXITCODE){throw 'Cannot read APK identity'}
 $match=[regex]::Match(($data -join "`n"),"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'")
 if(!$match.Success){throw 'Missing APK identity'}
 return @{Package=$match.Groups[1].Value;Code=[long]$match.Groups[2].Value;Version=$match.Groups[3].Value;Debuggable=[bool]($data -match 'application-debuggable')}
}
function Certificate([string]$Path){
 $data=& $java -jar (Join-Path $BuildTools 'lib/apksigner.jar') verify --verbose --print-certs $Path
 if($LASTEXITCODE){throw 'APK signature invalid'}
 $certs=@([regex]::Matches(($data -join "`n"),'certificate SHA-256 digest: ([a-fA-F0-9]{64})')|ForEach-Object {$_.Groups[1].Value}|Sort-Object -Unique)
 if(!$certs.Count){throw 'No certificate'}
 return ($certs -join ',')
}
$current=Identity $Apk
$old=Identity $PreviousApk
$flavor=if($current.Package -eq 'com.privateapps.autoreplay'){'automatic'}elseif($current.Package -eq 'com.privateapps.autoreplay.diagnostic.noaccessibility'){'noAccessibility'}else{throw 'Unknown package'}
$gradle=[IO.File]::ReadAllText((Join-Path $repo 'app/build.gradle.kts'))
$code=[int][regex]::Match($gradle,'versionCode = (\d+)').Groups[1].Value
$version=[regex]::Match($gradle,'versionName = "([^"]+)"').Groups[1].Value
if($flavor -eq 'noAccessibility'){$version+='-diagnostic-no-accessibility'}
if($current.Package -ne $old.Package -or $current.Code -le $old.Code -or $current.Code -ne $code -or $current.Version -ne $version -or $current.Debuggable){throw 'APK is not a compatible release upgrade of the expected source version'}
$certificate=Certificate $Apk
if($certificate -ne (Certificate $PreviousApk)){throw 'Signing key differs from the previous installed APK; do not publish'}
& (Join-Path $BuildTools 'zipalign.exe') -c 4 $Apk
if($LASTEXITCODE){throw 'APK alignment invalid'}
$variant=$flavor.Substring(0,1).ToUpperInvariant()+$flavor.Substring(1)
$tests=0
foreach($f in Get-ChildItem (Join-Path $repo "app/build/test-results/test${variant}DebugUnitTest") -Filter 'TEST-*.xml'){
 $x=[xml](Get-Content $f.FullName -Raw)
 if([int]$x.testsuite.failures -or [int]$x.testsuite.errors -or [int]$x.testsuite.skipped){throw 'Tests failed or skipped'}
 $tests+=[int]$x.testsuite.tests
}
if($tests -lt 96){throw 'Expected regression tests missing'}
$lint=[xml](Get-Content (Join-Path $repo "app/build/reports/lint-results-${flavor}Debug.xml") -Raw)
if(@($lint.issues.issue|Where-Object {$_.severity -in @('Error','Fatal')}).Count){throw 'Lint failed'}
$out=[IO.Path]::GetFullPath($OutputDirectory)
if(!$out.StartsWith((Join-Path $repo 'artifacts\updates\'),[StringComparison]::OrdinalIgnoreCase)){throw 'Output must be inside artifacts/updates'}
New-Item -ItemType Directory -Force $out|Out-Null
$name='Whatsapp-Auto-replay-'+$version+'.apk'
$hash=(Get-FileHash $Apk).Hash
$target=Join-Path $out $name
if((Test-Path $target) -and (Get-FileHash $target).Hash -ne $hash){throw 'Immutable release collision'}
Copy-Item -LiteralPath $Apk -Destination $target
$source=@(Get-ChildItem (Join-Path $repo 'app/src') -Recurse -File|ForEach-Object {@{Path=$_.FullName.Substring($repo.Length+1);Sha256=(Get-FileHash $_.FullName).Hash}})
$source+=@{Path='app/build.gradle.kts';Sha256=(Get-FileHash (Join-Path $repo 'app/build.gradle.kts')).Hash}
$record=@{Package=$current.Package;Version=$version;VersionCode=$code;Commit=(& git -C $repo rev-parse HEAD).Trim();AndroidTests=$tests;LintErrors=0;LintWarnings=@($lint.issues.issue|Where-Object {$_.severity -eq 'Warning'}).Count;SameSigningKey=$true;NonDebuggable=$true;ZipAligned=$true;ApkSha256=$hash;PreviousApkSha256=(Get-FileHash $PreviousApk).Hash;Certificate=$certificate;SourceFiles=$source;PhysicalDeviceChecksPending=$true}
$record|ConvertTo-Json -Depth 8|Set-Content (Join-Path $out 'validation.json') -Encoding UTF8
"$hash  $name"|Set-Content (Join-Path $out 'SHA256SUMS.txt') -Encoding ascii
'בדיקת עדכונים והורדה מתוך האפליקציה. לפני התקנה יש לשמור שינויים ולהשהות את המענה; לאחריה יש להפעילו מחדש.'|Set-Content (Join-Path $out 'update-notes.he.txt') -Encoding UTF8
[pscustomobject]$record|Select-Object Version,VersionCode,AndroidTests,LintErrors,SameSigningKey,ApkSha256|ConvertTo-Json
