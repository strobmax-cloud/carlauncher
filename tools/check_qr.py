# Decodes the QR test renders with OpenCV (independent decoder) and compares with the expected text.
import cv2, glob, sys
ok = True
for f in sorted(glob.glob("preview/robo-qr*.png")):
    v, _, _ = cv2.QRCodeDetector().detectAndDecode(cv2.imread(f))
    exp = open(f[:-4] + ".txt").read() if f[:-4].split("-")[-1].startswith("v") else "http://192.168.43.57:8080"
    print(f, "OK" if v == exp else f"FAIL got {v!r}"); ok &= v == exp
sys.exit(0 if ok else 1)
