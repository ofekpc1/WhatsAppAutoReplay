param(
 [Parameter(Mandatory=$true)][string]$ArtifactDirectory,
 [Parameter(Mandatory=$true)][ValidateSet('Stage','Production')][string]$Environment
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if([IO.Path]::GetFullPath($repo) -ne 'C:\Git-Hub\NopShop.PriceComparison\.local\WhatsAppAutoReplay'){throw 'Run from the canonical repository tools directory'}
$artifact=[IO.Path]::GetFullPath($ArtifactDirectory)
if(!$artifact.StartsWith((Join-Path $repo 'artifacts\updates\'),[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected artifact directory'}
$v=Get-Content (Join-Path $artifact 'validation.json') -Raw|ConvertFrom-Json
if($v.Version -notmatch '^\d[\w.\-]{0,63}$' -or $v.VersionCode -lt 6 -or $v.AndroidTests -lt 96 -or $v.LintErrors -ne 0 -or !$v.SameSigningKey -or !$v.NonDebuggable -or !$v.ZipAligned){throw 'Unvalidated release'}
$manifestName=if($v.Package -eq 'com.privateapps.autoreplay'){'latest.json'}elseif($v.Package -eq 'com.privateapps.autoreplay.diagnostic.noaccessibility'){'latest-diagnostic.json'}else{throw 'Unexpected package'}
$name='Whatsapp-Auto-replay-'+$v.Version+'.apk'
$apk=Join-Path $artifact $name
if((Get-FileHash $apk).Hash -ne $v.ApkSha256){throw 'APK differs from validated release'}
foreach($f in $v.SourceFiles){if((Get-FileHash (Join-Path $repo $f.Path)).Hash -ne $f.Sha256){throw ('Source changed: '+$f.Path)}}
$remoteBranches=@(& git -C $repo branch -r --contains $v.Commit)
if($LASTEXITCODE -or !@($remoteBranches|Where-Object {$_ -match '^\s*origin/'}).Count){throw 'Release source is not pushed to origin'}
$baseControl=Join-Path $repo ('.local/update-releases/'+$v.Version)
$control=Join-Path $baseControl $Environment
New-Item -ItemType Directory -Force $control|Out-Null
$metadata=[ordered]@{packageName=$v.Package;versionCode=$v.VersionCode;versionName=$v.Version;url=('https://ofekpc.co.il/downloads/whatsapp-autoreplay/'+$name+'.bin');sha256=$v.ApkSha256.ToLowerInvariant();size=(Get-Item $apk).Length;notes=(Get-Content (Join-Path $artifact 'update-notes.he.txt') -Raw -Encoding UTF8).Trim()}
if($metadata.notes.Length -gt 4000){throw 'Release notes too long'}
$json=$metadata|ConvertTo-Json -Compress
$utf8=[Text.UTF8Encoding]::new($false)
$manifestFile=Join-Path $control 'expected-latest.json'
[IO.File]::WriteAllText($manifestFile,$json,$utf8)
$manifestHash=(Get-FileHash $manifestFile).Hash
if($Environment -eq 'Stage'){
 $root='C:\Git-Hub\OfekPC.nopCommerce5\.local\stage-runtime'
 $baseUrl='https://localhost:55005'
 $state=Get-Content 'C:\Git-Hub\OfekPC.nopCommerce5\.local\stage-control\website.json' -Raw|ConvertFrom-Json
 if($state.Database -ne 'OfekPC5_Stage_20261106_R1' -or $state.Endpoint -ne $baseUrl -or !$state.ApplicationStarted){throw 'Isolated stage is not ready'}
 $env:DOTNET_ROOT='C:\Git-Hub\OfekPC.nopCommerce5\.dotnet'
 & 'C:\Git-Hub\OfekPC.nopCommerce5\.artifacts\stage-isolation-tool\StageIsolation.exe' verify
 if($LASTEXITCODE){throw 'Stage isolation invalid'}
}else{
 $proof=Get-Content (Join-Path $baseControl 'Stage/verified.json') -Raw|ConvertFrom-Json
 if($proof.Status -ne 'Verified' -or $proof.ApkSha256 -ne $v.ApkSha256 -or $proof.ManifestSha256 -ne $manifestHash){throw 'Exact stage acceptance required'}
 $root='C:\HostingSpaces\ofekpc\ofekpc.co.il\wwwroot'
 $baseUrl='https://ofekpc.co.il'
}
$protected=@('web.config','Nop.Web.dll','Nop.Web.Framework.dll','App_Data/appsettings.json','App_Data/plugins.json')
$before=@{}
foreach($path in $protected){$full=Join-Path $root $path;if(Test-Path $full){$before[$path]=(Get-FileHash $full).Hash}}
$destination=Join-Path $root 'wwwroot/downloads/whatsapp-autoreplay'
New-Item -ItemType Directory -Force $destination|Out-Null
$publishedApk=Join-Path $destination ($name+'.bin')
$latest=Join-Path $destination $manifestName
$prior=$null
if(Test-Path $latest){$prior=[IO.File]::ReadAllBytes($latest);$old=[Text.Encoding]::UTF8.GetString($prior)|ConvertFrom-Json;if([long]$old.versionCode -gt [long]$v.VersionCode){throw 'Refusing release downgrade'};if([long]$old.versionCode -eq [long]$v.VersionCode -and $old.sha256 -ne $metadata.sha256){throw 'Version already published with another APK'}}
if(Test-Path $publishedApk){if((Get-FileHash $publishedApk).Hash -ne $v.ApkSha256){throw 'Immutable APK collision'}}else{
 $temporary=Join-Path $destination ([guid]::NewGuid().ToString()+'.tmp')
 try{Copy-Item -LiteralPath $apk -Destination $temporary;if((Get-FileHash $temporary).Hash -ne $v.ApkSha256){throw 'Copy failed'};Move-Item -LiteralPath $temporary -Destination $publishedApk}finally{if(Test-Path $temporary){Remove-Item -LiteralPath $temporary}}
}
function Write-Manifest([byte[]]$Bytes){
 $temp=Join-Path $destination ([guid]::NewGuid().ToString()+'.tmp')
 try{[IO.File]::WriteAllBytes($temp,$Bytes);if(Test-Path $latest){[IO.File]::Replace($temp,$latest,[NullString]::Value)}else{[IO.File]::Move($temp,$latest)}}finally{if(Test-Path $temp){Remove-Item -LiteralPath $temp}}
}
function Fetch([string]$Url,[string]$Output){
 $args=@('--fail','--silent','--show-error','--max-time','60','--output',$Output,'--write-out','%{http_code}|%{content_type}')
 if($Environment -eq 'Stage'){$args+='--insecure'}
 $result=& curl.exe @args $Url
 if($LASTEXITCODE -or $result -notmatch '^200\|'){throw ('Download failed: '+$result)}
 return $result
}
$changed=$false
try{
 $apkHttp=Fetch ($baseUrl+'/downloads/whatsapp-autoreplay/'+$name+'.bin') (Join-Path $control 'download.apk')
 if($apkHttp -notmatch '^200\|application/octet-stream' -or (Get-FileHash (Join-Path $control 'download.apk')).Hash -ne $v.ApkSha256){throw 'Hosted APK verification failed'}
 Write-Manifest $utf8.GetBytes($json);$changed=$true
 $jsonHttp=Fetch ($baseUrl+'/downloads/whatsapp-autoreplay/'+$manifestName+'?check='+[guid]::NewGuid().ToString('N')) (Join-Path $control 'download.json')
 if($jsonHttp -notmatch '^200\|application/json' -or (Get-FileHash (Join-Path $control 'download.json')).Hash -ne $manifestHash){throw 'Hosted manifest verification failed'}
 foreach($path in $before.Keys){if((Get-FileHash (Join-Path $root $path)).Hash -ne $before[$path]){throw ('Protected file changed: '+$path)}}
 $record=@{Status='Verified';Environment=$Environment;Version=$v.Version;VersionCode=$v.VersionCode;Commit=$v.Commit;ApkSha256=$v.ApkSha256;ManifestSha256=$manifestHash;ApkHttp=$apkHttp;ManifestHttp=$jsonHttp;ProtectedFiles=$before;Restarted=$false;DatabaseChanged=$false;VerifiedUtc=[DateTime]::UtcNow.ToString('o')}
 $record|ConvertTo-Json -Depth 5|Set-Content (Join-Path $control 'verified.json') -Encoding UTF8
 [pscustomobject]$record|Select-Object Status,Environment,Version,Restarted,DatabaseChanged|ConvertTo-Json
}catch{
 if($changed){if($null -ne $prior){Write-Manifest $prior}else{Remove-Item -LiteralPath $latest}}
 throw
}
