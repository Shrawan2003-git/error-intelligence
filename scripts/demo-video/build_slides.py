"""Render the narrated product walkthrough from current synthetic app screenshots."""

from __future__ import annotations

import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[2]
STORYBOARD = json.loads((ROOT / "scripts/demo-video/storyboard.json").read_text(encoding="utf-8"))
OUTPUT = ROOT / ".local/demo-video/slides"
OUTPUT.mkdir(parents=True, exist_ok=True)

W, H = 1280, 720
BG = "#0b120b"
PANEL = "#151f12"
PANEL_ALT = "#1c2918"
LINE = "#35442c"
WHITE = "#f1f4e9"
MUTED = "#aebca1"
LIME = "#c8ec98"
SOFT = "#718a65"
FONT = Path("C:/Windows/Fonts/segoeui.ttf")
BOLD = Path("C:/Windows/Fonts/segoeuib.ttf")


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(BOLD if bold else FONT), size)


def lines_for(draw: ImageDraw.ImageDraw, text: str, face, width: int) -> list[str]:
    lines: list[str] = []
    current = ""
    for word in text.split():
        trial = f"{current} {word}".strip()
        if current and draw.textbbox((0, 0), trial, font=face)[2] > width:
            lines.append(current)
            current = word
        else:
            current = trial
    if current:
        lines.append(current)
    return lines


def multiline(draw, xy, text, face, fill, max_width, spacing=8):
    x, y = xy
    lh = face.size + spacing
    for line in lines_for(draw, text, face, max_width):
        draw.text((x, y), line, font=face, fill=fill)
        y += lh
    return y


def base(index: int, scene: dict):
    image = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((52, 34, 81, 63), radius=14, outline=SOFT, width=2)
    draw.line((60, 50, 66, 50, 70, 42, 73, 55), fill=LIME, width=2)
    draw.text((98, 35), "ERROR INTELLIGENCE", font=font(22, True), fill=WHITE)
    draw.text((1115, 38), f"{index + 1:02d} / {len(STORYBOARD):02d}", font=font(17), fill=MUTED)
    draw.line((52, 84, 1228, 84), fill=LINE, width=2)
    draw.text((52, 668), "SYNTHETIC LOCAL DEMO  ·  JAVA + REACT", font=font(15), fill=MUTED)
    draw.line((52, 700, 1228, 700), fill=LINE, width=4)
    progress = 52 + round((1176 * (index + 1)) / len(STORYBOARD))
    draw.line((52, 700, progress, 700), fill=LIME, width=4)
    return image, draw


def title_block(draw, scene, x=60, y=118, width=1120, size=49):
    draw.text((x, y), scene["eyebrow"], font=font(17, True), fill=LIME)
    end = multiline(draw, (x, y + 40), scene["title"], font(size, True), WHITE, width, 5)
    multiline(draw, (x, end + 13), scene["subtitle"], font(21), MUTED, width, 6)
    return end


def point_card(draw, text, box, number=None):
    x0, y0, x1, y1 = box
    draw.rounded_rectangle(box, radius=16, fill=PANEL, outline=LINE, width=2)
    draw.ellipse((x0 + 17, y0 + 24, x0 + 28, y0 + 35), fill=LIME)
    if number is not None:
        draw.text((x0 + 17, y0 + 16), str(number), font=font(20, True), fill=LIME)
        px = x0 + 52
    else:
        px = x0 + 45
    multiline(draw, (px, y0 + 18), text, font(22, True), WHITE, x1 - px - 16, 4)


