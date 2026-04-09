from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.inference import HotelCommentPredictor

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Test comment sentiment + aspect prediction.')
    parser.add_argument('--text', required=True, help='Noi dung comment can test')
    parser.add_argument('--model-dir', default='artifacts/models', help='Thu muc model')
    parser.add_argument('--use-bkai', action='store_true', help='Bat sua loi chinh ta BKAI neu co san')
    args = parser.parse_args()

    predictor = HotelCommentPredictor(model_dir=args.model_dir, use_bkai=args.use_bkai)
    result = predictor.predict(args.text)
    print(json.dumps(result, ensure_ascii=True))
