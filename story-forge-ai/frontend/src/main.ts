import 'element-plus/dist/index.css'
import '@/styles/main.css'

import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from '@/App.vue'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')

window.addEventListener('story-forge:unauthorized', () => {
  useAuthStore(pinia).logout()
  if (router.currentRoute.value.name !== 'login') {
    router.replace({
      name: 'login',
      query: { redirect: router.currentRoute.value.fullPath },
    })
  }
})
