[CmdletBinding()]
param(
    [ValidateSet("PixelArt", "Current", "MagicBook")]
    [string]$Variant = "PixelArt",
    [string]$Source
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.Drawing

$windowsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $windowsRoot ".."))
$outputDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $windowsRoot "src-tauri\icons")
)
$frontendAssetDirectory = [System.IO.Path]::GetFullPath(
    (Join-Path $windowsRoot "src\assets")
)

if ($Variant -eq "PixelArt") {
    # Keep the canonical Windows artwork in the repository so regenerating an
    # installer never depends on a developer's desktop. PixelArt preserves the
    # transparent canvas and source palette; smaller outputs use nearest-neighbor
    # sampling without a generated background, rounded mask, or smoothing.
    $defaultSourceName = $null
    $backgroundColor = [System.Drawing.Color]::Transparent
    $sourceInsetFraction = 0.0
    $preservePixelArt = $true
}
elseif ($Variant -eq "MagicBook") {
    $defaultSourceName = "ic_launcher_book_foreground.png"
    $backgroundColor = [System.Drawing.Color]::FromArgb(255, 255, 253, 248)
    $sourceInsetFraction = 0.0
    $preservePixelArt = $false
}
else {
    # Mirrors mipmap-anydpi/ic_launcher.xml: a black adaptive background plus
    # drawable/ic_launcher_art_inset.xml, whose source is inset by 20%.
    $defaultSourceName = "ic_launcher_art.png"
    $backgroundColor = [System.Drawing.Color]::Black
    $sourceInsetFraction = 0.2
    $preservePixelArt = $false
}

if ([string]::IsNullOrWhiteSpace($Source)) {
    if ($Variant -eq "PixelArt") {
        $Source = Join-Path $windowsRoot "app-icon.png"
    }
    else {
        $Source = Join-Path $repositoryRoot (
            "android\app\src\main\res\drawable-nodpi\" + $defaultSourceName
        )
    }
}

$resolvedSource = (Resolve-Path -LiteralPath $Source).Path
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $frontendAssetDirectory | Out-Null