def screen_slide(image, draw, scene):
    title_block(draw, scene, x=58, y=122, width=390, size=42)
    screenshot = ROOT / scene["image"]
    if not screenshot.is_file():
        raise FileNotFoundError(screenshot)
    panel = (480, 132, 1228, 609)
    draw.rounded_rectangle(panel, radius=18, fill=PANEL_ALT, outline=LINE, width=2)
    with Image.open(screenshot) as opened:
        frame = opened.convert("RGB")
        frame.thumbnail((728, 451), Image.Resampling.LANCZOS)
        fx = panel[0] + (748 - frame.width) // 2
        fy = panel[1] + (477 - frame.height) // 2
        image.paste(frame, (fx, fy))
    draw.text((488, 622), "CAPTURED FROM THE RUNNING APP · TEST DATA ONLY", font=font(13), fill=SOFT)
    start = 387
    for idx, point in enumerate(scene["points"]):
        y = start + idx * 77
        draw.rounded_rectangle((58, y, 452, y + 63), radius=11, fill=PANEL, outline=LINE)
        draw.ellipse((76, y + 24, 88, y + 36), fill=LIME)
        multiline(draw, (103, y + 15), point, font(18), WHITE, 332, 3)


def cover_slide(draw, scene):
    draw.text((60, 142), scene["eyebrow"], font=font(18, True), fill=LIME)
    multiline(draw, (60, 197), scene["title"], font(75, True), WHITE, 850, 6)
    multiline(draw, (64, 320), scene["subtitle"], font(34), MUTED, 820, 6)
    for i, point in enumerate(scene["points"]):
        draw.ellipse((65, 452 + i * 47, 77, 464 + i * 47), fill=LIME)
        draw.text((93, 443 + i * 47), point, font=font(22), fill=WHITE)
    for radius, color in [(214, LINE), (162, SOFT), (94, LIME)]:
        draw.ellipse((1094 - radius, 360 - radius, 1094 + radius, 360 + radius), outline=color, width=3)
    draw.rounded_rectangle((1055, 324, 1133, 402), radius=21, fill=PANEL_ALT, outline=LIME, width=2)
    draw.text((1071, 339), "EI", font=font(40, True), fill=LIME)


def problem_slide(draw, scene):
    title_block(draw, scene, width=1140)
    labels = ["25 log entries", "3 underlying failures", "0 shared explanations"]
    for idx, label in enumerate(labels):
        x = 78 + idx * 390
        box = (x, 360, x + 346, 545)
        draw.rounded_rectangle(box, radius=22, fill=PANEL, outline=LINE, width=2)
        draw.text((x + 25, 392), f"0{idx + 1}", font=font(24, True), fill=LIME)
        multiline(draw, (x + 25, 440), label, font(28, True), WHITE, 295)
        if idx < 2:
            draw.text((x + 357, 428), "→", font=font(35, True), fill=SOFT)


def flow_slide(draw, scene):
    title_block(draw, scene, width=1140)
    stages = ["Java app", "Safe event", "Grouped incident", "Team review", "Verified fix"]
    descriptions = ["Exception", "Validated intake", "Repeated pattern", "Evidence + comments", "Root cause saved"]
    for idx, stage in enumerate(stages):
        x = 54 + idx * 244
        draw.rounded_rectangle((x, 358, x + 220, 516), radius=19, fill=PANEL, outline=LINE, width=2)
        draw.text((x + 17, 382), f"0{idx + 1}", font=font(18, True), fill=LIME)
        multiline(draw, (x + 17, 420), stage, font(22, True), WHITE, 186)
        multiline(draw, (x + 17, 470), descriptions[idx], font(14), MUTED, 186)
        if idx < 4:
            draw.text((x + 220, 410), "→", font=font(30), fill=LIME)


def event_slide(draw, scene):
    title_block(draw, scene, x=58, width=385, size=43)
    for idx, point in enumerate(scene["points"]):
        y = 391 + idx * 73
        draw.ellipse((64, y + 10, 77, y + 23), fill=LIME)
        multiline(draw, (93, y), point, font(20), WHITE, 325)
    box = (485, 130, 1228, 606)
    draw.rounded_rectangle(box, radius=18, fill="#101810", outline=LINE, width=2)
    draw.ellipse((511, 155, 524, 168), fill="#eb8276")
    draw.ellipse((534, 155, 547, 168), fill="#e9ca80")
    draw.ellipse((557, 155, 570, 168), fill=LIME)
    code = [
        "POST /ingest/v1/events",
        "X-API-Key: [project key]",
        "",
        "{",
        '  "eventId": "new UUID",',
        '  "occurredAt": "UTC time",',
        '  "exceptionType": "java.lang.IllegalStateException",',
        '  "message": "safe summary",',
        '  "stackTrace": "sanitized frames"',
        "}",
    ]
    for idx, line in enumerate(code):
        fill = LIME if idx < 2 else WHITE
        draw.text((513, 205 + idx * 36), line, font=font(21), fill=fill)


