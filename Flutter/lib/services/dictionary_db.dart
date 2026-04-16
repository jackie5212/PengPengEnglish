import 'dart:io';

import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite/sqflite.dart';

import '../models/vocab_pack.dart';
import '../models/word_entry.dart';
import 'native_paths.dart';

class ExamplePair {
  const ExamplePair({required this.en, required this.cn});

  final String en;
  final String cn;
}

/// 与 Android `DictionaryDb.kt` 逻辑对齐（版本号、SQL、小学例句生成规则）。
class DictionaryDb {
  DictionaryDb._(
    this._mainPath,
    this._examplePath,
    this._primaryExamplePath,
  );

  final String _mainPath;
  final String _examplePath;
  final String _primaryExamplePath;

  final Map<String, String> _meaningCache = {};
  final Map<String, ExamplePair> _exampleCache = {};
  final Map<String, String> _phoneticCache = {};
  final Map<String, ExamplePair> _primaryExampleCache = {};

  /// 只读库长期保持单连接，避免 sqflite singleInstance 下反复 open/close 带来的异常与卡顿。
  Database? _mainDbCached;
  Database? _exampleDbCached;
  Database? _primaryDbCached;

  static const _metaAssetDb = 'asset_db_version';
  static const _metaExampleDb = 'example_db_version';
  static const _metaPrimaryExampleDb = 'primary_example_db_version';

  static const _assetDbVersion = 3;
  static const _exampleDbVersion = 1;
  static const _primaryExampleDbVersion = 1;

  static const _primaryPackId = 'primary_school';
  static const _primaryPackName = '小学英语大纲词汇';

  static final RegExp _cjkRegex = RegExp(r'[\u4e00-\u9fff]');
  static final RegExp _englishTokenRegex = RegExp(r'[A-Za-z]+');

  static Future<DictionaryDb> open() async {
    final prefs = await SharedPreferences.getInstance();
    final base = await getNoBackupOrSupportDir();
    final mainPath = p.join(base.path, 'ecdict.db');
    final examplePath = p.join(base.path, 'example_sentence.db');
    final primaryPath = p.join(base.path, 'primary_examples.db');

    await _ensureMainDbReady(mainPath, prefs);
    await _ensureExampleDbReady(examplePath, prefs);
    await _ensurePrimaryExampleDbReady(primaryPath, prefs);

    return DictionaryDb._(mainPath, examplePath, primaryPath);
  }

  static Future<void> _ensureMainDbReady(String outPath, SharedPreferences prefs) async {
    final copied = prefs.getInt(_metaAssetDb) ?? 0;
    final f = File(outPath);
    final needCopy = !await f.exists() || await f.length() == 0 || copied != _assetDbVersion;
    if (needCopy) {
      final data = await rootBundle.load('assets/ecdict.db');
      await f.writeAsBytes(data.buffer.asUint8List(), flush: true);
      await prefs.setInt(_metaAssetDb, _assetDbVersion);
    }
    await _ensureSchema(outPath);
    await _ensureBundledPacks(outPath);
  }

  static Future<void> _ensureSchema(String path) async {
    final db = await openDatabase(path);
    try {
      final cols = await db.rawQuery('PRAGMA table_info(vocab_pack_words)');
      final names = cols.map((r) => r['name'] as String).toSet();
      if (!names.contains('example_sentence')) {
        await db.execute('ALTER TABLE vocab_pack_words ADD COLUMN example_sentence TEXT');
      }
    } finally {
      await db.close();
    }
  }

  static Future<void> _ensureBundledPacks(String path) async {
    final words = await _loadWordListAsset('assets/packs/primary_school_words.txt');
    if (words.isEmpty) return;
    final db = await openDatabase(path);
    try {
      await db.transaction((txn) async {
        await txn.rawInsert(
          '''
          INSERT OR REPLACE INTO vocab_packs(pack_id, pack_name, source, word_count, updated_at)
          VALUES(?, ?, ?, ?, datetime('now', 'localtime'))
          ''',
          [_primaryPackId, _primaryPackName, 'builtin', words.length],
        );
        await txn.rawDelete('DELETE FROM vocab_pack_words WHERE pack_id = ?', [_primaryPackId]);
        var lineNo = 1;
        for (final w in words) {
          await txn.rawInsert(
            'INSERT OR REPLACE INTO vocab_pack_words(pack_id, word, line_no) VALUES(?, ?, ?)',
            [_primaryPackId, w, lineNo++],
          );
        }
      });
    } finally {
      await db.close();
    }
  }

