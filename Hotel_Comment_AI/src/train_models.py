from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import joblib
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy.sparse import csr_matrix, hstack
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix, f1_score, multilabel_confusion_matrix
from sklearn.model_selection import train_test_split
from sklearn.multiclass import OneVsRestClassifier
from sklearn.naive_bayes import MultinomialNB
from sklearn.preprocessing import MultiLabelBinarizer
from sklearn.svm import LinearSVC

from .config import DEFAULT_ASPECT_MODELS, DEFAULT_SENTIMENT_MODELS, MODEL_DIR, RANDOM_STATE, TEST_SIZE


MODEL_DISPLAY_NAMES = {
    'nb': 'MultinomialNB',
    'logreg': 'LogisticRegression',
    'svm': 'LinearSVC',
}


def build_feature_matrices(X_train: pd.Series, X_test: pd.Series):
    word_vectorizer = TfidfVectorizer(
        ngram_range=(1, 3),
        min_df=2,
        max_df=0.95,
        sublinear_tf=True,
    )
    char_vectorizer = TfidfVectorizer(
        analyzer='char_wb',
        ngram_range=(3, 5),
        min_df=2,
        sublinear_tf=True,
    )
    X_train_word = word_vectorizer.fit_transform(X_train)
    X_test_word = word_vectorizer.transform(X_test)
    X_train_char = char_vectorizer.fit_transform(X_train)
    X_test_char = char_vectorizer.transform(X_test)
    return hstack([X_train_word, X_train_char]).tocsr(), hstack([X_test_word, X_test_char]).tocsr(), word_vectorizer, char_vectorizer



def create_sentiment_model(model_name: str):
    if model_name == 'nb':
        return MultinomialNB(alpha=0.3)
    if model_name == 'logreg':
        return LogisticRegression(max_iter=2000, class_weight='balanced', random_state=RANDOM_STATE)
    if model_name == 'svm':
        return LinearSVC(class_weight='balanced', random_state=RANDOM_STATE)
    raise ValueError(f'Unsupported sentiment model: {model_name}')



def create_aspect_model(model_name: str):
    if model_name == 'nb':
        return OneVsRestClassifier(MultinomialNB(alpha=0.2))
    if model_name == 'logreg':
        return OneVsRestClassifier(LogisticRegression(max_iter=2000, class_weight='balanced', random_state=RANDOM_STATE))
    if model_name == 'svm':
        return OneVsRestClassifier(LinearSVC(class_weight='balanced', random_state=RANDOM_STATE))
    raise ValueError(f'Unsupported aspect model: {model_name}')



def train_and_score_sentiment(model_name: str, X_train_vec: csr_matrix, X_test_vec: csr_matrix, y_train: pd.Series, y_test: pd.Series) -> dict[str, Any]:
    model = create_sentiment_model(model_name)
    model.fit(X_train_vec, y_train)
    preds = model.predict(X_test_vec)
    labels = sorted(y_test.unique())
    report = classification_report(y_test, preds, output_dict=True, zero_division=0)
    macro_f1 = f1_score(y_test, preds, average='macro')
    weighted_f1 = f1_score(y_test, preds, average='weighted')
    cm = confusion_matrix(y_test, preds, labels=labels)
    return {
        'model_name': model_name,
        'display_name': MODEL_DISPLAY_NAMES.get(model_name, model_name),
        'model': model,
        'report': report,
        'macro_f1': float(macro_f1),
        'weighted_f1': float(weighted_f1),
        'labels': labels,
        'confusion_matrix': cm.tolist(),
        'predictions': preds.tolist(),
    }



def train_and_score_aspect(model_name: str, X_train_vec: csr_matrix, X_test_vec: csr_matrix, y_train: Any, y_test: Any, label_count: int, classes: list[str]) -> dict[str, Any]:
    model = create_aspect_model(model_name)
    model.fit(X_train_vec, y_train)
    y_pred = model.predict(X_test_vec)
    macro_f1 = f1_score(y_test, y_pred, average='macro', zero_division=0)
    micro_f1 = f1_score(y_test, y_pred, average='micro', zero_division=0)
    samples_f1 = f1_score(y_test, y_pred, average='samples', zero_division=0)
    label_confusions = multilabel_confusion_matrix(y_test, y_pred)
    return {
        'model_name': model_name,
        'display_name': MODEL_DISPLAY_NAMES.get(model_name, model_name),
        'model': model,
        'report': {
            'macro_f1': float(macro_f1),
            'micro_f1': float(micro_f1),
            'samples_f1': float(samples_f1),
            'label_count': label_count,
        },
        'predictions': np.asarray(y_pred).tolist(),
        'label_confusions': np.asarray(label_confusions).tolist(),
        'classes': classes,
    }



