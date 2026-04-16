import 'dart:io';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import '../services/native_paths.dart';
import '../services/voice_installer.dart';
import '../services/voice_remote_catalog.dart';

class VoiceDownloadScreen extends StatefulWidget {
  const VoiceDownloadScreen({super.key});

  @override
  State<VoiceDownloadScreen> createState() => _VoiceDownloadScreenState();
}

class _VoiceDownloadScreenState extends State<VoiceDownloadScreen> {
  String _status = '正在读取模型列表…';
  List<RemoteVoiceModel> _models = [];
  int _progress = 0;

  @override
  void initState() {
    super.initState();
    _loadIndex();
  }

  Future<void> _loadIndex() async {
    setState(() => _status = '正在读取模型列表…');
    try {
      final res = await http.get(Uri.parse(kModelIndexUrl)).timeout(const Duration(seconds: 25));
      if (res.statusCode < 200 || res.statusCode >= 300) {
        throw HttpException('HTTP ${res.statusCode}');
      }
      final list = parseModelIndex(res.body);
      if (!mounted) return;
      setState(() {
        _models = list;
        _status = list.isEmpty ? '模型列表为空' : '可下载模型：${list.length} 个';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = '读取模型列表失败：$e');
    }
  }

  Future<void> _download(RemoteVoiceModel model) async {
    setState(() {
      _status = '正在下载：${model.fileName}';
      _progress = 0;
    });
    try {
      await downloadVoiceArchive(
        fileName: model.fileName,
        onProgress: (p) {
          if (mounted) setState(() => _progress = p);
        },
      );
      final cache = await voiceDownloadCacheDir();
      final archive = File('${cache.path}/${model.fileName}');
      if (!mounted) return;
      setState(() => _status = '下载完成，正在解压：${model.fileName}');
      await installVoiceFromArchive(
        archiveFile: archive,
        displayName: model.displayName,
        onProgress: (processed, percent, status) {
          if (mounted) {
            setState(() {
              _progress = percent;
              _status = '正在解压：${model.fileName}（$processed 项，$percent%）';
            });
          }
        },
      );
      final ok = await validateVoiceInstall(model.id);
      if (!mounted) return;
      setState(() {
        _status = ok ? '已完成：${model.fileName}' : '解压后校验未通过';
        _progress = 100;
      });
      await _loadIndex();
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = '失败：$e');
    }
  }

  Future<void> _delete(RemoteVoiceModel model) async {
    setState(() => _status = '正在删除：${model.fileName}');
    try {
      await deleteVoiceModel(model.fileName);
      if (!mounted) return;
      setState(() => _status = '已删除：${model.fileName}');
      await _loadIndex();
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = '删除失败：$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('下载语音')),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(_status),
                const SizedBox(height: 8),
                LinearProgressIndicator(value: _progress / 100),
                Text('$_progress%'),
              ],
            ),
          ),
          Expanded(
            child: ListView.builder(
              itemCount: _models.length,
              itemBuilder: (ctx, i) {
                final m = _models[i];
                return FutureBuilder<bool>(
                  future: isVoiceInstalled(m.fileName),
                  builder: (ctx, snap) {
                    final installed = snap.data == true;
                    return ListTile(
                      title: Text(m.displayName),
                      subtitle: Text(m.fileName),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          FilledButton(
                            onPressed: () => _download(m),
                            child: const Text('下载'),
                          ),
                          if (installed) ...[
                            const SizedBox(width: 8),
                            OutlinedButton(
                              onPressed: () => _delete(m),
                              child: const Text('删除'),
                            ),
                          ],
                        ],
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
