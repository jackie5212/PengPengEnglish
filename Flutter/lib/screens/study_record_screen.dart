import 'package:flutter/material.dart';

import '../services/study_history_repository.dart';

class StudyRecordScreen extends StatefulWidget {
  const StudyRecordScreen({super.key});

  @override
  State<StudyRecordScreen> createState() => _StudyRecordScreenState();
}

class _StudyRecordScreenState extends State<StudyRecordScreen> {
  StudyHistoryRepository? _repo;
  List<StudyHistoryEntry> _entries = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final r = await StudyHistoryRepository.open();
    await r.ensureDefaultSamplesIfEmpty();
    if (!mounted) return;
    setState(() {
      _repo = r;
      _entries = r.loadAll();
    });
  }

  Future<void> _refresh() async {
    final r = _repo ?? await StudyHistoryRepository.open();
    setState(() => _entries = r.loadAll());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('学习记录')),
      body: _entries.isEmpty
          ? const Center(child: Text('暂无学习记录'))
          : ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: _entries.length,
              itemBuilder: (ctx, i) {
                final e = _entries[i];
                return Card(
                  child: ListTile(
                    title: Text(e.displayLine()),
                    trailing: TextButton(
                      onPressed: () async {
                        final r = _repo ?? await StudyHistoryRepository.open();
                        await r.deleteById(e.id);
                        if (mounted) setState(() => _repo = r);
                        await _refresh();
                      },
                      child: const Text('删除'),
                    ),
                  ),
                );
              },
            ),
    );
  }
}
