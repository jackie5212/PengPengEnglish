import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';

import '../services/voice_remote_catalog.dart';

class VersionInfoScreen extends StatefulWidget {
  const VersionInfoScreen({super.key});

  @override
  State<VersionInfoScreen> createState() => _VersionInfoScreenState();
}

class _VersionInfoScreenState extends State<VersionInfoScreen> {
  String _local = '…';
  String _remoteLine = '检查中…';

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check({bool showLatestSnack = false}) async {
    setState(() => _remoteLine = '检查中…');
    final info = await PackageInfo.fromPlatform();
    final local = info.version;
    String? remote;
    try {
      final res = await http.get(Uri.parse(kVersionUrl)).timeout(const Duration(seconds: 8));
      if (res.statusCode >= 200 && res.statusCode < 300) {
        for (final line in res.body.split(RegExp(r'\r?\n'))) {
          final t = line.trim();
          if (t.toLowerCase().startsWith('releaseversion:')) {
            remote = t.substring(t.indexOf(':') + 1).trim();
            break;
          }
        }
      }
    } catch (_) {}
    if (!mounted) return;
    setState(() {
      _local = local;
      _remoteLine = remote == null || remote.isEmpty ? '读取失败' : remote;
    });
    if (remote != null && remote.isNotEmpty && _isRemoteNewer(local, remote)) {
      if (!mounted) return;
      final r = remote;
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('发现新版本'),
          content: Text('检测到新版本 $r，请前往应用商店更新。'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('稍后')),
            FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('知道了')),
          ],
        ),
      );
    } else if (showLatestSnack && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已是最新版本')),
      );
    }
  }

  int _compareVersion(String a, String b) {
    List<int> parse(String v) =>
        v.trim().split(RegExp(r'[.\-_]')).map((t) => int.tryParse(t) ?? 0).toList();
    final x = parse(a);
    final y = parse(b);
    final n = x.length > y.length ? x.length : y.length;
    for (var i = 0; i < n; i++) {
      final xv = i < x.length ? x[i] : 0;
      final yv = i < y.length ? y[i] : 0;
      if (xv != yv) return xv.compareTo(yv);
    }
    return 0;
  }

  bool _isRemoteNewer(String local, String remote) => _compareVersion(remote, local) > 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('版本信息')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('当前版本：$_local'),
            Text('线上版本：$_remoteLine'),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => _check(showLatestSnack: true),
              child: const Text('检查更新'),
            ),
          ],
        ),
      ),
    );
  }
}
