"""Generate YTMD Lite app icon matching the in-app play button."""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app-icon.png"

SIZE = 1024
MARGIN = 72
RADIUS = 64  # ~2px at 32px UI size
FILL = (168, 199, 250, 255)  # --md-sys-color-primary #a8c7fa
TRI = (6, 46, 111, 255)  # --md-sys-color-on-primary #062e6f


def main() -> None:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle(
        [MARGIN, MARGIN, SIZE - MARGIN, SIZE - MARGIN],
        radius=RADIUS,
        fill=FILL,
    )

    inner = SIZE - 2 * MARGIN
    nudge = 0.6  # optical center (matches PlayerDock .ico-play)

    def map_pt(x: float, y: float) -> tuple[float, float]:
        return (
            MARGIN + ((x + nudge) / 24.0) * inner,
            MARGIN + (y / 24.0) * inner,
        )

    # Material play_arrow path: M8 5v14l11-7L8 5z
    pts = [map_pt(8, 5), map_pt(8, 19), map_pt(19, 12)]
    draw.polygon(pts, fill=TRI)

    img.save(OUT, "PNG")
    print(f"wrote {OUT} ({SIZE}x{SIZE})")


if __name__ == "__main__":
    main()
