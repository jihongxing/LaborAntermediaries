<script setup lang="ts">
/* eslint-disable max-len, vue/max-attributes-per-line */
import { ArrowDown, Bell, CircleCheck, Clock, Plus, Search, Setting, User } from '@element-plus/icons-vue'

type WorkspaceRole = 'employer' | 'supplier'

const props = defineProps<{ role: WorkspaceRole }>()
const emit = defineEmits<{ navigate: [path: string] }>()

const roleContent = {
  employer: {
    label: '用工方组织空间',
    organization: '示例制造服务组织',
    title: '业务总览',
    intro: '把需求、应标和合作确认集中在一条可追踪的业务线上。',
    primaryAction: '创建需求',
    stats: [
      ['待处理事项', '4', '需要你在本周处理'],
      ['应标比较中', '2', '等待采购评审'],
      ['合作确认中', '3', '等待供应商确认'],
      ['进行中项目', '5', '按当前组织权限显示'],
    ],
    tasks: [
      ['应标比较', '电子装配配套服务', '3 家供应商已提交应标，等待你比较方案', '今天 18:00 前', 'primary'],
      ['需求补正', '仓储分拣协作', '平台审核需要补充服务范围与保障说明', '明天 12:00 前', 'warning'],
      ['供应商确认', '门店补货协作', '2 家供应商已接受授予，仍有 1 家待确认', '11 月 08 日前', 'neutral'],
    ],
    records: [
      ['电子装配配套服务', '应标比较中', 'v3', '10 月 30 日 18:00'],
      ['仓储分拣协作', '待补正', 'v2', '11 月 02 日 12:00'],
      ['门店补货协作', '合作确认中', 'v1', '11 月 08 日 18:00'],
    ],
    nav: ['业务总览', '我的需求', '应标比较', '合作项目', '对账与结算', '组织与权限'],
  },
  supplier: {
    label: '劳务供应商组织空间',
    organization: '示例人力服务组织',
    title: '业务总览',
    intro: '从公开商机到应标确认，清楚管理每一次合作机会。',
    primaryAction: '浏览开放商机',
    stats: [
      ['待处理事项', '5', '需要你在本周处理'],
      ['开放商机', '12', '当前组织可见范围'],
      ['待提交应标', '3', '即将到达截止时间'],
      ['合作确认中', '2', '等待你的最终决定'],
    ],
    tasks: [
      ['即将截止', '电子装配配套服务', '还有 2 天截止，完成应标版本后再提交', '10 月 30 日 18:00 前', 'primary'],
      ['授予确认', '仓储分拣协作', '用工方已分配 60 人配额，等待你确认最终条件', '今天 18:00 前', 'warning'],
      ['需求更新', '门店补货协作', '需求方发布了新的服务版本，需要重新评估', '11 月 08 日前', 'neutral'],
    ],
    records: [
      ['电子装配配套服务', '应标草稿', 'v2', '10 月 30 日 18:00'],
      ['仓储分拣协作', '待确认授予', 'v1', '今天 18:00'],
      ['门店补货协作', '已撤回旧版本', 'v2', '需要重新响应'],
    ],
    nav: ['业务总览', '开放商机', '我的应标', '合作项目', '人员与供给', '对账与收款', '组织与权限'],
  },
} as const

const content = roleContent[props.role]
</script>

