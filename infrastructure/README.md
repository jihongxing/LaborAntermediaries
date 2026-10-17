# 基础设施

劳务中介管理系统 - 基础设施代码

## 📋 目录说明

```
infrastructure/
├── docker/               # Docker配置
│   ├── Dockerfile.backend
│   ├── Dockerfile.frontend
│   └── docker-compose.yml
├── kubernetes/          # Kubernetes配置（可选）
│   ├── deployment.yaml
│   └── service.yaml
├── nginx/               # Nginx配置
│   └── nginx.conf
├── scripts/             # 部署脚本
│   ├── deploy-dev.sh
│   ├── deploy-test.sh
│   └── deploy-prod.sh
└── README.md            # 本文件
```

## 🐳 Docker部署

### 后端Dockerfile

```dockerfile
# docker/Dockerfile.backend
FROM openjdk:17-jdk-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 前端Dockerfile

```dockerfile
# docker/Dockerfile.frontend
FROM node:18-alpine as builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx/nginx.conf /etc/nginx/nginx.conf
EXPOSE 80
```

### Docker Compose

```yaml
# docker/docker-compose.yml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: labor_intermediaries
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql

  redis:
    image: redis:7.0
    ports:
      - "6379:6379"

  backend:
    build:
      context: ../backend
      dockerfile: ../infrastructure/docker/Dockerfile.backend
    ports:
      - "8080:8080"
    depends_on:
      - mysql
      - redis
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/labor_intermediaries
      SPRING_REDIS_HOST: redis

  frontend:
    build:
      context: ../frontend/web-admin
      dockerfile: ../../infrastructure/docker/Dockerfile.frontend
    ports:
      - "80:80"
    depends_on:
      - backend

volumes:
  mysql-data:
```

### 使用Docker Compose

```bash
# 启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止所有服务
docker-compose down

# 重启某个服务
docker-compose restart backend
```

## ☸️ Kubernetes部署（可选）

### Deployment配置

```yaml
# kubernetes/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: labor-backend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: labor-backend
  template:
    metadata:
      labels:
        app: labor-backend
    spec:
      containers:
      - name: backend
        image: labor-backend:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
```

### Service配置

```yaml
# kubernetes/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: labor-backend-service
spec:
  type: LoadBalancer
  ports:
  - port: 80
    targetPort: 8080
  selector:
    app: labor-backend
```

## 🌐 Nginx配置

```nginx
# nginx/nginx.conf
upstream backend {
    server backend:8080;
}

server {
    listen 80;
    server_name example.com;

    # 前端静态文件
    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }

    # API代理
    location /api {
        proxy_pass http://backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket支持
    location /ws {
        proxy_pass http://backend;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # 文件上传大小限制
    client_max_body_size 10M;
}
```

## 📜 部署脚本

### 开发环境部署

```bash
#!/bin/bash
# scripts/deploy-dev.sh

set -e

echo "🚀 开始部署到开发环境..."

# 拉取最新代码
git pull origin develop

# 构建后端
cd backend
mvn clean package -DskipTests
cd ..

# 构建前端
cd frontend/web-admin
npm install
npm run build
cd ../..

# 重启Docker容器
docker-compose -f infrastructure/docker/docker-compose.yml up -d --build

echo "✅ 部署完成！"
```

### 生产环境部署

```bash
#!/bin/bash
# scripts/deploy-prod.sh

set -e

echo "🚀 开始部署到生产环境..."

# 备份当前版本
echo "📦 备份当前版本..."
docker tag labor-backend:latest labor-backend:previous
docker tag labor-frontend:latest labor-frontend:previous

# 拉取最新代码
git fetch --tags
git checkout $1  # 传入tag版本号

# 构建镜像
echo "🏗️ 构建Docker镜像..."
docker build -t labor-backend:latest -f infrastructure/docker/Dockerfile.backend backend/
docker build -t labor-frontend:latest -f infrastructure/docker/Dockerfile.frontend frontend/web-admin/

# 蓝绿部署
echo "🔄 执行蓝绿部署..."
# 启动新版本
docker-compose -f infrastructure/docker/docker-compose.prod.yml up -d

# 健康检查
echo "🏥 健康检查..."
sleep 30
if curl -f http://localhost:8080/actuator/health; then
    echo "✅ 健康检查通过"
else
    echo "❌ 健康检查失败，开始回滚..."
    docker-compose -f infrastructure/docker/docker-compose.prod.yml down
    docker tag labor-backend:previous labor-backend:latest
    docker tag labor-frontend:previous labor-frontend:latest
    docker-compose -f infrastructure/docker/docker-compose.prod.yml up -d
    exit 1
fi

echo "✅ 部署成功！"
```

## 📊 监控配置

### Prometheus配置（可选）

```yaml
# prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'spring-boot'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['backend:8080']
```

### Grafana配置（可选）

- 数据源：Prometheus
- 仪表盘：Spring Boot 2.1 System Monitor

## 🔐 环境变量管理

### 开发环境

```env
# .env.dev
SPRING_PROFILES_ACTIVE=dev
MYSQL_HOST=localhost
MYSQL_PORT=3306
REDIS_HOST=localhost
REDIS_PORT=6379
```

### 生产环境

```env
# .env.prod (不提交到Git)
SPRING_PROFILES_ACTIVE=prod
MYSQL_HOST=prod-db.example.com
MYSQL_PORT=3306
MYSQL_PASSWORD=***
REDIS_HOST=prod-redis.example.com
REDIS_PORT=6379
REDIS_PASSWORD=***
```

## 🚨 回滚流程

```bash
# 快速回滚到上一个版本
docker-compose down
docker tag labor-backend:previous labor-backend:latest
docker tag labor-frontend:previous labor-frontend:latest
docker-compose up -d
```

## 📚 相关文档

- [Docker官方文档](https://docs.docker.com/)
- [Kubernetes官方文档](https://kubernetes.io/zh/)
- [Nginx官方文档](https://nginx.org/en/docs/)
- [工程治理](../GOVERNANCE.md)

## 🤝 贡献

基础设施变更请遵循以下流程：
1. 在测试环境验证
2. 提交Pull Request
3. 经过Code Review
4. 在非高峰时段部署

详细请参考 [贡献指南](../CONTRIBUTING.md)
