param([string]$Aapt2)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$resRoot = Join-Path $projectRoot 'app/src/main/res'
$androidNs = 'http://schemas.android.com/apk/res/android'
$resourceNames = @{}
$resourceFiles = Get-ChildItem -LiteralPath $resRoot -Recurse -File -Filter '*.xml'
foreach ($file in $resourceFiles) {
    [xml]$doc = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName
    $type = $file.Directory.Name.Split('-')[0]
    if ($type -eq 'values') {
        foreach ($node in $doc.resources.ChildNodes) {
            if ($node -is [System.Xml.XmlElement] -and $node.HasAttribute('name')) {
                $resourceNames[$node.LocalName + '/' + $node.GetAttribute('name')] = $true
            }
        }
    } else {
        $resourceNames[$type + '/' + $file.BaseName] = $true
    }
}
foreach ($file in $resourceFiles) {
    $xmlText = Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName
    foreach ($reference in [regex]::Matches($xmlText, '@(string|color|drawable|layout|style|dimen)/([A-Za-z0-9_.]+)')) {
        $key = $reference.Groups[1].Value + '/' + $reference.Groups[2].Value
        if (-not $resourceNames.ContainsKey($key)) { throw "Missing resource: $key in $($file.Name)" }
    }
}

[xml]$layout = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $resRoot 'layout/activity_main.xml')
$root = $layout.DocumentElement
if ($root.Name -ne 'ScrollView' -or $root.GetAttribute('scrollbars', $androidNs) -ne 'none') {
    throw 'The page must remain scrollable with no visible scrollbars.'
}
$buttons = @($layout.SelectNodes('//Button'))
$content = $root.SelectSingleNode('LinearLayout')
if ($content.GetAttribute('paddingStart', $androidNs) -ne '@dimen/page_padding' -or
    $content.GetAttribute('paddingEnd', $androidNs) -ne '@dimen/page_padding') {
    throw 'Content padding must be inside the inset-handling ScrollView.'
}
if ($buttons.Count -ne 2) { throw 'Only the details toggle and root restart buttons are expected.' }
$ids = @{}
foreach ($node in $layout.SelectNodes('//*')) {
    $id = $node.GetAttribute('id', $androidNs)
    if ($id.StartsWith('@+id/')) { $ids[$id.Substring(5)] = $node }
}
if ($ids['details_panel'].GetAttribute('visibility', $androidNs) -ne 'gone') {
    throw 'Advanced details must be collapsed by default.'
}
if ($layout.SelectNodes('//ImageView').Count -ne 0) { throw 'Decorative image controls have returned.' }
$activity = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $projectRoot 'app/src/main/java/io/github/blacktom222/hyperos4notificationimportance/MainActivity.java')
foreach ($id in [regex]::Matches($activity, 'findViewById\(R\.id\.(\w+)\)')) {
    if (-not $ids.ContainsKey($id.Groups[1].Value)) { throw "Missing view: $($id.Groups[1].Value)" }
}
foreach ($listener in @('detailsToggle.setOnClickListener', 'restartButton.setOnClickListener')) {
    if (-not $activity.Contains($listener)) { throw "Missing listener: $listener" }
}
Write-Output "PASS: $($resourceFiles.Count) XML files, local resource references, view bindings, and UI structure."

if ($Aapt2) {
    $outputDir = Join-Path $projectRoot 'build/ui-validation'
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    & $Aapt2 compile --dir $resRoot -o (Join-Path $outputDir 'resources.zip')
    if ($LASTEXITCODE -ne 0) { throw 'Android resource compilation failed.' }
    Write-Output 'PASS: AAPT2 resource compilation (not a full APK build).'
}