  static Future<void> _ensureExampleDbReady(String outPath, SharedPreferences prefs) async {
    final copied = prefs.getInt(_metaExampleDb) ?? 0;
    final f = File(outPath);
    final needCopy = !await f.exists() || await f.length() == 0 || copied != _exampleDbVersion;
    if (needCopy) {
      final data = await rootBundle.load('assets/example_sentence.db');
      await f.writeAsBytes(data.buffer.asUint8List(), flush: true);
      await prefs.setInt(_metaExampleDb, _exampleDbVersion);
    }
  }

  static Future<void> _ensurePrimaryExampleDbReady(String outPath, SharedPreferences prefs) async {
    final built = prefs.getInt(_metaPrimaryExampleDb) ?? 0;
    final f = File(outPath);
    final needBuild = !await f.exists() || await f.length() == 0 || built != _primaryExampleDbVersion;
    if (!needBuild) return;

    if (await f.exists()) await f.delete();

    final words = (await _loadWordListAsset('assets/packs/primary_school_words.txt'))
        .map((e) => e.trim().toLowerCase())
        .where((e) => e.isNotEmpty)
        .toSet()
        .toList();
    final pairs = await _loadPrimaryExamplePairs('assets/packs/primary_school_examples.txt');
    if (words.isEmpty || pairs.isEmpty) {
      await prefs.setInt(_metaPrimaryExampleDb, _primaryExampleDbVersion);
      return;
    }

    await _buildPrimaryExamplesFull(outPath, words, pairs);
    await prefs.setInt(_metaPrimaryExampleDb, _primaryExampleDbVersion);
  }

  static Future<void> _buildPrimaryExamplesFull(
    String outPath,
    List<String> words,
    List<ExamplePair> pairs,
  ) async {
    final db = await openDatabase(outPath, version: 1, onCreate: (db, _) async {
      await db.execute('''
        CREATE TABLE IF NOT EXISTS primary_examples(
          word TEXT NOT NULL,
          sentence_en TEXT NOT NULL,
          sentence_cn TEXT NOT NULL,
          priority INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(word, sentence_en)
        )
        ''');
      await db.execute(
          'CREATE INDEX IF NOT EXISTS idx_primary_examples_word_pri ON primary_examples(word, priority)');
    });

    await db.transaction((txn) async {
      var batch = txn.batch();
      var count = 0;
      for (var index = 0; index < pairs.length; index++) {
        final pair = pairs[index];
        for (final word in words) {
          if (!_containsWordOrPhrase(pair.en, word)) continue;
          batch.rawInsert(
            'INSERT OR IGNORE INTO primary_examples(word, sentence_en, sentence_cn, priority) VALUES(?, ?, ?, ?)',
            [word, pair.en, pair.cn, index],
          );
          count++;
          if (count >= 200) {
            await batch.commit(noResult: true);
            batch = txn.batch();
            count = 0;
          }
        }
      }
      if (count > 0) {
        await batch.commit(noResult: true);
      }
    });
    await db.close();
  }

  static Future<List<String>> _loadWordListAsset(String path) async {
    final ordered = <String>{};
    try {
      final s = await rootBundle.loadString(path);
      for (final raw in s.split(RegExp(r'\r?\n'))) {
        final w = raw.trim();
        if (w.isNotEmpty) ordered.add(w);
      }
    } catch (_) {}
    return ordered.toList();
  }

