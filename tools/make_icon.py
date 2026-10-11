from PIL import Image, ImageDraw
import math

S = 128
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

# rounded-square background: deep blue -> teal gradient
bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
bd = ImageDraw.Draw(bg)
for y in range(S):
    t = y / (S - 1)
    r = int(18 + 10 * t)
    g = int(42 + 60 * t)
    b = int(96 + 70 * t)
    bd.line([(0, y), (S, y)], fill=(r, g, b, 255))
mask = Image.new("L", (S, S), 0)
md = ImageDraw.Draw(mask)
md.rounded_rectangle([4, 4, S - 5, S - 5], radius=26, fill=255)
img.paste(bg, (0, 0), mask)

d = ImageDraw.Draw(img)

# globe: circle outline with meridians (represents the public internet domain)
cx, cy, R = 64, 58, 32
d.ellipse([cx - R, cy - R, cx + R, cy + R], outline=(235, 245, 255, 255), width=4)
# latitudes
for dy in (-15, 0, 15):
    w = int(math.sqrt(max(R * R - dy * dy, 1)))
    d.arc([cx - w, cy + dy - 7, cx + w, cy + dy + 7], 0, 180, fill=(180, 220, 255, 230), width=3)
# meridians
for rx in (10, 21):
    d.ellipse([cx - rx, cy - R, cx + rx, cy + R], outline=(180, 220, 255, 200), width=3)
d.line([(cx, cy - R), (cx, cy + R)], fill=(180, 220, 255, 200), width=3)

# lightning bolt through the globe (one-click speed)
bolt = [(70, 32), (56, 60), (66, 60), (58, 84), (80, 54), (69, 54), (78, 32)]
d.polygon(bolt, fill=(255, 214, 64, 255), outline=(120, 90, 0, 255))

# small "LAN" plaque at the bottom
d.rounded_rectangle([42, 96, 86, 112], radius=5, outline=(235, 245, 255, 255), width=3)
try:
    d.text((52, 99), "LAN", fill=(235, 245, 255, 255))
except Exception:
    pass

img.save(r"src\main\resources\assets\cloudlan\icon.png")
print("icon written", img.size)