function New-DeskCubbyBitmap {
    param(
        [Parameter(Mandatory)]
        [System.Drawing.Image]$SourceImage,
        [Parameter(Mandatory)]
        [int]$Size,
        [Parameter(Mandatory)]
        [System.Drawing.Color]$BackgroundColor,
        [Parameter(Mandatory)]
        [double]$SourceInsetFraction,
        [Parameter(Mandatory)]
        [bool]$PreservePixelArt
    )

    $bitmap = [System.Drawing.Bitmap]::new(
        $Size,
        $Size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        if ($PreservePixelArt) {
            $graphics.CompositingMode = (
                [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
            )
            $graphics.CompositingQuality = (
                [System.Drawing.Drawing2D.CompositingQuality]::HighSpeed
            )
            $graphics.InterpolationMode = (
                [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            )
            $graphics.PixelOffsetMode = (
                [System.Drawing.Drawing2D.PixelOffsetMode]::Half
            )
            $graphics.SmoothingMode = (
                [System.Drawing.Drawing2D.SmoothingMode]::None
            )

            $scale = [Math]::Min(
                $Size / [double]$SourceImage.Width,
                $Size / [double]$SourceImage.Height
            )
            $destinationWidth = [Math]::Max(
                1,
                [int][Math]::Round($SourceImage.Width * $scale)
            )
            $destinationHeight = [Math]::Max(
                1,
                [int][Math]::Round($SourceImage.Height * $scale)
            )
            $destination = [System.Drawing.Rectangle]::new(
                [int][Math]::Floor(($Size - $destinationWidth) / 2),
                [int][Math]::Floor(($Size - $destinationHeight) / 2),
                $destinationWidth,
                $destinationHeight
            )
            $graphics.DrawImage(
                $SourceImage,
                $destination,
                0,
                0,
                $SourceImage.Width,
                $SourceImage.Height,
                [System.Drawing.GraphicsUnit]::Pixel
            )
            return $bitmap
        }

        $graphics.CompositingQuality = (
            [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        )
        $graphics.InterpolationMode = (
            [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        )
        $graphics.PixelOffsetMode = (
            [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        )
        $graphics.SmoothingMode = (
            [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        )

        $corner = [Math]::Max(2, [int]($Size * 0.18))
        $diameter = $corner * 2
        $edge = $Size - 1
        $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
        try {
            $path.AddArc(0, 0, $diameter, $diameter, 180, 90)
            $path.AddArc(
                $edge - $diameter,
                0,
                $diameter,
                $diameter,
                270,
                90
            )
            $path.AddArc(
                $edge - $diameter,
                $edge - $diameter,
                $diameter,
                $diameter,
                0,
                90
            )
            $path.AddArc(
                0,
                $edge - $diameter,
                $diameter,
                $diameter,
                90,
                90
            )
            $path.CloseFigure()

            $backgroundBrush = [System.Drawing.SolidBrush]::new(
                $BackgroundColor
            )
            try {
                $graphics.FillPath($backgroundBrush, $path)
            }
            finally {
                $backgroundBrush.Dispose()
            }
            $graphics.SetClip($path)
            $sourceInset = [int][Math]::Round($Size * $SourceInsetFraction)
            $sourceSize = [Math]::Max(1, $Size - (2 * $sourceInset))
            $destination = [System.Drawing.Rectangle]::new(
                $sourceInset,
                $sourceInset,
                $sourceSize,
                $sourceSize
            )
            $graphics.DrawImage(
                $SourceImage,
                $destination,
                0,
                0,
                $SourceImage.Width,
                $SourceImage.Height,
                [System.Drawing.GraphicsUnit]::Pixel
            )
            $graphics.ResetClip()
        }
        finally {
            $path.Dispose()
        }
    }
    finally {
        $graphics.Dispose()
    }

    return $bitmap
}

function Convert-BitmapToPngBytes {
    param(
        [Parameter(Mandatory)]
        [System.Drawing.Bitmap]$Bitmap
    )

    $stream = [System.IO.MemoryStream]::new()
    try {
        $Bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        return $stream.ToArray()
    }
    finally {
        $stream.Dispose()
    }
}

$sourceImage = [System.Drawing.Image]::FromFile($resolvedSource)
try {
    $pngOutputs = @{
        "32x32.png" = 32
        "128x128.png" = 128
        "128x128@2x.png" = 256
        "icon.png" = 512
    }

    foreach ($entry in $pngOutputs.GetEnumerator()) {
        $path = Join-Path $outputDirectory $entry.Key
        if (
            $preservePixelArt -and
            $sourceImage.Width -eq $entry.Value -and
            $sourceImage.Height -eq $entry.Value
        ) {
            # Preserve the canonical 512 px PNG byte-for-byte when it already
            # has the requested dimensions. This avoids an unnecessary codec
            # round trip while retaining identical pixels and metadata.
            Copy-Item -LiteralPath $resolvedSource -Destination $path -Force
            continue
        }

        $bitmap = New-DeskCubbyBitmap `
            -SourceImage $sourceImage `
            -Size $entry.Value `
            -BackgroundColor $backgroundColor `
            -SourceInsetFraction $sourceInsetFraction `
            -PreservePixelArt $preservePixelArt
        try {
            $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        }
        finally {
            $bitmap.Dispose()
        }
    }
    Copy-Item -LiteralPath (Join-Path $outputDirectory "128x128.png") `
        -Destination (Join-Path $frontendAssetDirectory "deskcubby.png") `
        -Force

    $icoSizes = @(16, 24, 32, 48, 64, 128, 256)
    $frames = foreach ($size in $icoSizes) {
        $bitmap = New-DeskCubbyBitmap `
            -SourceImage $sourceImage `
            -Size $size `
            -BackgroundColor $backgroundColor `
            -SourceInsetFraction $sourceInsetFraction `
            -PreservePixelArt $preservePixelArt
        try {
            [pscustomobject]@{
                Size = $size
                Bytes = Convert-BitmapToPngBytes -Bitmap $bitmap
            }
        }
        finally {
            $bitmap.Dispose()
        }
    }

    $iconStream = [System.IO.MemoryStream]::new()
    $writer = [System.IO.BinaryWriter]::new($iconStream)
    try {
        $writer.Write([uint16]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]$frames.Count)

        $offset = 6 + (16 * $frames.Count)
        foreach ($frame in $frames) {
            $dimension = if ($frame.Size -eq 256) { 0 } else { $frame.Size }
            $writer.Write([byte]$dimension)
            $writer.Write([byte]$dimension)
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $writer.Write([uint16]1)
            $writer.Write([uint16]32)
            $writer.Write([uint32]$frame.Bytes.Length)
            $writer.Write([uint32]$offset)
            $offset += $frame.Bytes.Length
        }

        foreach ($frame in $frames) {
            $writer.Write([byte[]]$frame.Bytes)
        }
        $writer.Flush()
        [System.IO.File]::WriteAllBytes(
            (Join-Path $outputDirectory "icon.ico"),
            $iconStream.ToArray()
        )
    }
    finally {
        $writer.Dispose()
        $iconStream.Dispose()
    }
}
finally {
    $sourceImage.Dispose()
}

Write-Host "Generated DeskCubby Windows icons in $outputDirectory"
