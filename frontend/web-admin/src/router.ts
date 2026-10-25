import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import PublicPortal from './modules/public/PublicPortal.vue'
import AuthPage from './modules/account/AuthPage.vue'
import { renewSession, useSession } from './platform/session/session'

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
})
