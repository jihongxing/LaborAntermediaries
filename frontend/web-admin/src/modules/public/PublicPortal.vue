<script setup lang="ts">
/* eslint-disable max-len */
import { computed, ref } from 'vue'
import { ArrowRight, Calendar, Clock, Location, Lock, Search, User } from '@element-plus/icons-vue'
import PublicHeader from '../../shared/ui/PublicHeader.vue'

export interface PublicDemand {
  id: string
  title: string
  serviceType: string
  city: string
  quantityRange: string
  startLabel: string
  durationLabel: string
  deadlineLabel: string
  summary: string
  updatedLabel: string
}

const props = defineProps<{ detailId?: string }>()
const emit = defineEmits<{ navigate: [path: string] }>()

const isPreview = import.meta.env.DEV
const demoDemands: PublicDemand[] = [
  {
    id: 'demo-assembly-hangzhou',
    title: '电子装配配套服务',
    serviceType: '生产协作',
    city: '杭州',
    quantityRange: '80–120 人',
    startLabel: '2026 年 11 月起',
    durationLabel: '约 3 个月',
    deadlineLabel: '10 月 30 日 18:00',
    summary: '为电子装配产线提供稳定的人员组织与现场协作服务，需求方已完成平台内容审核。',
    updatedLabel: '2 小时前更新',
  },
  {
    id: 'demo-warehouse-foshan',
    title: '仓储分拣协作',
    serviceType: '仓储物流',
    city: '佛山',
    quantityRange: '50–80 人',
    startLabel: '2026 年 11 月起',
    durationLabel: '长期协作',
    deadlineLabel: '11 月 02 日 12:00',
    summary: '面向仓储分拣和出库环节的人员供给与现场协作需求，服务范围和验收口径已结构化。',
    updatedLabel: '昨天更新',
  },
  {
    id: 'demo-retail-suzhou',
    title: '连锁门店补货协作',
    serviceType: '零售运营',
    city: '苏州',
    quantityRange: '20–40 人',
    startLabel: '2026 年 12 月起',
    durationLabel: '约 6 个月',
    deadlineLabel: '11 月 08 日 18:00',
    summary: '为连锁门店提供区域性补货、陈列和现场协作人员，支持多个服务点分批启动。',
    updatedLabel: '昨天更新',
  },
]
const demands = isPreview ? demoDemands : []

const keyword = ref('')
const city = ref('全部区域')
const serviceType = ref('全部服务类型')

const filteredDemands = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return demands.filter(item => {
    const matchesQuery = !query || `${item.title}${item.serviceType}${item.city}${item.summary}`.toLowerCase().includes(query)
    const matchesCity = city.value === '全部区域' || item.city === city.value
    const matchesType = serviceType.value === '全部服务类型' || item.serviceType === serviceType.value
    return matchesQuery && matchesCity && matchesType
  })
})

const selectedDemand = computed(() => demands.find(item => item.id === props.detailId))

function openDemand(id: string) {
  emit('navigate', `/demands/${id}`)
}

function showAccount() {
  emit('navigate', '/login')
}
</script>

