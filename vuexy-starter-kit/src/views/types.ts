export interface MonitorGroup {
  _id: string;
  member: string[];
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
    txt: '電力異常',
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
