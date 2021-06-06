import Vue from 'vue';
import VueRouter from 'vue-router';

Vue.use(VueRouter);

const router = new VueRouter({
  mode: 'hash',
  base: process.env.BASE_URL,
  scrollBehavior() {
    return { x: 0, y: 0 };
  },
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/Home.vue'),
      meta: {
        pageTitle: '儀表板',
        breadcrumb: [
          {
            text: '儀表板',
            active: true,
          },
        ],
      },
    },
    {
      path: '/realtime-data',
      name: 'realtime-data',
      component: () => import('@/views/RealtimeData.vue'),
      meta: {
        pageTitle: '即時狀況資訊',
        breadcrumb: [
          {
            text: '即時狀況資訊',
            active: true,
          },
        ],
      },
    },
    {
      path: '/history-data',
      name: 'history-data',
      component: () => import('@/views/HistoryData.vue'),
      meta: {
        pageTitle: '歷史資料',
        breadcrumb: [
          {
            text: '數據查詢',
            active: true,
          },
          {
            text: '歷史資料查詢',
            active: true,
          },
        ],
      },
    },
    {
      path: '/history-trend',
      name: 'history-trend',
      component: () => import('@/views/HistoryTrend.vue'),
      meta: {
        pageTitle: '歷史趨勢圖',
        breadcrumb: [
          {
            text: '數據查詢',
            active: true,
          },
          {
            text: '歷史趨勢圖',
            active: true,
          },
        ],
      },
    },
    {
      path: '/alarm-query',
      name: 'alarm-query',
      component: () => import('@/views/AlarmQuery.vue'),
      meta: {
        pageTitle: '警報查詢',
        breadcrumb: [
          {
            text: '數據查詢',
            active: true,
          },
          {
            text: '警報查詢',
            active: true,
          },
        ],
      },
    },
    {
      path: '/report',
      name: 'report',
      component: () => import('@/views/ReportQuery.vue'),
      meta: {
        pageTitle: '監測報表',
        breadcrumb: [
          {
            text: '報表查詢',
            active: true,
          },
          {
            text: '監測報表',
            active: true,
          },
        ],
      },
    },
    {
      path: '/monthly-hour-report',
      name: 'monthly-hour-report',
      component: () => import('@/views/MonthlyHourReportQuery.vue'),
      meta: {
        pageTitle: '月份時報表',
        breadcrumb: [
          {
            text: '報表查詢',
            active: true,
          },
          {
            text: '月份時報表',
            active: true,
          },
        ],
      },
    },
    {
      path: '/decay-report',
      name: 'decay-report',
      component: () => import('@/views/DecayReport.vue'),
      meta: {
        pageTitle: '衰減分析報表',
        breadcrumb: [
          {
            text: '報表查詢',
            active: true,
          },
          {
            text: '衰減分析報表',
            active: true,
          },
        ],
      },
    },
    {
      path: '/outstanding-report',
      name: 'outstanding-report',
      component: () => import('@/views/OutstandingReport.vue'),
      meta: {
        pageTitle: '離群分析報表',
        breadcrumb: [
          {
            text: '報表查詢',
            active: true,
          },
          {
            text: '離群分析報表',
            active: true,
          },
        ],
      },
    },
    {
      path: '/instrument-management',
      name: 'instrument-management',
      component: () => import('@/views/InstrumentManagement.vue'),
      meta: {
        pageTitle: '儀器管理',
        breadcrumb: [
          {
            text: '系統管理',
            active: true,
          },
          {
            text: '儀器管理',
            active: true,
          },
        ],
      },
    },
    {
      path: '/monitor-config',
      name: 'monitor-config',
      component: () => import('@/views/MonitorConfig.vue'),
      meta: {
        pageTitle: '測點管理',
        breadcrumb: [
          {
            text: '系統管理',
            active: true,
          },
          {
            text: '測點管理',
            active: true,
          },
        ],
      },
    },
    {
      path: '/monitor-type-config',
      name: 'monitor-type-config',
      component: () => import('@/views/MonitorTypeConfig.vue'),
      meta: {
        pageTitle: '測項管理',
        breadcrumb: [
          {
            text: '系統管理',
            active: true,
          },
          {
            text: '測項管理',
            active: true,
          },
        ],
      },
    },
    {
      path: '/user-management',
      name: 'user-management',
      component: () => import('@/views/UserManagement.vue'),
      meta: {
        pageTitle: '使用者管理',
        breadcrumb: [
          {
            text: '系統管理',
            active: true,
          },
          {
            text: '使用者管理',
            active: true,
          },
        ],
      },
    },
    {
      path: '/group-management',
      name: 'group-management',
      component: () => import('@/views/GroupManagement.vue'),
      meta: {
        pageTitle: '群組管理',
        breadcrumb: [
          {
            text: '系統管理',
            active: true,
          },
          {
            text: '群組管理',
            active: true,
          },
        ],
      },
    },
    {
      path: '/upload-data',
      name: 'upload-data',
      component: () => import('@/views/UploadData.vue'),
      meta: {
        pageTitle: '上傳資料',
        breadcrumb: [
          {
            text: '系統管理',
            active: true,
          },
          {
            text: '上傳資料',
            active: true,
          },
        ],
      },
    },
    {
      path: '/system-config',
      name: 'system-config',
      component: () => import('@/views/SystemConfig.vue'),
      meta: {
        pageTitle: '系統設定',
        breadcrumb: [
          {
            text: '系統管理',
            active: true,
          },
          {
            text: '系統設定',
            active: true,
          },
        ],
      },
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: {
        layout: 'full',
      },
    },
    {
      path: '/error-404',
      name: 'error-404',
      component: () => import('@/views/error/Error404.vue'),
      meta: {
        layout: 'full',
      },
    },
    {
      path: '*',
      redirect: 'error-404',
    },
  ],
});

export default router;
