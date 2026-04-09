from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import pandas as pd

from src.config import PROCESSED_DATA_DIR
from src.load_data import save_prepared_csv
from src.spell_correct import normalize_dataframe

if __name__ == '__main__':
    raw_csv = PROCESSED_DATA_DIR / 'prepared_raw.csv'
    normalized_csv = PROCESSED_DATA_DIR / 'prepared_normalized.csv'
    save_prepared_csv(raw_csv)
    df = pd.read_csv(raw_csv)
    normalized = normalize_dataframe(df, text_col='text', use_bkai=False)
    normalized.to_csv(normalized_csv, index=False, encoding='utf-8-sig')
    print(f'Da tao: {raw_csv}')
    print(f'Da tao: {normalized_csv}')
    print('Chay tiep: python scripts/train.py --input data/processed/prepared_normalized.csv')
