from PIL import Image, ImageDraw, ImageFilter
from pathlib import Path
import math

SIZE = 1024
S = 4
W = SIZE * S
img = Image.new('RGBA', (W, W), (255, 255, 255, 0))
p = img.load()
# Indigo to cobalt vertical gradient
for y in range(W):
    t = y / (W - 1)
    c1 = (92, 93, 231)
    c2 = (43, 42, 137)
    c = tuple(round(c1[i] * (1-t) + c2[i] * t) for i in range(3))
    for x in range(W):
        p[x, y] = (*c, 255)

d = ImageDraw.Draw(img, 'RGBA')
def rr(box, r, fill, outline=None, width=1):
    box = tuple(int(v*S) for v in box)
    d.rounded_rectangle(box, radius=int(r*S), fill=fill, outline=outline, width=max(1,int(width*S)))
def poly(points, fill):
    d.polygon([(int(x*S), int(y*S)) for x,y in points], fill=fill)
def ellipse(box, fill, outline=None, width=1):
    box = tuple(int(v*S) for v in box)
    d.ellipse(box, fill=fill, outline=outline, width=max(1,int(width*S)))

# App tile inset, keeps a safe margin for launcher masks
rr((28, 28, 996, 996), 224, (64, 64, 188, 255))
rr((38, 30, 986, 978), 218, (43, 42, 137, 255))

# Calendar shadow
shadow = Image.new('RGBA', (W,W), (0,0,0,0))
sd = ImageDraw.Draw(shadow, 'RGBA')
sd.rounded_rectangle((174*S, 190*S, 850*S, 888*S), radius=156*S, fill=(18, 17, 74, 125))
shadow = shadow.filter(ImageFilter.GaussianBlur(28*S))
img.alpha_composite(shadow)
d = ImageDraw.Draw(img, 'RGBA')

# Sunburst behind card (brand cue for Awake)
ellipse((667, 590, 879, 802), (255, 190, 75, 255))
for ang in range(0, 360, 45):
    a = math.radians(ang)
    x1 = 773 + math.cos(a)*130
    y1 = 696 + math.sin(a)*130
    x2 = 773 + math.cos(a)*166
    y2 = 696 + math.sin(a)*166
    d.line((int(x1*S),int(y1*S),int(x2*S),int(y2*S)), fill=(255,211,112,255), width=12*S)

# Calendar body
rr((180, 176, 844, 864), 152, (250, 252, 255, 255))
# Header band
# create header with rounded top corners by drawing full rounded rect and cover lower rounding
rr((180, 176, 844, 410), 152, (37, 42, 123, 255))
d.rectangle((180*S, 300*S, 844*S, 410*S), fill=(37,42,123,255))
# Header highlight
rr((212, 204, 812, 260), 28, (79, 84, 191, 255))
# Binder tabs
rr((304, 128, 362, 246), 28, (255, 190, 75, 255))
rr((662, 128, 720, 246), 28, (255, 190, 75, 255))
rr((316, 139, 350, 221), 17, (255, 218, 133, 255))
rr((674, 139, 708, 221), 17, (255, 218, 133, 255))
# Header title bars
rr((250, 284, 470, 323), 19, (250,252,255,255))
rr((250, 340, 395, 366), 13, (156,164,245,255))
# Small sunrise mark in header
ellipse((642, 276, 748, 382), (255, 190, 75, 255))
# mask lower half of sun, giving rising-sun feel
d.rectangle((631*S, 330*S, 760*S, 392*S), fill=(37,42,123,255))
d.line((647*S, 333*S, 747*S, 333*S), fill=(255,218,133,255), width=10*S)

# Calendar grid
cols = [242, 360, 478, 596, 714]
rows = [454, 548, 642, 736]
colors = [
    (232,235,255,255), (232,248,246,255), (255,242,214,255), (238,232,255,255), (228,242,255,255),
    (232,248,246,255), (221,229,255,255), (255,232,224,255), (232,235,255,255), (255,242,214,255),
    (255,242,214,255), (232,235,255,255), (227,244,255,255), (232,248,246,255), (238,232,255,255),
    (232,235,255,255), (255,232,224,255), (232,248,246,255), (255,242,214,255), (232,235,255,255),
]
idx = 0
for y in rows:
    for x in cols:
        rr((x, y, x+76, y+58), 21, colors[idx])
        idx += 1
# Highlight course blocks
rr((242, 548, 318, 642), 22, (82, 92, 214, 255))
rr((596, 642, 672, 736), 22, (255, 149, 106, 255))
rr((714, 736, 790, 794), 21, (255, 190, 75, 255))
# tiny details in selected blocks
rr((259, 565, 301, 578), 6, (255,255,255,255))
rr((259, 587, 288, 597), 5, (191,198,255,255))
rr((613, 659, 655, 672), 6, (255,255,255,255))
rr((613, 681, 646, 691), 5, (255,215,199,255))

