[CmdletBinding()]
param(
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

if ([string]::IsNullOrWhiteSpace($Source)) {
    $Source = Join-Path $repositoryRoot (
        "android\app\src\main\res\drawable-nodpi\" +
        "ic_launcher_cubby_foreground.png"
    )
}

$resolvedSource = (Resolve-Path -LiteralPath $Source).Path
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

function New-DeskCubbyBitmap {
    param(
        [Parameter(Mandatory)]
        [System.Drawing.Image]$SourceImage,
        [Parameter(Mandatory)]
        [int]$Size
    )

    $bitmap = [System.Drawing.Bitmap]::new(
        $Size,
        $Size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
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

            $graphics.FillPath([System.Drawing.Brushes]::White, $path)
            $graphics.SetClip($path)
            $destination = [System.Drawing.Rectangle]::new(0, 0, $Size, $Size)
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
        $bitmap = New-DeskCubbyBitmap -SourceImage $sourceImage -Size $entry.Value
        try {
            $path = Join-Path $outputDirectory $entry.Key
            $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        }
        finally {
            $bitmap.Dispose()
        }
    }

    $icoSizes = @(16, 24, 32, 48, 64, 128, 256)
    $frames = foreach ($size in $icoSizes) {
        $bitmap = New-DeskCubbyBitmap -SourceImage $sourceImage -Size $size
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
