import re
import sqlite3
from pathlib import Path
from typing import Dict


ROOT = Path(r"d:\PengPengEnglish")
WN_DIR = ROOT / "wn3.1.dict"
DB_PATH = ROOT / "UniApp" / "ecdict.db"

EXAMPLE_RE = re.compile(r'"([^"]+)"')


def parse_data_file(path: Path, examples: Dict[str, str]) -> None:
    with path.open("r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            if not line or line.startswith("  "):
                continue
            if "|" not in line:
                continue
            head, gloss = line.split("|", 1)
            m = EXAMPLE_RE.search(gloss)
            if not m:
                continue
            example = m.group(1).strip()
            if not example:
                continue

            tokens = head.strip().split()
            if len(tokens) < 5:
                continue
            try:
                w_cnt = int(tokens[3], 16)
            except ValueError:
                continue

            idx = 4
            for _ in range(w_cnt):
                if idx + 1 >= len(tokens):
                    break
                lemma = tokens[idx].replace("_", " ").lower()
                idx += 2  # skip lex_id
                if lemma and lemma not in examples:
                    examples[lemma] = example


def main() -> None:
    if not DB_PATH.exists():
        raise SystemExit(f"DB not found: {DB_PATH}")

    examples: Dict[str, str] = {}
    for name in ("data.noun", "data.verb", "data.adj", "data.adv"):
        data_file = WN_DIR / name
        if data_file.exists():
            parse_data_file(data_file, examples)

    if not examples:
        raise SystemExit("No examples parsed from WordNet data files.")

    con = sqlite3.connect(str(DB_PATH))
    cur = con.cursor()
    cols = cur.execute("PRAGMA table_info(vocab_pack_words)").fetchall()
    col_names = {c[1] for c in cols}
    if "example_sentence" not in col_names:
        cur.execute("ALTER TABLE vocab_pack_words ADD COLUMN example_sentence TEXT")

    updated = 0
    for lemma, sentence in examples.items():
        cur.execute(
            "UPDATE vocab_pack_words SET example_sentence = ? WHERE lower(word) = ? AND (example_sentence IS NULL OR example_sentence = '')",
            (sentence, lemma),
        )
        updated += cur.rowcount

    con.commit()
    con.close()
    print(f"parsed examples: {len(examples)}")
    print(f"updated rows: {updated}")


if __name__ == "__main__":
    main()
