export default [
  {
    title: '即時資訊',
    icon: 'ActivityIcon',
    children: [
      {
        title: '儀錶板',
        route: 'home',
        action: 'read',
        resource: 'Dashboard',
      },
      {
        title: '即時數據',
        route: 'realtime-data',
      },
    ],
  },
  {
    title: '數據查詢',
    icon: 'DatabaseIcon',
    children: [
      {
        title: '歷史資料查詢',
        route: 'history-data',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '歷史趨勢圖',
        route: 'history-trend',
        action: 'read',
        resource: 'Data',
      },
      {
        title: '警報記錄查詢',
        route: 'alarm-query',
        action: 'read',
        resource: 'Data',
      },
    ],
  },
  {
    title: '報表查詢',
    icon: 'BookOpenIcon',
    children: [
      {
        title: '監測報表',
        route: 'report',
      },
      {
        title: '月份時報表',
        route: 'monthly-hour-report',
      },
      {
        title: '衰減報表',
        route: 'decay-report',
      },
    ],
  },
  {
    title: '系統管理',
    icon: 'BookOpenIcon',
    children: [
      {
        title: '儀器管理',
        route: 'instrument-management',
      },
      {
        title: '測點管理',
        route: 'monitor-config',
        action: 'set',
        resource: 'Alarm',
      },
      {
        title: '測項管理',
        route: 'monitor-type-config',
        action: 'set',
        resource: 'Alarm',
      },
      {
        title: '使用者管理',
        route: 'user-management',
      },
      {
        title: '群組管理',
        route: 'group-management',
      },
      {
        title: '上傳資料',
        route: 'upload-data',
      },
      {
        title: '系統設定',
        route: 'system-config',
      },
    ],
  },
];
