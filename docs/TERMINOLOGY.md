# 项目术语表（Terminology）

**项目名称**：劳务供应链交易平台
**文档版本**：v1.0  
**最后更新**：2026年10月20日
**维护人**：技术负责人

> 当前业务术语以自主推进需求基线 v0.4 和劳务供应链交易平台 PRD v0.1 为准；本表中的历史术语只作为兼容和检索参考，不得覆盖当前产品定位、收费、资金及角色边界。

---

## 📋 文档说明

本文档是项目的**全局统一术语表**，定义了所有业务概念、技术术语、数据字段的标准命名和含义。

**使用规则**：
1. ✅ 所有文档、代码、接口必须使用本表定义的术语
2. ✅ 出现新术语时，必须先更新本表，再使用
3. ✅ 禁止使用同义词或自创术语
4. ✅ 术语冲突时，以本表为准

**更新流程**：
1. 提出新术语需求
2. 技术负责人审查
3. 更新本文档
4. 通知团队成员

---

## 🎯 目录

1. [业务术语](#业务术语)
2. [技术术语](#技术术语)
3. [数据库术语](#数据库术语)
4. [API术语](#api术语)
5. [前端术语](#前端术语)
6. [状态枚举值](#状态枚举值)
7. [禁止使用的术语](#禁止使用的术语)

---

## 📊 一、业务术语

### 1.1 核心业务概念

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **租户** | tenant | - | 平台内具有独立数据与权限边界的组织空间；具体组织类型与平台运营空间关系按系统架构定义 | 租户ID、租户名称 |
| **工人** | worker | worker | 实际参与岗位或项目履约的个人；劳动关系、合同主体与报酬责任依真实安排确定 | 工人编号、工人姓名 |
| **项目** | project | proj | 中标后承载履约方案、供给、人员、考勤、计费与结算记录的业务单元 | 项目名称、项目编号 |
| **排班** | schedule / shift assignment | - | 经授权项目人员发布并版本化的人员、岗位、服务日期和计划班次安排；计划时间不等于实际出勤 | 项目排班、班次安排 |
| **平台原始打卡事件** | platform clock event | - | 工人经平台小程序提交的上班/下班打卡原始事实；追加保存并关联工人、项目、履约关系、班次和平台接收时间 | 上班打卡事件、下班打卡事件 |
| **计算工时** | calculated work hours | - | 平台按排班版本、打卡/已批准补正事件及适用规则计算的班次工时，尚不必然代表最终确认或计费结果 | 班次计算工时 |
| **确认工时** | confirmed work hours | - | 经规定主体复核确认并绑定规则版本的工时快照；更正须以调整记录引用原快照 | 月度确认工时 |
| **外部考勤记录** | external attendance record | - | 来自用工方/工厂接口、文件或其他授权来源的考勤数据，作为独立来源用于差异比对，不覆盖平台原始打卡 | 工厂考勤导入 |
| **招聘渠道** | recruiting channel | - | 经劳务公司授权、协助其获取候选人或人员供给的合作渠道；不自动成为履约供应商 | 渠道来源、渠道合作费用 |
| **用工单位** | work unit / employer | - | 发布真实需求、选择供应商并按约确认履约结果的依法设立主体 | 用工单位名称 |
| **政策** | policy | - | 工资计算规则、返费计算规则 | 工资政策、返费政策 |
| **管理员** | admin / administrator | - | 系统管理员 | 系统管理员、租户管理员 |
| **操作员** | operator | - | 系统操作人员 | 操作员工号、操作员姓名 |
| **用工方** | employer / work unit | - | 在平台发布用工需求、选择供应商并参与履约确认的组织角色 | 用工方需求 |
| **劳务供应商** | labor supplier | - | 通过平台准入审核并针对用工需求应标、按实际合同组织相应履约的合规劳务公司 | 中标供应商、供应商配额 |
| **交易网络** | transaction network | - | 用工单位与合规劳务供应商围绕需求、应标、履约、核验及结算形成的多边交易关系；不是单家组织的内部管理软件 | 供需网络、交易复购 |
| **交易基础设施** | transaction infrastructure | - | 平台为交易标准化、供应商选择、履约记录核验、计费、对账及结算状态管理提供的共同规则和产品能力 | 需求标准、有效计费记录 |
| **用工需求** | labor demand | - | 描述服务范围、岗位/标项、地点、数量、目标指标、期限、报价与结算条件的结构化需求 | 需求编号、目标指标 |
| **应标** | bid | - | 劳务公司针对用工需求提交的结构化承诺、履约方案、供给能力、报价及偏离说明 | 应标方案、承诺值 |
| **中标快照** | award snapshot | - | 中标时锁定的需求与应标版本、配额、承诺、价格、计量、账期和责任条件；平台费规则仅在适用合同已确定时关联 | 中标条件、配额 |
| **第三方资金账户** | third-party payment account | - | 仅在实际合同、授权、机构能力和适用要求支持时采用的资金通道；不是平台默认账户安排 | 机构账户、流水 |
| **资金分配** | fund allocation | - | 按具体合同、授权和通道规则执行的资金支付或分配，不预设所有项目采用固定顺序 | 分配指令、机构回执 |
| **平台管理费/平台服务费** | platform management/service fee | - | 平台为用工方提供履约管理与监督服务，费用由履约方承担、用工方原则上不付；价格和审批状态见[商业模式与收费标准](02-开发阶段/产品设计/商业模式与收费标准-v0.1.md) | 履约方平台服务费账单 |
| **需求线索解锁费** | demand lead unlock fee | - | 100 元/条授权有效线索/次解锁价格及前置条件已批准；收费结算暂缓，详见[商业模式与收费标准](02-开发阶段/产品设计/商业模式与收费标准-v0.1.md) | 线索解锁订单 |
| **有效履约人天** | verified billable worker-day | - | 按项目事先确认的计量口径、履约证据和合同条件核验后可计入平台服务费计算的履约量；争议量单独标记 | 有效计费明细 |
| **供应商业务结算价** | supplier service price | - | 用工单位与中标劳务公司依各自合同约定的服务单价或结算条件；不是平台服务费 | 中标单价、结算账单 |
| **管理费** | management fee | - | 单独使用时仍有歧义；如指平台管理费，按[商业模式与收费标准](02-开发阶段/产品设计/商业模式与收费标准-v0.1.md)解释，基线为履约方承担、用工方原则上不付；如指供应商业务结算价中的管理成分，应标明所属结算项目和责任主体 | 平台管理费账单、供应商结算明细 |
| **返费保障安排** | rebate protection arrangement | - | 可能采用的返费风险保障方式；是否启用、资金形态、责任及规则需单独批准，不表示平台默认承担赔付 | 保障规则、授权记录 |

### 1.2 工资相关

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **工资** | salary | - | 员工的劳动报酬 | 月工资、工资单 |
| **薪资** | salary | - | 同"工资" | 薪资管理 |
| **底薪** | base_salary / basic_salary | - | 固定工资部分 | 底薪2000元 |
| **应发工资** | gross_salary | - | 扣款前的工资总额 | 应发5500元 |
| **实发工资** | net_salary | - | 实际发放的工资 | 实发5000元 |
| **时薪** | hourly_rate | - | 每小时工资 | 时薪15元/小时 |
| **日薪** | daily_rate | - | 每日工资 | 日薪120元/天 |
| **计件工资** | piece_rate_salary | - | 按件计算的工资 | 计件工资 |
| **加班费** | overtime_pay | - | 加班产生的工资 | 加班费500元 |
| **工时** | work_hour / working_hour | - | 工作时长（小时） | 工时200小时/月 |
| **正常工时** | normal_hour | - | 正常上班时长 | 正常工时160小时 |
| **加班工时** | overtime_hour | - | 加班时长 | 加班工时40小时 |
| **扣款** | deduction | - | 从工资中扣除的金额 | 扣款300元 |
| **罚款** | penalty / fine | - | 违规产生的扣款 | 罚款50元 |
| **补贴** | allowance / subsidy | - | 额外的补助 | 交通补贴 |

### 1.3 考勤相关

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **考勤** | attendance | att | 由排班、平台原始打卡及其他授权来源构成的出勤记录；须区分来源和确认状态 | 平台考勤、外部考勤 |
| **打卡** | clock_in / clock_out | - | 工人对本人有效班次提交的上班/下班事件；不自动等于完整工时或有效计费人天 | 上班打卡、下班打卡 |
| **漏卡补正** | clock correction | - | 针对缺失或错误打卡发起并经授权处理的更正流程；保留原事件/缺失状态、申请理由、证据、处理结论和审计记录 | 下班卡补正 |
| **考勤差异** | attendance discrepancy | - | 平台计算结果与外部考勤或人工核对结果之间待处理的差别；任一来源不得静默覆盖另一来源 | 工时差异待复核 |
| **班次工时** | shift work hours | - | 关联特定人员、项目、班次与服务日期的计算或确认工时明细 | 当日班次工时 |
| **出勤** | present / attendance | - | 正常上班 | 出勤天数 |
| **缺勤** | absent / absence | - | 未到岗 | 缺勤天数 |
| **迟到** | late / tardy | - | 晚于规定时间到岗 | 迟到30分钟 |
| **早退** | early_leave | - | 早于规定时间离岗 | 早退1小时 |
| **请假** | leave | - | 申请休假 | 请假3天 |
| **病假** | sick_leave | - | 因病请假 | 病假2天 |
| **事假** | personal_leave | - | 因私事请假 | 事假1天 |
| **年假** | annual_leave | - | 带薪年假 | 年假5天 |
| **旷工** | absent_without_leave | AWOL | 无故缺勤 | 旷工1天 |
| **调休** | compensatory_leave | - | 加班后的补休 | 调休半天 |

### 1.4 返费相关

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **返费** | rebate | - | 渠道方向劳务公司支付的介绍费 | 返费政策、返费结算 |
| **佣金** | commission | - | 同"返费"（在某些语境下） | 佣金比例 |
| **回扣** | kickback | - | ❌ 禁止使用（贬义词） | - |
| **人头费** | per_capita_fee | - | 按人数计算的返费 | 人头费800元/人 |
| **达标** | meet_standard / qualified | - | 员工满足返费条件 | 达标30天 |
| **未达标** | not_meet_standard / unqualified | - | 员工未满足返费条件 | 未达标（不足30天） |
| **撞单** | duplicate_registration | - | 同一员工被多个渠道重复报名 | 撞单检测 |
| **结算** | settlement | - | 返费的计算和支付 | 月度结算 |

### 1.5 借支相关

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **借支** | advance_payment | adv | 员工提前支取工资 | 借支500元 |
| **预支** | advance_payment | - | 同"借支" | 预支工资 |
| **借款** | loan | - | ❌ 不推荐（容易与金融借贷混淆） | - |
| **扣款** | deduction | - | 从工资中扣除借支 | 扣款500元 |
| **还款** | repayment | - | 归还借支 | 还款计划 |

### 1.6 员工状态

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **在职** | working / active | - | 正在工作 | 在职员工 |
| **离职** | resigned / quit | - | 已离开公司 | 离职日期 |
| **待岗** | standby / waiting | - | 暂时没有工作安排 | 待岗员工 |
| **试用期** | probation | - | 试用阶段 | 试用期3个月 |
| **转正** | regularization | - | 试用期结束，成为正式员工 | 转正日期 |
| **休假** | on_leave | - | 正在休假中 | 休假中 |
| **停薪留职** | leave_without_pay | - | 保留职位但不发工资 | 停薪留职 |

### 1.7 合同相关

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **合同** | contract | - | 劳动合同 | 劳动合同 |
| **劳务合同** | labor_contract | - | 劳务派遣合同 | 劳务合同编号 |
| **服务协议** | service_agreement | - | 服务合同 | 服务协议 |
| **签约** | sign_contract | - | 签订合同 | 签约日期 |
| **续约** | renew_contract | - | 延长合同 | 续约1年 |
| **解约** | terminate_contract | - | 终止合同 | 解约日期 |
| **违约** | breach_contract | - | 违反合同 | 违约金 |

---

## 🔧 二、技术术语

### 2.1 架构术语

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **后端** | backend | BE | 服务端 | 后端服务 |
| **前端** | frontend | FE | 客户端 | 前端页面 |
| **数据库** | database | DB | 数据存储 | 数据库设计 |
| **缓存** | cache | - | 临时数据存储 | Redis缓存 |
| **消息队列** | message queue | MQ | 异步消息传递 | RabbitMQ |
| **接口** | API | - | 应用程序接口 | RESTful API |
| **微服务** | microservice | - | 服务拆分架构 | 微服务架构 |
| **单体架构** | monolithic | - | 单一应用架构 | 单体应用 |
| **分布式** | distributed | - | 多节点部署 | 分布式系统 |
| **高可用** | high availability | HA | 系统可用性保障 | 高可用架构 |
| **负载均衡** | load balancing | LB | 流量分发 | 负载均衡器 |
| **容器化** | containerization | - | Docker容器部署 | 容器化部署 |

### 2.2 开发术语

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **控制器** | controller | - | MVC的C层 | EmployeeController |
| **服务层** | service | - | 业务逻辑层 | EmployeeService |
| **数据访问层** | mapper / repository / DAO | - | 数据库操作层 | EmployeeMapper |
| **实体类** | entity | - | 数据库表映射类 | Employee |
| **数据传输对象** | data transfer object | DTO | 接口传输对象 | EmployeeDTO |
| **视图对象** | view object | VO | 前端展示对象 | EmployeeVO |
| **业务对象** | business object | BO | 业务逻辑对象 | EmployeeBO |
| **查询对象** | query object | QO | 查询条件对象 | EmployeeQueryDTO |
| **分页对象** | page object | - | 分页查询结果 | Page<Employee> |
| **枚举** | enum | - | 枚举类型 | EmployeeStatus |
| **常量** | constant | - | 不可变值 | BusinessConstant |
| **工具类** | util / utility | - | 工具方法集合 | DateUtil |
| **异常** | exception | - | 错误处理 | BusinessException |
| **拦截器** | interceptor | - | 请求拦截 | TenantInterceptor |
| **过滤器** | filter | - | 请求过滤 | AuthFilter |
| **切面** | aspect | - | AOP切面 | LogAspect |

### 2.3 设计模式术语

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **单例模式** | singleton | - | 单一实例 | 配置管理器 |
| **工厂模式** | factory | - | 对象创建 | SalaryCalculatorFactory |
| **策略模式** | strategy | - | 算法切换 | PaymentStrategy |
| **模板方法** | template method | - | 算法框架 | AbstractCalculator |
| **观察者模式** | observer | - | 事件监听 | EventListener |
| **装饰器模式** | decorator | - | 功能增强 | CacheDecorator |
| **代理模式** | proxy | - | 访问控制 | ServiceProxy |
| **适配器模式** | adapter | - | 接口适配 | ThirdPartyAdapter |

---

## 💾 三、数据库术语

### 3.1 PostgreSQL核心术语

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **主库** | primary | - | 主数据库（可写） | Primary节点 |
| **从库** | standby / replica | - | 从数据库（只读） | Standby节点 |
| **流复制** | streaming replication | - | WAL流式同步 | 流复制延迟 |
| **WAL** | write-ahead log | - | 预写日志 | WAL归档 |
| **复制槽** | replication slot | - | 复制位置标记 | 复制槽管理 |
| **热备** | hot standby | - | 支持只读的备库 | 热备模式 |
| **MVCC** | multi-version concurrency control | - | 多版本并发控制 | MVCC机制 |
| **VACUUM** | vacuum | - | 清理死元组 | VACUUM ANALYZE |
| **死元组** | dead tuple | - | 已删除但未清理的行 | 死元组清理 |
| **表膨胀** | table bloat | - | 表空间浪费 | 表膨胀检测 |
| **分区表** | partitioned table | - | 表分区 | Range分区 |
| **分区裁剪** | partition pruning | - | 自动过滤无关分区 | 分区裁剪优化 |

### 3.2 数据库对象术语

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **表** | table | - | 数据存储结构 | employee_basic |
| **视图** | view | - | 虚拟表 | employee_view |
| **索引** | index | idx | 查询优化结构 | idx_employee_tenant_id |
| **唯一索引** | unique index | uk | 唯一约束索引 | uk_employee_id_card |
| **主键** | primary key | pk | 主键约束 | id |
| **外键** | foreign key | fk | 外键约束 | fk_employee_tenant |
| **序列** | sequence | seq | 自增序列 | employee_id_seq |
| **触发器** | trigger | - | 自动执行的函数 | update_updated_at |
| **函数** | function | - | 存储过程 | update_updated_at_column |
| **模式** | schema | - | 命名空间 | public |
| **约束** | constraint | - | 数据完整性规则 | check_salary_positive |
| **检查约束** | check constraint | - | 字段值校验 | CHECK (salary >= 0) |

### 3.3 索引类型术语

| 中文 | 英文术语 | 缩写 | 说明 | 示例 |
|------|---------|------|------|------|
| **B-Tree索引** | B-Tree index | - | 默认索引类型 | 常规查询 |
| **GIN索引** | generalized inverted index | GIN | 倒排索引 | JSONB、全文搜索 |
| **BRIN索引** | block range index | BRIN | 块范围索引 | 时序数据 |
| **Hash索引** | hash index | - | 哈希索引 | 等值查询 |
| **部分索引** | partial index | - | 条件索引 | WHERE deleted_at IS NULL |
| **表达式索引** | expression index | - | 基于表达式的索引 | LOWER(name) |
| **覆盖索引** | covering index | - | 包含所有查询字段的索引 | 避免回表 |

### 3.4 数据类型术语

| 中文 | PostgreSQL类型 | Java类型 | TypeScript类型 | 说明 |
|------|---------------|---------|---------------|------|
| **整数** | INTEGER / BIGINT | Integer / Long | number | 整数 |
| **自增整数** | SERIAL / BIGSERIAL | Long | number | 自增主键 |
| **浮点数** | DOUBLE PRECISION | Double | number | ❌ 禁止用于金额 |
| **精确数字** | NUMERIC(p,s) | BigDecimal | string | ✅ 金额必须用 |
| **字符串** | VARCHAR(n) | String | string | 可变长字符串 |
| **长文本** | TEXT | String | string | 不限长度文本 |
| **布尔** | BOOLEAN | Boolean | boolean | 布尔值 |
| **日期** | DATE | LocalDate | string | 日期（无时间） |
| **时间** | TIME | LocalTime | string | 时间（无日期） |
| **日期时间** | TIMESTAMP | LocalDateTime | string | 日期+时间（无时区） |
| **带时区日期时间** | TIMESTAMPTZ | ZonedDateTime | string | 日期+时间+时区 ✅ 推荐 |
| **JSON** | JSON | String | object | JSON文本 |
| **JSONB** | JSONB | String / JsonNode | object | 二进制JSON ✅ 推荐 |
| **数组** | ARRAY | List<T> | T[] | 数组类型 |
| **UUID** | UUID | UUID | string | 全局唯一标识 |

---

## 🌐 四、API术语

### 4.1 HTTP方法术语

| HTTP方法 | 用途 | 说明 | 示例 |
|---------|------|------|------|
| **GET** | 查询 | 获取资源 | GET /api/employees |
| **POST** | 创建 | 新增资源 | POST /api/employees |
| **PUT** | 更新（全量）| 完整更新资源 | PUT /api/employees/1 |
| **PATCH** | 更新（部分）| 部分更新资源 | PATCH /api/employees/1 |
| **DELETE** | 删除 | 删除资源 | DELETE /api/employees/1 |

### 4.2 RESTful资源命名

| 资源 | URL规范 | 说明 |
|------|---------|------|
| **员工** | /api/employees | 复数形式 |
| **单个员工** | /api/employees/{id} | 资源ID |
| **员工的工资** | /api/employees/{id}/salaries | 子资源 |
| **工资列表** | /api/salaries | 独立资源 |

### 4.3 请求参数术语

| 中文 | 英文术语 | 位置 | 说明 | 示例 |
|------|---------|------|------|------|
| **路径参数** | path parameter | URL路径 | 资源标识 | /api/employees/{id} |
| **查询参数** | query parameter | URL参数 | 过滤、排序、分页 | ?status=working&page=1 |
| **请求体** | request body | HTTP Body | 复杂数据 | JSON对象 |
| **请求头** | request header | HTTP Header | 元数据 | Authorization |

### 4.4 响应术语

| 中文 | 英文术语 | 说明 | 示例 |
|------|---------|------|------|
| **状态码** | status code | HTTP状态码 | 200、400、500 |
| **响应体** | response body | 返回数据 | JSON对象 |
| **响应头** | response header | 元数据 | Content-Type |
| **分页信息** | pagination | 分页元数据 | page、size、total |

### 4.5 标准字段命名

| 字段含义 | 字段名 | 类型 | 说明 |
|---------|--------|------|------|
| **主键ID** | id | Long | 主键 |
| **租户ID** | tenantId | Long | 租户ID |
| **创建时间** | createdAt | String (ISO 8601) | 创建时间 |
| **创建人** | createdBy | Long | 创建人ID |
| **更新时间** | updatedAt | String (ISO 8601) | 更新时间 |
| **更新人** | updatedBy | Long | 更新人ID |
| **删除时间** | deletedAt | String (ISO 8601) | 软删除时间 |
| **版本号** | version | Integer | 乐观锁版本 |

---

## 🎨 五、前端术语

### 5.1 Vue术语

| 中文 | 英文术语 | 说明 | 示例 |
|------|---------|------|------|
| **组件** | component | 可复用的UI单元 | EmployeeForm.vue |
| **页面** | page / view | 路由对应的页面 | EmployeeList.vue |
| **路由** | route | URL到组件的映射 | /employee/list |
| **状态** | state | 应用数据 | Vuex state |
| **计算属性** | computed | 派生数据 | computed |
| **侦听器** | watcher | 数据变化监听 | watch |
| **生命周期** | lifecycle | 组件生命周期钩子 | mounted |
| **插槽** | slot | 内容分发 | <slot> |
| **指令** | directive | DOM操作指令 | v-if、v-for |
| **组合式API** | composition API | Vue 3 API风格 | setup() |

### 5.2 前端状态管理

| 中文 | 英文术语 | 说明 | 示例 |
|------|---------|------|------|
| **状态** | state | 全局状态 | 用户信息、租户信息 |
| **获取器** | getter | 计算状态 | 派生数据 |
| **变更** | mutation | 同步修改状态 | SET_USER |
| **动作** | action | 异步操作 | fetchEmployeeList |
| **模块** | module | 状态模块化 | user、employee |

### 5.3 前端路由

| 中文 | 英文术语 | 说明 | 示例 |
|------|---------|------|------|
| **路由** | route | 路由配置 | { path, component } |
| **路由器** | router | 路由管理器 | VueRouter |
| **导航守卫** | navigation guard | 路由拦截 | beforeEach |
| **动态路由** | dynamic route | 参数路由 | /employee/:id |
| **嵌套路由** | nested route | 子路由 | children |
| **路由懒加载** | lazy loading | 按需加载 | () => import() |

---

## 📝 六、状态枚举值

### 6.1 员工状态（EmployeeStatus）

| 状态值 | 中文 | 说明 | 备注 |
|--------|------|------|------|
| `working` | 在职 | 正在工作 | 默认状态 |
| `resigned` | 离职 | 已离职 | 终态 |
| `standby` | 待岗 | 暂无工作安排 | - |
| `probation` | 试用期 | 试用阶段 | - |
| `on_leave` | 休假 | 正在休假 | - |
| `suspended` | 停薪留职 | 保留职位但不发工资 | - |

### 6.2 工资状态（SalaryStatus）

| 状态值 | 中文 | 说明 | 备注 |
|--------|------|------|------|
| `pending` | 待计算 | 尚未计算 | 初始状态 |
| `calculated` | 已计算 | 已计算完成 | - |
| `approved` | 已审核 | 已通过审核 | - |
| `paid` | 已发放 | 已支付 | 终态 |
| `cancelled` | 已取消 | 已作废 | 终态 |

### 6.3 考勤状态（AttendanceStatus）

| 状态值 | 中文 | 说明 | 备注 |
|--------|------|------|------|
| `present` | 出勤 | 正常上班 | - |
| `absent` | 缺勤 | 未到岗 | - |
| `late` | 迟到 | 晚于规定时间 | - |
| `early_leave` | 早退 | 早于规定时间 | - |
| `on_leave` | 请假 | 已批准的假期 | - |
| `awol` | 旷工 | 无故缺勤 | - |

### 6.3.1 平台打卡事件类型与工时状态

| 类别 | 状态/类型值 | 中文 | 说明 |
|---|---|---|---|
| 打卡事件类型 | `clock_in` | 上班打卡 | 工人小程序提交的上班原始事件 |
| 打卡事件类型 | `clock_out` | 下班打卡 | 工人小程序提交的下班原始事件 |
| 工时处理状态 | `pending_calculation` | 待计算 | 已接收事件或排班尚未形成班次计算结果 |
| 工时处理状态 | `calculated` | 已计算 | 已按对应排班和工时规则生成结果 |
| 工时处理状态 | `needs_correction` | 待补正 | 存在缺卡、错卡或必要信息缺失 |
| 工时处理状态 | `under_review` | 待复核 | 差异、补正申请或异常正在核对 |
| 工时处理状态 | `confirmed` | 已确认 | 按项目适用规则由授权主体显式确认 |
| 工时处理状态 | `adjusted` | 已调整 | 确认后通过关联原记录的调整留痕更正 |

上述状态为需求词汇，最终枚举及状态拆分由领域模型评审确定；不得以单一 AttendanceStatus 混合表达事件类型、计算状态和确认状态。

### 6.4 返费状态（RebateStatus）

| 状态值 | 中文 | 说明 | 备注 |
|--------|------|------|------|
| `pending` | 待判定 | 尚未判定是否达标 | 初始状态 |
| `qualified` | 已达标 | 满足返费条件 | - |
| `unqualified` | 未达标 | 不满足返费条件 | - |
| `settled` | 已结算 | 已支付返费 | 终态 |
| `cancelled` | 已取消 | 已作废 | 终态 |

### 6.5 借支状态（AdvanceStatus）

| 状态值 | 中文 | 说明 | 备注 |
|--------|------|------|------|
| `pending` | 待审核 | 申请中 | 初始状态 |
| `approved` | 已批准 | 已通过审核 | - |
| `rejected` | 已拒绝 | 审核未通过 | 终态 |
| `paid` | 已发放 | 已支付借支款 | - |
| `deducted` | 已扣款 | 已从工资扣除 | 终态 |

### 6.6 审核状态（AuditStatus）

| 状态值 | 中文 | 说明 | 备注 |
|--------|------|------|------|
| `pending` | 待审核 | 等待审核 | 初始状态 |
| `approved` | 已通过 | 审核通过 | 终态 |
| `rejected` | 已拒绝 | 审核拒绝 | 终态 |

### 6.7 支付状态（PaymentStatus）

| 状态值 | 中文 | 说明 | 备注 |
|--------|------|------|------|
| `pending` | 待支付 | 尚未支付 | 初始状态 |
| `processing` | 处理中 | 支付处理中 | - |
| `success` | 已成功 | 支付成功 | 终态 |
| `failed` | 已失败 | 支付失败 | - |
| `refunded` | 已退款 | 已退回 | 终态 |

---

## ⛔ 七、禁止使用的术语

### 7.1 业务术语禁用词

| ❌ 禁止使用 | ✅ 应该使用 | 原因 |
|-----------|-----------|------|
| worker | employee | "worker"歧义太多 |
| staff | employee | 统一用"employee" |
| wage | salary | 统一用"salary" |
| payment | salary | "payment"歧义（可能是支付记录） |
| commission | rebate | "commission"易与销售佣金混淆 |
| kickback | rebate | "kickback"是贬义词 |
| loan | advance_payment | "loan"易与金融借贷混淆 |
| company / organization | tenant（仅表示隔离边界） | tenant 不是公司或组织的同义词；业务对象应使用具体角色或组织术语 |
| job | project | "job"歧义太多 |
| task | project | "task"易与任务管理混淆 |

### 7.2 技术术语禁用词

| ❌ 禁止使用 | ✅ 应该使用 | 原因 |
|-----------|-----------|------|
| master | primary | 避免敏感词 |
| slave | standby / replica | 避免敏感词 |
| blacklist | blocklist | 避免敏感词 |
| whitelist | allowlist | 避免敏感词 |
| double / float | BigDecimal / NUMERIC | 金额必须用精确类型 |
| DATETIME | TIMESTAMPTZ | PostgreSQL推荐带时区 |
| AUTO_INCREMENT | SERIAL / BIGSERIAL | PostgreSQL语法 |
| ENGINE=InnoDB | - | PostgreSQL无此概念 |

### 7.3 数据库字段命名禁用词

| ❌ 禁止使用 | ✅ 应该使用 | 原因 |
|-----------|-----------|------|
| createTime | created_at | 统一用下划线 |
| updateTime | updated_at | 统一用下划线 |
| isDeleted | deleted_at | 软删除用时间戳 |
| tenantID | tenant_id | 统一用下划线 |
| employeeID | employee_id | 统一用下划线 |
| userId | user_id | 统一用下划线 |

---

## 📚 八、使用示例

### 8.1 正确示例

#### 数据库表设计：
```sql
CREATE TABLE employee_basic (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL,  -- 'working', 'resigned'
  
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  deleted_at TIMESTAMPTZ
);
```

#### Java实体类：
```java
@TableName("employee_basic")
public class Employee extends BaseEntity {
    private Long id;
    private Long tenantId;
    private String name;
    private EmployeeStatus status;  // 枚举
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
```

#### API接口：
```
GET    /api/employees              # 查询员工列表
GET    /api/employees/{id}         # 查询员工详情
POST   /api/employees              # 新增员工
PUT    /api/employees/{id}         # 更新员工
DELETE /api/employees/{id}         # 删除员工
```

#### TypeScript接口：
```typescript
interface Employee {
  id: number;
  tenantId: number;
  name: string;
  status: 'working' | 'resigned' | 'standby';
  
  createdAt: string;  // ISO 8601
  updatedAt: string;
  deletedAt?: string;
}
```

### 8.2 错误示例（❌ 禁止）

#### ❌ 错误的数据库设计：
```sql
-- 错误1：使用驼峰命名
CREATE TABLE EmployeeBasic (
  id BIGINT PRIMARY KEY,
  tenantId BIGINT,  -- ❌ 应该是 tenant_id
  createTime TIMESTAMP,  -- ❌ 应该是 created_at
  isDeleted BOOLEAN  -- ❌ 应该是 deleted_at (时间戳)
);

-- 错误2：使用MySQL语法
CREATE TABLE employee_basic (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,  -- ❌ 应该是 BIGSERIAL
  salary DOUBLE,  -- ❌ 应该是 NUMERIC(10,2)
  create_time DATETIME  -- ❌ 应该是 TIMESTAMPTZ
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;  -- ❌ PostgreSQL无此语法
```

#### ❌ 错误的API设计：
```
GET    /api/getEmployeeList        # ❌ 不符合RESTful
POST   /api/addEmployee            # ❌ 不符合RESTful
POST   /api/employee/delete        # ❌ 应该用 DELETE 方法
```

#### ❌ 错误的TypeScript类型：
```typescript
interface Employee {
  id: number;
  tenant_id: number;  // ❌ 应该是驼峰 tenantId
  salary: number;     // ❌ 金额应该是 string
  create_time: string;  // ❌ 应该是驼峰 createdAt
}
```

---

## 🔄 九、术语更新记录

### v1.0（2026-10-17）
- 初始版本
- 定义核心业务术语、技术术语、数据库术语
- 定义状态枚举值
- 定义禁用术语清单

### v1.1（2026-10-20）
- 增加交易网络、交易基础设施及劳务供应商术语
- 明确 tenant 仅描述数据与权限隔离边界，不代表产品采用 SaaS 商业定位

### v1.2（2026-10-20）
- 增加平台排班、原始打卡事件、计算/确认工时及外部考勤差异等术语
- 明确工人小程序打卡为平台第一方考勤来源，外部数据不可覆盖平台原始记录
- 区分考勤状态、打卡事件类型与工时处理状态

---

## 📞 十、联系方式

**术语相关问题**：
- 提Issue：在项目仓库提交术语相关Issue
- 联系人：技术负责人
- 审查机制：技术负责人审查后更新本文档

**新术语添加流程**：
1. 提出新术语需求（Issue/会议/即时通讯）
2. 技术负责人审查（检查冲突、语义合理性）
3. 更新本文档
4. 通知团队成员（会议/公告）

---

**文档维护人**：技术负责人  
**最后更新**：2026年10月20日
**下次审查**：2026年11月17日
