export interface MonitorGroup {
  _id: string;
  member: string[];
}

export interface SensorDetail {
  sensorType: string;
  roadName: string;
  locationDesc: string;
  authority: string;
  epaCode: string;
  target: string;
  targetDetail: string;
  height: number;
  distance: Array<number>;
}

export interface Monitor {
  _id: string;
  desc: string;
  monitorTypes: Array<string>;
  tags: Array<string>;
  location?: Array<number>;
  shortCode?: Array<string>;
  code?: string;
  enabled?: boolean;
  county?: string;
  district?: string;
  sensorDetail?: SensorDetail;
}

export const sensorTypes = [
  {
    txt: '不限',
    value: '',
  },
  {
    txt: '工業區',
    value: 'ID',
  },
  {
    txt: '其他汙染',
    value: 'OT',
  },
  {
    txt: '社區',
    value: 'CO',
  },
  {
    txt: '交通',
    value: 'TR',
  },
  {
    txt: '監測比對',
    value: 'MO',
  },
  {
    txt: '長期比對',
    value: 'LO',
  },
  {
    txt: '巡檢機',
    value: 'AO',
  },
];

export const countyFilters = [
  {
    txt: '不限',
    value: '',
  },
  {
    txt: '基隆',
    value: '基隆市',
  },
  {
    txt: '屏東',
    value: '屏東縣',
  },
  {
    txt: '宜蘭',
    value: '宜蘭縣',
  },
  {
    txt: '其他',
    value: '其他',
  },
];

export const errorFilters = [
  {
    txt: '通訊中斷',
    value: 'disconnect',
  },
  {
    txt: '完整率 < 90%',
    value: 'lt95',
  },
  {
    txt: '定值',
    value: 'constant',
  },
  {
    txt: '充電異常',
    value: 'powerError',
  },
  {
    txt: '無電力資訊',
    value: 'noPowerInfo',
  },
];

export interface TxtStrValue {
  txt: string;
  value: string;
}

export const pm25Filters = [
  {
    txt: '不限',
    value: '',
  },
  {
    txt: 'PM2.5 < 1',
    value: -1,
  },
  {
    txt: 'PM2.5 > 25',
    value: 25,
  },
  {
    txt: 'PM2.5 > 50',
    value: 50,
  },
];

export function getDistrict(county: string): Array<TxtStrValue> {
  if (county === '基隆市') {
    return [
      { txt: '不限', value: '' },
      { txt: '安樂區', value: 'AL' },
      { txt: '七堵區', value: 'QD' },
      { txt: '仁愛區', value: 'RA' },
      { txt: '中正區', value: 'ZZ' },
      { txt: '暖暖區', value: 'NN' },
      { txt: '中山區', value: 'ZS' },
      { txt: '信義區', value: 'XY' },
    ];
  } else if (county === '屏東縣') {
    return [
      { txt: '不限', value: '' },
      { txt: '屏東市', value: 'PT' },
      { txt: '恆春鎮', value: 'HC' },
      { txt: '琉球鄉', value: 'LQ' },
      { txt: '內埔鄉', value: 'NP' },
      { txt: '麟洛鄉', value: 'LL' },
      { txt: '車城鄉', value: 'CC' },
      { txt: '九如鄉', value: 'JR' },
      { txt: '三地門鄉', value: 'SD' },
      { txt: '里港鄉', value: 'LG' },
      { txt: '霧台鄉', value: 'WT' },
      { txt: '鹽埔鄉', value: 'YP' },
      { txt: '佳冬鄉', value: 'JD' },
      { txt: '竹田鄉', value: 'JT' },
      { txt: '長治鄉', value: 'CJ' },
      { txt: '東港鎮', value: 'DG' },
      { txt: '枋山鄉', value: 'FS' },
      { txt: '新園鄉', value: 'SY' },
      { txt: '枋寮鄉', value: 'FL' },
      { txt: '瑪家鄉', value: 'MJ' },
      { txt: '泰武鄉', value: 'TW' },
      { txt: '潮州鎮', value: 'CZ' },
      { txt: '來義鄉', value: 'LY' },
      { txt: '新埤鄉', value: 'SP' },
      { txt: '南州鄉', value: 'NC' },
      { txt: '萬巒鄉', value: 'WL' },
      { txt: '林邊鄉', value: 'LB' },
      { txt: '崁頂鄉', value: 'KD' },
      { txt: '獅子鄉', value: 'SZ' },
      { txt: '萬丹鄉', value: 'WD' },
      { txt: '高樹鄉', value: 'GS' },
      { txt: '滿州鄉', value: 'MZ' },
      { txt: '牡丹鄉', value: 'MD' },
      { txt: '春日鄉', value: 'CR' },
    ];
  } else if (county === '宜蘭縣') {
    return [
      { txt: '不限', value: '' },
      { txt: '蘇澳鎮', value: 'SA' },
      { txt: '冬山鄉', value: 'DS' },
      { txt: '南澳鄉', value: 'NA' },
      { txt: '五結鄉', value: 'WJ' },
      { txt: '壯圍鄉', value: 'ZW' },
      { txt: '宜蘭市', value: 'YL' },
      { txt: '羅東鎮', value: 'LD' },
      { txt: '頭城鎮', value: 'TC' },
      { txt: '礁溪鄉', value: 'JS' },
      { txt: '員山鄉', value: 'YS' },
      { txt: '三星鄉', value: 'SS' },
      { txt: '大同鄉', value: 'DT' },
    ];
  } else if (county === '其他') {
    return [{ txt: '其他', value: '' }];
  }
  return [{ txt: '不限', value: '' }];
}