<template>
  <div class="public-site">
    <PublicHeader
      :active="detailId ? 'demands' : 'home'"
      @navigate="emit('navigate', $event)"
      @account="showAccount"
    />

    <main v-if="selectedDemand" class="public-main public-detail-main">
      <button class="back-link" type="button" @click="emit('navigate', '/demands')">
        ← 返回需求大厅
      </button>
      <div class="detail-layout">
        <article class="demand-detail">
          <div class="detail-meta-line">
            <span class="public-status">公开需求 · 应标开放</span>
            <span>{{ selectedDemand.updatedLabel }}</span>
          </div>
          <h1>{{ selectedDemand.title }}</h1>
          <p class="detail-lede">{{ selectedDemand.summary }}</p>
          <div class="detail-facts">
            <div><el-icon><Location /></el-icon><span>{{ selectedDemand.city }}（服务区域摘要）</span></div>
            <div><el-icon><User /></el-icon><span>{{ selectedDemand.quantityRange }}</span></div>
            <div><el-icon><Calendar /></el-icon><span>{{ selectedDemand.startLabel }} · {{ selectedDemand.durationLabel }}</span></div>
            <div><el-icon><Clock /></el-icon><span>应标截止 {{ selectedDemand.deadlineLabel }}</span></div>
          </div>
          <section class="detail-section">
            <h2>需求摘要</h2>
            <p>{{ selectedDemand.summary }}</p>
          </section>
        </article>
        <aside class="detail-aside">
          <div class="detail-aside-block">
            <span class="aside-label">需求方身份与联系人</span>
            <strong>认证组织信息受授权保护</strong>
            <p>公开页面不会展示组织名称、精确地址、联系人或联系方式。</p>
            <button class="public-primary full-width" type="button" @click="showAccount">
              登录后查看认证路径
              <el-icon><ArrowRight /></el-icon>
            </button>
            <button class="public-outline full-width" type="button" @click="showAccount">
              申请供应商组织认证
            </button>
          </div>
          <div class="detail-aside-note">
            <el-icon><Lock /></el-icon>
            <span>登录本身不会解锁联系方式。通过供应商组织审核并获得应标权限后，才能查看该需求允许披露的业务详情。</span>
          </div>
        </aside>
      </div>
    </main>

    <main v-else-if="detailId" class="public-main public-detail-main">
      <button class="back-link" type="button" @click="emit('navigate', '/demands')">
        ← 返回需求大厅
      </button>
      <section class="unavailable-demand">
        <span class="eyebrow">公开需求</span>
        <h1>该需求当前不可公开查看</h1>
        <p>需求可能已截止、撤回，或没有获得公开展示授权。</p>
        <button class="public-primary" type="button" @click="emit('navigate', '/demands')">
          返回需求大厅
          <el-icon><ArrowRight /></el-icon>
        </button>
      </section>
    </main>
    <main v-else class="public-main">
      <section class="portal-intro">
        <div class="portal-intro-copy">
          <span class="eyebrow">劳务供需平台</span>
          <h1>让真实用工需求，找到合适的服务伙伴</h1>
          <p>公开浏览经过授权的需求摘要，完成个人登录与组织认证后，再进入对应的交易协作空间。</p>
          <div class="portal-intro-actions">
            <button class="public-primary" type="button" @click="emit('navigate', '/demands')">
              浏览公开需求
              <el-icon><ArrowRight /></el-icon>
            </button>
            <button class="text-action" type="button" @click="showAccount">发布需求 / 申请入驻</button>
          </div>
        </div>
        <div class="portal-flow" aria-label="平台协作流程">
          <span>公开浏览</span><i>→</i><span>个人登录</span><i>→</i><span>组织认证</span><i>→</i><strong>角色空间</strong>
        </div>
      </section>

      <section class="public-demand-section">
        <div class="section-heading-row">
          <div>
            <span class="eyebrow">公开需求</span>
            <h2>正在寻找合作方的服务需求</h2>
            <p>以下内容经过需求方授权，仅展示撮合所需的公开摘要。</p>
          </div>
          <span v-if="isPreview" class="sample-note">本地原型 · 合成示例数据</span>
        </div>
        <div class="demand-filter-bar">
          <label class="search-control">
            <el-icon><Search /></el-icon>
            <input v-model="keyword" type="search" placeholder="搜索工种、服务或关键词" />
          </label>
          <select v-model="city" aria-label="区域">
            <option>全部区域</option>
            <option>杭州</option>
            <option>佛山</option>
            <option>苏州</option>
          </select>
          <select v-model="serviceType" aria-label="服务类型">
            <option>全部服务类型</option>
            <option>生产协作</option>
            <option>仓储物流</option>
            <option>零售运营</option>
          </select>
          <button class="filter-button" type="button" @click="keyword = ''">清除筛选</button>
        </div>
        <div class="public-demand-list">
          <button
            v-for="item in filteredDemands"
            :key="item.id"
            class="public-demand-row"
            type="button"
            @click="openDemand(item.id)"
          >
            <span class="demand-row-main">
              <span class="demand-row-kicker">{{ item.serviceType }} · {{ item.city }}</span>
              <strong>{{ item.title }}</strong>
              <span>{{ item.summary }}</span>
            </span>
            <span class="demand-row-fact"><small>需求规模</small><strong>{{ item.quantityRange }}</strong></span>
            <span class="demand-row-fact"><small>服务周期</small><strong>{{ item.durationLabel }}</strong></span>
            <span class="demand-row-fact"><small>应标截止</small><strong>{{ item.deadlineLabel }}</strong></span>
            <el-icon class="demand-row-arrow"><ArrowRight /></el-icon>
          </button>
          <div v-if="!filteredDemands.length" class="public-empty">
            {{ isPreview ? '没有符合当前条件的公开需求。' : '当前没有可公开查看的需求。' }}
          </div>
        </div>
      </section>

      <section id="how-it-works" class="entry-section">
        <div>
          <span class="eyebrow">进入平台</span>
          <h2>先看清需求，再按组织身份参与</h2>
        </div>
        <div class="entry-grid">
          <button type="button" @click="showAccount"><strong>我是用工方</strong><span>提交需求，寻找合规服务伙伴 →</span></button>
          <button type="button" @click="showAccount"><strong>我是劳务供应商</strong><span>申请认证，参与适合的公开需求 →</span></button>
          <button type="button" @click="showAccount"><strong>我已有组织账号</strong><span>查看认证状态，进入组织空间 →</span></button>
        </div>
      </section>
    </main>
  </div>
</template>
