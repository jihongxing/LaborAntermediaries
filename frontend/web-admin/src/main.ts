import { createApp } from 'vue'
import { router } from './router'
import './shared/styles.css'
import PortalApp from './PortalApp.vue'

createApp(PortalApp).use(router).mount('#app')
