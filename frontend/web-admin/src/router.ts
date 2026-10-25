import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import PublicPortal from './modules/public/PublicPortal.vue'

const routes: RouteRecordRaw[] = [
  { path: '/', name: 'public-home', component: PublicPortal },
  { path: '/demands', name: 'public-demands', component: PublicPortal },
  { path: '/demands/:detailId', name: 'public-demand-detail', component: PublicPortal, props: true },
]

if (import.meta.env.DEV) {
  routes.push(
    {
      path: '/account',
      name: 'account',
      component: () => import('./modules/account/AccountPage.vue'),
    },
    {
      path: '/workspace/employer',
      name: 'employer-workspace',
      component: () => import('./modules/workspace/WorkspaceOverview.vue'),
      props: { role: 'employer' },
    },
    {
      path: '/workspace/supplier',
      name: 'supplier-workspace',
      component: () => import('./modules/workspace/WorkspaceOverview.vue'),
      props: { role: 'supplier' },
    },
    {
      path: '/__dev/console',
      name: 'dev-console',
      component: () => import('./App.vue'),
    },
  )
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    ...routes,
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