export interface MtRecord {
  mtName: string;
  value: number;
  status: string;
}

export interface MonitorGroup {
  _id: string;
  member: Array<string>;
}

export interface Quartile {
  min: number;
  q1: number;
  q2: number;
  q3: number;
  max: number;
}

export interface QuartileReport {
  name: string;
  quartile: Quartile;
  outlier: number[];
  away?: boolean;
}

export interface MonitorField {
  key: string;
  name: string;
  getter?: (arg: any) => string | number;
}

export const MonitorExportFields: Array<MonitorField> = [
  {
    key: '_id',
    name: '設備序號',
  },
  {
    key: 'shortCode',
    name: '標識簡碼',
  },
  {
    key: 'code',
    name: '代碼',
  },
  {
    key: 'enabled',
    name: '啟用',
    getter: (arg: boolean) => {
      if (arg) return 1;
      else return '';
    },
  },
  {
    key: 'sensorDetail.sensorType',
    name: '型號',
  },
  {
    key: 'tags',
    name: '類型',
    getter: (arg: any) => {
      const tags = arg as Array<string>;
      if (tags.indexOf('ID') !== -1) return '工業區(ID)';
      if (tags.indexOf('TR') !== -1) return '交通(TR)';
      if (tags.indexOf('OT') !== -1) return '其他污染(OT)';
      if (tags.indexOf('CO') !== -1) return '社區(CO)';
      if (tags.indexOf('LO') !== -1) return '長期比對(LO)';
      if (tags.indexOf('MO') !== -1) return '監測比對(MO)';
      return '';
    },
  },
  {
    key: 'county',
    name: '縣市',
  },
  {
    key: 'district',
    name: '鄉鎮區',
  },
  {
    key: 'sensorDetail.roadName',
    name: '路名',
  },
  {
    key: 'sensorDetail.locationDesc',
    name: '位置',
  },
  {
    key: 'sensorDetail.authority',
    name: '所屬單位',
  },
  {
    key: 'sensorDetail.epaCode',
    name: 'EPA代碼',
  },
  {
    key: 'sensorDetail.target',
    name: '目標',
  },
  {
    key: 'sensorDetail.targetDetail',
    name: '目標細分',
  },
  {
    key: 'sensorDetail.height',
    name: '高度',
  },
  {
    key: 'sensorDetail.year',
    name: '年度',
    getter: () => {
      return '107年';
    },
  },
  {
    key: 'location[0]',
    name: '經度',
  },
  {
    key: 'location[1]',
    name: '緯度',
  },
  {
    key: 'sensorDetail.distance[0]',
    name: '站1',
  },
  {
    key: 'sensorDetail.distance[1]',
    name: '站2',
  },
  {
    key: 'sensorDetail.distance[2]',
    name: '站3',
  },
  {
    key: 'sensorDetail.distance[3]',
    name: '站4',
  },
];

export interface CountByCounty {
  kl: number;
  pt: number;
  yl: number;
  rest: number;
}

export interface GroupSummary {
  name: string;
  totalCount: CountByCounty;
  count: CountByCounty;
  lessThanExpected: CountByCounty;
  constant: CountByCounty;
  disconnected: CountByCounty;
  powerError: CountByCounty;
}

export interface CellData {
  v: string;
  cellClassName: Array<String>;
  status?: string;
}

export interface StatRow {
  name: string;
  cellData: Array<CellData>;
}

export interface RowData {
  date: number;
  dateStr?: string;
  cellData: Array<CellData>;
}

export interface HourEntry {
  time: number;
  cells: CellData;
}

export interface DailyReport {
  columnNames: Array<string>;
  hourRows: Array<RowData>;
  statRows: Array<StatRow>;
}

export interface MonthlyHourReport {
  columnNames: Array<string>;
  rows: Array<RowData>;
  statRows: Array<StatRow>;
}

export interface Field {
  key: string;
  label: string;
  sortable: boolean;
  formatter?: any;
}
export interface EffectiveRate {
  _id: string;
  rate: number;
}
