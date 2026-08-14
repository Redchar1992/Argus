import { createApp } from 'vue';
import {
  ElAlert,
  ElAside,
  ElButton,
  ElCard,
  ElContainer,
  ElHeader,
  ElInputNumber,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import './styles.css';
import App from './App.vue';
import { router } from './router';

// Match the lien / analyst-console dark design language.
document.documentElement.classList.add('dark');

const app = createApp(App);

// Register only the components this console uses. Installing the full
// Element Plus plugin pulled every component into the initial JavaScript
// bundle and triggered Vite's large-chunk warning.
for (const component of [
  ElAlert,
  ElAside,
  ElButton,
  ElCard,
  ElContainer,
  ElHeader,
  ElInputNumber,
  ElMain,
  ElMenu,
  ElMenuItem,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
]) {
  app.component(component.name!, component);
}

app.use(router).mount('#app');
