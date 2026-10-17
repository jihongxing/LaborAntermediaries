# 移动App

劳务中介管理系统 - 移动App（Flutter）

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
