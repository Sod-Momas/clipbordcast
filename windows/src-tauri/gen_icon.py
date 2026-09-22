"""生成 ClipBordcast 图标：蓝色圆角方块 + 白色剪贴板图形，输出多尺寸 .ico"""
from PIL import Image, ImageDraw
import os

SIZE = 256
img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 圆角方块背景（蓝）
d.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=56, fill=(37, 99, 235, 255))

# 剪贴板图形（白色）：板身 + 顶部夹子 + 两条横线
m = 64  # 边距
x0, y0, x1, y1 = m, m + 16, SIZE - m, SIZE - m
d.rounded_rectangle([x0, y0, x1, y1], radius=14, fill=(255, 255, 255, 255))
# 夹子
cx0, cx1 = SIZE // 2 - 28, SIZE // 2 + 28
d.rounded_rectangle([cx0, y0 - 20, cx1, y0 + 18], radius=10, fill=(37, 99, 235, 255),
                    outline=(255, 255, 255, 255), width=4)
# 板身内的两条"文字"线
d.rounded_rectangle([x0 + 24, y0 + 52, x1 - 24, y0 + 66], radius=6, fill=(37, 99, 235, 255))
d.rounded_rectangle([x0 + 24, y0 + 84, x1 - 44, y0 + 98], radius=6, fill=(37, 99, 235, 255))

out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icons")
os.makedirs(out_dir, exist_ok=True)
img.save(os.path.join(out_dir, "icon.png"))
img.save(
    os.path.join(out_dir, "icon.ico"),
    sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
)
print("icons generated:", os.listdir(out_dir))
