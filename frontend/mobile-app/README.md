# 移动App

劳务供应链交易平台的独立移动 App 预留目录。

## 用途、约定与禁止事项

- **现状**：只有 README 和 `analysis_options.yaml`，无 `pubspec.yaml` 与应用源码；下文 Flutter 版本、页面和运行步骤为历史规划参考。
- **用途**：承载独立移动端体验；不得默认由小程序代替，具体能力以当前 PRD 为准。
- **必须遵守**：初始化前确认工具链、目标平台与权限需求；敏感权限按需申请，令牌安全保存，离线数据与重试操作明确隔离和幂等策略。
- **禁止**：不得提交应用签名密钥或真实用户数据，不得明文保存敏感信息，不得以客户端判断替代服务端授权与金额核算。

共同约定：[前端总入口](../README.md)。

## 📋 技术栈

- **框架**：Flutter 3.x
- **语言**：Dart 3.x
- **状态管理**：Provider / Riverpod
- **网络请求**：Dio
- **本地存储**：shared_preferences
- **路由管理**：go_router

## 🏗️ 项目结构

```
mobile-app/
├── lib/
│   ├── main.dart          # 应用入口
│   ├── screens/           # 页面
│   │   ├── home/
│   │   ├── attendance/    # 考勤录入
│   │   └── ...
│   ├── widgets/           # 通用组件
│   ├── services/          # 服务层
│   │   ├── api_service.dart
│   │   └── auth_service.dart
│   ├── models/            # 数据模型
│   ├── providers/         # 状态管理
│   └── utils/             # 工具类
├── assets/                # 资源文件
├── android/               # Android配置
├── ios/                   # iOS配置
├── test/                  # 测试
├── pubspec.yaml           # 依赖配置
└── README.md              # 本文件
```

## 🚀 快速开始

### 环境要求

- Flutter 3.x
- Android Studio / Xcode
- Dart 3.x

### 安装依赖

```bash
flutter pub get
```

### 运行应用

```bash
# 运行在模拟器/真机
flutter run

# 运行在特定设备
flutter run -d <device_id>

# 查看可用设备
flutter devices
```

### 构建应用

```bash
# 构建Android APK
flutter build apk --release

# 构建iOS
flutter build ios --release

# 构建App Bundle (Google Play)
flutter build appbundle --release
```

## 🧪 测试

```bash
# 运行所有测试
flutter test

# 运行单个测试文件
flutter test test/widget_test.dart

# 生成测试覆盖率
flutter test --coverage
```

## 📝 开发规范

### 命名规范

```dart
// 文件名：snake_case
// attendance_screen.dart

// 类名：PascalCase
class AttendanceScreen extends StatelessWidget {
  // ...
}

// 变量名：camelCase
final String userName = 'xxx';
```

### API调用示例

```dart
import 'package:dio/dio.dart';

class ApiService {
  final Dio _dio = Dio(BaseOptions(
    baseUrl: 'https://api.example.com',
  ));

  Future<List<Employee>> getEmployees() async {
    final response = await _dio.get('/employees');
    return (response.data['data'] as List)
        .map((e) => Employee.fromJson(e))
        .toList();
  }
}
```

## 📚 相关文档

- [Flutter官方文档](https://flutter.dev/)
- [Dart官方文档](https://dart.dev/)
- [技术规范](../../CLAUDE.md)

## 🤝 贡献

请参考 [贡献指南](../../CONTRIBUTING.md)