def architecture_slide(draw, scene):
    title_block(draw, scene, width=1140)
    boxes = [
        ((63, 374, 317, 515), "React", "Browser interface"),
        ((418, 374, 802, 515), "Java 21 + Spring Boot", "Secured API and workflows"),
        ((903, 374, 1217, 515), "PostgreSQL", "System of record"),
    ]
    for box, heading, detail in boxes:
        draw.rounded_rectangle(box, radius=20, fill=PANEL, outline=LINE, width=2)
        draw.text((box[0] + 23, box[1] + 28), heading, font=font(25, True), fill=WHITE)
        draw.text((box[0] + 23, box[1] + 82), detail, font=font(17), fill=MUTED)
    draw.text((337, 417), "→", font=font(38, True), fill=LIME)
    draw.text((824, 417), "→", font=font(38, True), fill=LIME)
    draw.rounded_rectangle((440, 544, 780, 599), radius=13, fill=PANEL_ALT, outline=LINE)
    draw.text((463, 555), "Redis limits  ·  Optional AI workers", font=font(17), fill=LIME)


def boundaries_slide(draw, scene):
    title_block(draw, scene, width=1140)
    columns = [
        (65, "WORKS IN THE LOCAL DEMO", ["Verified accounts", "Projects and scoped keys", "Incident investigation", "Verified fixes and analytics"]),
        (661, "NEEDS YOUR SETUP", ["Java error sender", "SMTP for hosted email", "Provider for paid AI", "Production staging and hosting"]),
    ]
    for x, heading, items in columns:
        draw.rounded_rectangle((x, 340, x + 554, 603), radius=20, fill=PANEL, outline=LINE, width=2)
        draw.text((x + 22, 365), heading, font=font(18, True), fill=LIME)
        for idx, item in enumerate(items):
            y = 413 + idx * 43
            draw.ellipse((x + 24, y + 7, x + 34, y + 17), fill=LIME)
            draw.text((x + 51, y), item, font=font(20), fill=WHITE)


def closing_slide(draw, scene):
    title_block(draw, scene, width=1140)
    for idx, point in enumerate(scene["points"]):
        x = 72 + idx * 397
        draw.rounded_rectangle((x, 361, x + 370, 552), radius=21, fill=PANEL, outline=LINE, width=2)
        draw.text((x + 25, 388), f"0{idx + 1}", font=font(31, True), fill=LIME)
        multiline(draw, (x + 25, 448), point, font(22, True), WHITE, 320, 5)


for index, scene in enumerate(STORYBOARD):
    image, draw = base(index, scene)
    kind = scene["kind"]
    if kind == "screen":
        screen_slide(image, draw, scene)
    elif kind == "cover":
        cover_slide(draw, scene)
    elif kind == "problem":
        problem_slide(draw, scene)
    elif kind == "flow":
        flow_slide(draw, scene)
    elif kind == "event":
        event_slide(draw, scene)
    elif kind == "architecture":
        architecture_slide(draw, scene)
    elif kind == "boundaries":
        boundaries_slide(draw, scene)
    elif kind == "closing":
        closing_slide(draw, scene)
    else:
        raise ValueError(f"Unknown slide kind: {kind}")
    image.save(OUTPUT / f"{index:02d}-{scene['id']}.png", optimize=True)

(ROOT / "docs/demo/poster.png").write_bytes((OUTPUT / "00-opening.png").read_bytes())
print(f"Rendered {len(STORYBOARD)} product slides.")
