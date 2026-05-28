# GMS Admin UI (前端管理后台)

基于 Vue 3 + TypeScript + Arco Design Pro 的现代化游戏服务端管理后台。
集成了 Loki 日志系统可视化、服务器状态监控及配置管理功能。

## 1. 环境准备

*   **Node.js**: >= 14.0.0 (推荐 16.x 或 18.x)
*   **包管理器**: `pnpm@7.33.7`

## 2. 安装依赖

在 `gms-ui` 根目录下执行以下命令安装所有依赖：

```bash
pnpm install
```

### 2.1 日志系统核心依赖
本项目集成了强大的日志可视化功能，依赖以下核心库（已包含在 `package.json` 中，无需单独安装，仅作说明）：
*   `echarts`: ^5.6.0 (图表渲染引擎)
*   `vue-echarts`: ^6.7.3 (Vue 3 集成组件)
*   `sortablejs`: ^1.15.6 (拖拽排序支持)
*   `axios`: ^0.24.0 (HTTP 请求)

## 3. 开发模式 (Development)

启动本地开发服务器，支持热重载：

```bash
pnpm dev
```

启动后访问: `http://localhost:3000` (端口可能根据占用情况自动调整)

## 4. 生产构建 (Production Build)

构建用于生产环境的静态资源文件：

```bash
pnpm build
```

构建产物将输出到 `dist/` 目录。

## 5. 部署指南

### 5.1 静态资源部署
将 `dist/` 目录下的所有文件上传至 Web 服务器（如 Nginx, Apache, IIS）的根目录或指定路径。

**Nginx 配置示例:**
```nginx
server {
    listen 80;
    server_name your-domain.com;

    location / {
        root /path/to/gms-ui/dist;
        index index.html;
        try_files $uri $uri/ /index.html; # 支持 History 路由模式
    }

    # 反向代理后端 API (假设后端运行在 8080)
    location /api/ {
        proxy_pass http://localhost:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
    
    # 反向代理日志系统 API (如果后端未做统一转发)
    # location /log/ {
    #     proxy_pass http://localhost:8080/log/;
    # }
}
```

### 5.2 与后端集成部署 (推荐)
本项目通常作为 Spring Boot 后端的静态资源进行部署。
1.  执行 `pnpm build` 生成 `dist/`。
2.  将 `dist/` 目录下的所有文件复制到后端项目的 `src/main/resources/static/` 或 `src/main/resources/public/` 目录下。
3.  重新打包后端 JAR 文件。
4.  启动后端服务，访问后端地址即可看到前端页面。

## 6. 日志系统集成说明

前端通过 `/log/*` 接口与后端交互，后端负责代理 Loki/Promtail 的请求及进程管理。
*   **仪表盘**: `src/views/log/dashboard/`
*   **查询页**: `src/views/log/query/`
*   **配置页**: `src/views/log/config/`
*   **API 定义**: `src/api/log.ts`

**注意**: 确保后端服务已正确配置并启动了 `LogSystem` (Loki + Promtail)，否则前端日志相关功能将不可用。
