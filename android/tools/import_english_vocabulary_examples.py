import json
import sqlite3
from pathlib import Path


ROOT = Path(r"d:\PengPengEnglish")
SRC_DIR = ROOT / "english-vocabulary-master"
DB_PATH = ROOT / "Android" / "app" / "src" / "main" / "assets" / "example_sentence.db"


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def main() -> None:
    vocab_rows = load_json(SRC_DIR / "tb_vocabulary.json")
    ex_rows = load_json(SRC_DIR / "tb_voc_examples.json")

    id_to_word = {}
    for row in vocab_rows:
        wid = row.get("wordid")
        spelling = (row.get("spelling") or "").strip().lower()
        if wid is not None and spelling:
            id_to_word[int(wid)] = spelling

    con = sqlite3.connect(str(DB_PATH))
    cur = con.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS example_sentences (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            word TEXT NOT NULL,
            sentence_en TEXT NOT NULL,
            sentence_cn TEXT,
            heat INTEGER,
            source TEXT NOT NULL,
            updated_at TEXT
        )
        """
    )
    cur.execute("CREATE INDEX IF NOT EXISTS idx_example_sentences_word ON example_sentences(word)")
    cur.execute("DELETE FROM example_sentences")

    batch = []
    for row in ex_rows:
        wid = row.get("wordid")
        if wid is None:
            continue
        word = id_to_word.get(int(wid))
        if not word:
            continue
        en = (row.get("en") or "").strip()
        cn = (row.get("cn") or "").strip()
        if not en:
            continue
        heat = row.get("heat")
        adddate = row.get("adddate")
        batch.append((word, en, cn, heat, "english-vocabulary-master", adddate))

        if len(batch) >= 5000:
            cur.executemany(
                """
                INSERT INTO example_sentences
                (word, sentence_en, sentence_cn, heat, source, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                batch,
            )
            batch.clear()

    if batch:
        cur.executemany(
            """
            INSERT INTO example_sentences
            (word, sentence_en, sentence_cn, heat, source, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            batch,
        )

    con.commit()
    total = cur.execute("SELECT COUNT(1) FROM example_sentences").fetchone()[0]
    mapped_words = cur.execute("SELECT COUNT(DISTINCT word) FROM example_sentences").fetchone()[0]
    con.close()
    print(f"inserted_examples={total}")
    print(f"distinct_words={mapped_words}")


if __name__ == "__main__":
    main()
