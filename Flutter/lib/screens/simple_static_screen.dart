import 'package:flutter/material.dart';

class SimpleStaticScreen extends StatelessWidget {
  const SimpleStaticScreen({super.key, required this.title, required this.body});

  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: SingleChildScrollView(child: Text(body)),
      ),
    );
  }
}
