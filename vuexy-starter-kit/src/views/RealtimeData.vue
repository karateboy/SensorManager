<template>
  <b-card title="異常感測器列表">
    <div id="sensorFilter" class="sensorFilter mt-2">
      <b-table-simple small fixed>
        <b-tr>
          <b-th>縣市</b-th>
          <b-th>區域劃分</b-th>
          <b-th>類型</b-th>
          <b-th>異常狀態</b-th>
          <b-th></b-th>
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
            <b-td class="text-center"
              ><b-button
                variant="outline-success"
                size="sm"
                @click="exportExcel"
                ><b-img
                  v-b-tooltip.hover
                  src="../assets/excel_export.svg"
                  title="匯出 Excel"
                  width="24"
                  fluid
                  @click="exportExcel" /></b-button
            ></b-td>
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
import {
  sensorTypes,
  countyFilters,
  errorFilters,
  getDistrict,
  TxtStrValue,
  Field,
  Monitor,
  EffectiveRate,
} from './types';
import axios from 'axios';
import moment from 'moment';
const excel = require('../libs/excel');
const _ = require('lodash');

interface Sensor {
  _id: string;
  road: string;
  status: string;
}

export default Vue.extend({
  data() {
    let constantList = Array<Sensor>();
    let disconnectedList = Array<Sensor>();
    let lt95List = Array<EffectiveRate>();
    let powerErrorList = Array<string>();
    let noPowerInfoList = Array<string>();

    const errorStatus = Array<string>('constant', 'disconnect');
    return {
      items: [],
      timer: 0,
      disconnectedList,
      constantList,
      lt95List,
      powerErrorList,
      noPowerInfoList,
      errorFilters,
      errorStatus,
      sensorStatusParam: {
        pm25Threshold: '',
        county: '基隆市',
        district: '',
        sensorType: '',
      },
      countyFilters,
      sensorTypes,
      updateTime: moment(),
    };
  },
  computed: {
    ...mapState('monitors', ['monitors']),
    ...mapGetters('monitors', ['mMap']),
    fields(): Array<Field> {
      let ret: Array<Field> = [
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
          key: 'location[0]',
          label: '經度',
          sortable: true,
        },
        {
          key: 'location[1]',
          label: '緯度',
          sortable: true,
        },
        {
          key: 'dataTime',
          label: '紀錄時間',
          sortable: true,
        },
        {
          key: 'status',
          label: '狀態',
          sortable: true,
        },
      ];
      if (this.errorStatus.indexOf('lt95') !== -1) {
        ret.push({
          key: 'effectRate',
          label: '完整率',
          sortable: true,
          formatter: (v: number) => {
            if (isNaN(v) || v === null) {
              return `N/A`;
            } else {
              let percent = v * 100;
              return `${percent.toFixed(0)}%`;
            }
          },
        });
      }

      return ret;
    },
    districtFilters(): Array<TxtStrValue> {
      return getDistrict(this.sensorStatusParam.county);
    },
    errorSensorList(): Array<Sensor> {
      let ret = Array<Sensor>();

      if (this.errorStatus.indexOf('constant') !== -1)
        for (let id of this.constantList) {
          const m = this.mMap.get(id);
          if (!m || !m.location) continue;

          let dataTime = moment().subtract(1, 'days').format('ll');
          let sensor = Object.assign({ status: '定值', dataTime }, m);
          if (m.sensorDetail) {
            sensor.locationDesc = m.sensorDetail.locationDesc;
            sensor.road = m.sensorDetail.roadName;
          }
          ret.push(sensor);
        }

      if (this.errorStatus.indexOf('lt95') !== -1) {
        for (const ef of this.lt95List) {
          const m = this.mMap.get(ef._id);
          if (!m || !m.location) continue;

          let dataTime = moment().subtract(1, 'days').format('ll');
          let sensor = Object.assign(
            { status: '低於90%', dataTime, effectRate: ef.rate },
            m,
          );
          if (m.sensorDetail) {
            sensor.locationDesc = m.sensorDetail.locationDesc;
            sensor.road = m.sensorDetail.roadName;
          }
          ret.push(sensor);
        }
      }

      if (this.errorStatus.indexOf('disconnect') !== -1)
        for (const id of this.disconnectedList) {
          const m = this.mMap.get(id);
          if (!m || !m.location) continue;

          let dataTime = moment().subtract(10, 'minute').fromNow();
          let sensor = Object.assign({ status: '斷線', dataTime }, m);
          if (m.sensorDetail) {
            sensor.locationDesc = m.sensorDetail.locationDesc;
            sensor.road = m.sensorDetail.roadName;
          }

          ret.push(sensor);
        }

      if (this.errorStatus.indexOf('powerError') !== -1) {
        for (const id of this.powerErrorList) {
          const m = this.mMap.get(id);
          if (!m || !m.location) continue;

          let dataTime = moment().subtract(1, 'days').format('ll');
          let sensor = Object.assign({ status: '電力異常', dataTime }, m);
          if (m.sensorDetail) {
            sensor.locationDesc = m.sensorDetail.locationDesc;
            sensor.road = m.sensorDetail.roadName;
          }

          ret.push(sensor);
        }
      }

      if (this.errorStatus.indexOf('noPowerInfo') !== -1) {
        for (const id of this.noPowerInfoList) {
          const m = this.mMap.get(id);
          if (!m) continue;

          let dataTime = moment().subtract(1, 'days').format('ll');
          let sensor = Object.assign({ status: '無電力資訊', dataTime }, m);
          if (m.sensorDetail) {
            sensor.locationDesc = m.sensorDetail.locationDesc;
            sensor.road = m.sensorDetail.roadName;
          }

          ret.push(sensor);
        }
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
    errorStatus() {
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

      if (this.errorStatus.indexOf('disconnect') !== -1)
        await this.getDisconnected();

      if (this.errorStatus.indexOf('constant') !== -1)
        await this.getConstantValue();

      if (this.errorStatus.indexOf('lt95') !== -1) await this.getLt95List();

      if (this.errorStatus.indexOf('powerError') !== -1)
        await this.getPowerErrorList();

      if (this.errorStatus.indexOf('noPowerInfo') !== -1)
        await this.getNoPowerInfoList();

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
      this.updateTime = moment();
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
      this.updateTime = moment();
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
      this.updateTime = moment();
      this.lt95List = ret.data;
    },
    async getPowerErrorList(): Promise<void> {
      const params = {
        county: this.sensorStatusParam.county,
        district: this.sensorStatusParam.district,
        sensorType: this.sensorStatusParam.sensorType,
      };
      const ret = await axios.get('/PowerUsageErrorSensor', {
        params,
      });

      this.updateTime = moment();
      this.powerErrorList = ret.data;
    },
    async getNoPowerInfoList(): Promise<void> {
      const params = {
        county: this.sensorStatusParam.county,
        district: this.sensorStatusParam.district,
        sensorType: this.sensorStatusParam.sensorType,
      };
      const ret = await axios.get('/NoPowerInfoSensor', {
        params,
      });

      this.updateTime = moment();
      this.noPowerInfoList = ret.data;
    },
    exportExcel() {
      const title = this.fields.map(e => e.label);
      const key = this.fields.map(e => e.key);
      for (let entry of this.errorSensorList) {
        let e = entry as any;
        for (let k of key) {
          e[k] = _.get(entry, k);
        }
      }
      const params = {
        title,
        key,
        data: this.errorSensorList,
        autoWidth: true,
        filename: '感測器異常列表',
      };
      excel.export_array_to_excel(params);
    },
  },
});
</script>
