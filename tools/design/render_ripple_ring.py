# Renders design/src/main/res/drawable-nodpi/design_ripple_ring.webp: run
#   python3 tools/design/render_ripple_ring.py design/src/main/res/drawable-nodpi/design_ripple_ring.webp
# Needs numpy and Pillow.
# Offline render of the water ripple ring used on Android 12 instead of the AGSL ripple.
# A damped ring wavefront on a water surface is lit by a soft top-left key light; crests catch
# light, troughs fall into shade. Rendered supersampled, then downsampled for clean edges.
import sys
import numpy as np
from PIL import Image

size, ss = 512, 4
n = size * ss
y, x = np.mgrid[0:n, 0:n].astype(np.float64)
c = (n - 1) / 2
r = np.hypot(x - c, y - c) / (n / 2)            # 0 at centre, 1 at edge
front, width, k = 0.78, 0.11, 2 * np.pi / 0.16  # ring position, envelope, wavelength
env = np.exp(-((r - front) / width) ** 2) * np.clip((1 - r) / 0.08, 0, 1)
h = env * np.cos(k * (r - front))
gy, gx = np.gradient(h)
slope = 1.0 / (gx.std() * 6 + 1e-9)
nx, ny, nz = -gx * slope, -gy * slope, np.ones_like(h)
norm = np.sqrt(nx**2 + ny**2 + nz**2); nx, ny, nz = nx / norm, ny / norm, nz / norm
L = np.array([-0.55, -0.62, 0.56]); L /= np.linalg.norm(L)
V = np.array([0, 0, 1.0]); H = (L + V) / np.linalg.norm(L + V)
lambert = nx * L[0] + ny * L[1] + nz * L[2] - L[2]   # zero on flat water
spec = np.clip(nx * H[0] + ny * H[1] + nz * H[2], 0, 1) ** 60
shade = np.clip(lambert * 1.6, -1, 1)
light = np.clip(np.maximum(shade, 0) * 0.55 + spec * 0.9, 0, 1)
dark = np.clip(-shade * 0.45, 0, 1)
alpha = np.clip(light + dark, 0, 1)
lum = np.where(alpha > 0, light / np.maximum(alpha, 1e-6), 0)
rgb = np.stack([lum] * 3, -1)
img = np.dstack([rgb * 255, alpha * 255]).astype(np.uint8)
Image.fromarray(img, "RGBA").resize((size, size), Image.LANCZOS).save(sys.argv[1], "WEBP", quality=92)
