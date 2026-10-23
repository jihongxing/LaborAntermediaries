# 🛡️ 规范执行快速参考卡

## 五层保护机制

```
📄 CLAUDE.md (文档)
   ↓ 明确规定必须使用P0/P1能力
   
🔍 CheckStyle (自动检查)
   ↓ 编译时自动拒绝违规代码
   
🤖 4个Skills (AI审查)
   ↓ java-spring-code-review / mybatis-generator / database-schema-checker / api-doc-generator
   
👥 Code Review (人工审查)
   ↓ PR必须≥90分才能merge
   
🔄 CI/CD (持续监督)
   ↓ 每日扫描 + 每周会议 + 月度清理
```

---

## ✅ 开发前（必读3份文档）

```bash
1. D:\codeSpace\LaborAntermediaries\CLAUDE.md
2. backend/P0_IMPLEMENTATION_COMPLETE.md
3. backend/P1_IMPLEMENTATION_COMPLETE.md
```

---

## ✅ 开发中（必用P0/P1能力）

| 场景 | 必须使用 | 禁止使用 |
|------|---------|---------|
| 金额计算 | `MoneyUtil` | double/float |
| 日期处理 | `DateUtil` | SimpleDateFormat |
| 获取用户 | `UserContext` | 参数传递userId |
| 生成ID | `IdGenerator` | UUID.randomUUID() |
| 加密解密 | `CryptoUtil` | 自己写BCrypt/AES |
| 参数校验 | `AssertUtil` | if + throw |
| 数据脱敏 | `DesensitizeUtil` | 手动截取字符串 |
| 字符串 | `StringUtil` | Apache Commons |
| 集合操作 | `CollectionUtil` | 手动for循环 |
| 统一响应 | `Result` | 返回Map |
| 业务异常 | `BusinessException` | RuntimeException |

---

## ✅ 开发后（必查3项）

```bash
# 1. CheckStyle检查
mvn checkstyle:check

# 2. 单元测试
mvn test

# 3. AI审查
对Claude说："review这个Service，使用java-spring-code-review skill"
```

---

## ✅ 提交前（必须通过）

- ✅ CheckStyle通过（100%）
- ✅ 单元测试通过（100%）
- ✅ Code Review评分 ≥ 90分

---

## 🚫 绝对禁止

```java
// ❌ 1. Controller注入Mapper
@Autowired
private EmployeeMapper employeeMapper;

// ❌ 2. 金额用double
private double salary;

// ❌ 3. 缺少tenant_id
CREATE TABLE xxx (id BIGINT ...);  // 没有tenant_id

// ❌ 4. SQL拼接
String sql = "SELECT * FROM employee WHERE name = '" + name + "'";

// ❌ 5. 明文存储密码
user.setPassword(rawPassword);

// ❌ 6. 手动传递userId
public void add(EmployeeDTO dto, Long userId) { ... }

// ❌ 7. 返回Map
public Map<String, Object> list() { ... }

// ❌ 8. throw RuntimeException
throw new RuntimeException("错误");

// ❌ 9. 手动for循环转换
List<EmployeeVO> voList = new ArrayList<>();
for (Employee emp : employees) {
    voList.add(convertToVO(emp));
}

// ❌ 10. 手动脱敏
String masked = idCard.substring(0, 3) + "***" + idCard.substring(14);
```

---

## 💬 对AI说话的正确方式

### ❌ 错误
```
"帮我写一个员工Service"
```

### ✅ 正确
```
"帮我写一个员工Service，严格遵守CLAUDE.md规范：
- 参数校验用AssertUtil
- 获取用户用UserContext
- 生成ID用IdGenerator
- 金额用MoneyUtil
- 集合用CollectionUtil

写完后用java-spring-code-review审查。"
```

---

## 🎯 评分标准

| 评分 | 等级 | 说明 |
|------|------|------|
| 90-100 | 优秀 | 完全符合规范 |
| 80-89 | 良好 | 有少量警告 |
| 70-79 | 及格 | 存在一些问题 |
| 60-69 | 不及格 | 存在严重问题 |
| <60 | 危险 | **必须重写** |

---

## 🛠️ 4个Skills快速使用

```
1. java-spring-code-review
   "review这个Service"
   "检查这段代码是否符合规范"

2. mybatis-generator
   "生成员工管理模块的代码"
   "根据employee_basic表生成MyBatis代码"

3. database-schema-checker
   "检查这个表结构是否符合规范"
   "review employee_basic表"

4. api-doc-generator
   "生成员工管理的API文档"
   "补充Swagger注解"
```

---

## 📞 遇到问题怎么办

1. **先查文档** - CLAUDE.md / P0文档 / P1文档
2. **使用Skills审查** - java-spring-code-review
3. **询问Tech Lead** - 周五Code Review会议
4. **提Issue** - GitHub Issues讨论

---

## 🎓 学习路径

```
第1天：阅读CLAUDE.md + P0文档 + P1文档
第2天：运行CheckStyle，查看检查项
第3天：使用4个Skills，熟悉用法
第4天：写一个简单模块，完整走一遍流程
第5天：参加Code Review会议，观察别人怎么做
```

---

**打印此卡片，贴在显示器旁边**  
**每次开发前看一眼，避免违规**

---

最后更新：2026年10月18日
# 后端底座接入更新（2026-10-23）

当前后端能力与运行步骤见 `backend/README.md`，能力建设依据见 `docs/02-开发阶段/技术方案/通用后端底座建设方案-v1.md`。本页旧示例中的 `IdGenerator` 已移除，改用 `SequenceIds`；加密使用外部密钥 `CryptoService`，JSON 使用 `JsonCodec`，身份及权限使用 `ExecutionContext`。后续开发不得重新引入固定 AES 密钥、请求体日志或业务代码中的租户切换。
