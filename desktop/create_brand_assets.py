"""Build the Life Moments drop/photograph brand using local vector geometry.

The SVG is the main artwork. The matching Pillow render uses supersampling so
the Windows icon has crisp edges without a browser, network, or SVG renderer.
"""
from pathlib import Path
from PIL import Image, ImageDraw


SIZE = 512
SCALE = 4
BLUE = (37, 113, 255)
CYAN = (34, 198, 217)
INK = (249, 252, 255)
SUN = (255, 193, 116)

# One continuous white drop carries the silhouette of a landscape photograph.
# Commands are shared by SVG and the raster masks to keep every size identical.
DROP = [
    ("M", 256, 105),
    ("C", 240, 131, 200, 162, 174, 194),
    ("C", 141, 233, 132, 270, 147, 313),
    ("C", 163, 363, 205, 397, 256, 399),
    ("C", 319, 401, 370, 364, 380, 314),
    ("C", 391, 262, 367, 225, 337, 191),
    ("C", 310, 162, 276, 131, 256, 105),
    ("Z",),
]
LANDSCAPE = [
    ("M", 116, 333),
    ("C", 176, 296, 198, 267, 220, 279),
    ("C", 240, 290, 251, 317, 268, 320),
    ("C", 284, 323, 299, 295, 319, 299),
    ("C", 343, 303, 360, 319, 410, 368),
    ("C", 410, 414, 410, 438, 410, 440),
    ("C", 313, 440, 204, 440, 116, 440),
    ("C", 116, 412, 116, 367, 116, 333),
    ("Z",),
]


def svg_path(commands):
    return " ".join(command[0] + " ".join(str(x) for x in command[1:])
                    for command in commands)


def polygon_path(commands):
    points = []
    current = first = None
    for command in commands:
        kind, *args = command
        if kind == "M":
            current = first = tuple(args)
            points.append(current)
        elif kind == "C":
            x0, y0 = current
            x1, y1, x2, y2, x3, y3 = args
            for step in range(1, 65):
                t = step / 64
                u = 1 - t
                points.append((u**3*x0 + 3*u*u*t*x1 + 3*u*t*t*x2 + t**3*x3,
                               u**3*y0 + 3*u*u*t*y1 + 3*u*t*t*y2 + t**3*y3))
            current = (x3, y3)
        elif kind == "Z":
            points.append(first)
            current = first
        else:
            raise ValueError("Unsupported path command: " + kind)
    return [(round(x*SCALE), round(y*SCALE)) for x, y in points]


def gradient():
    # SVG endpoints (64,48)-(440,472), including a soft highlight at upper left.
    small = Image.new("RGBA", (SIZE, SIZE))
    pixels = small.load()
    dx, dy = 376, 424
    distance = dx*dx + dy*dy
    for y in range(SIZE):
        for x in range(SIZE):
            amount = max(0.0, min(1.0, ((x-64)*dx+(y-48)*dy)/distance))
            glow = max(0.0, 1-(((x-144)**2+(y-112)**2)**.5)/360)*.075
            rgb = [round((a+(b-a)*amount)*(1-glow)+255*glow)
                   for a, b in zip(BLUE, CYAN)]
            pixels[x, y] = (*rgb, 255)
    return small.resize((SIZE*SCALE, SIZE*SCALE), Image.Resampling.BICUBIC)


def create_assets():
    app = Path(__file__).resolve().parent / "app"
    brand = app / "brand"
    brand.mkdir(parents=True, exist_ok=True)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" role="img" aria-labelledby="brand-title">
  <title id="brand-title">生活点滴 · 点滴里的照片</title>
  <defs>
    <linearGradient id="blue" x1="64" y1="48" x2="440" y2="472" gradientUnits="userSpaceOnUse">
      <stop stop-color="#2571ff"/><stop offset="1" stop-color="#22c6d9"/>
    </linearGradient>
    <radialGradient id="glow" cx="144" cy="112" r="360" gradientUnits="userSpaceOnUse">
      <stop stop-color="#fff" stop-opacity=".075"/><stop offset="1" stop-color="#fff" stop-opacity="0"/>
    </radialGradient>
    <clipPath id="tile"><rect x="32" y="32" width="448" height="448" rx="112"/></clipPath>
    <mask id="drop"><rect width="512" height="512" fill="#000"/>
      <path d="{svg_path(DROP)}" fill="#fff"/>
      <path d="{svg_path(LANDSCAPE)}" fill="#000"/>
    </mask>
  </defs>
  <g clip-path="url(#tile)">
    <rect x="32" y="32" width="448" height="448" rx="112" fill="url(#blue)"/>
    <rect x="32" y="32" width="448" height="448" rx="112" fill="url(#glow)"/>
    <rect width="512" height="512" fill="#f9fcff" mask="url(#drop)"/>
    <circle cx="309" cy="232" r="22" fill="#ffc174"/>
  </g>
</svg>
'''
    (brand / "logo.svg").write_text(svg, encoding="utf-8")

    canvas = Image.new("RGBA", (SIZE*SCALE, SIZE*SCALE), (0, 0, 0, 0))
    tile_mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(tile_mask).rounded_rectangle(
        tuple(value*SCALE for value in (32, 32, 480, 480)),
        radius=112*SCALE, fill=255)
    canvas.paste(gradient(), (0, 0), tile_mask)
    drop_mask = Image.new("L", canvas.size, 0)
    draw_mask = ImageDraw.Draw(drop_mask)
    draw_mask.polygon(polygon_path(DROP), fill=255)
    draw_mask.polygon(polygon_path(LANDSCAPE), fill=0)
    canvas.paste((*INK, 255), (0, 0, *canvas.size), drop_mask)
    ImageDraw.Draw(canvas).ellipse(
        tuple(value*SCALE for value in (287, 210, 331, 254)), fill=(*SUN, 255))
    logo = canvas.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
    logo.save(brand / "logo.png")
    logo.save(app / "app.ico", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
    print("Life Moments drop brand prepared (SVG, 512 px PNG, Windows ICO).")


if __name__ == "__main__":
    create_assets()
