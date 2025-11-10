param(
    [string]$Folder = 'c:\Projects\Figma plugin',
    [string]$Pattern = '*.tif',
    [string]$NameContains = '142'
)

Add-Type -AssemblyName System.Drawing

$files = Get-ChildItem -LiteralPath $Folder -Filter $Pattern -File -ErrorAction SilentlyContinue | Sort-Object Name
if ($NameContains) {
    $files = $files | Where-Object { $_.BaseName -like "*$NameContains*" }
}
if (-not $files) {
    Write-Host "Файлы по шаблону '$Pattern' не найдены в '$Folder'."
    return
}

$bitmaps = @{}
$info = @()

foreach ($file in $files) {
    $img = [System.Drawing.Bitmap]::new($file.FullName)
    $bitmaps[$file.Name] = $img
    $info += [PSCustomObject]@{
        FileName    = $file.Name
        Width       = $img.Width
        Height      = $img.Height
        DpiX        = [math]::Round($img.HorizontalResolution, 2)
        DpiY        = [math]::Round($img.VerticalResolution, 2)
        PixelFormat = $img.PixelFormat
    }
}

$info | Format-Table | Out-String -Width 200 | Write-Host

function Compare-Bitmaps {
    param (
        [System.Drawing.Bitmap]$a,
        [System.Drawing.Bitmap]$b,
        [string]$name
    )

    if ($null -eq $a -or $null -eq $b) {
        Write-Host "Cannot compare $name"
        return
    }

    $width = [Math]::Min($a.Width, $b.Width)
    $height = [Math]::Min($a.Height, $b.Height)
    $stepX = [Math]::Max([Math]::Floor($width / 400), 1)
    $stepY = [Math]::Max([Math]::Floor($height / 400), 1)

    $sum = 0.0
    $count = 0

    for ($y = 0; $y -lt $height; $y += $stepY) {
        for ($x = 0; $x -lt $width; $x += $stepX) {
            $ca = $a.GetPixel($x, $y)
            $cb = $b.GetPixel($x, $y)

            $sum += [Math]::Abs($ca.R - $cb.R)
            $sum += [Math]::Abs($ca.G - $cb.G)
            $sum += [Math]::Abs($ca.B - $cb.B)
            $count += 3
        }
    }

    $avg = if ($count -eq 0) { 0 } else { $sum / $count }
    Write-Host "$name average channel diff: $([math]::Round($avg, 3))"
}

$names = @($bitmaps.Keys | Sort-Object)
if ($names.Count -ge 2) {
    Compare-Bitmaps $bitmaps[$names[0]] $bitmaps[$names[1]] "$($names[0]) vs $($names[1])"
}
if ($names.Count -ge 3) {
    Compare-Bitmaps $bitmaps[$names[0]] $bitmaps[$names[2]] "$($names[0]) vs $($names[2])"
    Compare-Bitmaps $bitmaps[$names[1]] $bitmaps[$names[2]] "$($names[1]) vs $($names[2])"
}

foreach ($img in $bitmaps.Values) {
    $img.Dispose()
}
