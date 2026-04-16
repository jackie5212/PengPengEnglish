import 'package:flutter/material.dart';

import 'simple_static_screen.dart';

class MinePage extends StatelessWidget {
  const MinePage({
    super.key,
    required this.onOpenStudyRecord,
    required this.onOpenVoiceDownload,
    required this.onOpenVersion,
  });

  final VoidCallback onOpenStudyRecord;
  final VoidCallback onOpenVoiceDownload;
  final VoidCallback onOpenVersion;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text('我的', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600)),
        const SizedBox(height: 12),
        ListTile(
          title: const Text('个人资料'),
          onTap: () => Navigator.push<void>(
            context,
            MaterialPageRoute(
              builder: (_) => const SimpleStaticScreen(title: '个人资料', body: '个人资料（占位）'),
            ),
          ),
        ),
        ListTile(title: const Text('学习记录'), onTap: onOpenStudyRecord),
        ListTile(
          title: const Text('收藏内容'),
          onTap: () => Navigator.push<void>(
            context,
            MaterialPageRoute(
              builder: (_) => const SimpleStaticScreen(title: '收藏内容', body: '收藏内容（占位）'),
            ),
          ),
        ),
        ListTile(title: const Text('下载语音'), onTap: onOpenVoiceDownload),
        ListTile(
          title: const Text('设置'),
          onTap: () => _openSettingsMenu(context),
        ),
        ListTile(title: const Text('关于我们'), onTap: () => _openAboutMenu(context, onOpenVersion)),
      ],
    );
  }

  void _openSettingsMenu(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: const Text('通用设置'),
              onTap: () {
                Navigator.pop(ctx);
                Navigator.push<void>(
                  context,
                  MaterialPageRoute(
                    builder: (_) => const SimpleStaticScreen(title: '通用设置', body: '通用设置（占位）'),
                  ),
                );
              },
            ),
            ListTile(
              title: const Text('播放设置'),
              onTap: () {
                Navigator.pop(ctx);
                Navigator.push<void>(
                  context,
                  MaterialPageRoute(
                    builder: (_) => const SimpleStaticScreen(title: '播放设置', body: '播放设置（占位）'),
                  ),
                );
              },
            ),
            ListTile(
              title: const Text('隐私设置'),
              onTap: () {
                Navigator.pop(ctx);
                Navigator.push<void>(
                  context,
                  MaterialPageRoute(
                    builder: (_) => const SimpleStaticScreen(title: '隐私设置', body: '隐私设置（占位）'),
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }

  void _openAboutMenu(BuildContext context, VoidCallback onOpenVersion) {
    showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              title: const Text('版本信息'),
              onTap: () {
                Navigator.pop(ctx);
                onOpenVersion();
              },
            ),
            ListTile(
              title: const Text('用户协议'),
              onTap: () {
                Navigator.pop(ctx);
                Navigator.push<void>(
                  context,
                  MaterialPageRoute(
                    builder: (_) => const SimpleStaticScreen(title: '用户协议', body: '用户协议（占位长文）'),
                  ),
                );
              },
            ),
            ListTile(
              title: const Text('隐私政策'),
              onTap: () {
                Navigator.pop(ctx);
                Navigator.push<void>(
                  context,
                  MaterialPageRoute(
                    builder: (_) => const SimpleStaticScreen(title: '隐私政策', body: '隐私政策（占位长文）'),
                  ),
                );
              },
            ),
            ListTile(
              title: const Text('鸣谢'),
              onTap: () {
                Navigator.pop(ctx);
                Navigator.push<void>(
                  context,
                  MaterialPageRoute(
                    builder: (_) => const SimpleStaticScreen(title: '鸣谢', body: '鸣谢（占位）'),
                  ),
                );
              },
            ),
            ListTile(
              title: const Text('开发者信息'),
              onTap: () {
                Navigator.pop(ctx);
                Navigator.push<void>(
                  context,
                  MaterialPageRoute(
                    builder: (_) => const SimpleStaticScreen(title: '开发者信息', body: '开发者信息（占位）'),
                  ),
                );
              },
            ),
            ListTile(
              title: const Text('手机信息'),
              onTap: () {
                Navigator.pop(ctx);
                Navigator.push<void>(
                  context,
                  MaterialPageRoute(builder: (_) => _PhoneInfoScreen()),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _PhoneInfoScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    // Dart 无法直接读取 Android Build 全字段；此处为占位，完整对齐需 platform channel
    return Scaffold(
      appBar: AppBar(title: const Text('手机信息')),
      body: const Padding(
        padding: EdgeInsets.all(16),
        child: Text('设备信息展示依赖平台通道；当前为占位页。'),
      ),
    );
  }
}
