import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// 与 Android `Context.getNoBackupFilesDir()` 对齐，便于 Piper 与下载模型共用目录。
Future<Directory> getNoBackupOrSupportDir() async {
  if (defaultTargetPlatform == TargetPlatform.android) {
    try {
      const ch = MethodChannel('com.pengpengenglish/paths');
      final dir = await ch.invokeMethod<String>('noBackupFilesDir');
      if (dir != null && dir.isNotEmpty) {
        return Directory(dir);
      }
    } catch (_) {
      // Channel 未注册时使用 fallback
    }
  }
  return getApplicationSupportDirectory();
}

Future<Directory> piperRuntimeRoot() async {
  final base = await getNoBackupOrSupportDir();
  return Directory(p.join(base.path, 'piper'));
}

Future<Directory> voiceDownloadCacheDir() async {
  final tmp = await getTemporaryDirectory();
  return Directory(p.join(tmp.path, 'voice-downloads'));
}
