# 서울시 보행등 xlsx → iOS 번들용 2열 CSV 변환
# (경도, 위도) 완전중복 제거 후 lat,lon 소수 6자리. 출력 행 수가 25,378이 아니면 중단.
# 출처: 서울시 보행등 위도 경도 현황 (서울특별시, 공공누리 제1유형, 2026-02-13 갱신)

import os
import sys

import pandas as pd

BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, "data", "서울시_보행등_경도_위도_현황_20260213.xlsx")
DST = os.path.abspath(os.path.join(
    BASE, "..", "..", "iosApp", "iosApp", "Resources", "pedlights_seoul_20260213.csv"
))
EXPECTED_ROWS = 25378


def main():
    df = pd.read_excel(SRC, sheet_name=0)
    out = df.drop_duplicates(subset=["경도", "위도"])[["위도", "경도"]]
    if len(out) != EXPECTED_ROWS:
        print(f"중단: 고유 좌표 {len(out):,}행 ≠ 기대 {EXPECTED_ROWS:,}행 — 입력 파일 확인 필요")
        sys.exit(1)
    os.makedirs(os.path.dirname(DST), exist_ok=True)
    with open(DST, "w", encoding="utf-8", newline="\n") as f:
        f.write("lat,lon\n")
        for lat, lon in out.itertuples(index=False):
            f.write(f"{lat:.6f},{lon:.6f}\n")
    size_kb = os.path.getsize(DST) / 1024
    print(f"저장: {DST} ({EXPECTED_ROWS:,}행 + 헤더, {size_kb:.0f}KB)")


if __name__ == "__main__":
    main()
