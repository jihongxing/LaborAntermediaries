import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import PublicPortal from './modules/public/PublicPortal.vue'
import AuthPage from './modules/account/AuthPage.vue'
import { renewSession, setToken, useSession } from './platform/session/session'
import { authApi } from './modules/account/api/auth'

const routes: RouteRecordRaw[] = [
  { path: '/', name: 'public-home', component: PublicPortal },
  { path: '/demands', name: 'public-demands', component: PublicPortal },
  {
    path: '/demands/:detailId',
    name: 'public-demand-detail',
    component: PublicPortal,
    props: true,
  },
  { path: '/login', name: 'login', component: AuthPage, props: { mode: 'login' } },
  { path: '/register', name: 'register', component: AuthPage, props: { mode: 'register' } },
  {
    path: '/password-recovery',
    name: 'password-recovery',
    component: AuthPage,
    props: { mode: 'password-recovery' },
  },
  {
    path: '/account',
    name: 'account',
    component: () => import('./modules/account/AccountPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/workspace/:organizationId',
    name: 'organization-workspace',
    component: () => import('./modules/workspace/OrganizationWorkspacePage.vue'),
    props: true,
    meta: { requiresAuth: true, requiresOrganization: true },
  },
  {
    path: '/platform',
    name: 'platform-home',
    component: () => import('./modules/platform/PlatformHomePage.vue'),
    meta: { requiresAuth: true, requiresPlatform: true },
  },
  {
    path: '/platform/reviews/organizations',
    name: 'platform-organization-reviews',
    component: () => import('./modules/platform/PlatformReviewPage.vue'),
    meta: { requiresAuth: true, requiresPlatform: true },
  },
  {
    path: '/platform/reviews/demands',
    name: 'platform-demand-reviews',
    component: () => import('./modules/platform/PlatformDemandReviewPage.vue'),
    meta: { requiresAuth: true, requiresPlatform: true },
  },
  {
    path: '/platform/reviews/appeals',
    name: 'platform-appeal-reviews',
    component: () => import('./modules/platform/PlatformAppealReviewPage.vue'),
    meta: { requiresAuth: true, requiresPlatform: true },
  },
  {
    path: '/platform/reviews/appeals/:appealId',
    name: 'platform-appeal-detail',
    component: () => import('./modules/platform/PlatformAppealReviewPage.vue'),
    meta: { requiresAuth: true, requiresPlatform: true },
  },
  {
    path: '/platform/audit',
    name: 'platform-audit',
    component: () => import('./modules/platform/PlatformAuditPage.vue'),
    meta: { requiresAuth: true, requiresPlatform: true },
  },
  {
    path: '/platform/audit/coverage',
    name: 'platform-audit-coverage',
    component: () => import('./modules/platform/PlatformAuditCoveragePage.vue'),
    meta: { requiresAuth: true, requiresPlatform: true },
  },
  {
    path: '/platform/audit/findings',
    name: 'platform-audit-findings',
    component: () => import('./modules/platform/PlatformAuditFindingsPage.vue'),
    meta: { requiresAuth: true, requiresPlatform: true },
  },
  {
    path: '/platform/audit/requests/:reviewCaseId',
    name: 'platform-audit-request-history',
    component: () => import('./modules/platform/PlatformAuditHistoryPage.vue'),
    props: true,
    meta: { requiresAuth: true, requiresPlatform: true },
  },
]

if (import.meta.env.DEV) {
  routes.push(
    {
      path: '/__dev/workspace/employer',
      name: 'employer-workspace',
      component: () => import('./modules/workspace/WorkspaceOverview.vue'),
      props: { role: 'employer' },
    },
    {
      path: '/__dev/workspace/supplier',
      name: 'supplier-workspace',
      component: () => import('./modules/workspace/WorkspaceOverview.vue'),
      props: { role: 'supplier' },
    },
    {
      path: '/__dev/console',
      name: 'dev-console',
      component: () => import('./App.vue'),
    }
  )
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [...routes, { path: '/:pathMatch(.*)*', redirect: '/' }],
})

router.beforeEach(async to => {
  if (to.meta.requiresAuth && !useSession().token.value && !(await renewSession())) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresPlatform) {
    try {
      const session = await authApi.selectPlatformContext()
      setToken(session.accessToken)
    } catch {
      return { name: 'account', query: { platform: 'denied', redirect: to.fullPath } }
    }
  }
})