def save_artifacts(prefix: str, artifacts: dict[str, Any], model_dir: Path) -> None:
    for name, value in artifacts.items():
        suffix = 'json' if name in {'report', 'comparison'} else 'joblib'
        file_path = model_dir / f'{prefix}_{name}.{suffix}'
        if suffix == 'json':
            file_path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
        else:
            joblib.dump(value, file_path)



def _plot_metric_bars(model_names: list[str], metrics: dict[str, list[float]], title: str, output_path: Path) -> None:
    metric_names = list(metrics.keys())
    x = np.arange(len(model_names))
    width = 0.8 / max(len(metric_names), 1)

    fig, ax = plt.subplots(figsize=(10, 6))
    for idx, metric_name in enumerate(metric_names):
        offset = (idx - (len(metric_names) - 1) / 2) * width
        values = metrics[metric_name]
        bars = ax.bar(x + offset, values, width=width, label=metric_name)
        for bar, value in zip(bars, values):
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.005, f'{value:.3f}', ha='center', va='bottom', fontsize=9)

    ax.set_title(title)
    ax.set_xticks(x)
    ax.set_xticklabels(model_names)
    ax.set_ylim(0, 1.08)
    ax.set_ylabel('Score')
    ax.legend()
    ax.grid(axis='y', linestyle='--', alpha=0.3)
    fig.tight_layout()
    fig.savefig(output_path, dpi=200, bbox_inches='tight')
    plt.close(fig)



def _plot_confusion_matrix(cm: np.ndarray, labels: list[str], title: str, output_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(7, 6))
    im = ax.imshow(cm, cmap='Blues')
    ax.figure.colorbar(im, ax=ax)
    ax.set_title(title)
    ax.set_xlabel('Predicted label')
    ax.set_ylabel('True label')
    ax.set_xticks(np.arange(len(labels)))
    ax.set_yticks(np.arange(len(labels)))
    ax.set_xticklabels(labels, rotation=45, ha='right')
    ax.set_yticklabels(labels)

    threshold = cm.max() / 2 if cm.size else 0
    for i in range(cm.shape[0]):
        for j in range(cm.shape[1]):
            ax.text(j, i, str(cm[i, j]), ha='center', va='center', color='white' if cm[i, j] > threshold else 'black')

    fig.tight_layout()
    fig.savefig(output_path, dpi=200, bbox_inches='tight')
    plt.close(fig)



def _print_sentiment_results(results: list[dict[str, Any]]) -> None:
    print('\n=== CHI TIET KET QUA SENTIMENT TUNG MODEL ===')
    for item in results:
        print(f"- {item['display_name']} ({item['model_name']})")
        print(f"  macro_f1    : {item['macro_f1']:.4f}")
        print(f"  weighted_f1 : {item['weighted_f1']:.4f}")
        print(f"  accuracy     : {item['report'].get('accuracy', 0.0):.4f}")



def _print_aspect_results(results: list[dict[str, Any]]) -> None:
    print('\n=== CHI TIET KET QUA ASPECT TUNG MODEL ===')
    for item in results:
        report = item['report']
        print(f"- {item['display_name']} ({item['model_name']})")
        print(f"  macro_f1   : {report['macro_f1']:.4f}")
        print(f"  micro_f1   : {report['micro_f1']:.4f}")
        print(f"  samples_f1 : {report['samples_f1']:.4f}")



def _build_sentiment_comparison(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            'model_name': item['model_name'],
            'display_name': item['display_name'],
            'macro_f1': item['macro_f1'],
            'weighted_f1': item['weighted_f1'],
            'accuracy': float(item['report'].get('accuracy', 0.0)),
        }
        for item in results
    ]



def _build_aspect_comparison(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            'model_name': item['model_name'],
            'display_name': item['display_name'],
            **item['report'],
        }
        for item in results
    ]



