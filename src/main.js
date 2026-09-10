import Vue from 'vue';
import App from './App.vue';

import '@fontsource/noto-sans/400.css';
import '@fontsource/noto-sans/500.css';
import '@fontsource/noto-sans/600.css';
import '@fontsource/noto-sans/700.css';

Vue.config.productionTip = false;

new Vue({
  render: (h) => h(App)
}).$mount('#app');
