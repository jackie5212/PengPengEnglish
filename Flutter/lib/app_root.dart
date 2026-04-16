import 'package:flutter/material.dart';

import 'main_shell.dart';

class PengPengEnglishApp extends StatelessWidget {
  const PengPengEnglishApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '碰碰英语',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: const MainShell(),
    );
  }
}