<template>
  <div class="workspace-product">
    <aside class="workspace-sidebar">
      <button class="workspace-brand" type="button" @click="emit('navigate', '/')">
        <span class="public-brand-mark">劳</span>
        <span><strong>劳务供需平台</strong><small>{{ content.label }}</small></span>
      </button>
      <div class="workspace-org-switcher">
        <span>当前组织</span>
        <strong>{{ content.organization }}</strong>
        <el-icon><ArrowDown /></el-icon>
      </div>
      <nav class="workspace-nav" aria-label="组织空间导航">
        <button v-for="(item, index) in content.nav" :key="item" :class="{ active: index === 0 }" type="button">
          <span class="nav-marker"></span>{{ item }}
        </button>
      </nav>
      <div class="workspace-sidebar-footer">
        <button type="button"><el-icon><Setting /></el-icon>空间设置</button>
        <button type="button" @click="emit('navigate', '/account')"><el-icon><User /></el-icon>个人账号</button>
      </div>
    </aside>
    <main class="workspace-main">
      <header class="workspace-topbar">
        <div class="workspace-breadcrumb">组织空间 <span>/</span> {{ content.title }}</div>
        <div class="workspace-top-actions">
          <button class="icon-action" type="button" title="搜索"><el-icon><Search /></el-icon></button>
          <button class="icon-action notification-action" type="button" title="通知"><el-icon><Bell /></el-icon><i></i></button>
          <button class="workspace-user" type="button"><span>林先生</span><el-icon><ArrowDown /></el-icon></button>
        </div>
      </header>
      <div class="workspace-content">
        <section class="workspace-intro">
          <div>
            <span class="eyebrow">{{ content.label }}</span>
            <h1>{{ content.title }}</h1>
            <p>{{ content.intro }}</p>
            <span class="sample-note workspace-sample-note">本地原型 · 合成示例数据</span>
          </div>
          <button class="workspace-primary" type="button" @click="emit('navigate', props.role === 'employer' ? '/workspace/employer/demands/new' : '/workspace/supplier/opportunities')">
            <el-icon><Plus /></el-icon>{{ content.primaryAction }}
          </button>
        </section>
        <section class="workspace-stat-line" aria-label="业务概况">
          <div v-for="stat in content.stats" :key="stat[0]" class="workspace-stat">
            <span>{{ stat[0] }}</span><strong>{{ stat[1] }}</strong><small>{{ stat[2] }}</small>
          </div>
        </section>
        <div class="workspace-columns">
          <section class="task-section">
            <div class="section-heading-row compact">
              <div><span class="eyebrow">下一步</span><h2>需要你处理</h2></div>
              <button class="text-action" type="button">查看全部</button>
            </div>
            <div class="task-list">
              <button v-for="task in content.tasks" :key="task[1]" class="task-row" type="button">
                <span :class="['task-status', task[4]]"><CircleCheck v-if="task[4] === 'primary'" /><Clock v-else /></span>
                <span class="task-copy"><strong>{{ task[1] }}</strong><small>{{ task[0] }} · {{ task[2] }}</small></span>
                <span class="task-deadline">{{ task[3] }}</span>
                <span class="task-arrow">→</span>
              </button>
            </div>
          </section>
          <section class="activity-section">
            <div class="section-heading-row compact">
              <div><span class="eyebrow">最近动态</span><h2>业务记录</h2></div>
            </div>
            <div class="activity-list">
              <div v-for="record in content.records" :key="record[0]" class="activity-row">
                <span class="activity-dot"></span>
                <span><strong>{{ record[0] }}</strong><small>{{ record[1] }} · {{ record[2] }}</small></span>
                <em>{{ record[3] }}</em>
              </div>
            </div>
          </section>
        </div>
        <section class="workspace-table-section">
          <div class="section-heading-row compact">
            <div><span class="eyebrow">业务对象</span><h2>{{ props.role === 'employer' ? '我的需求' : '我的应标' }}</h2></div>
            <button class="text-action" type="button">进入列表 →</button>
          </div>
          <div class="workspace-table">
            <div class="workspace-table-head"><span>名称</span><span>状态</span><span>版本</span><span>下一节点</span><span></span></div>
            <button v-for="record in content.records" :key="`table-${record[0]}`" class="workspace-table-row" type="button">
              <strong>{{ record[0] }}</strong><span class="table-status">{{ record[1] }}</span><span>{{ record[2] }}</span><span>{{ record[3] }}</span><span>→</span>
            </button>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
