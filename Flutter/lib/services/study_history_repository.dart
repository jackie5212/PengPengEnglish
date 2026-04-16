import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

class StudyHistoryEntry {
  StudyHistoryEntry({
    required this.id,
    required this.packId,
    required this.packName,
    required this.pageIndex,
    required this.totalPages,
    required this.savedAtMs,
  });

  final String id;
  final String packId;
  final String packName;
  final int pageIndex;
  final int totalPages;
  final int savedAtMs;

  String displayLine() => '$packName  第 ${pageIndex + 1}/$totalPages 页';

  Map<String, dynamic> toJson() => {
        'id': id,
        'packId': packId,
        'packName': packName,
        'pageIndex': pageIndex,
        'totalPages': totalPages,
        'savedAtMs': savedAtMs,
      };

  static StudyHistoryEntry fromJson(Map<String, dynamic> o) {
    return StudyHistoryEntry(
      id: o['id'] as String,
      packId: o['packId'] as String,
      packName: o['packName'] as String,
      pageIndex: o['pageIndex'] as int,
      totalPages: o['totalPages'] as int,
      savedAtMs: o['savedAtMs'] as int,
    );
  }
}

/// 与 Android `StudyHistoryRepository` 行为一致（prefs 名、JSON 结构、最多 5 条）。
class StudyHistoryRepository {
  StudyHistoryRepository(this._prefs);

  final SharedPreferences _prefs;

  static const _keyRecords = 'records_json';
  static const _maxRecords = 5;

  static Future<StudyHistoryRepository> open() async {
    final p = await SharedPreferences.getInstance();
    return StudyHistoryRepository(p);
  }

  List<StudyHistoryEntry> loadAll() {
    final raw = _prefs.getString(_keyRecords);
    if (raw == null || raw.isEmpty) return [];
    try {
      final arr = jsonDecode(raw) as List<dynamic>;
      return arr
          .map((e) => StudyHistoryEntry.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> recordSession(
    String packId,
    String packName,
    int pageIndex,
    int totalPages,
  ) async {
    final list = loadAll()..removeWhere((e) => e.packId == packId);
    list.insert(
      0,
      StudyHistoryEntry(
        id: '${DateTime.now().microsecondsSinceEpoch}',
        packId: packId,
        packName: packName,
        pageIndex: pageIndex < 0 ? 0 : pageIndex,
        totalPages: totalPages < 1 ? 1 : totalPages,
        savedAtMs: DateTime.now().millisecondsSinceEpoch,
      ),
    );
    while (list.length > _maxRecords) {
      list.removeLast();
    }
    await _saveAll(list);
  }

  Future<void> deleteById(String id) async {
    final list = loadAll().where((e) => e.id != id).toList();
    await _saveAll(list);
  }

  Future<void> ensureDefaultSamplesIfEmpty() async {
    if (loadAll().isNotEmpty) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    final demos = [
      StudyHistoryEntry(
          id: 'demo-1',
          packId: 'demo1',
          packName: '示例：初中词汇',
          pageIndex: 2,
          totalPages: 120,
          savedAtMs: now - 86400000 * 5),
      StudyHistoryEntry(
          id: 'demo-2',
          packId: 'demo2',
          packName: '示例：高中词汇',
          pageIndex: 0,
          totalPages: 200,
          savedAtMs: now - 86400000 * 4),
      StudyHistoryEntry(
          id: 'demo-3',
          packId: 'demo3',
          packName: '示例：四级词汇',
          pageIndex: 15,
          totalPages: 350,
          savedAtMs: now - 86400000 * 3),
      StudyHistoryEntry(
          id: 'demo-4',
          packId: 'demo4',
          packName: '示例：考研词汇',
          pageIndex: 7,
          totalPages: 400,
          savedAtMs: now - 86400000 * 2),
      StudyHistoryEntry(
          id: 'demo-5',
          packId: 'demo5',
          packName: '示例：小学大纲',
          pageIndex: 4,
          totalPages: 56,
          savedAtMs: now - 86400000),
    ];
    await _saveAll(demos);
  }

  Future<void> _saveAll(List<StudyHistoryEntry> list) async {
    final arr = list.map((e) => e.toJson()).toList();
    await _prefs.setString(_keyRecords, jsonEncode(arr));
  }
}
