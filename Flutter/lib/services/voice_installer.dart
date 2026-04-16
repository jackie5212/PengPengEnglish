import 'dart:io';

import 'package:archive/archive.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;

import 'native_paths.dart';
import 'voice_remote_catalog.dart';

/// 与 Android `VoiceDownloadActivity` 解压规则一致：仅抽取 onnx、tokens.txt、espeak-ng-data。
Future<void> downloadVoiceArchive({
  required String fileName,
  required void Function(int percent) onProgress,
}) async {
  final cache = await voiceDownloadCacheDir();
  if (!await cache.exists()) await cache.create(recursive: true);
  final outFile = File(p.join(cache.path, fileName));
  final uri = Uri.parse('$kModelBaseUrl/$fileName');
  final request = http.Request('GET', uri);
  final streamed = await http.Client().send(request);
  if (streamed.statusCode < 200 || streamed.statusCode >= 300) {
    throw HttpException('HTTP ${streamed.statusCode}', uri: uri);
  }
  final total = streamed.contentLength ?? 0;
  var received = 0;
  final sink = outFile.openWrite();
  try {
    await for (final chunk in streamed.stream) {
      sink.add(chunk);
      received += chunk.length;
      if (total > 0) {
        onProgress((received * 100 ~/ total).clamp(0, 100));
      }
    }
  } finally {
    await sink.close();
  }
  if (total <= 0) onProgress(100);
}

Future<void> installVoiceFromArchive({
  required File archiveFile,
  required String displayName,
  required void Function(int processed, int percent, String status) onProgress,
}) async {
  final fileName = archiveFile.path.split(Platform.pathSeparator).last;
  final modelId = fileName.replaceAll('.tar.bz2', '');
  final root = await piperRuntimeRoot();
  final targetDir = Directory(p.join(root.path, modelId));
  if (await targetDir.exists()) {
    await targetDir.delete(recursive: true);
  }
  await targetDir.create(recursive: true);

  final bytes = await archiveFile.readAsBytes();
  final decompressed = BZip2Decoder().decodeBytes(bytes);
  final tarArchive = TarDecoder().decodeBytes(decompressed);

  var processed = 0;
  final fileEntries = tarArchive.files.where((f) => f.isFile).toList();
  final nFiles = fileEntries.length;
  final denom = nFiles < 1 ? 1 : nFiles;

  Future<void> writeFile(String relativePath, List<int> content) async {
    final out = File(p.join(targetDir.path, relativePath));
    await out.parent.create(recursive: true);
    await out.writeAsBytes(content, flush: true);
  }

  for (var i = 0; i < fileEntries.length; i++) {
    final file = fileEntries[i];
    final name = file.name.replaceAll('\\', '/');
    final content = file.content;
    if (content == null) continue;

    final percent = ((i + 1) * 100 ~/ denom).clamp(0, 99);

    if (name.endsWith('.onnx')) {
      final base = name.contains('/') ? name.split('/').last : name;
      await writeFile(base, content);
      processed++;
      onProgress(processed, percent, name);
    } else if (name.endsWith('tokens.txt')) {
      await writeFile('tokens.txt', content);
      processed++;
      onProgress(processed, percent, name);
    } else if (name.contains('/espeak-ng-data/')) {
      final rel = name.substring(name.indexOf('/espeak-ng-data/') + '/espeak-ng-data/'.length);
      if (rel.isNotEmpty) {
        await writeFile(p.join('espeak-ng-data', rel), content);
        processed++;
        onProgress(processed, percent, name);
      }
    }
  }

  await File(p.join(targetDir.path, 'voice_name.txt')).writeAsString(displayName);
  onProgress(processed, 100, 'done');
}

Future<bool> validateVoiceInstall(String modelId) async {
  final root = await piperRuntimeRoot();
  final dir = Directory(p.join(root.path, modelId));
  if (!await dir.exists()) return false;
  final onnx = dir
      .listSync()
      .whereType<File>()
      .any((f) => f.path.toLowerCase().endsWith('.onnx'));
  final tokenOk = await File(p.join(dir.path, 'tokens.txt')).exists();
  final espeak = Directory(p.join(dir.path, 'espeak-ng-data'));
  var espeakOk = false;
  if (await espeak.exists()) {
    espeakOk = espeak.listSync().isNotEmpty;
  }
  return onnx && tokenOk && espeakOk;
}

Future<void> deleteVoiceModel(String fileName) async {
  final modelId = fileName.replaceAll('.tar.bz2', '');
  final root = await piperRuntimeRoot();
  final dir = Directory(p.join(root.path, modelId));
  if (await dir.exists()) {
    await dir.delete(recursive: true);
  }
  final cache = await voiceDownloadCacheDir();
  final arc = File(p.join(cache.path, fileName));
  if (await arc.exists()) await arc.delete();
}

Future<bool> isVoiceInstalled(String fileName) async {
  final modelId = fileName.replaceAll('.tar.bz2', '');
  final root = await piperRuntimeRoot();
  final dir = Directory(p.join(root.path, modelId));
  if (!await dir.exists()) return false;
  final onnx = dir
      .listSync()
      .whereType<File>()
      .any((f) => f.path.toLowerCase().endsWith('.onnx'));
  final tokenOk = await File(p.join(dir.path, 'tokens.txt')).exists();
  final espeak = Directory(p.join(dir.path, 'espeak-ng-data'));
  return onnx || tokenOk || (await espeak.exists() && espeak.listSync().isNotEmpty);
}
