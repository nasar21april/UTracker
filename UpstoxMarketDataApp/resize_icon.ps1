Add-Type -AssemblyName System.Drawing

# Source: glowing 3D UT globe with candlesticks & orbital ring
$src = "C:\Users\5012001749\.gemini\antigravity\brain\9739628b-3fe6-447d-965f-3c7da91bdebe\.user_uploaded\media_1790099593347.jpg"
$img = [System.Drawing.Image]::FromFile($src)

Write-Host "Source image: $($img.Width) x $($img.Height)"

# Crop to center square
$side = [Math]::Min($img.Width, $img.Height)
$cropX = [int](($img.Width - $side) / 2)
$cropY = [int](($img.Height - $side) / 2)
$cropRect = New-Object System.Drawing.Rectangle($cropX, $cropY, $side, $side)

$cropped = New-Object System.Drawing.Bitmap($side, $side)
$g = [System.Drawing.Graphics]::FromImage($cropped)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $side, $side)), $cropRect, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()

$projectRoot = "C:\Users\5012001749\Desktop\Artemis\UpstoxMarketDataApp"
$resBase = "$projectRoot\app\src\main\res"

# Generate 512x512 Play Store icon
$playBmp = New-Object System.Drawing.Bitmap(512, 512)
$gPlay = [System.Drawing.Graphics]::FromImage($playBmp)
$gPlay.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gPlay.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$gPlay.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$gPlay.DrawImage($cropped, 0, 0, 512, 512)
$gPlay.Dispose()
$playBmp.Save("$projectRoot\play_console_logo_512x512.png", [System.Drawing.Imaging.ImageFormat]::Png)
$playBmp.Dispose()
Write-Host "Generated play_console_logo_512x512.png"

# Save to all mipmap sizes
$dirSizes = @("mipmap-mdpi:48","mipmap-hdpi:72","mipmap-xhdpi:96","mipmap-xxhdpi:144","mipmap-xxxhdpi:192")

foreach ($entry in $dirSizes) {
    $parts = $entry.Split(":")
    $dir = $parts[0]; $sz = [int]$parts[1]
    $destDir = "$resBase\$dir"

    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }

    $bmp = New-Object System.Drawing.Bitmap($sz, $sz)
    $g2 = [System.Drawing.Graphics]::FromImage($bmp)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g2.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g2.DrawImage($cropped, 0, 0, $sz, $sz)
    $g2.Dispose()

    $bmp.Save("$destDir\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Save("$destDir\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "Saved $dir at ${sz}x${sz}px"
}

$cropped.Dispose()
$img.Dispose()
Write-Host "All icons saved!"
