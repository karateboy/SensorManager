<template>
  <b-card title="異常感測器列表">
    <div id="sensorFilter" class="sensorFilter mt-2">
      <b-table-simple small>
        <b-tr>
          <b-th>縣市</b-th>
          <b-th>區域劃分</b-th>
          <b-th>類型</b-th>
          <b-th>異常狀態</b-th>
        </b-tr>
        <b-tbody>
          <b-tr>
            <b-td
              ><v-select
                v-model="sensorStatusParam.county"
                label="txt"
                :reduce="entry => entry.value"
                :options="countyFilters"
            /></b-td>
            <b-td
              ><v-select
                v-model="sensorStatusParam.district"
                label="txt"
                :reduce="entry => entry.value"
                :options="districtFilters"
            /></b-td>
            <b-td
              ><v-select
                v-model="sensorStatusParam.sensorType"
                label="txt"
                :reduce="entry => entry.value"
                :options="sensorTypes"
            /></b-td>
            <b-td
              ><v-select
                v-model="errorStatus"
                label="txt"
                :reduce="entry => entry.value"
                :options="errorFilters"
                multiple
            /></b-td>
          </b-tr>
        </b-tbody>
      </b-table-simple>
    </div>
    <b-table striped hover :fields="fields" :items="errorSensorList" />
  </b-card>
</template>

<script lang="ts">
import Vue from 'vue';
import { mapActions, mapState, mapGetters, mapMutations } from 'vuex';
import axios from 'axios';
interface Sensor {
  _id: string;
  road: string;
  status: string;
}

export default Vue.extend({
  data() {
    let constantList = Array<Sensor>();
    let disconnectedList = Array<Sensor>();
    let lt95List = Array<Sensor>();
    const errorFilters = [
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
    ];
    const countyFilters = [
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
    const sensorTypes = [
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

    const errorStatus = Array<string>('constant', 'disconnect');
    return {
      fields: [
        {
          key: '_id',
          label: '設備序號',
          sortable: true,
        },
        {
          key: 'code',
          label: '代碼',
          sortable: true,
        },
        {
          key: 'shortCode',
          label: '簡碼',
          sortable: true,
        },
        {
          key: 'road',
          label: '路名',
          sortable: true,
        },
        {
          key: 'locationDesc',
          label: '位置',
          sortable: true,
        },
        {
          key: 'location',
          label: '經緯度',
          sortable: true,
        },
        {
          key: 'status',
          label: '狀態',
          sortable: true,
        },
      ],
      items: [],
      timer: 0,
      disconnectedList,
      constantList,
      lt95List,
      errorFilters,
      errorStatus,
      sensorStatusParam: {
        pm25Threshold: '',
        county: '',
        district: '',
        sensorType: '',
      },
      countyFilters,
      sensorTypes,
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),
    districtFilters() {
      if (this.sensorStatusParam.county === '基隆市') {
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
      }
      if (this.sensorStatusParam.county === '屏東縣') {
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
      }
      if (this.sensorStatusParam.county === '宜蘭縣') {
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
    },
    errorSensorList(): Array<Sensor> {
      let ret = Array<Sensor>();

      if (this.errorStatus.indexOf('constant') !== -1)
        for (let sensor of this.constantList) {
          sensor.status = '定值';
          const m = this.mMap.get(sensor._id);
          if (m.sensorDetail) {
            sensor.road = m.sensorDetail.roadName;
          }
          ret.push(sensor);
        }

      if (this.errorStatus.indexOf('lt95') !== -1)
        for (const sensor of this.lt95List) {
          sensor.status = '低於90%';
          const m = this.mMap.get(sensor._id);
          if (m.sensorDetail) {
            sensor.road = m.sensorDetail.roadName;
          }
          ret.push(sensor);
        }

      if (this.errorStatus.indexOf('disconnect') !== -1)
        for (const id of this.disconnectedList) {
          const m = this.mMap.get(id);
          if (!m || !m.location) continue;

          let sensor = Object.assign({ status: '斷線' }, m);
          if (m.sensorDetail) {
            sensor.locationDesc = m.sensorDetail.locationDesc;
            sensor.road = m.sensorDetail.roadName;
          }

          ret.push(sensor);
        }

      return ret;
    },
  },
  watch: {
    'sensorStatusParam.county': function () {
      if (this.sensorStatusParam.county === null)
        this.sensorStatusParam.county = '';

      this.sensorStatusParam.district = '';
      this.getErrorSensors();
    },
    'sensorStatusParam.district': function () {
      if (this.sensorStatusParam.district === null)
        this.sensorStatusParam.district = '';

      this.getErrorSensors();
    },
    'sensorStatusParam.sensorType': function () {
      if (this.sensorStatusParam.sensorType === null)
        this.sensorStatusParam.sensorType = '';

      this.getErrorSensors();
    },
  },
  async mounted() {
    await this.fetchMonitors();
    this.getErrorSensors();
  },

  beforeDestroy() {},

  methods: {
    ...mapActions('monitors', ['fetchMonitors']),
    ...mapMutations(['setLoading']),
    async getErrorSensors() {
      this.setLoading({ loading: true });
      await this.getDisconnected();
      await this.getConstantValue();
      await this.getLt95List();
      this.setLoading({ loading: false });
    },
    async getDisconnected() {
      const params = {
        county: this.sensorStatusParam.county,
        district: this.sensorStatusParam.district,
        sensorType: this.sensorStatusParam.sensorType,
      };
      const ret = await axios.get('/RealtimeDisconnectedSensor', {
        params,
      });
      this.disconnectedList = ret.data;
    },
    async getConstantValue() {
      const params = {
        county: this.sensorStatusParam.county,
        district: this.sensorStatusParam.district,
        sensorType: this.sensorStatusParam.sensorType,
      };
      const ret = await axios.get('/RealtimeConstantValueSensor', {
        params,
      });
      this.constantList = ret.data;
    },
    async getLt95List() {
      const params = {
        county: this.sensorStatusParam.county,
        district: this.sensorStatusParam.district,
        sensorType: this.sensorStatusParam.sensorType,
      };
      const ret = await axios.get('/Lt95Sensor', {
        params,
      });

      this.lt95List = ret.data;
    },
  },
});
</script>
