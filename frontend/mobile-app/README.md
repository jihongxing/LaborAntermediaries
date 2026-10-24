# 移动App

劳务供应链交易平台的独立移动 App 预留目录。

## 用途、约定与禁止事项

- **现状**：只有 README 和 `analysis_options.yaml`，无 `pubspec.yaml` 与应用源码；技术栈版本、页面示例和运行步骤为历史规划参考，新增文件遵循下方目录规划。
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

## 目录规划与新增文件规则

以下为目标放置模板，不代表已初始化 Flutter 工程。只创建真实需求需要的目录；`<feature>` 为小写下划线形式的业务名称。状态管理等依赖仍须初始化时评审，不因目录规划选定 Provider 或 Riverpod。

```text
mobile-app/
├── lib/
│   ├── main.dart                 启动入口
│   ├── app/                      路由、主题、依赖装配与应用生命周期
│   ├── features/
│   │   └── <feature>/
│   │       ├── presentation/
│   │       │   ├── pages/        页面
│   │       │   ├── widgets/      业务专属 UI
│   │       │   └── state/        业务状态与展示编排
│   │       ├── domain/           业务模型、纯规则及必要的小接口
│   │       ├── data/             业务端点、传输模型、缓存与数据访问实现
│   │       └── <feature>.dart    必要的公开入口，按需新增
│   ├── core/
│   │   ├── network/              请求与错误转换
│   │   ├── session/              会话生命周期
│   │   ├── storage/              安全存储与本地存储封装
│   │   └── platform/             相机、定位等插件适配，按需新增
│   └── shared/
│       ├── widgets/              无业务归属的复用 UI
│       └── utils/                无副作用的纯工具
├── assets/
│   ├── shared/                   应用共用资源
│   └── features/<feature>/       业务专属资源，按需新增
├── android/ / ios/               目标平台配置与原生接入，按需生成
├── test/                         镜像 lib 的相对目录结构
├── integration_test/             跨页面或真机集成场景，按需新增
├── pubspec.yaml / pubspec.lock   应用依赖与锁定结果
├── analysis_options.yaml
└── README.md
```

### 放置与依赖规则

- 页面、业务状态、传输模型和数据访问跟随 `features/<feature>`，不新增全局 `screens`、`models`、`providers` 或业务 `services` 集合。简单功能不强制建齐所有分层，也不为每个动作创建用例类。
- `presentation` 处理展示和交互，`domain` 保持纯 Dart 业务规则，不依赖 Flutter UI、Dio 或平台插件；`data` 封装传输转换与持久化细节。确需隔离数据访问时，在 `domain` 定义小接口，由 `data` 实现，在 `app` 装配。
- `core` 不引用业务模块，平台插件的具体调用集中在 `core/platform` 等对应能力中；业务通过明确接口使用，避免页面到处直接调用插件。安全存储实现待工具链评审确定，不用普通偏好设置保存敏感令牌。
- `app` 引用业务和公共能力，业务不反向引用应用装配；`shared` 不依赖业务或 `core`，所需行为通过参数或小接口传入。跨业务仅使用公开契约，不引用其他业务内部状态或数据访问实现。
- 单元测试与 Widget 测试镜像被测路径，例如 `lib/features/<feature>/domain/` 对应 `test/features/<feature>/domain/`，文件使用 `*_test.dart`；共用测试辅助代码按需放 `test/support/`，真实设备流程放 `integration_test/`。
- 资源按归属放置并在初始化后的 `pubspec.yaml` 中声明；不在 `build/`、`.dart_tool/` 等生成目录保存手工维护文件。签名材料不纳入源码目录；业务拆分和公共提取遵循[前端共同规则](../README.md#目录规划与新增文件规则)。

### 放置示例

以下只说明未来获批需求的归属：

| 新增内容 | 位置 |
|---|---|
| 考勤页面及其状态 | `lib/features/attendance/presentation/pages/`、`presentation/state/` |
| 考勤业务规则 | `lib/features/attendance/domain/` |
| 考勤请求及离线数据转换 | `lib/features/attendance/data/` |
| 定位插件适配 | `lib/core/platform/`，考勤专属定位判定仍在业务模块 |
| 考勤规则单元测试 | `test/features/attendance/domain/` |
| 考勤页面图标 | `assets/features/attendance/` |

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

业务请求实现放在 `lib/features/employee/data/`，通过构造参数接收由应用装配的网络客户端；不新增全局业务 `ApiService`。以下仅演示职责和注入方式，`Employee` 的传输转换也归该业务的 `data`，具体返回结构仍须对齐后端契约。

```dart
import 'package:dio/dio.dart';

class EmployeeRemoteDataSource {
  EmployeeRemoteDataSource(this._dio);

  final Dio _dio;

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
