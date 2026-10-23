# Generates Android launcher icons + splash screens from assets/icon-source.jpg
# Run once on Windows:  powershell -ExecutionPolicy Bypass -File scripts/gen-assets.ps1
# Output PNGs are committed to the repo, so CI does not need to run this.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$srcPath = Join-Path $root 'assets\icon-source.jpg'
$res = Join-Path $root 'android\app\src\main\res'
if (-not (Test-Path $srcPath)) { throw "missing icon source: $srcPath" }

$src = [System.Drawing.Bitmap]::new($srcPath)
$BG = [System.Drawing.Color]::FromArgb(255, 255, 255)
$DARK = [System.Drawing.Color]::FromArgb(255, 15, 15, 15)   # app background #0f0f0f

function New-Canvas([int]$w, [int]$h, [System.Drawing.Color]$bg) {
  $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $g.Clear($bg)
  return @{ bmp = $bmp; g = $g }
}

# draw $src centered into a square box of side $side at (x,y)
function Draw-Logo($g, [double]$x, [double]$y, [double]$side) {
  $g.DrawImage($src, [System.Drawing.RectangleF]::new($x, $y, $side, $side))
}

function Save-Png($bmp, [string]$path) {
  $dir = Split-Path -Parent $path
  if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# ---------- legacy square icon (API < 26) : white bg, logo fills ~92% ----------
function Gen-Square([int]$size, [string]$path) {
  $c = New-Canvas $size $size $BG
  $side = $size * 0.92
  Draw-Logo $c.g (($size - $side) / 2) (($size - $side) / 2) $side
  Save-Png $c.bmp $path
  $c.g.Dispose(); $c.bmp.Dispose()
}

# ---------- round icon : logo clipped to a circle ----------
function Gen-Round([int]$size, [string]$path) {
  $c = New-Canvas $size $size $BG
  $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
  $clip.AddEllipse(0, 0, $size, $size)
  $c.g.SetClip($clip)
  $side = $size * 1.0
  Draw-Logo $c.g 0 0 $side
  $c.g.ResetClip()
  Save-Png $c.bmp $path
  $c.g.Dispose(); $c.bmp.Dispose(); $clip.Dispose()
}

# ---------- adaptive foreground : transparent canvas, logo inside the 66% safe zone ----------
function Gen-Foreground([int]$size, [string]$path) {
  $c = New-Canvas $size $size ([System.Drawing.Color]::Transparent)
  # white padded plate so the artwork keeps its own background but stays inside the mask
  $side = $size * 0.70
  $x = ($size - $side) / 2
  $y = ($size - $side) / 2
  $rect = [System.Drawing.RectangleF]::new($x, $y, $side, $side)
  $c.g.FillRectangle((New-Object System.Drawing.SolidBrush($BG)), $rect)
  $c.g.DrawImage($src, $rect)
  Save-Png $c.bmp $path
  $c.g.Dispose(); $c.bmp.Dispose()
}

# ---------- splash : dark bg + rounded white tile with the logo ----------
function Gen-Splash([int]$w, [int]$h, [string]$path) {
  $c = New-Canvas $w $h $DARK
  $side = [Math]::Min($w, $h) * 0.42
  $tile = $side * 1.16
  $tx = ($w - $tile) / 2
  $ty = ($h - $tile) / 2
  $r = $tile * 0.22
  $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
  $d = $r * 2
  $gp.AddArc($tx, $ty, $d, $d, 180, 90)
  $gp.AddArc($tx + $tile - $d, $ty, $d, $d, 270, 90)
  $gp.AddArc($tx + $tile - $d, $ty + $tile - $d, $d, $d, 0, 90)
  $gp.AddArc($tx, $ty + $tile - $d, $d, $d, 90, 90)
  $gp.CloseFigure()
  $plate = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 250, 250, 250))
  $c.g.FillPath($plate, $gp)
  $c.g.SetClip($gp)
  Draw-Logo $c.g ($tx + ($tile - $side) / 2) ($ty + ($tile - $side) / 2) $side
  $c.g.ResetClip()
  Save-Png $c.bmp $path
  $c.g.Dispose(); $c.bmp.Dispose(); $gp.Dispose(); $plate.Dispose()
}

# density -> (legacy icon size, adaptive foreground size)
$densities = @{
  'mdpi'    = @{ icon = 48;  fg = 108 }
  'hdpi'    = @{ icon = 72;  fg = 162 }
  'xhdpi'   = @{ icon = 96;  fg = 216 }
  'xxhdpi'  = @{ icon = 144; fg = 324 }
  'xxxhdpi' = @{ icon = 192; fg = 432 }
}

foreach ($k in $densities.Keys) {
  $icon = $densities[$k].icon
  $fg = $densities[$k].fg
  Gen-Square $icon (Join-Path $res "mipmap-$k\ic_launcher.png")
  Gen-Round  $icon (Join-Path $res "mipmap-$k\ic_launcher_round.png")
  Gen-Foreground $fg (Join-Path $res "mipmap-$k\ic_launcher_foreground.png")
  Write-Host "icons -> mipmap-$k"
}

# splash sizes mirror Capacitor's template so every qualifier keeps an asset
$splashes = @(
  @{ dir = 'drawable';              w = 480;  h = 320  },
  @{ dir = 'drawable-land-mdpi';    w = 480;  h = 320  },
  @{ dir = 'drawable-land-hdpi';    w = 800;  h = 480  },
  @{ dir = 'drawable-land-xhdpi';   w = 1280; h = 720  },
  @{ dir = 'drawable-land-xxhdpi';  w = 1600; h = 960  },
  @{ dir = 'drawable-land-xxxhdpi'; w = 1920; h = 1280 },
  @{ dir = 'drawable-port-mdpi';    w = 320;  h = 480  },
  @{ dir = 'drawable-port-hdpi';    w = 480;  h = 800  },
  @{ dir = 'drawable-port-xhdpi';   w = 720;  h = 1280 },
  @{ dir = 'drawable-port-xxhdpi';  w = 960;  h = 1600 },
  @{ dir = 'drawable-port-xxxhdpi'; w = 1280; h = 1920 }
)
foreach ($s in $splashes) {
  Gen-Splash $s.w $s.h (Join-Path $res "$($s.dir)\splash.png")
}
Write-Host "splash -> all density buckets"

$src.Dispose()
Write-Host "done."
