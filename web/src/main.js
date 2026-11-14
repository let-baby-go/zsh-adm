import { createApp } from 'vue'
// Components are imported on demand (vite.config.js); these two are called from scripts, not templates.
import '@arco-design/web-vue/es/message/style/css.js'
import '@arco-design/web-vue/es/modal/style/css.js'
import App from './App.vue'
import './style.css'

createApp(App).mount('#app')
