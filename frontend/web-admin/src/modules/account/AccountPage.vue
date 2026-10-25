<script setup lang="ts">
/* eslint-disable max-len */
import { ArrowRight, CircleCheck, Clock, DocumentAdd, OfficeBuilding } from '@element-plus/icons-vue'
import PublicHeader from '../../shared/ui/PublicHeader.vue'

const emit = defineEmits<{ navigate: [path: string] }>()
</script>

<template>
  <div class="public-site account-site">
    <PublicHeader active="account" @navigate="emit('navigate', $event)" @account="emit('navigate', '/account')" />
    <main class="public-main account-main">
      <div class="account-intro">
        <span class="eyebrow">个人账号</span>
        <h1>先完成组织认证，再进入业务空间</h1>
        <p>登录只代表你拥有个人账号。组织身份、成员关系和具体操作权限需要分别审核和授权。</p>
      </div>
      <div class="account-layout">
        <aside class="account-sidebar">
          <button class="account-nav active" type="button">账号与组织</button>
          <button class="account-nav" type="button" @click="emit('navigate', '/demands')">公开需求</button>
          <div class="account-sidebar-help">
            <strong>需要先了解平台？</strong>
            <span>你可以继续浏览公开需求，不影响认证申请。</span>
            <button class="text-action" type="button" @click="emit('navigate', '/demands')">返回需求大厅 <el-icon><ArrowRight /></el-icon></button>
          </div>
        </aside>
        <section class="account-content">
          <div class="account-status-bar">
            <div>
              <span class="status-label">当前状态</span>
              <strong>个人账号已登录</strong>
            </div>
            <span class="status-pill neutral">尚未关联通过审核的组织</span>
          </div>
          <div class="account-actions-grid">
            <button class="account-action" type="button">
              <span class="action-icon blue"><OfficeBuilding /></span>
              <strong>申请成为用工方</strong>
              <span>提交主体和经办授权资料，发布真实用工需求。</span>
              <em>开始申请 <el-icon><ArrowRight /></el-icon></em>
            </button>
            <button class="account-action" type="button">
              <span class="action-icon green"><DocumentAdd /></span>
              <strong>申请成为劳务供应商</strong>
              <span>提交供应商资质和组织资料，参与合适的服务需求。</span>
              <em>开始申请 <el-icon><ArrowRight /></el-icon></em>
            </button>
            <button class="account-action" type="button">
              <span class="action-icon amber"><CircleCheck /></span>
              <strong>加入已有组织</strong>
              <span>使用组织邀请或组织编号提交成员加入申请。</span>
              <em>提交加入申请 <el-icon><ArrowRight /></el-icon></em>
            </button>
          </div>
          <section class="account-progress">
            <div class="section-heading-row compact">
              <div>
                <span class="eyebrow">认证进度</span>
                <h2>你的组织资格</h2>
              </div>
              <span class="muted-copy">尚未提交申请</span>
            </div>
            <div class="progress-line">
              <div class="progress-step current"><span>1</span><strong>个人账号</strong><small>已完成</small></div>
              <i></i>
              <div class="progress-step"><span>2</span><strong>组织申请</strong><small>待开始</small></div>
              <i></i>
              <div class="progress-step"><span>3</span><strong>平台审核</strong><small>待开始</small></div>
              <i></i>
              <div class="progress-step"><span>4</span><strong>角色空间</strong><small>审核通过后开放</small></div>
            </div>
          </section>
          <div class="account-note">
            <el-icon><Clock /></el-icon>
            <span>认证通过不代表当前成员拥有组织全部权限。进入工作空间后，需求、应标、选标和项目操作仍按成员岗位逐项校验。</span>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