# Downsample
img = img.resize((SIZE, SIZE), Image.Resampling.LANCZOS)
# PNG preview and app asset
Path('design/awake-app-icon.png').parent.mkdir(parents=True, exist_ok=True)
img.save('design/awake-app-icon.png', optimize=True)
Path('app/src/main/res/drawable-nodpi').mkdir(parents=True, exist_ok=True)
img.save('app/src/main/res/drawable-nodpi/awake_app_icon.png', optimize=True)

# SVG source with the same visual language
svg = '''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<defs>
  <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#5C5DE7"/><stop offset="1" stop-color="#2B2A89"/></linearGradient>
  <filter id="shadow" x="-30%" y="-30%" width="160%" height="170%"><feGaussianBlur stdDeviation="22"/></filter>
</defs>
<rect width="1024" height="1024" rx="224" fill="url(#bg)"/>
<rect x="38" y="30" width="948" height="948" rx="218" fill="#000" opacity=".08"/>
<ellipse cx="773" cy="696" rx="106" ry="106" fill="#FFBE4B"/>
<g stroke="#FFD370" stroke-width="12" stroke-linecap="round" opacity=".95">
  <path d="M773 530v-36M773 898v-36M607 696h-36M939 696h-36M655 578l-26-26M891 814l-26-26M655 814l-26 26M891 578l-26 26"/>
</g>
<rect x="174" y="190" width="676" height="698" rx="156" fill="#12114A" opacity=".5" filter="url(#shadow)"/>
<rect x="180" y="176" width="664" height="688" rx="152" fill="#FAFCFF"/>
<path d="M332 176h360a152 152 0 0 1 152 152v82H180v-82a152 152 0 0 1 152-152Z" fill="#252A7B"/>
<rect x="212" y="204" width="600" height="56" rx="28" fill="#5459C7" opacity=".55"/>
<rect x="304" y="128" width="58" height="118" rx="28" fill="#FFBE4B"/>
<rect x="662" y="128" width="58" height="118" rx="28" fill="#FFBE4B"/>
<rect x="316" y="139" width="34" height="82" rx="17" fill="#FFD985"/>
<rect x="674" y="139" width="34" height="82" rx="17" fill="#FFD985"/>
<rect x="250" y="284" width="220" height="39" rx="19" fill="#FAFCFF" opacity=".9"/>
<rect x="250" y="340" width="145" height="26" rx="13" fill="#9CA5F5" opacity=".85"/>
<circle cx="695" cy="329" r="53" fill="#FFBE4B"/>
<rect x="631" y="330" width="129" height="62" fill="#252A7B"/>
<path d="M647 333h100" stroke="#FFD985" stroke-width="10" stroke-linecap="round"/>
<g>
  <rect x="242" y="454" width="76" height="58" rx="21" fill="#E8EBFF"/><rect x="360" y="454" width="76" height="58" rx="21" fill="#E8F8F6"/><rect x="478" y="454" width="76" height="58" rx="21" fill="#FFF2D6"/><rect x="596" y="454" width="76" height="58" rx="21" fill="#EEE8FF"/><rect x="714" y="454" width="76" height="58" rx="21" fill="#E4F2FF"/>
  <rect x="242" y="548" width="76" height="94" rx="22" fill="#525CD6"/><rect x="360" y="548" width="76" height="58" rx="21" fill="#DDE5FF"/><rect x="478" y="548" width="76" height="58" rx="21" fill="#FFE8E0"/><rect x="596" y="548" width="76" height="58" rx="21" fill="#E8EBFF"/><rect x="714" y="548" width="76" height="58" rx="21" fill="#FFF2D6"/>
  <rect x="242" y="642" width="76" height="58" rx="21" fill="#FFF2D6"/><rect x="360" y="642" width="76" height="58" rx="21" fill="#E8EBFF"/><rect x="478" y="642" width="76" height="58" rx="21" fill="#E3F4FF"/><rect x="596" y="642" width="76" height="94" rx="22" fill="#FF956A"/><rect x="714" y="642" width="76" height="58" rx="21" fill="#EEE8FF"/>
  <rect x="242" y="736" width="76" height="58" rx="21" fill="#E8EBFF"/><rect x="360" y="736" width="76" height="58" rx="21" fill="#FFE8E0"/><rect x="478" y="736" width="76" height="58" rx="21" fill="#E8F8F6"/><rect x="596" y="736" width="76" height="58" rx="21" fill="#FFF2D6"/><rect x="714" y="736" width="76" height="58" rx="21" fill="#FFBE4B"/>
</g>
<rect x="259" y="565" width="42" height="13" rx="6" fill="#FFF" opacity=".8"/><rect x="259" y="587" width="29" height="10" rx="5" fill="#BFC7FF"/>
<rect x="613" y="659" width="42" height="13" rx="6" fill="#FFF" opacity=".85"/><rect x="613" y="681" width="33" height="10" rx="5" fill="#FFD7C7"/>
</svg>'''
Path('design/awake-app-icon.svg').write_text(svg, encoding='utf-8')