  static Future<List<ExamplePair>> _loadPrimaryExamplePairs(String path) async {
    List<String> lines;
    try {
      lines = (await rootBundle.loadString(path)).split(RegExp(r'\r?\n')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList();
    } catch (_) {
      return [];
    }
    final pairs = <ExamplePair>[];
    String? pendingEn;
    for (final line in lines) {
      if (_looksLikeEnglishSentence(line)) {
        pendingEn = line;
        continue;
      }
      if (pendingEn != null && _looksLikeChineseSentence(line)) {
        pairs.add(ExamplePair(en: pendingEn, cn: line));
        pendingEn = null;
      }
    }
    return pairs;
  }

  static bool _looksLikeEnglishSentence(String text) {
    final hasAscii = _englishTokenRegex.hasMatch(text);
    final hasCjk = _cjkRegex.hasMatch(text);
    return hasAscii && !hasCjk;
  }

  static bool _looksLikeChineseSentence(String text) => _cjkRegex.hasMatch(text);

  static bool _containsWordOrPhrase(String sentence, String word) {
    final sentenceLower = sentence.toLowerCase();
    final w = word.toLowerCase();
    if (word.contains(' ')) {
      final idx = sentenceLower.indexOf(w);
      if (idx < 0) return false;
      final beforeOk = idx == 0 || !_isLetter(sentenceLower.codeUnitAt(idx - 1));
      final end = idx + w.length;
      final afterOk = end >= sentenceLower.length || !_isLetter(sentenceLower.codeUnitAt(end));
      return beforeOk && afterOk;
    }
    return RegExp(r'\b' + RegExp.escape(w) + r'\b', caseSensitive: false).hasMatch(sentenceLower);
  }

  static bool _isLetter(int codeUnit) {
    final c = String.fromCharCode(codeUnit);
    return RegExp(r'[A-Za-z]').hasMatch(c);
  }

  Future<Database> _openMainReadCached() async {
    _mainDbCached ??= await openDatabase(_mainPath, readOnly: true, singleInstance: true);
    return _mainDbCached!;
  }

  Future<Database> _openExampleReadCached() async {
    _exampleDbCached ??= await openDatabase(_examplePath, readOnly: true, singleInstance: true);
    return _exampleDbCached!;
  }

  Future<Database> _openPrimaryReadCached() async {
    _primaryDbCached ??= await openDatabase(_primaryExamplePath, readOnly: true, singleInstance: true);
    return _primaryDbCached!;
  }

  Future<List<VocabPack>> getPacks() async {
    final db = await _openMainReadCached();
    final rows = await db.rawQuery('SELECT pack_id, pack_name FROM vocab_packs ORDER BY pack_name');
    return rows
        .map((r) => VocabPack(id: r['pack_id']! as String, name: r['pack_name']! as String))
        .toList();
  }

  Future<int> countWords(String packId) async {
    final db = await _openMainReadCached();
    final c = await db.rawQuery(
      'SELECT COUNT(1) AS c FROM vocab_pack_words WHERE pack_id = ?',
      [packId],
    );
    if (c.isEmpty) return 0;
    return _readSqlInt(c.first['c']);
  }

  static int _readSqlInt(Object? v) {
    if (v == null) return 0;
    if (v is int) return v;
    if (v is num) return v.toInt();
    return int.tryParse(v.toString()) ?? 0;
  }

  Future<List<WordEntry>> getWordsPage(String packId, int limit, int offset) async {
    final db = await _openMainReadCached();
    final rows = await db.rawQuery(
      '''
        SELECT vpw.word
        FROM vocab_pack_words vpw
        WHERE vpw.pack_id = ?
        ORDER BY vpw.line_no
        LIMIT ? OFFSET ?
        ''',
      [packId, limit, offset],
    );
    final out = <WordEntry>[];
    for (final r in rows) {
      final word = (r['word'] as String?)?.trim() ?? '';
      if (word.isEmpty) continue;
      final ex = await lookupExample(word, packId);
      out.add(WordEntry(word: word, example: ex.en, exampleCn: ex.cn));
    }
    return out;
  }

  Future<int> countSearchWords(String keyword) async {
    final q = keyword.trim();
    if (q.isEmpty) return 0;
    final db = await _openMainReadCached();
    final c = await db.rawQuery(
      '''
        SELECT COUNT(1) AS c
        FROM ecdict_entries
        WHERE lower(word) LIKE lower(?)
        ''',
      ['$q%'],
    );
    if (c.isEmpty) return 0;
    return _readSqlInt(c.first['c']);
  }

  Future<List<WordEntry>> searchWords({
    required String keyword,
    int limit = 30,
    int offset = 0,
  }) async {
    final q = keyword.trim();
    if (q.isEmpty) return [];
    final db = await _openMainReadCached();
    final rows = await db.rawQuery(
      '''
        SELECT word
        FROM ecdict_entries
        WHERE lower(word) LIKE lower(?)
        ORDER BY CASE WHEN lower(word) = lower(?) THEN 0 ELSE 1 END, word
        LIMIT ? OFFSET ?
        ''',
      ['$q%', q, limit, offset],
    );
    final out = <WordEntry>[];
    for (final r in rows) {
      final word = r['word'] as String? ?? '';
      if (word.isEmpty) continue;
      final ex = await lookupExample(word, null);
      out.add(WordEntry(word: word, example: ex.en, exampleCn: ex.cn));
    }
    return out;
  }

  Future<ExamplePair> lookupExample(String word, String? packId) async {
    if (packId == _primaryPackId) {
      return lookupPrimaryExample(word);
    }
    final key = word.toLowerCase();
    final hit = _exampleCache[key];
    if (hit != null) return hit;

    final db = await _openExampleReadCached();
    final rows = await db.rawQuery(
      '''
        SELECT sentence_en, COALESCE(sentence_cn, '')
        FROM example_sentences
        WHERE lower(word) = lower(?)
          AND sentence_en IS NOT NULL
          AND sentence_en <> ''
        ORDER BY COALESCE(heat, 0) DESC, id ASC
        LIMIT 1
        ''',
      [word],
    );
    final ex = rows.isEmpty
        ? const ExamplePair(en: '', cn: '')
        : ExamplePair(
            en: (rows.first['sentence_en'] as String?) ?? '',
            cn: (rows.first['sentence_cn'] as String?) ?? '',
          );
    _exampleCache[key] = ex;
    return ex;
  }

  Future<ExamplePair> lookupPrimaryExample(String word) async {
    final key = word.toLowerCase();
    final hit = _primaryExampleCache[key];
    if (hit != null) return hit;

    final db = await _openPrimaryReadCached();
    final rows = await db.rawQuery(
      '''
        SELECT sentence_en, sentence_cn
        FROM primary_examples
        WHERE word = ?
        ORDER BY priority ASC
        LIMIT 1
        ''',
      [key],
    );
    final ex = rows.isEmpty
        ? const ExamplePair(en: '', cn: '')
        : ExamplePair(
            en: (rows.first['sentence_en'] as String?) ?? '',
            cn: (rows.first['sentence_cn'] as String?) ?? '',
          );
    _primaryExampleCache[key] = ex;
    return ex;
  }

  Future<String> lookupMeaning(String word) async {
    final key = word.toLowerCase();
    final hit = _meaningCache[key];
    if (hit != null) return hit;

    final db = await _openMainReadCached();
    final rows = await db.rawQuery(
      '''
        SELECT COALESCE(NULLIF(translation, ''), NULLIF(definition, ''), '') AS meaning
        FROM ecdict_entries
        WHERE lower(word) = lower(?)
        LIMIT 1
        ''',
      [word],
    );
    final m = rows.isEmpty ? '' : (rows.first['meaning'] as String?) ?? '';
    _meaningCache[key] = m;
    return m;
  }

  Future<String> lookupPhonetic(String word) async {
    final key = word.toLowerCase();
    final hit = _phoneticCache[key];
    if (hit != null) return hit;

    final db = await _openMainReadCached();
    final rows = await db.rawQuery(
      '''
        SELECT COALESCE(NULLIF(phonetic, ''), '') AS phonetic
        FROM ecdict_entries
        WHERE lower(word) = lower(?)
        LIMIT 1
        ''',
      [word],
    );
    final p0 = rows.isEmpty ? '' : (rows.first['phonetic'] as String?) ?? '';
    _phoneticCache[key] = p0;
    return p0;
  }
}