def main() -> None:
    parser = argparse.ArgumentParser(description='Train sentiment + aspect-sentiment and compare models.')
    parser.add_argument('--input', required=True, help='CSV da chuan hoa')
    parser.add_argument('--model-dir', default=str(MODEL_DIR), help='Thu muc luu model')
    parser.add_argument('--sentiment-models', nargs='*', default=DEFAULT_SENTIMENT_MODELS)
    parser.add_argument('--aspect-models', nargs='*', default=DEFAULT_ASPECT_MODELS)
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    model_dir.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(args.input)
    df['multilabel_targets'] = df['multilabel_targets'].fillna('').apply(lambda x: [item for item in str(x).split('|') if item])
    train_df, test_df = train_test_split(df, test_size=TEST_SIZE, random_state=RANDOM_STATE, stratify=df['overall_sentiment'])
    X_train_vec, X_test_vec, word_vectorizer, char_vectorizer = build_feature_matrices(train_df['text'], test_df['text'])

    sentiment_results = [
        train_and_score_sentiment(model_name, X_train_vec, X_test_vec, train_df['overall_sentiment'], test_df['overall_sentiment'])
        for model_name in args.sentiment_models
    ]
    _print_sentiment_results(sentiment_results)
    best_sentiment = max(sentiment_results, key=lambda item: item['macro_f1'])
    sentiment_comparison = _build_sentiment_comparison(sentiment_results)

    mlb = MultiLabelBinarizer()
    y_train = mlb.fit_transform(train_df['multilabel_targets'])
    y_test = mlb.transform(test_df['multilabel_targets'])
    aspect_results = [
        train_and_score_aspect(model_name, X_train_vec, X_test_vec, y_train, y_test, len(mlb.classes_), list(mlb.classes_))
        for model_name in args.aspect_models
    ]
    _print_aspect_results(aspect_results)
    best_aspect = max(aspect_results, key=lambda item: item['report']['macro_f1'])
    aspect_comparison = _build_aspect_comparison(aspect_results)

    sentiment_artifacts = {
        'model': best_sentiment['model'],
        'word_vectorizer': word_vectorizer,
        'char_vectorizer': char_vectorizer,
        'report': {
            **best_sentiment['report'],
            'best_model': best_sentiment['model_name'],
            'best_model_display_name': best_sentiment['display_name'],
            'macro_f1': best_sentiment['macro_f1'],
            'weighted_f1': best_sentiment['weighted_f1'],
            'confusion_matrix': best_sentiment['confusion_matrix'],
            'labels': best_sentiment['labels'],
        },
        'comparison': sentiment_comparison,
    }

    aspect_artifacts = {
        'model': best_aspect['model'],
        'word_vectorizer': word_vectorizer,
        'char_vectorizer': char_vectorizer,
        'mlb': mlb,
        'report': {
            **best_aspect['report'],
            'best_model': best_aspect['model_name'],
            'best_model_display_name': best_aspect['display_name'],
            'classes': list(mlb.classes_),
        },
        'comparison': aspect_comparison,
    }

    save_artifacts('sentiment', sentiment_artifacts, model_dir)
    save_artifacts('aspect', aspect_artifacts, model_dir)

    visualization_dir = model_dir / 'visualizations'
    visualization_dir.mkdir(parents=True, exist_ok=True)

    _plot_metric_bars(
        [item['display_name'] for item in sentiment_results],
        {
            'macro_f1': [item['macro_f1'] for item in sentiment_results],
            'weighted_f1': [item['weighted_f1'] for item in sentiment_results],
            'accuracy': [float(item['report'].get('accuracy', 0.0)) for item in sentiment_results],
        },
        'So sanh chi so sentiment giua cac model',
        visualization_dir / 'sentiment_model_comparison.png',
        )

    _plot_metric_bars(
        [item['display_name'] for item in aspect_results],
        {
            'macro_f1': [item['report']['macro_f1'] for item in aspect_results],
            'micro_f1': [item['report']['micro_f1'] for item in aspect_results],
            'samples_f1': [item['report']['samples_f1'] for item in aspect_results],
        },
        'So sanh chi so aspect giua cac model',
        visualization_dir / 'aspect_model_comparison.png',
        )

    for item in sentiment_results:
        _plot_confusion_matrix(
            np.asarray(item['confusion_matrix']),
            item['labels'],
            f"Confusion Matrix - {item['display_name']}",
            visualization_dir / f"confusion_matrix_{item['model_name']}.png",
            )

    split_info = {
        'train_size': int(len(train_df)),
        'test_size': int(len(test_df)),
        'columns': list(df.columns),
        'sentiment_models_tested': args.sentiment_models,
        'aspect_models_tested': args.aspect_models,
        'visualization_dir': str(visualization_dir),
    }
    (model_dir / 'split_info.json').write_text(json.dumps(split_info, ensure_ascii=False, indent=2), encoding='utf-8')

    print('\n=== CHON MODEL TOT NHAT ===')
    print(f"Best sentiment model: {best_sentiment['display_name']} ({best_sentiment['model_name']}) | macro F1: {best_sentiment['macro_f1']:.4f}")
    print(f"Best aspect model: {best_aspect['display_name']} ({best_aspect['model_name']}) | macro F1: {best_aspect['report']['macro_f1']:.4f}")
    print(f"Aspect micro F1: {best_aspect['report']['micro_f1']:.4f}")
    print(f'Da luu model vao: {model_dir}')
    print(f'Da luu anh bieu do va confusion matrix vao: {visualization_dir}')


if __name__ == '__main__':
    main()
