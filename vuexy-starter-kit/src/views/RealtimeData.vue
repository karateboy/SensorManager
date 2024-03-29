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
                  @click="exportExcel"
              /></b-button>
              <b-button
                variant="outline-success"
                class="ml-1"
                @click="checkSensor"
                >立刻檢測</b-button
              >
            </b-td>
          </b-tr>
        </b-tbody>
      </b-table-simple>
    </div>
    <b-table
      striped
      hover
      :fields="fields"
      :items="errorSensorList"
      select-mode="single"
      selectable
      @row-selected="onSensorSelected"
    />
    <b-modal id="historyTrendModal" size="xl" hide-footer>
      <history-trend
        :query-monitors="targetSensors"
        :query-monitor-types="targetMonitorTypes"
        :query-range="targetRange"
      ></history-trend>
    </b-modal>
  </b-card>
</template>

<script lang="ts">
import Vue from 'vue';
import { mapActions, mapState, mapGetters, mapMutations } from 'vuex';
import {
  sensorTypes,
  countyFilters,
  errorFilters as defaultErrorFilters,
  getDistrict,
  TxtStrValue,
  Field,
} from './types';
import HistoryTrend from './HistoryTrend.vue';
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
  components: {
    HistoryTrend,
  },
  data() {
    let constantList = Array<string>();
    let disconnectedList = Array<string>();
    let powerErrorList = Array<string>();

    const errorStatus = Array<string>('constant', 'disconnect', 'powerError');
    let errorFilters = defaultErrorFilters.filter(v => {
      return v.value != 'lt95' && v.value != 'noPowerInfo';
    });
    let targetSensors = Array<string>();
    let targetMonitorTypes = Array<string>();
    let targetRange = [
      moment().startOf('day').subtract(3, 'day').startOf('hour').valueOf(),
      moment().startOf('hour').valueOf(),
    ];
    return {
      items: [],
      timer: 0,
      disconnectedList,
      constantList,
      powerErrorList,
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
      targetSensors,
      targetMonitorTypes,
      targetRange,
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
          key: 'dataTime',
          label: '檢核時間',
          sortable: true,
        },
        {
          key: 'status',
          label: '狀態',
          sortable: true,
        },
      ];

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

          let dataTime = moment().hour(7).minute(30).format('lll');
          let sensor = Object.assign({ status: '定值', dataTime }, m);
          if (m.sensorDetail) {
            sensor.locationDesc = m.sensorDetail.locationDesc;
            sensor.road = m.sensorDetail.roadName;
          }
          ret.push(sensor);
        }

      if (this.errorStatus.indexOf('disconnect') !== -1)
        for (const id of this.disconnectedList) {
          const m = this.mMap.get(id);
          if (!m || !m.location) continue;

          let dataTime = moment().hour(7).minute(30).format('lll');
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

          let dataTime = moment()
            .subtract(1, 'days')
            .hour(20)
            .minute(0)
            .format('lll');
          let sensor = Object.assign({ status: '充電異常', dataTime }, m);
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

      if (this.errorStatus.indexOf('powerError') !== -1)
        await this.getPowerErrorList();

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
    onSensorSelected(items: Sensor[]) {
      let sensor = items[0];
      this.targetSensors = items.map(sensor => sensor._id);
      if (sensor.status === '斷線' || sensor.status == '定值')
        this.targetMonitorTypes = ['PM25'];
      else this.targetMonitorTypes = ['BATTERY'];

      this.$bvModal.show('historyTrendModal');
    },
    async checkSensor() {
      const url = `/CheckSensor`;
      try {
        const ret = await axios.get(url);
        if (ret.status === 200) this.$bvModal.msgBoxOk('成功');
        else this.$bvModal.msgBoxOk(`錯誤${ret.statusText}`);
      } catch (err) {
        this.$bvModal.msgBoxOk(`錯誤 ${err}`);
        throw new Error(`${err}`);
      } finally {
      }
    },
  },
});
</script>
